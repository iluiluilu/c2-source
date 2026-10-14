package com.aismartstore.backend.reporting;

import com.aismartstore.backend.common.BadRequestException;
import com.aismartstore.backend.groups.GroupMemberRepository;
import com.aismartstore.backend.groups.ScopeResolutionException;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import com.aismartstore.backend.tags.Tag;
import com.aismartstore.backend.tags.TagKinds;
import com.aismartstore.backend.tags.TagRepository;
import com.aismartstore.backend.warehouse.WarehouseFolderRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * SPEC §4 bước 2 (AC-9): validates parameters BEFORE any statistics query runs. Ids that do not
 * exist, groups the user is not a member of, and tags of the wrong {@code kind} are all rejected with
 * ONE identical message so the response never reveals whether out-of-scope data exists (fail-closed).
 */
@Component
public class ReportParamValidator {

    public static final String INVALID_FILTERS_MESSAGE =
            "Một số bộ lọc không hợp lệ hoặc không khả dụng, hãy chọn lại";

    private final TagRepository tagRepository;
    private final WarehouseFolderRepository warehouseFolderRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final SystemConfigService systemConfigService;

    public ReportParamValidator(
            TagRepository tagRepository,
            WarehouseFolderRepository warehouseFolderRepository,
            GroupMemberRepository groupMemberRepository,
            SystemConfigService systemConfigService) {
        this.tagRepository = tagRepository;
        this.warehouseFolderRepository = warehouseFolderRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.systemConfigService = systemConfigService;
    }

    /**
     * @return the normalized parameters (sorted/distinct ids, granularity resolved)
     * @throws BadRequestException on any invalid value - no query has run at that point
     */
    public ReportParams validate(
            ReportParams raw, ReportTemplateRegistry.Template template, String userSub) {
        if (raw == null || raw.from() == null || raw.to() == null) {
            throw new BadRequestException("Thiếu khoảng thời gian: hãy nhập cả ngày bắt đầu (from) và ngày kết thúc (to)");
        }
        if (raw.from().isAfter(raw.to())) {
            throw new BadRequestException("Ngày bắt đầu phải nhỏ hơn hoặc bằng ngày kết thúc, hãy chọn lại khoảng thời gian");
        }
        Granularity granularity = Granularity.MONTH;
        if (raw.granularity() != null && !raw.granularity().isBlank()) {
            granularity = Granularity.parse(raw.granularity());
            if (granularity == null) {
                throw new BadRequestException("Độ chi tiết không hợp lệ, hãy chọn một trong: day, month, quarter, year");
            }
        }
        if (template.usesGranularity()) {
            long maxBuckets = systemConfigService.getLong(SystemConfigDefaults.REPORTING_MAX_BUCKETS);
            if (raw.period().bucketCount(granularity) > maxBuckets) {
                throw new BadRequestException(
                        "Khoảng thời gian quá dài cho độ chi tiết đã chọn, hãy chọn tháng/quý/năm hoặc thu hẹp khoảng thời gian");
            }
        }

        ReportParams normalized = raw.normalized(granularity);
        checkGroups(normalized.groupIdsOrEmpty(), userSub);
        checkTags(normalized.topicTagIdsOrEmpty(), TagKinds.TOPIC);
        checkTags(normalized.docTypeTagIdsOrEmpty(), TagKinds.DOC_TYPE);
        if (normalized.folderId() != null && !warehouseFolderRepository.existsById(normalized.folderId())) {
            throw invalidFilters();
        }
        return normalized;
    }

    private void checkGroups(List<UUID> groupIds, String userSub) {
        if (groupIds.isEmpty()) {
            return;
        }
        if (userSub == null || userSub.isBlank()) {
            throw new ScopeResolutionException("Current user token is missing a subject claim");
        }
        Set<UUID> memberOf = new HashSet<>(groupMemberRepository.findGroupIdsByUserSub(userSub));
        if (!memberOf.containsAll(groupIds)) {
            throw invalidFilters();
        }
    }

    private void checkTags(List<UUID> tagIds, String expectedKind) {
        if (tagIds.isEmpty()) {
            return;
        }
        List<Tag> found = tagRepository.findAllById(tagIds);
        if (found.size() != tagIds.size() || found.stream().anyMatch(t -> !expectedKind.equals(t.getKind()))) {
            throw invalidFilters();
        }
    }

    private static BadRequestException invalidFilters() {
        return new BadRequestException(INVALID_FILTERS_MESSAGE);
    }
}
