package com.aismartstore.backend.reporting;

import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.BadRequestException;
import com.aismartstore.backend.common.NotFoundException;
import com.aismartstore.backend.groups.GroupMemberRepository;
import com.aismartstore.backend.reporting.dto.ReportDtos;
import com.aismartstore.backend.tags.Tag;
import com.aismartstore.backend.tags.TagKinds;
import com.aismartstore.backend.tags.TagRepository;
import com.aismartstore.backend.warehouse.WarehouseFolderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** T10 (AC-11): a user's private saved report parameters. */
@Service
public class SavedQueryService {

    public static final int MAX_PER_USER = 50;
    public static final String WARNING_PARAMS_DROPPED =
            "Một số tham số không còn khả dụng và đã được bỏ";

    private final ReportSavedQueryRepository repository;
    private final ReportTemplateRegistry registry;
    private final ReportParamValidator validator;
    private final GroupMemberRepository groupMemberRepository;
    private final TagRepository tagRepository;
    private final WarehouseFolderRepository folderRepository;
    private final ObjectMapper objectMapper;

    public SavedQueryService(
            ReportSavedQueryRepository repository,
            ReportTemplateRegistry registry,
            ReportParamValidator validator,
            GroupMemberRepository groupMemberRepository,
            TagRepository tagRepository,
            WarehouseFolderRepository folderRepository,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.registry = registry;
        this.validator = validator;
        this.groupMemberRepository = groupMemberRepository;
        this.tagRepository = tagRepository;
        this.folderRepository = folderRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<ReportDtos.SavedQuerySummary> list(KeycloakUser user) {
        return repository.findByOwnerSubOrderByUpdatedAtDesc(user.sub()).stream()
                .map(q -> new ReportDtos.SavedQuerySummary(q.getId(), q.getName(), q.getTemplateCode(), q.getUpdatedAt()))
                .toList();
    }

    @Transactional
    public ReportDtos.SavedQuerySummary create(KeycloakUser user, ReportDtos.SaveQueryRequest request) {
        String name = normalizeName(request == null ? null : request.name());
        ReportTemplateRegistry.Template template = registry.find(request.templateCode())
                .orElseThrow(() -> new BadRequestException("Mẫu báo cáo không hợp lệ"));
        ReportParams params = validator.validate(request.params(), template, user.sub());
        if (repository.countByOwnerSub(user.sub()) >= MAX_PER_USER) {
            throw new BadRequestException("Bạn đã lưu tối đa " + MAX_PER_USER + " báo cáo, hãy xoá bớt trước khi lưu thêm");
        }
        if (repository.countByNormalizedName(user.sub(), name) > 0) {
            throw new BadRequestException("Tên báo cáo đã tồn tại, hãy chọn tên khác");
        }
        ReportSavedQuery q = new ReportSavedQuery();
        q.setOwnerSub(user.sub());
        q.setTemplateCode(template.code());
        q.setName(name);
        q.setParamsJson(params.toMap());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        q.setCreatedAt(now);
        q.setUpdatedAt(now);
        ReportSavedQuery saved = repository.save(q);
        return new ReportDtos.SavedQuerySummary(saved.getId(), saved.getName(), saved.getTemplateCode(), saved.getUpdatedAt());
    }

    /** Another user's id is a 404 (existence is not revealed). */
    @Transactional(readOnly = true)
    public ReportDtos.SavedQueryDetail get(KeycloakUser user, UUID id) {
        ReportSavedQuery q = repository.findByIdAndOwnerSub(id, user.sub()).orElseThrow(SavedQueryService::notFound);
        ReportParams stored = objectMapper.convertValue(q.getParamsJson(), ReportParams.class);
        CleanResult cleaned = dropUnavailable(stored, user.sub());
        return new ReportDtos.SavedQueryDetail(
                q.getId(), q.getName(), q.getTemplateCode(), cleaned.params(),
                cleaned.dropped() ? List.of(WARNING_PARAMS_DROPPED) : List.of());
    }

    @Transactional
    public ReportDtos.SavedQuerySummary rename(KeycloakUser user, UUID id, ReportDtos.RenameQueryRequest request) {
        ReportSavedQuery q = repository.findByIdAndOwnerSub(id, user.sub()).orElseThrow(SavedQueryService::notFound);
        String name = normalizeName(request == null ? null : request.name());
        if (repository.countByNormalizedNameExcluding(user.sub(), name, id) > 0) {
            throw new BadRequestException("Tên báo cáo đã tồn tại, hãy chọn tên khác");
        }
        q.setName(name);
        q.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        ReportSavedQuery saved = repository.save(q);
        return new ReportDtos.SavedQuerySummary(saved.getId(), saved.getName(), saved.getTemplateCode(), saved.getUpdatedAt());
    }

    @Transactional
    public void delete(KeycloakUser user, UUID id) {
        ReportSavedQuery q = repository.findByIdAndOwnerSub(id, user.sub()).orElseThrow(SavedQueryService::notFound);
        repository.delete(q);
    }

    static String normalizeName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.length() > 100) {
            throw new BadRequestException("Tên báo cáo phải từ 1 đến 100 ký tự");
        }
        return name;
    }

    private static NotFoundException notFound() {
        return new NotFoundException("Không tìm thấy báo cáo đã lưu");
    }

    record CleanResult(ReportParams params, boolean dropped) {
    }

    /**
     * AC-11: when reopening, ids that were deleted or are now out of the user's scope are removed and
     * ONE generic warning is returned - deleted and out-of-scope are indistinguishable (consistent with AC-9).
     */
    CleanResult dropUnavailable(ReportParams p, String sub) {
        boolean dropped = false;

        List<UUID> groups = p.groupIdsOrEmpty();
        Set<UUID> memberOf = new HashSet<>(groupMemberRepository.findGroupIdsByUserSub(sub));
        List<UUID> keptGroups = groups.stream().filter(memberOf::contains).toList();
        dropped |= keptGroups.size() != groups.size();

        List<UUID> keptTopics = keepTags(p.topicTagIdsOrEmpty(), TagKinds.TOPIC);
        dropped |= keptTopics.size() != p.topicTagIdsOrEmpty().size();
        List<UUID> keptTypes = keepTags(p.docTypeTagIdsOrEmpty(), TagKinds.DOC_TYPE);
        dropped |= keptTypes.size() != p.docTypeTagIdsOrEmpty().size();

        UUID folder = p.folderId();
        if (folder != null && !folderRepository.existsById(folder)) {
            folder = null;
            dropped = true;
        }
        return new CleanResult(
                new ReportParams(p.from(), p.to(), keptGroups, keptTopics, keptTypes, folder, p.granularity()), dropped);
    }

    private List<UUID> keepTags(List<UUID> ids, String kind) {
        if (ids.isEmpty()) {
            return ids;
        }
        Map<UUID, Tag> found = new java.util.HashMap<>();
        tagRepository.findAllById(ids).forEach(t -> found.put(t.getId(), t));
        return ids.stream().filter(id -> found.containsKey(id) && kind.equals(found.get(id).getKind())).toList();
    }
}
