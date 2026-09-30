package com.aismartstore.backend.personal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonalFolderRepository extends JpaRepository<PersonalFolder, UUID> {

    /** Owner scoping: someone else's folder is a 404, never a 403 - existence must not leak. */
    Optional<PersonalFolder> findByIdAndOwnerSub(UUID id, String ownerSub);

    List<PersonalFolder> findAllByOwnerSubOrderByNameAsc(String ownerSub);
}
