package com.aismartstore.backend.scanning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** Unit coverage for data-ingestion.md §4.3 (Task 3): Redis -> DB -> fail-closed. */
class AllowlistServiceTest {

    private AllowedFileFormatRepository repository;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private AllowlistService service;

    private static final String CACHE_KEY = "ingest:allowlist";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(AllowedFileFormatRepository.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        service = new AllowlistService(repository, redis, new ObjectMapper());
    }

    private static AllowedFileFormat pngRow() {
        AllowedFileFormat row = new AllowedFileFormat();
        row.setMimeType("image/png");
        row.setExtensions(new String[] {"png"});
        row.setCategory("IMAGE");
        row.setMagicBytes(List.of(Map.of("offset", 0, "hex", "89504E47")));
        return row;
    }

    private static File pngFile() throws IOException {
        File f = Files.createTempFile("test-", ".png").toFile();
        Files.write(f.toPath(), new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A});
        f.deleteOnExit();
        return f;
    }

    @SuppressWarnings("unchecked")
    private static String codeOf(Throwable ex) {
        return (String) ((Map<String, Object>) ((ApiException) ex).getBody()).get("code");
    }

    @Test
    void redisHitIsUsedWithoutTouchingDb() throws Exception {
        String json = "[{\"mimeType\":\"image/png\",\"extensions\":[\"png\"],\"category\":\"IMAGE\","
                + "\"magicBytes\":[{\"offset\":0,\"hex\":\"89504E47\"}]}]";
        when(valueOps.get(CACHE_KEY)).thenReturn(json);

        AllowlistService.FormatCheck check = service.checkFormat(pngFile(), "photo.png", "image/png");

        assertThat(check.ok()).isTrue();
        verify(repository, never()).findAll();
    }

    @Test
    void redisMissFallsBackToDbAndRepopulatesCache() throws Exception {
        when(valueOps.get(CACHE_KEY)).thenReturn(null);
        when(repository.findAll()).thenReturn(List.of(pngRow()));

        AllowlistService.FormatCheck check = service.checkFormat(pngFile(), "photo.png", "image/png");

        assertThat(check.ok()).isTrue();
        verify(valueOps).set(eq(CACHE_KEY), anyString(), any());
    }

    @Test
    void redisConnectionErrorFallsBackToDbAndStillWorks() throws Exception {
        when(valueOps.get(CACHE_KEY)).thenThrow(new RuntimeException("connection refused"));
        when(repository.findAll()).thenReturn(List.of(pngRow()));

        AllowlistService.FormatCheck check = service.checkFormat(pngFile(), "photo.png", "image/png");

        assertThat(check.ok()).isTrue();
    }

    @Test
    void redisAndDbBothFailingIsFailClosed() throws Exception {
        when(valueOps.get(CACHE_KEY)).thenThrow(new RuntimeException("connection refused"));
        when(repository.findAll()).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service.checkFormat(pngFile(), "photo.png", "image/png"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo("ALLOWLIST_UNAVAILABLE"));
    }

    @Test
    void unknownMimeTypeIsRejected() throws Exception {
        when(valueOps.get(CACHE_KEY)).thenReturn(null);
        when(repository.findAll()).thenReturn(List.of(pngRow()));

        AllowlistService.FormatCheck check =
                service.checkFormat(pngFile(), "malware.exe", "application/x-msdownload");

        assertThat(check.ok()).isFalse();
    }

    @Test
    void extensionMismatchIsRejected() throws Exception {
        when(valueOps.get(CACHE_KEY)).thenReturn(null);
        when(repository.findAll()).thenReturn(List.of(pngRow()));

        AllowlistService.FormatCheck check = service.checkFormat(pngFile(), "photo.jpg", "image/png");

        assertThat(check.ok()).isFalse();
    }

    @Test
    void magicByteMismatchIsRejected() throws Exception {
        when(valueOps.get(CACHE_KEY)).thenReturn(null);
        when(repository.findAll()).thenReturn(List.of(pngRow()));

        File notActuallyPng = Files.createTempFile("fake-", ".png").toFile();
        Files.write(notActuallyPng.toPath(), "not a png".getBytes());
        notActuallyPng.deleteOnExit();

        AllowlistService.FormatCheck check = service.checkFormat(notActuallyPng, "photo.png", "image/png");

        assertThat(check.ok()).isFalse();
    }
}
