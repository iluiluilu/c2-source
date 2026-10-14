package com.aismartstore.backend.tags;

import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.BadRequestException;
import com.aismartstore.backend.common.ConflictException;
import com.aismartstore.backend.common.NotFoundException;
import com.aismartstore.backend.common.SqlErrors;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.groups.BusinessDataScope;
import com.aismartstore.backend.groups.DocumentScopeGuard;
import com.aismartstore.backend.groups.GroupMemberRepository;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Java port of the NestJS backend's tags/tags.service.ts, extended per
 * classification-notes-01-tags.md: {@code topic}/{@code doc_type} catalogue
 * management (§3/§4.1-4.2), {@link DocumentScopeGuard} on every
 * document-scoped read/write (§4.1 step 2, closing the gap flagged by SA
 * Quyết định 4/Rủi ro mục 6), the {@code doc_type} "always exactly one, auto-
 * replace" invariant (§4.3 step 4), and the {@code outbox_event} write on
 * every classification change (§4.6).
 */
@Service
public class TagsService {

    private final TagRepository tagRepository;
    private final DocumentTagRepository documentTagRepository;
    private final AuditService auditService;
    private final AuditRequestContext auditRequestContext;
    private final DocumentScopeGuard documentScopeGuard;
    private final SystemConfigService systemConfigService;
    private final OutboxEventRepository outboxEventRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final TagReportRepository tagReportRepository;

    public TagsService(
            TagRepository tagRepository,
            DocumentTagRepository documentTagRepository,
            AuditService auditService,
            AuditRequestContext auditRequestContext,
            DocumentScopeGuard documentScopeGuard,
            SystemConfigService systemConfigService,
            OutboxEventRepository outboxEventRepository,
            GroupMemberRepository groupMemberRepository,
            TagReportRepository tagReportRepository) {
        this.tagRepository = tagRepository;
        this.documentTagRepository = documentTagRepository;
        this.auditService = auditService;
        this.auditRequestContext = auditRequestContext;
        this.documentScopeGuard = documentScopeGuard;
        this.systemConfigService = systemConfigService;
        this.outboxEventRepository = outboxEventRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.tagReportRepository = tagReportRepository;
    }

    // ---- catalogue (§3 API contract) -------------------------------------

    /** {@code POST /tags} - only ever creates a {@code topic} (US3 AC-6: creating a {@code doc_type} is 400). */
    public Tag create(KeycloakUser user, String kind, String label) {
        if (!TagKinds.TOPIC.equals(kind)) {
            throw new BadRequestException("Chỉ có thể tạo nhãn 'topic' qua API này");
        }
        return createTopic(user, label);
    }

