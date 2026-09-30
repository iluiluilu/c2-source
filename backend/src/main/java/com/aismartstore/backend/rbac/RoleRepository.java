package com.aismartstore.backend.rbac;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** The 3 fixed roles (V12), ordered here by the DB's natural {@code code} order (CAN_BO, LANH_DAO, QUAN_TRI). */
public interface RoleRepository extends JpaRepository<RoleEntity, String> {

    List<RoleEntity> findAllByOrderByCodeAsc();
}
