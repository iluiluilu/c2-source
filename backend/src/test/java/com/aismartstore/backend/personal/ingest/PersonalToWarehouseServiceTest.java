package com.aismartstore.backend.personal.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ApiException;
import com.aismartstore.backend.common.NotFoundException;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.ingest.UploadBatchRepository;
import com.aismartstore.backend.ingest.UploadBatchService;
import com.aismartstore.backend.personal.PersonalFile;
import com.aismartstore.backend.personal.PersonalFileRepository;
import com.aismartstore.backend.personal.PersonalFolder;
import com.aismartstore.backend.personal.PersonalFolderRepository;
import com.aismartstore.backend.scanning.AllowlistService;
import com.aismartstore.backend.warehouse.WarehouseCapacityService;
import com.aismartstore.backend.warehouse.WarehouseService;
import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

class PersonalToWarehouseServiceTest {

    private static final KeycloakUser USER = new KeycloakUser("u1", "user1", List.of("can-bo"));

    private PersonalFileRepository fileRepository;
    private PersonalFolderRepository folderRepository;
    private PersonalIngestItemRepository itemRepository;
    private UploadBatchService uploadBatchService;
    private WarehouseService warehouseService;
    private AllowlistService allowlistService;
    private PersonalToWarehouseService service;

    private final List<PersonalFolder> folders = new ArrayList<>();
    private final List<PersonalFile> files = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fileRepository = mock(PersonalFileRepository.class);
        folderRepository = mock(PersonalFolderRepository.class);
        itemRepository = mock(PersonalIngestItemRepository.class);
        uploadBatchService = mock(UploadBatchService.class);
        warehouseService = mock(WarehouseService.class);
        allowlistService = mock(AllowlistService.class);
        service = new PersonalToWarehouseService(
                fileRepository, folderRepository, itemRepository, mock(UploadBatchRepository.class),
                uploadBatchService, warehouseService, mock(WarehouseCapacityService.class), allowlistService,
                mock(DocumentRepository.class), mock(com.aismartstore.backend.displayname.NameCheckRepository.class),
                mock(AuditService.class), mock(PersonalIngestWorker.class),
                mock(PlatformTransactionManager.class), 30);

