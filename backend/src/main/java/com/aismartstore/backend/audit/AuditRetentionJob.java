package com.aismartstore.backend.audit;

import com.aismartstore.backend.config.StorageProperties;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * audit-logging.md §4.5 (Task 8, Story 4 AC-2-AC-5): monthly, for every
 * partition older than 24 months - export to SeaweedFS, record an {@code
 * audit.archive} event, then detach and drop the partition. Never deletes
 * rows one at a time (append-only at the row level per the DB trigger); the
 * whole partition is dropped as a unit, only after its export is confirmed
 * uploaded and covered by a checkpoint.
 */
@Component
public class AuditRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionJob.class);
    private static final Pattern PARTITION_NAME = Pattern.compile("audit_log_(\\d{4})_(\\d{2})");
    private static final DateTimeFormatter PARTITION_SUFFIX = DateTimeFormatter.ofPattern("yyyy_MM");
    private static final int RETENTION_MONTHS = 24;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;
    private final AuditAdvisoryLock advisoryLock;
    private final AuditCheckpointJob checkpointJob;
    private final AuditService auditService;
    private final ObjectStorageService objectStorageService;
    private final StorageProperties storageProperties;

    public AuditRetentionJob(
            JdbcTemplate jdbcTemplate,
            AuditAdvisoryLock advisoryLock,
            AuditCheckpointJob checkpointJob,
            AuditService auditService,
            ObjectStorageService objectStorageService,
            StorageProperties storageProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.advisoryLock = advisoryLock;
        this.checkpointJob = checkpointJob;
        this.auditService = auditService;
        this.objectStorageService = objectStorageService;
        this.storageProperties = storageProperties;
    }

    @Scheduled(cron = "0 30 3 1 * *")
    @Transactional
    public void run() {
        if (!advisoryLock.tryLockForTransaction(AuditAdvisoryLock.RETENTION)) {
            return;
        }

        YearMonth cutoff = YearMonth.now().minusMonths(RETENTION_MONTHS);
        for (String partitionName : listPartitionsOlderThan(cutoff)) {
            try {
                archiveAndDrop(partitionName);
            } catch (Exception e) {
                // Never let one bad partition abort the others, and never drop on failure -
                // see the "export success -> audit event -> detach/drop" ordering in the spec's risk notes.
                log.error("AuditRetentionJob: failed to archive partition {} - left in place, will retry next run",
                        partitionName, e);
            }
        }
    }

    private List<String> listPartitionsOlderThan(YearMonth cutoff) {
        List<String> names = jdbcTemplate.queryForList(
                "SELECT child.relname FROM pg_inherits "
                        + "JOIN pg_class parent ON pg_inherits.inhparent = parent.oid "
                        + "JOIN pg_class child ON pg_inherits.inhrelid = child.oid "
                        + "WHERE parent.relname = 'audit_log' ORDER BY child.relname ASC",
                String.class);
        return names.stream().filter(name -> partitionMonth(name) != null && partitionMonth(name).isBefore(cutoff))
                .toList();
    }

    private static YearMonth partitionMonth(String partitionName) {
        Matcher matcher = PARTITION_NAME.matcher(partitionName);
        if (!matcher.matches()) {
            return null;
        }
        return YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }

    private void archiveAndDrop(String partitionName) throws IOException {
        Long minId = jdbcTemplate.queryForObject("SELECT MIN(id) FROM \"" + partitionName + "\"", Long.class);
        Long maxId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM \"" + partitionName + "\"", Long.class);
        if (maxId == null) {
            dropPartition(partitionName); // empty partition (e.g. a month with zero events) - nothing to export
            return;
        }

        String checkpointHash = ensureCheckpointCovers(maxId);
        if (checkpointHash == null) {
            log.warn("AuditRetentionJob: partition {} not fully sealed/checkpointed yet - skipping this run",
                    partitionName);
            return;
        }

        File exportFile = Files.createTempFile("audit-archive-" + partitionName, ".jsonl").toFile();
        try {
            long exported = exportPartitionToJsonl(partitionName, exportFile);
            String key = partitionName + ".jsonl";
            objectStorageService.putObject(
                    key, exportFile, "application/x-ndjson", storageProperties.auditArchiveBucket());

            auditService.record(new AuditService.AuditEntry(
                    "system", null, "audit.archive", "audit_log_partition", partitionName, "success",
                    null, null, null, null,
                    Map.of(
                            "partition", partitionName,
                            "fromId", minId,
                            "toId", maxId,
                            "rowCount", exported,
                            "archiveKey", key,
                            "checkpointRootHash", checkpointHash)));

            dropPartition(partitionName);
        } finally {
            Files.deleteIfExists(exportFile.toPath());
        }
    }

    /** Runs the checkpoint job if the latest checkpoint does not yet cover {@code upToId}; returns its root hash, or null if still not covered (some rows in range are not sealed yet). */
    private String ensureCheckpointCovers(long upToId) {
        String hash = checkpointHashCovering(upToId);
        if (hash != null) {
            return hash;
        }
        checkpointJob.run();
        return checkpointHashCovering(upToId);
    }

    private String checkpointHashCovering(long upToId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT up_to_id, root_hash FROM audit_checkpoint WHERE up_to_id >= ? ORDER BY up_to_id ASC LIMIT 1",
                upToId);
        return rows.isEmpty() ? null : (String) rows.get(0).get("root_hash");
    }

    private long exportPartitionToJsonl(String partitionName, File exportFile) throws IOException {
        long[] count = {0};
        try (BufferedWriter writer = Files.newBufferedWriter(exportFile.toPath(), StandardCharsets.UTF_8)) {
            jdbcTemplate.query("SELECT * FROM \"" + partitionName + "\" ORDER BY id", rs -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("createdAt", rs.getObject("created_at", java.time.OffsetDateTime.class).toString());
                row.put("actor", rs.getString("actor"));
                row.put("actorSub", rs.getString("actor_sub"));
                row.put("action", rs.getString("action"));
                row.put("resourceType", rs.getString("resource_type"));
                row.put("resourceId", rs.getString("resource_id"));
                row.put("outcome", rs.getString("outcome"));
                row.put("method", rs.getString("method"));
                row.put("path", rs.getString("path"));
                row.put("statusCode", (Integer) rs.getObject("status_code"));
                row.put("ip", rs.getString("ip"));
                String metadataJson = rs.getString("metadata");
                row.put("metadata", metadataJson == null ? null : parseJson(metadataJson));
                row.put("prevHash", rs.getString("prev_hash"));
                row.put("hash", rs.getString("hash"));
                var sealedAt = rs.getObject("sealed_at", java.time.OffsetDateTime.class);
                row.put("sealedAt", sealedAt == null ? null : sealedAt.toString());
                try {
                    writer.write(JSON.writeValueAsString(row));
                    writer.write("\n");
                    count[0]++;
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
        return count[0];
    }

    @SuppressWarnings("unchecked")
    private static Object parseJson(String json) {
        try {
            return JSON.readValue(json, Map.class);
        } catch (Exception e) {
            return json; // best-effort - never fail the whole export over one row's metadata shape
        }
    }

    private void dropPartition(String partitionName) {
        jdbcTemplate.execute("ALTER TABLE audit_log DETACH PARTITION \"" + partitionName + "\"");
        jdbcTemplate.execute("DROP TABLE \"" + partitionName + "\"");
        log.info("AuditRetentionJob: archived and dropped partition {}", partitionName);
    }
}
