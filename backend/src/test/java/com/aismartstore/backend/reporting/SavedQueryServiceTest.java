package com.aismartstore.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.BadRequestException;
import com.aismartstore.backend.groups.GroupMemberRepository;
import com.aismartstore.backend.reporting.dto.ReportDtos;
import com.aismartstore.backend.tags.TagRepository;
import com.aismartstore.backend.warehouse.WarehouseFolderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** AC-11: name 1..100, unique per person ignoring case/space, 50 per person, ids that vanished are dropped with one warning. */
class SavedQueryServiceTest {

    private ReportSavedQueryRepository repository;
    private ReportParamValidator validator;
    private GroupMemberRepository groupMemberRepository;
    private TagRepository tagRepository;
    private WarehouseFolderRepository folderRepository;
    private SavedQueryService service;
    private final KeycloakUser user = new KeycloakUser("u1", "user1", List.of());

    @BeforeEach
    void setUp() {
        repository = mock(ReportSavedQueryRepository.class);
        validator = mock(ReportParamValidator.class);
        groupMemberRepository = mock(GroupMemberRepository.class);
        tagRepository = mock(TagRepository.class);
        folderRepository = mock(WarehouseFolderRepository.class);
        when(validator.validate(any(), any(), anyString()))
                .thenAnswer(inv -> ((ReportParams) inv.getArgument(0)).normalized(Granularity.MONTH));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new SavedQueryService(
                repository, new ReportTemplateRegistry(), validator, groupMemberRepository, tagRepository, folderRepository,
                new ObjectMapper().findAndRegisterModules());
    }

    private static ReportParams params() {
        return new ReportParams(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), null, null, null, null, null);
    }

    private ReportDtos.SaveQueryRequest save(String name) {
        return new ReportDtos.SaveQueryRequest(name, "REPO_SUMMARY", params());
    }

    @Test
    void nameMustBeBetween1And100CharactersAfterTrim() {
        assertThatThrownBy(() -> service.create(user, save(null))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.create(user, save("   "))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.create(user, save("x".repeat(101)))).isInstanceOf(BadRequestException.class);
        assertThat(service.create(user, save("x".repeat(100))).name()).hasSize(100);
        assertThat(service.create(user, save("  trimmed  ")).name()).isEqualTo("trimmed");
    }

    @Test
    void duplicateNameIsRejectedCaseInsensitively() {
        when(repository.countByNormalizedName("u1", "Báo Cáo")).thenReturn(1L);
        assertThatThrownBy(() -> service.create(user, save(" Báo Cáo ")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("đã tồn tại");
        verify(repository, never()).save(any());
    }

    @Test
    void fiftyIsTheCapPerPerson() {
        when(repository.countByOwnerSub("u1")).thenReturn(49L);
        assertThat(service.create(user, save("ok"))).isNotNull();
        when(repository.countByOwnerSub("u1")).thenReturn(50L);
        assertThatThrownBy(() -> service.create(user, save("one-too-many")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("50");
    }

    @Test
    void unknownTemplateIsRejected() {
        assertThatThrownBy(() -> service.create(user, new ReportDtos.SaveQueryRequest("n", "NOPE", params())))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void goneOrOutOfScopeIdsAreDroppedWithoutRevealingWhich() {
        UUID keptGroup = UUID.randomUUID();
        UUID lostGroup = UUID.randomUUID();
        UUID folder = UUID.randomUUID();
        when(groupMemberRepository.findGroupIdsByUserSub("u1")).thenReturn(List.of(keptGroup));
        when(folderRepository.existsById(folder)).thenReturn(false);
        ReportParams stored = new ReportParams(
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), List.of(keptGroup, lostGroup), List.of(), List.of(), folder,
                "month");
        SavedQueryService.CleanResult cleaned = service.dropUnavailable(stored, "u1");
        assertThat(cleaned.dropped()).isTrue();
        assertThat(cleaned.params().groupIds()).containsExactly(keptGroup);
        assertThat(cleaned.params().folderId()).isNull();
        assertThat(SavedQueryService.WARNING_PARAMS_DROPPED).doesNotContain("xoá").doesNotContain("phạm vi");
    }

    @Test
    void nothingDroppedMeansNoWarning() {
        UUID g = UUID.randomUUID();
        when(groupMemberRepository.findGroupIdsByUserSub("u1")).thenReturn(List.of(g));
        ReportParams stored = new ReportParams(
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), List.of(g), List.of(), List.of(), null, "month");
        assertThat(service.dropUnavailable(stored, "u1").dropped()).isFalse();
    }
}
