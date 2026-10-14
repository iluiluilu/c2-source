package com.aismartstore.backend.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Unit coverage of docs/03-dev/specs/audit-logging.md §5: {@code record()}
 * no longer takes an advisory lock or computes a hash - both mocked
 * collaborators here ({@link AuditLogRepository}, {@link JdbcTemplate}) are
 * only ever touched by {@code save(...)}, never by any lock/hash-related
 * call, and the row handed to {@code save} always has hash/prevHash/
 * sealedAt left {@code null} (AuditSealerJob's job now, covered separately).
 * Also carries forward classification-notes-02-notes.md §5's metadata
 * allowlist coverage, unaffected by this refactor.
 */
class AuditServiceTest {

    private AuditLogRepository repository;
    private JdbcTemplate jdbcTemplate;
    private AuditService auditService;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        repository = mock(AuditLogRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        auditService = new AuditService(repository, jdbcTemplate);

        logAppender = new ListAppender<>();
        logAppender.start();
        auditLogger().addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        auditLogger().detachAppender(logAppender);
    }

    private static Logger auditLogger() {
        return (Logger) LoggerFactory.getLogger(AuditService.class);
    }

    private boolean hasErrorLogContaining(String fragment) {
        return logAppender.list.stream()
                .anyMatch(e -> e.getLevel() == Level.ERROR && e.getFormattedMessage().contains(fragment));
    }

    private AuditLog captureSavedRow() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    // ---- record() no longer locks or computes the chain (§4.1, Story 1 AC-4) ----

    @Test
    void recordNeverTouchesJdbcTemplateAndLeavesTheRowUnsealed() {
        auditService.recordAsync(new AuditService.AuditEntry(
                "user-1", "sub-1", "tag.assign", "document", "doc-1", "success",
                "POST", "/tags/assign", 200, "127.0.0.1", Map.of("tagId", "tag-1")));

        AuditLog saved = captureSavedRow();
        assertThat(saved.getHash()).isNull();
        assertThat(saved.getPrevHash()).isNull();
        assertThat(saved.getSealedAt()).isNull();
        // no advisory lock, no read-tail-then-compute-hash: the repository is the only collaborator record() touches.
        verifyNoInteractions(jdbcTemplate);
    }

    // ---- metadata chứa kiểu ngày (bộ lọc ngày nạp của tìm kiếm) vẫn ghi được, dạng chuỗi ISO ----

    @Test
    void metadataWithJavaTimeValuesIsWrittenAsIsoStrings() {
        Map<String, Object> filters = new java.util.LinkedHashMap<>();
        filters.put("uploadedFrom", java.time.LocalDate.of(2026, 10, 13));
        filters.put("uploadedTo", java.time.OffsetDateTime.parse("2026-10-13T23:59:59Z"));

        auditService.recordAsync(new AuditService.AuditEntry(
                "user-1", "sub-1", "search.executed", "search", null, "success",
                "POST", "/search", 200, "127.0.0.1", Map.of("filters", filters, "resultCount", 2)));

        AuditLog saved = captureSavedRow();
        assertThat(saved.getMetadata().get("filters"))
                .isEqualTo(Map.of("uploadedFrom", "2026-10-13", "uploadedTo", "2026-10-13T23:59:59Z"));
        assertThat(hasErrorLogContaining("Failed to write audit log entry")).isFalse();
    }

    // ---- eventType đã khai báo, metadata đúng allowlist -> không đổi, không log ERROR ----

    @Test
    void registeredActionWithOnlyAllowedKeysIsWrittenAsIsWithNoErrorLog() {
        String documentId = "doc-1";
        String noteId = "note-1";

        auditService.recordAsync(new AuditService.AuditEntry(
                "user-1", "sub-1", "note.create", "document_note", noteId, "success",
                null, null, null, null, Map.of("documentId", documentId, "noteId", noteId)));

        AuditLog saved = captureSavedRow();
        assertThat(saved.getMetadata()).containsOnlyKeys("documentId", "noteId");
        assertThat(saved.getMetadata()).containsEntry("documentId", documentId).containsEntry("noteId", noteId);
        assertThat(hasErrorLogContaining("note.create")).isFalse();
    }

    // ---- eventType đã khai báo, có khoá lạ -> loại khoá lạ + log ERROR (không log giá trị) ----

    @Test
    void registeredActionWithAnExtraKeyDropsItAndLogsAnErrorWithoutTheValue() {
        String secretBody = "nội dung ghi chú bí mật không được lọt vào audit_log";

        auditService.recordAsync(new AuditService.AuditEntry(
                "user-1", "sub-1", "note.update", "document_note", "note-1", "success",
                null, null, null, null,
                Map.of("documentId", "doc-1", "noteId", "note-1", "body", secretBody)));

        AuditLog saved = captureSavedRow();
        assertThat(saved.getMetadata()).containsOnlyKeys("documentId", "noteId");
        assertThat(saved.getMetadata()).doesNotContainKey("body");
        assertThat(saved.getMetadata().values()).doesNotContain(secretBody);

        boolean errorLogged = logAppender.list.stream()
                .anyMatch(e -> e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains("note.update")
                        && e.getFormattedMessage().contains("body"));
        assertThat(errorLogged).isTrue();
        assertThat(logAppender.list.stream().map(ILoggingEvent::getFormattedMessage))
                .noneMatch(message -> message.contains(secretBody));
    }

    // ---- eventType chưa đăng ký -> validator không can thiệp (opt-in) ----------

    @Test
    void unregisteredActionIsNotTouchedByTheValidator() {
        auditService.recordAsync(new AuditService.AuditEntry(
                "user-1", "sub-1", "tag.assign", "document", "doc-1", "success",
                null, null, null, null, Map.of("tagId", "tag-1", "someOtherKey", "value")));

        AuditLog saved = captureSavedRow();
        assertThat(saved.getMetadata()).containsOnlyKeys("tagId", "someOtherKey");
        assertThat(hasErrorLogContaining("tag.assign")).isFalse();
    }
}