        when(folderRepository.findAllByOwnerSubOrderByNameAsc("u1")).thenAnswer(i -> folders);
        when(folderRepository.findByIdAndOwnerSub(any(), eq("u1"))).thenAnswer(i -> folders.stream()
                .filter(f -> f.getId().equals(i.getArgument(0))).findFirst());
        when(fileRepository.findByIdAndOwnerSub(any(), eq("u1"))).thenAnswer(i -> files.stream()
                .filter(f -> f.getId().equals(i.getArgument(0))).findFirst());
        when(fileRepository.findByOwnerSubAndFolderIdIn(eq("u1"), anyCollection())).thenAnswer(i -> {
            java.util.Collection<UUID> ids = i.getArgument(1);
            return files.stream().filter(f -> f.getFolderId() != null && ids.contains(f.getFolderId())).toList();
        });
    }

    private static void setId(Object entity, UUID id) {
        try {
            Field f = entity.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private PersonalFolder folder(String name, PersonalFolder parent) {
        PersonalFolder f = new PersonalFolder();
        setId(f, UUID.randomUUID());
        f.setName(name);
        f.setOwnerSub("u1");
        f.setParentId(parent == null ? null : parent.getId());
        folders.add(f);
        return f;
    }

    private PersonalFile file(String name, PersonalFolder in, long size) {
        PersonalFile f = new PersonalFile();
        setId(f, UUID.randomUUID());
        f.setOriginalName(name);
        f.setMimeType("image/png");
        f.setSize(size);
        f.setOwnerSub("u1");
        f.setFolderId(in == null ? null : in.getId());
        files.add(f);
        return f;
    }

    private Map<String, String> pathsByName(List<PersonalToWarehouseService.Collected> collected) {
        return collected.stream().collect(Collectors.toMap(
                c -> c.file().getOriginalName(), PersonalToWarehouseService.Collected::relativePath));
    }

    @Test
    void collectKeepsSelectedFolderNameAndNestsSubfolders() {
        PersonalFolder a = folder("Hồ sơ A", null);
        PersonalFolder b = folder("B", a);
        PersonalFolder deep = folder("C d/e", b); // free-form characters
        folder("Rỗng", a); // empty folder contributes nothing
        file("inA.png", a, 1);
        file("x.pdf", b, 1);
        file("deep.png", deep, 1);
        PersonalFile loose = file("loose.png", null, 1);

        var collected = service.collect(USER, List.of(loose.getId()), List.of(a.getId()));

        assertThat(pathsByName(collected)).containsOnly(
                Map.entry("loose.png", ""),
                Map.entry("inA.png", "Hồ sơ A"),
                Map.entry("x.pdf", "Hồ sơ A/B"),
                Map.entry("deep.png", "Hồ sơ A/B/C d/e"));
    }

    @Test
    void collectDeduplicatesFilesSelectedTwiceAndKeepsFirstPath() {
        PersonalFolder a = folder("A", null);
        PersonalFile f = file("same.png", a, 1);

        // picked by hand (path "") and again through its folder: appears once, hand pick wins.
        var collected = service.collect(USER, List.of(f.getId()), List.of(a.getId()));

        assertThat(collected).hasSize(1);
        assertThat(collected.get(0).relativePath()).isEmpty();
    }

    @Test
    void sameFileNameInTwoSubfoldersStaysDistinct() {
        PersonalFolder a = folder("A", null);
        PersonalFolder b1 = folder("B1", a);
        PersonalFolder b2 = folder("B2", a);
        file("dup.png", b1, 1);
        file("dup.png", b2, 1);

        var collected = service.collect(USER, List.of(), List.of(a.getId()));

        assertThat(collected).extracting(PersonalToWarehouseService.Collected::relativePath)
                .containsExactlyInAnyOrder("A/B1", "A/B2");
    }

    @Test
    void unknownOrForeignIdsAreTheSame404() {
        assertThatThrownBy(() -> service.collect(USER, List.of(UUID.randomUUID()), List.of()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.collect(USER, List.of(), List.of(UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Personal file or folder not found");
    }

    @Test
    void startCountsLimitsOnlyOverEligibleFiles() {
        when(warehouseService.folderExists(any())).thenReturn(true);
        when(uploadBatchService.getLimits()).thenReturn(new UploadBatchService.Limits(2, 1_000, 500, 100));
        PersonalFile p1 = file("p1.png", null, 10);
        PersonalFile p2 = file("p2.png", null, 10);
        PersonalFile p3 = file("p3.png", null, 10);
        PersonalFile exe = file("virus.exe", null, 10);
        PersonalFile huge = file("huge.png", null, 501);
        when(allowlistService.isAllowedByNameAndMime(anyString(), anyString())).thenAnswer(i -> !((String) i.getArgument(0)).endsWith(".exe"));

        // 3 eligible > max 2 -> 400 even though 2 more files are ineligible.
        FromPersonalDto dto = new FromPersonalDto(
                UUID.randomUUID(), null, List.of(p1.getId(), p2.getId(), p3.getId(), exe.getId(), huge.getId()), List.of(),
                names(p1, p2, p3, exe, huge));
        assertThatThrownBy(() -> service.start(USER, dto))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(400);
                    assertThat(((Map<?, ?>) e.getBody()).get("code")).isEqualTo("BATCH_LIMIT_EXCEEDED");
                });
        verify(itemRepository, never()).saveAll(any());
    }

    @Test
    void startWithOnlyIneligibleFilesIsRejectedWithTheirCodes() {
        when(warehouseService.folderExists(any())).thenReturn(true);
        when(uploadBatchService.getLimits()).thenReturn(new UploadBatchService.Limits(10, 1_000, 500, 100));
        PersonalFile exe = file("virus.exe", null, 10);
        when(allowlistService.isAllowedByNameAndMime(anyString(), anyString())).thenReturn(false);

        FromPersonalDto dto = new FromPersonalDto(UUID.randomUUID(), null, List.of(exe.getId()), List.of(), names(exe));
        assertThatThrownBy(() -> service.start(USER, dto))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(((Map<?, ?>) e.getBody()).get("code")).isEqualTo("NO_ELIGIBLE_FILES"));
    }

    /** Display names equal to the original names (the "no clash" case of SPEC-folder-upload-display-name). */
    private static List<FromPersonalDto.DisplayNameEntry> names(PersonalFile... files) {
        return java.util.Arrays.stream(files)
                .map(f -> new FromPersonalDto.DisplayNameEntry(f.getId(), f.getOriginalName()))
                .toList();
    }

    @Test
    void startRequiresAnExistingWarehouseFolderAndANonEmptySelection() {
        when(warehouseService.folderExists(any())).thenReturn(false);
        PersonalFile f = file("a.png", null, 1);

        assertThatThrownBy(() -> service.start(USER, new FromPersonalDto(UUID.randomUUID(), null, List.of(f.getId()), List.of(), null)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.start(USER, new FromPersonalDto(UUID.randomUUID(), null, List.of(), List.of(), null)))
                .isInstanceOf(com.aismartstore.backend.common.BadRequestException.class);
    }

    @Test
    void purgeUsesRetentionDaysCutoffForBothQueries() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-29T00:00:00Z");
        when(itemRepository.deleteForFinishedBatchesBefore(any())).thenReturn(2);
        when(itemRepository.deleteForExpiredOpenBatchesBefore(any())).thenReturn(1);

        assertThat(service.purgeOldItems(now)).isEqualTo(3);

        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(itemRepository).deleteForFinishedBatchesBefore(cutoff.capture());
        assertThat(cutoff.getValue()).isEqualTo(now.minusDays(30));
        verify(itemRepository).deleteForExpiredOpenBatchesBefore(now.minusDays(30));
    }
}
