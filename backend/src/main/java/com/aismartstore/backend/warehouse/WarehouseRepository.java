package com.aismartstore.backend.warehouse;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseRepository extends JpaRepository<Warehouse, Short> {

    /** The single {@code warehouse} row's fixed id (CK id = 1). */
    short SINGLETON_ID = 1;
}
