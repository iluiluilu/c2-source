package com.aismartstore.backend.accounts;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, String> {

    /**
     * Point lookup used by {@link AccountStatusFilter} on a cache miss (spec
     * §4.5 step 4) - a single indexed-column read, not the whole row.
     */
    @Query("select a.status from Account a where a.keycloakUserId = :id")
    Optional<String> findStatusById(@Param("id") String id);
}
