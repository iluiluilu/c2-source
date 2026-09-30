package com.aismartstore.backend.warehouse;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WarehouseFolderRepository extends JpaRepository<WarehouseFolder, UUID> {

    List<WarehouseFolder> findAllByOrderByNameAsc();

    /** Story 3 AC-1/self-FK RESTRICT pre-check: does this folder have any direct child folder? */
    boolean existsByParentId(UUID parentId);

    /**
     * Every folder strictly under {@code folderId} (not including it), via
     * the {@code path_ids} GIN index (Story 2 AC-2/AC-4 move: subtree
     * depth/path recompute, spec §4.2).
     */
    @Query(value = "SELECT * FROM warehouse_folder WHERE path_ids @> ARRAY[:folderId]::uuid[]", nativeQuery = true)
    List<WarehouseFolder> findDescendants(@Param("folderId") UUID folderId);
}
