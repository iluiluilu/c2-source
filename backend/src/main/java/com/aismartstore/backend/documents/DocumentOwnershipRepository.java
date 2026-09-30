package com.aismartstore.backend.documents;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * digitization-review-03-ownership-transfer.md §3.1: metadata-only read
 * model for {@code GET /admin/document-ownership} - {@code document} LEFT
 * JOIN {@code document_digitization_status} (the row is only created lazily
 * on a document's first {@code digitization_run}, so a document with none
 * yet must still show up with every status {@code not_applicable}, hence
 * LEFT JOIN + COALESCE, not INNER JOIN). Hand-rolled SQL (same rationale as
 * {@code ReviewQueueRepository}) rather than a JPA projection, since the
 * shape nests a {@code digitizationStatus} object.
 */
@Repository
public class DocumentOwnershipRepository {

    private final JdbcTemplate jdbcTemplate;

    public DocumentOwnershipRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param uploaderSub current uploader to look up (mục 7 câu hỏi mở BA
     *     #4: R3 finds a person's backlog by uploader, not by folder/tag).
     *     Soft-deleted documents ({@code deleted_at IS NOT NULL}) are
     *     excluded - consistent with every other active-document read in
     *     this codebase (e.g. {@code DocumentRepository}).
     */
    public Page<DocumentOwnershipRow> findByUploaderSub(String uploaderSub, int page, int size) {
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document d WHERE d.uploader_sub = ? AND d.deleted_at IS NULL",
                Long.class, uploaderSub);
        long totalElements = total != null ? total : 0L;

        List<DocumentOwnershipRow> content = jdbcTemplate.query(
                "SELECT d.id, d.original_name, d.uploader_sub, "
                        + "COALESCE(s.ocr_status, 'not_applicable') AS ocr_status, "
                        + "COALESCE(s.stt_status, 'not_applicable') AS stt_status, "
                        + "COALESCE(s.sed_status, 'not_applicable') AS sed_status, "
                        + "COALESCE(s.video_summary_status, 'not_applicable') AS video_summary_status "
                        + "FROM document d LEFT JOIN document_digitization_status s ON s.document_id = d.id "
                        + "WHERE d.uploader_sub = ? AND d.deleted_at IS NULL "
                        + "ORDER BY d.created_at DESC "
                        + "LIMIT ? OFFSET ?",
                ROW_MAPPER, uploaderSub, size, page * size);

        return new PageImpl<>(content, PageRequest.of(page, size), totalElements);
    }

    private static final RowMapper<DocumentOwnershipRow> ROW_MAPPER = (rs, rowNum) -> new DocumentOwnershipRow(
            rs.getObject("id", UUID.class),
            rs.getString("original_name"),
            rs.getString("uploader_sub"),
            new DocumentOwnershipRow.DigitizationStatus(
                    rs.getString("ocr_status"), rs.getString("stt_status"), rs.getString("sed_status"),
                    rs.getString("video_summary_status")));
}
