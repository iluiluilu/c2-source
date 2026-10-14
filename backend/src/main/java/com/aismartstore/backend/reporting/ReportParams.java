package com.aismartstore.backend.reporting;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Report parameters (AC-7). {@code granularity} stays a string so an invalid value reaches the
 * validator as a 400 with an actionable message instead of a generic deserialization error.
 * After {@link ReportParamValidator#validate} an instance is normalized: ids sorted and
 * de-duplicated, lists never null, granularity lower-case and defaulted.
 */
public record ReportParams(
        LocalDate from,
        LocalDate to,
        List<UUID> groupIds,
        List<UUID> topicTagIds,
        List<UUID> docTypeTagIds,
        UUID folderId,
        String granularity) {

    public ReportParams normalized(Granularity resolvedGranularity) {
        return new ReportParams(
                from,
                to,
                sortedDistinct(groupIds),
                sortedDistinct(topicTagIds),
                sortedDistinct(docTypeTagIds),
                folderId,
                resolvedGranularity.wire());
    }

    private static List<UUID> sortedDistinct(List<UUID> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream().filter(java.util.Objects::nonNull).distinct().sorted(Comparator.comparing(UUID::toString)).toList();
    }

    @JsonIgnore
    public Period period() {
        return new Period(from, to);
    }

    @JsonIgnore
    public Granularity granularityEnum() {
        Granularity g = Granularity.parse(granularity);
        return g != null ? g : Granularity.MONTH;
    }

    public List<UUID> groupIdsOrEmpty() {
        return groupIds == null ? List.of() : groupIds;
    }

    public List<UUID> topicTagIdsOrEmpty() {
        return topicTagIds == null ? List.of() : topicTagIds;
    }

    public List<UUID> docTypeTagIdsOrEmpty() {
        return docTypeTagIds == null ? List.of() : docTypeTagIds;
    }

    /** Stable map form for {@code params_json} and the audit allowlist (ids and dates only - never display names). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", from == null ? null : from.toString());
        m.put("to", to == null ? null : to.toString());
        m.put("groupIds", groupIdsOrEmpty().stream().map(UUID::toString).toList());
        m.put("topicTagIds", topicTagIdsOrEmpty().stream().map(UUID::toString).toList());
        m.put("docTypeTagIds", docTypeTagIdsOrEmpty().stream().map(UUID::toString).toList());
        m.put("folderId", folderId == null ? null : folderId.toString());
        m.put("granularity", granularity);
        return m;
    }
}
