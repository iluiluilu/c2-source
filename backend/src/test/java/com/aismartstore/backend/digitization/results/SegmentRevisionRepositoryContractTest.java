package com.aismartstore.backend.digitization.results;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * digitization-results-store.md §5 ("reviseSegment ... thử UPDATE trực tiếp 1
 * dòng segment_revision bằng SQL thô trong test để xác nhận không có code
 * path ứng dụng nào làm việc này (kiểm bằng review code/Repository không
 * expose method update, không phải kiểm bằng DB permission)"): guards the
 * append-only contract at compile time - fails as soon as anyone adds a
 * custom update/delete-style method to {@link SegmentRevisionRepository},
 * other than the one named, deliberate exception below.
 */
class SegmentRevisionRepositoryContractTest {

    /**
     * bugs/bug-004-trash-purge-missing-child-table-cleanup.md - the sole
     * permitted exception to append-only: hard delete on document purge
     * (never a mutation of an existing revision's content). Any other
     * update/delete-named method must still fail this test.
     */
    private static final Set<String> ALLOWED_DELETE_METHODS = Set.of("deleteByContentSegmentDocumentId");

    @Test
    void declaresNoCustomUpdateOrDeleteMethodBeyondTheNamedHardDeleteException() {
        Method[] declared = SegmentRevisionRepository.class.getDeclaredMethods();
        boolean hasUpdateOrDelete = Arrays.stream(declared)
                .filter(m -> !ALLOWED_DELETE_METHODS.contains(m.getName()))
                .anyMatch(m -> m.getName().toLowerCase().contains("update") || m.getName().toLowerCase().contains("delete"));

        assertThat(hasUpdateOrDelete)
                .as("SegmentRevisionRepository must stay append-only - only save() (insert), finders, and the "
                        + "named hard-delete exception in ALLOWED_DELETE_METHODS")
                .isFalse();
    }
}
