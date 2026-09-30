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
}
