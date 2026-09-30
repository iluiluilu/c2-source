package com.aismartstore.backend.systemconfig;

import org.springframework.data.jpa.repository.JpaRepository;

/** JPA repository for {@link SystemConfig}, keyed by the config {@code key}. */
public interface SystemConfigRepository extends JpaRepository<SystemConfig, String> {
}
