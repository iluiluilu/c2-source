package com.aismartstore.backend.reporting;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReportSavedQueryRepository extends JpaRepository<ReportSavedQuery, UUID> {

    List<ReportSavedQuery> findByOwnerSubOrderByUpdatedAtDesc(String ownerSub);

    Optional<ReportSavedQuery> findByIdAndOwnerSub(UUID id, String ownerSub);

    long countByOwnerSub(String ownerSub);

    /** Case-insensitive, trimmed uniqueness probe (mirrors UQ_report_saved_query_owner_name). */
    @Query(value = "SELECT count(*) FROM report_saved_query WHERE owner_sub = :sub "
            + "AND lower(btrim(name)) = lower(btrim(:name))", nativeQuery = true)
    long countByNormalizedName(@Param("sub") String sub, @Param("name") String name);

    @Query(value = "SELECT count(*) FROM report_saved_query WHERE owner_sub = :sub "
            + "AND lower(btrim(name)) = lower(btrim(:name)) AND id <> :excludeId", nativeQuery = true)
    long countByNormalizedNameExcluding(
            @Param("sub") String sub, @Param("name") String name, @Param("excludeId") UUID excludeId);
}
