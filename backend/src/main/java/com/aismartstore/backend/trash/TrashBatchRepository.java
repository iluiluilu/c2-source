package com.aismartstore.backend.trash;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrashBatchRepository extends JpaRepository<TrashBatch, UUID> {
}
