package com.aismartstore.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.common.BadRequestException;
import com.aismartstore.backend.groups.GroupMemberRepository;
import com.aismartstore.backend.groups.ScopeResolutionException;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import com.aismartstore.backend.tags.Tag;
import com.aismartstore.backend.tags.TagRepository;
import com.aismartstore.backend.warehouse.WarehouseFolderRepository;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** AC-9 / Q5: validation happens before any query; ids out of scope read exactly like ids that do not exist. */
class ReportParamValidatorTest {

    private TagRepository tagRepository;
    private WarehouseFolderRepository folderRepository;
    private GroupMemberRepository groupMemberRepository;
    private SystemConfigService config;
    private ReportParamValidator validator;
    private final ReportTemplateRegistry registry = new ReportTemplateRegistry();
    private final ReportTemplateRegistry.Template repoSummary = registry.find("REPO_SUMMARY").orElseThrow();
    private final ReportTemplateRegistry.Template classification = registry.find("DOC_BY_CLASSIFICATION").orElseThrow();

    @BeforeEach
    void setUp() {
        tagRepository = mock(TagRepository.class);
        folderRepository = mock(WarehouseFolderRepository.class);
        groupMemberRepository = mock(GroupMemberRepository.class);
        config = mock(SystemConfigService.class);
        when(config.getLong(SystemConfigDefaults.REPORTING_MAX_BUCKETS)).thenReturn(400L);
        validator = new ReportParamValidator(tagRepository, folderRepository, groupMemberRepository, config);
    }

    private static ReportParams params(LocalDate from, LocalDate to, List<UUID> groups, List<UUID> topics, List<UUID> types, UUID folder, String g) {
        return new ReportParams(from, to, groups, topics, types, folder, g);
    }

    private static ReportParams plain(String g) {
        return params(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), null, null, null, null, g);
    }

    private static Tag tag(UUID id, String kind) throws Exception {
        Tag t = new Tag();
        Field f = Tag.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(t, id);
        t.setKind(kind);
        return t;
    }

    @Test
    void missingOrInvertedPeriodIsRejectedWithoutTouchingTheDatabase() {
        assertThatThrownBy(() -> validator.validate(params(null, LocalDate.now(), null, null, null, null, null), repoSummary, "u"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> validator.validate(params(LocalDate.now(), null, null, null, null, null, null), repoSummary, "u"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> validator.validate(
                        params(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 3, 1), null, null, null, null, null), repoSummary, "u"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> validator.validate(null, repoSummary, "u")).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(tagRepository, folderRepository, groupMemberRepository);
    }

    @Test
    void fromEqualToIsAValidOneDayPeriod() {
        LocalDate d = LocalDate.of(2026, 3, 1);
        ReportParams p = validator.validate(params(d, d, null, null, null, null, "day"), repoSummary, "u");
        assertThat(p.from()).isEqualTo(p.to());
    }

    @Test
    void granularityDefaultsToMonthAndRejectsUnknownValues() {
        assertThat(validator.validate(plain(null), repoSummary, "u").granularity()).isEqualTo("month");
        assertThat(validator.validate(plain("QUARTER"), repoSummary, "u").granularity()).isEqualTo("quarter");
        assertThatThrownBy(() -> validator.validate(plain("week"), repoSummary, "u")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void bucketLimitAppliesOnlyToTemplatesThatUseGranularity() {
        ReportParams threeYearsDaily = params(LocalDate.of(2023, 1, 1), LocalDate.of(2026, 3, 31), null, null, null, null, "day");
        assertThatThrownBy(() -> validator.validate(threeYearsDaily, repoSummary, "u"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("tháng/quý/năm");
        // DOC_BY_CLASSIFICATION ignores granularity, so the same period is fine there
        assertThat(validator.validate(threeYearsDaily, classification, "u")).isNotNull();
        // exactly the limit passes
        LocalDate from = LocalDate.of(2025, 1, 1);
        assertThat(validator.validate(params(from, from.plusDays(399), null, null, null, null, "day"), repoSummary, "u")).isNotNull();
        assertThatThrownBy(() -> validator.validate(params(from, from.plusDays(400), null, null, null, null, "day"), repoSummary, "u"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void idsAreSortedAndDeduplicated() throws Exception {
        UUID a = new UUID(0, 1);
        UUID b = new UUID(0, 2);
        when(groupMemberRepository.findGroupIdsByUserSub("u")).thenReturn(List.of(a, b));
        ReportParams p = validator.validate(
                params(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 2), List.of(b, a, b), null, null, null, null),
                repoSummary, "u");
        assertThat(p.groupIds()).containsExactly(a, b);
    }

    @Test
    void everyKindOfBadIdGivesTheSameMessage() throws Exception {
        UUID ghost = UUID.randomUUID();
        UUID topicId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();
        when(groupMemberRepository.findGroupIdsByUserSub("u")).thenReturn(List.of());
        when(tagRepository.findAllById(any())).thenAnswer(inv -> {
            Iterable<UUID> ids = inv.getArgument(0);
            List<Tag> found = new java.util.ArrayList<>();
            for (UUID id : ids) {
                if (id.equals(topicId)) {
                    found.add(tag(id, "topic"));
                } else if (id.equals(typeId)) {
                    found.add(tag(id, "doc_type"));
                }
            }
            return found;
        });
        when(folderRepository.existsById(any())).thenReturn(false);
        LocalDate f = LocalDate.of(2026, 3, 1);
        LocalDate t = LocalDate.of(2026, 3, 2);
        List<ReportParams> bad = List.of(
                params(f, t, List.of(ghost), null, null, null, null), // not a member / nonexistent
                params(f, t, null, List.of(ghost), null, null, null), // tag does not exist
                params(f, t, null, List.of(typeId), null, null, null), // doc_type id used as topic
                params(f, t, null, null, List.of(topicId), null, null), // topic id used as doc_type
                params(f, t, null, null, null, ghost, null)); // folder
        for (ReportParams p : bad) {
            assertThatThrownBy(() -> validator.validate(p, repoSummary, "u"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage(ReportParamValidator.INVALID_FILTERS_MESSAGE);
        }
        // and right kinds pass
        when(folderRepository.existsById(any())).thenReturn(true);
        assertThat(validator.validate(params(f, t, null, List.of(topicId), List.of(typeId), UUID.randomUUID(), null), repoSummary, "u"))
                .isNotNull();
    }

    @Test
    void groupFilterWithoutASubjectFailsClosed() {
        assertThatThrownBy(() -> validator.validate(
                        params(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 2), List.of(UUID.randomUUID()), null, null, null, null),
                        repoSummary, null))
                .isInstanceOf(ScopeResolutionException.class);
    }
}
