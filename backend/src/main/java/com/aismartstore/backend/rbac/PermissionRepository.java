package com.aismartstore.backend.rbac;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** The closed catalogue of 15 permission codes (V12) - never written by this module. */
public interface PermissionRepository extends JpaRepository<PermissionEntity, String> {

    List<PermissionEntity> findAllByOrderByCodeAsc();
}
