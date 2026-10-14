package com.aismartstore.backend.reporting;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.storage.ObjectStorageService;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/** In-memory stand-in for the 4-argument bucket-aware calls of {@link ObjectStorageService} (the project's ITs mock it with {@code @MockBean}). */
final class FakeStorage {

    final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    void install(ObjectStorageService mock) {
        reset(mock);
        objects.clear();
        try {
            doAnswer(inv -> {
                        File f = inv.getArgument(1);
                        objects.put(inv.getArgument(3) + "/" + inv.getArgument(0), Files.readAllBytes(f.toPath()));
                        return null;
                    })
                    .when(mock)
                    .putObject(anyString(), any(File.class), anyString(), anyString());
            when(mock.getObject(anyString(), anyString())).thenAnswer(inv -> {
                byte[] bytes = objects.get(inv.getArgument(1) + "/" + inv.getArgument(0));
                if (bytes == null) {
                    throw NoSuchKeyException.builder().message("missing").build();
                }
                return new ResponseInputStream<>(
                        GetObjectResponse.builder().build(), AbortableInputStream.create(new ByteArrayInputStream(bytes)));
            });
            doAnswer(inv -> {
                        objects.remove(inv.getArgument(1) + "/" + inv.getArgument(0));
                        return null;
                    })
                    .when(mock)
                    .deleteObject(anyString(), anyString());
        } catch (RuntimeException e) {
            throw e;
        }
    }

    boolean has(String bucket, String key) {
        return objects.containsKey(bucket + "/" + key);
    }
}
