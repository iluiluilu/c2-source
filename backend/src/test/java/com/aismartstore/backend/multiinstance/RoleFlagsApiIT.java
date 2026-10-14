package com.aismartstore.backend.multiinstance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/** backend-multi-instance Task 1/2 (AC-2, AC-3, AC-4): role flags decide scheduler + listener auto-start. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {"app.semantic.enabled=true"})
@ActiveProfiles("api")
class RoleFlagsApiIT extends MultiInstanceITBase {

    @Autowired
    private ApplicationContext context;

    @Test
    void roleFlagsApplied() {
        RoleFlagsAssertions.assertRole(context, false, false, false, false);
    }
}
