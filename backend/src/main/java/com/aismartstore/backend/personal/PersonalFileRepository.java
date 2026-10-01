package com.aismartstore.backend.personal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersonalFileRepository extends JpaRepository<PersonalFile, UUID> {

    /** Owner scoping: someone else's file is a 404, never a 403 - existence must not leak. */
    Optional<PersonalFile> findByIdAndOwnerSub(UUID id, String ownerSub);

    List<PersonalFile> findByOwnerSubAndFolderIdIn(String ownerSub, java.util.Collection<UUID> folderIds);

    List<PersonalFile> findByOwnerSubOrderByCreatedAtDesc(String ownerSub);

    /** Live SUM over the owner's rows - single source of truth for usage, never a drifting counter. */
    @Query("select coalesce(sum(f.size), 0) from PersonalFile f where f.ownerSub = :ownerSub")
    long sumSizeByOwnerSub(@Param("ownerSub") String ownerSub);

    /** Same expression as UQ_personal_file_display_name (R14); {@code folderKey} = folder id or the all-zero sentinel. */
    @Query(value = "SELECT EXISTS(SELECT 1 FROM personal_file f WHERE f.owner_sub = :ownerSub "
            + "AND COALESCE(f.folder_id, '00000000-0000-0000-0000-000000000000'::uuid) = CAST(:folderKey AS uuid) "
            + "AND md5(f.relative_path || '/' || lower(f.display_name)) = md5(:path || '/' || lower(:name)))",
            nativeQuery = true)
    boolean existsDisplayName(
            @Param("ownerSub") String ownerSub,
            @Param("folderKey") String folderKey,
            @Param("path") String path,
            @Param("name") String name);
}
