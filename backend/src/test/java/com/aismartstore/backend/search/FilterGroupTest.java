package com.aismartstore.backend.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.aismartstore.backend.search.dto.SearchFilters;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** search-fulltext-02-tim-kiem-nang-cao.md §4 step 2/§5 (AC-12) - which groups are "active" for diagnostics. */
class FilterGroupTest {

    @Test
    void noFiltersMeansNoActiveGroups() {
        assertThat(FilterGroup.activeGroupsOf(null, List.of())).isEmpty();
        assertThat(FilterGroup.activeGroupsOf(new SearchFilters(null, null, null, null, null, null, null, null, null), List.of()))
                .isEmpty();
    }

    @Test
    void foldsFolderMediaTypeAndConfirmSourceIntoASingleGroup() {
        SearchFilters onlyFolder = new SearchFilters(null, null, null, null, null, null, UUID.randomUUID(), null, null);
        SearchFilters onlyMediaType = new SearchFilters(null, null, null, null, null, null, null, List.of("audio"), null);
        SearchFilters onlyConfirmSource = new SearchFilters(null, null, null, null, null, null, null, null, "manual");

        assertThat(FilterGroup.activeGroupsOf(onlyFolder, List.of())).containsExactly(FilterGroup.FOLDER_MEDIA_CONFIRM);
        assertThat(FilterGroup.activeGroupsOf(onlyMediaType, List.of())).containsExactly(FilterGroup.FOLDER_MEDIA_CONFIRM);
        assertThat(FilterGroup.activeGroupsOf(onlyConfirmSource, List.of())).containsExactly(FilterGroup.FOLDER_MEDIA_CONFIRM);
    }

    @Test
    void groupIdsIsActiveOnlyWhenTheEffectiveIntersectionIsNonEmpty() {
        SearchFilters filters = new SearchFilters(null, null, null, null, null, List.of(UUID.randomUUID()), null, null, null);

        assertThat(FilterGroup.activeGroupsOf(filters, List.of())).isEmpty();
        assertThat(FilterGroup.activeGroupsOf(filters, List.of("some-group"))).containsExactly(FilterGroup.GROUP_IDS);
    }

    @Test
    void ordersGroupsAsDocTypeTopicTagsUploadedAtGroupIdsThenFolderMediaConfirm() {
        SearchFilters filters = new SearchFilters(
                "văn bản",
                List.of("dat_dai"),
                null,
                LocalDate.now(),
                null,
                List.of(UUID.randomUUID()),
                UUID.randomUUID(),
                null,
                null);

        List<FilterGroup> active = FilterGroup.activeGroupsOf(filters, List.of("effective-group"));

        assertThat(active)
                .containsExactly(
                        FilterGroup.DOC_TYPE,
                        FilterGroup.TOPIC_TAGS,
                        FilterGroup.UPLOADED_AT,
                        FilterGroup.GROUP_IDS,
                        FilterGroup.FOLDER_MEDIA_CONFIRM);
    }

    @Test
    void labelsMatchTheApiContractExamples() {
        assertThat(FilterGroup.UPLOADED_AT.label()).isEqualTo("uploadedFrom/uploadedTo");
        assertThat(FilterGroup.FOLDER_MEDIA_CONFIRM.label()).isEqualTo("folderId/mediaType/confirmSource");
    }
}
