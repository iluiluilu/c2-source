package com.aismartstore.backend.tags;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<Tag, UUID> {

    List<Tag> findAllByOrderByKindAscLabelAsc();

    List<Tag> findByKindOrderByLabelAsc(String kind);

    /**
     * classification-notes-01-tags.md §4.3 (Task 6/7 - ánh xạ MIME ->
     * doc_type): tra 1 trong 4 nhãn {@code doc_type} cố định seed ở V20,
     * không tạo mới ở tầng service.
     */
    Optional<Tag> findByKindAndLabelIgnoreCase(String kind, String label);
}