    private Tag createTopic(KeycloakUser user, String label) {
        Tag tag = new Tag();
        tag.setKind(TagKinds.TOPIC);
        tag.setLabel(normalizeLabel(label));
        tag.setActive(true);
        tag.setCreatedBySub(user.sub());
        tag.setCreatedByUsername(user.preferredUsername());
        tag.setCreatedAt(OffsetDateTime.now());

        try {
            tagRepository.save(tag);
        } catch (DataIntegrityViolationException e) {
            if (SqlErrors.isUniqueViolation(e)) {
                throw new ConflictException("Đã có chủ đề cùng tên");
            }
            throw e;
        }

        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "tag.create", "tag", tag.getId().toString(), "success",
                auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(),
                Map.of("kind", tag.getKind(), "label", tag.getLabel())));
        return tag;
    }

    public List<Tag> list(String kind) {
        return kind != null
                ? tagRepository.findByKindOrderByLabelAsc(kind)
                : tagRepository.findAllByOrderByKindAscLabelAsc();
    }

    /** {@code PATCH /tags/{id}} - rename a {@code topic} (US3 AC-2); existing {@code document_tag} links are untouched. */
    @Transactional
    public Tag rename(KeycloakUser user, UUID id, String label) {
        Tag tag = tagRepository.findById(id).orElseThrow(() -> new NotFoundException("Không tìm thấy nhãn"));
        if (!TagKinds.TOPIC.equals(tag.getKind())) {
            throw new BadRequestException("Chỉ có thể đổi tên nhãn 'topic'");
        }

        String oldLabel = tag.getLabel();
        tag.setLabel(normalizeLabel(label));
        try {
            tagRepository.save(tag);
        } catch (DataIntegrityViolationException e) {
            if (SqlErrors.isUniqueViolation(e)) {
                throw new ConflictException("Đã có chủ đề cùng tên");
            }
            throw e;
        }

        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "tag.rename", "tag", tag.getId().toString(), "success",
                auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(),
                Map.of("oldLabel", oldLabel, "newLabel", tag.getLabel())));
        return tag;
    }

    /**
     * {@code POST /tags/{id}/deactivate} - replaces the old hard-delete
     * {@code DELETE /tags/{id}} (US3 AC-3/AC-4): a deactivated {@code topic}
     * can no longer be assigned (§4.1 step 3) but existing {@code
     * document_tag} links stay (Quyết định 2 SA - no hard-delete of an
     * already-applied label).
     */
    @Transactional
    public Tag deactivate(KeycloakUser user, UUID id) {
        Tag tag = tagRepository.findById(id).orElseThrow(() -> new NotFoundException("Không tìm thấy nhãn"));
        if (!TagKinds.TOPIC.equals(tag.getKind())) {
            throw new BadRequestException("Chỉ có thể ngừng dùng nhãn 'topic'");
        }

        tag.setActive(false);
        tagRepository.save(tag);

        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "tag.deactivate", "tag", tag.getId().toString(), "success",
                auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(), null));
        return tag;
    }

    // ---- document-scoped reads/writes (§4.1) -----------------------------

    /** rbac-group-scope.md-style guard: same 404-for-out-of-scope contract as {@code DocumentsService.findOne}. */
    public List<Tag> listForDocument(KeycloakUser user, UUID documentId) {
        documentScopeGuard.requireVisible(user, documentId);
        return documentTagRepository.findTagsForDocument(documentId);
    }

    /**
     * §4.1/§4.2/§4.3 step 4: assigns an existing tag ({@code tagId}) or,
     * when {@code allow_adhoc} is on, creates+assigns a new {@code topic} on
     * the fly ({@code label}). Assigning a {@code doc_type} atomically
     * replaces the document's previous {@code doc_type} link, if any (US2
     * AC-3) - never a 409 for that case, unlike {@code topic} (already-
     * assigned {@code topic} -> 409, US1).
     */
    @Transactional
    public void assign(KeycloakUser user, UUID documentId, UUID tagId, String label) {
        documentScopeGuard.requireVisibleClean(user, documentId);

        Tag tag = resolveAssignTarget(user, tagId, label);
        if (!tag.isActive()) {
            throw new BadRequestException("Nhãn đã ngừng sử dụng");
        }

        if (TagKinds.DOC_TYPE.equals(tag.getKind())) {
            // §4.3 step 4: replace, not reject - drop any previous doc_type
            // link for this document before inserting the new one, in the
            // same transaction, so UQ_document_tag_one_doc_type never sees a
            // moment with 2 rows for this document.
            documentTagRepository.deleteByDocumentIdAndTagKind(documentId, TagKinds.DOC_TYPE);
        }

        try {
            documentTagRepository.insert(
                    documentId, tag.getId(), tag.getKind(), user.sub(), user.preferredUsername());
        } catch (DataIntegrityViolationException e) {
            if (SqlErrors.isUniqueViolation(e)) {
                throw new ConflictException("Tài liệu đã được gắn nhãn này");
            }
            throw e;
        }

        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "tag.assign", "document", documentId.toString(), "success",
                auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(),
                Map.of("tagId", tag.getId().toString())));

        ClassificationOutbox.write(outboxEventRepository, documentId, user.sub());
    }

    private Tag resolveAssignTarget(KeycloakUser user, UUID tagId, String label) {
        if (tagId != null) {
            return tagRepository.findById(tagId).orElseThrow(() -> new NotFoundException("Không tìm thấy nhãn"));
        }
        if (label == null || label.isBlank()) {
            throw new BadRequestException("tagId hoặc label là bắt buộc");
        }

        // §4.2: ad-hoc topic creation at assign time.
        String normalized = normalizeLabel(label);
        Optional<Tag> existing = tagRepository.findByKindAndLabelIgnoreCase(TagKinds.TOPIC, normalized);
        if (existing.isPresent()) {
            return existing.get();
        }
        boolean allowAdhoc = systemConfigService.getBoolean(SystemConfigDefaults.CLASSIFICATION_ALLOW_ADHOC_TOPIC_TAG);
        if (!allowAdhoc) {
            // §4.2 step 2: 400 (not 404) - this is the ad-hoc-by-label path,
            // distinct from the by-tagId path (§4.1 step 3) which is a plain
            // "load by id" 404. Same "nhãn không tồn tại" message, different
            // status per spec §4.2/§5 test plan.
            throw new BadRequestException("Nhãn không tồn tại");
        }
        return createTopic(user, normalized);
    }

    /**
     * {@code DELETE /documents/{documentId}/tags/{tagId}}. A {@code doc_type}
     * link can never be removed through this standalone endpoint (US2 AC-2 -
     * {@code doc_type} is mandatory); replacing it is only possible via
     * {@link #assign} (§3 API contract, DELETE row). This guard does not
     * apply to {@link #assign}'s own internal
     * {@code deleteByDocumentIdAndTagKind} replace step, which is not routed
     * through this method.
     */
    @Transactional
    public void unassign(KeycloakUser user, UUID documentId, UUID tagId) {
        documentScopeGuard.requireVisible(user, documentId);

        DocumentTag link = documentTagRepository
                .findById(new DocumentTagId(documentId, tagId))
                .orElseThrow(() -> new NotFoundException("Tài liệu chưa được gắn nhãn này"));

        if (TagKinds.DOC_TYPE.equals(link.getTagKind())) {
            throw new BadRequestException("doc_type là bắt buộc, không thể gỡ mà không gán nhãn thay thế");
        }

        documentTagRepository.deleteByDocumentIdAndTagId(documentId, tagId);

        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "tag.unassign", "document", documentId.toString(), "success",
                auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(),
                Map.of("tagId", tagId.toString())));

        ClassificationOutbox.write(outboxEventRepository, documentId, user.sub());
    }

    // ---- report (§4.5, US5) ----------------------------------------------

    /** One row of {@code GET /tags/report}'s response. */
    public record TagReportRow(UUID tagId, String kind, String label, long count) {
    }

    /**
     * {@code GET /tags/report}: always scoped through {@link
     * BusinessDataScope} before {@code GROUP BY} (§4.5 step 1-3) - never
     * cached per-user (§4.5 step 4, only the catalogue {@code GET /tags} is
     * cached).
     */
    public List<TagReportRow> report(KeycloakUser user, String kind, LocalDate from, LocalDate to, UUID folderId) {
        Specification<Document> scope = BusinessDataScope.forDocuments(user.sub(), groupMemberRepository);
        OffsetDateTime fromInclusive = from != null ? from.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime() : null;
        OffsetDateTime toExclusive =
                to != null ? to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime() : null;

        return tagReportRepository.report(scope, kind, fromInclusive, toExclusive, folderId).stream()
                .map(row -> new TagReportRow(row.tagId(), row.kind(), row.label(), row.count()))
                .toList();
    }

    // ---- internals ---------------------------------------------------------

    /**
     * §2 Data model bước 4 / §4.2 step 3: trim + collapse internal whitespace
     * so labels compare consistently with the DB's {@code lower(label)}
     * unique index.
     */
    private static String normalizeLabel(String label) {
        if (label == null) {
            throw new BadRequestException("label là bắt buộc");
        }
        String normalized = label.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            throw new BadRequestException("label là bắt buộc");
        }
        return normalized;
    }
}
