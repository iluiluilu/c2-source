package com.aismartstore.backend.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * search-fulltext-01-tim-kiem-co-ban.md §2.1 - Jackson mirror of the {@code
 * segment} index mapping (only the fields §4's read path actually needs; the
 * rest of the mapping is write-side, out of scope until spec {@code -04}).
 * Deserialized by the OpenSearch client's default {@code JacksonJsonpMapper},
 * hence plain {@code @JsonProperty} rather than this project's usual
 * camelCase REST DTOs (the index field names are snake_case, QĐ 2 SA).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SegmentDocument {

    @JsonProperty("segment_id")
    private String segmentId;

    @JsonProperty("document_id")
    private String documentId;

    @JsonProperty("content_source")
    private String contentSource;

    @JsonProperty("confirm_source")
    private String confirmSource;

    @JsonProperty("run_id")
    private String runId;

    @JsonProperty("doc_type")
    private String docType;

    @JsonProperty("page_no")
    private Integer pageNo;

    @JsonProperty("line_from")
    private Integer lineFrom;

    @JsonProperty("line_to")
    private Integer lineTo;

    @JsonProperty("time_start_ms")
    private Integer timeStartMs;

    @JsonProperty("time_end_ms")
    private Integer timeEndMs;

    @JsonProperty("position_type")
    private String positionType;

    public String getSegmentId() {
        return segmentId;
    }

    public void setSegmentId(String segmentId) {
        this.segmentId = segmentId;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getContentSource() {
        return contentSource;
    }

    public void setContentSource(String contentSource) {
        this.contentSource = contentSource;
    }

    public String getConfirmSource() {
        return confirmSource;
    }

    public void setConfirmSource(String confirmSource) {
        this.confirmSource = confirmSource;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getDocType() {
        return docType;
    }

    public void setDocType(String docType) {
        this.docType = docType;
    }

    public Integer getPageNo() {
        return pageNo;
    }

    public void setPageNo(Integer pageNo) {
        this.pageNo = pageNo;
    }

    public Integer getLineFrom() {
        return lineFrom;
    }

    public void setLineFrom(Integer lineFrom) {
        this.lineFrom = lineFrom;
    }

    public Integer getLineTo() {
        return lineTo;
    }

    public void setLineTo(Integer lineTo) {
        this.lineTo = lineTo;
    }

    public Integer getTimeStartMs() {
        return timeStartMs;
    }

    public void setTimeStartMs(Integer timeStartMs) {
        this.timeStartMs = timeStartMs;
    }

    public Integer getTimeEndMs() {
        return timeEndMs;
    }

    public void setTimeEndMs(Integer timeEndMs) {
        this.timeEndMs = timeEndMs;
    }

    public String getPositionType() {
        return positionType;
    }

    public void setPositionType(String positionType) {
        this.positionType = positionType;
    }
}
