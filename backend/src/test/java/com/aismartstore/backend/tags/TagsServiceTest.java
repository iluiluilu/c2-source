package com.aismartstore.backend.tags;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.BadRequestException;
import com.aismartstore.backend.common.NotFoundException;
import com.aismartstore.backend.groups.DocumentScopeGuard;
import com.aismartstore.backend.groups.GroupMemberRepository;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage of classification-notes-01-tags.md §5 ("Unit test -
 * TagsService"). Every collaborator is mocked - the DB-level invariants
 * ({@code UQ_document_tag_one_doc_type}, case-insensitive unique index,
 * {@code DocumentScopeGuard}'s real audit trail) are covered by {@code
 * TagsControllerIT} instead.
 */
class TagsServiceTest {

    private TagRepository tagRepository;
    private DocumentTagRepository documentTagRepository;
    private AuditService auditService;
    private DocumentScopeGuard documentScopeGuard;
    private SystemConfigService systemConfigService;
    private OutboxEventRepository outboxEventRepository;
    private GroupMemberRepository groupMemberRepository;
    private TagReportRepository tagReportRepository;
    private TagsService tagsService;

    private final KeycloakUser user = new KeycloakUser("user-1", "user-1", List.of("can-bo"));
    private final UUID documentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tagRepository = mock(TagRepository.class);
        documentTagRepository = mock(DocumentTagRepository.class);
        auditService = mock(AuditService.class);
        AuditRequestContext auditRequestContext = mock(AuditRequestContext.class);
        documentScopeGuard = mock(DocumentScopeGuard.class);
        systemConfigService = mock(SystemConfigService.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        groupMemberRepository = mock(GroupMemberRepository.class);
        tagReportRepository = mock(TagReportRepository.class);
        tagsService = new TagsService(
                tagRepository,
                documentTagRepository,
                auditService,
                auditRequestContext,
                documentScopeGuard,
                systemConfigService,
                outboxEventRepository,
                groupMemberRepository,
                tagReportRepository);
    }

    private static Tag topic(UUID id, String label, boolean active) {
        Tag tag = new Tag();
        tag.setKind(TagKinds.TOPIC);
        tag.setLabel(label);
        tag.setActive(active);
        setId(tag, id);
        return tag;
    }

    private static Tag docType(UUID id, String label) {
        Tag tag = new Tag();
        tag.setKind(TagKinds.DOC_TYPE);
        tag.setLabel(label);
        tag.setActive(true);
        setId(tag, id);
        return tag;
    }

    /** {@code Tag.id} has no public setter (DB-generated) - reflection keeps fixtures realistic. */
    private static void setId(Tag tag, UUID id) {
        try {
            var field = Tag.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(tag, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // ---- assign/unassign topic hợp lệ --------------------------------------

    @Test
    void assignsAnExistingActiveTopicToADocument() {
        UUID tagId = UUID.randomUUID();
        Tag tag = topic(tagId, "Hợp đồng", true);
        when(tagRepository.findById(tagId)).thenReturn(Optional.of(tag));

        tagsService.assign(user, documentId, tagId, null);

        verify(documentScopeGuard).requireVisible(user, documentId);
        verify(documentTagRepository).insert(documentId, tagId, TagKinds.TOPIC, user.sub(), user.preferredUsername());
        verify(outboxEventRepository).save(any());
        verify(documentTagRepository, never()).deleteByDocumentIdAndTagKind(any(), any());
    }

    @Test
    void unassignsATopicLink() {
        UUID tagId = UUID.randomUUID();
        DocumentTag link = new DocumentTag();
        link.setDocumentId(documentId);
        link.setTagId(tagId);
        link.setTagKind(TagKinds.TOPIC);
        when(documentTagRepository.findById(new DocumentTagId(documentId, tagId))).thenReturn(Optional.of(link));

        tagsService.unassign(user, documentId, tagId);

        verify(documentScopeGuard).requireVisible(user, documentId);
        verify(documentTagRepository).deleteByDocumentIdAndTagId(documentId, tagId);
        verify(outboxEventRepository).save(any());
    }

    // ---- gán nhãn active=false -> 400 --------------------------------------

    @Test
    void assigningADeactivatedTopicIsRejectedWith400() {
        UUID tagId = UUID.randomUUID();
        when(tagRepository.findById(tagId)).thenReturn(Optional.of(topic(tagId, "Cũ", false)));

        assertThatThrownBy(() -> tagsService.assign(user, documentId, tagId, null))
                .isInstanceOf(BadRequestException.class);

        verify(documentTagRepository, never()).insert(any(), any(), any(), any(), any());
    }

    // ---- ad-hoc topic creation gated by classification.allow_adhoc_topic_tag ----

    @Test
    void assigningAnUnknownLabelWithAdhocDisabledIsRejectedWith400() {
        when(tagRepository.findByKindAndLabelIgnoreCase(TagKinds.TOPIC, "Mới")).thenReturn(Optional.empty());
        when(systemConfigService.getBoolean(SystemConfigDefaults.CLASSIFICATION_ALLOW_ADHOC_TOPIC_TAG))
                .thenReturn(false);

        assertThatThrownBy(() -> tagsService.assign(user, documentId, null, "Mới"))
                .isInstanceOf(BadRequestException.class);

        verify(tagRepository, never()).save(any());
    }

    @Test
    void assigningAnUnknownLabelWithAdhocEnabledCreatesAndAssignsIt() {
        when(tagRepository.findByKindAndLabelIgnoreCase(TagKinds.TOPIC, "Mới")).thenReturn(Optional.empty());
        when(systemConfigService.getBoolean(SystemConfigDefaults.CLASSIFICATION_ALLOW_ADHOC_TOPIC_TAG))
                .thenReturn(true);
        when(tagRepository.save(any())).thenAnswer(invocation -> {
            Tag saved = invocation.getArgument(0);
            setId(saved, UUID.randomUUID());
            return saved;
        });

        tagsService.assign(user, documentId, null, "  Mới   chủ đề  ");

        verify(tagRepository).save(any());
        verify(documentTagRepository).insert(eq(documentId), any(), eq(TagKinds.TOPIC), eq(user.sub()), anyString());
        verify(outboxEventRepository).save(any());
    }

    // ---- gán ngoài phạm vi -> 404 -------------------------------------------

    @Test
    void assigningOutOfScopeIsRejectedByTheGuardWith404() {
        org.mockito.Mockito.doThrow(new NotFoundException("Document not found"))
                .when(documentScopeGuard)
                .requireVisible(user, documentId);

        assertThatThrownBy(() -> tagsService.assign(user, documentId, UUID.randomUUID(), null))
                .isInstanceOf(NotFoundException.class);

        verify(documentTagRepository, never()).insert(any(), any(), any(), any(), any());
    }

    @Test
    void listingForDocumentOutOfScopeIsRejectedByTheGuardWith404() {
        org.mockito.Mockito.doThrow(new NotFoundException("Document not found"))
                .when(documentScopeGuard)
                .requireVisible(user, documentId);

        assertThatThrownBy(() -> tagsService.listForDocument(user, documentId))
                .isInstanceOf(NotFoundException.class);
    }

    // ---- đổi tên topic giữ nguyên document_tag ------------------------------

    @Test
    void renamingATopicNeverTouchesDocumentTagLinks() {
        UUID tagId = UUID.randomUUID();
        Tag tag = topic(tagId, "Cũ", true);
        when(tagRepository.findById(tagId)).thenReturn(Optional.of(tag));
        when(tagRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Tag renamed = tagsService.rename(user, tagId, "Mới");

        assertThat(renamed.getLabel()).isEqualTo("Mới");
        verify(documentTagRepository, never()).deleteByDocumentIdAndTagId(any(), any());
        verify(documentTagRepository, never()).insert(any(), any(), any(), any(), any());
    }

    @Test
    void renamingADocTypeTagIsRejectedWith400() {
        UUID tagId = UUID.randomUUID();
        when(tagRepository.findById(tagId)).thenReturn(Optional.of(docType(tagId, "văn bản")));

        assertThatThrownBy(() -> tagsService.rename(user, tagId, "khác"))
                .isInstanceOf(BadRequestException.class);
    }

    // ---- ngừng dùng topic không xoá liên kết --------------------------------

    @Test
    void deactivatingATopicNeverDeletesExistingDocumentTagLinks() {
        UUID tagId = UUID.randomUUID();
        Tag tag = topic(tagId, "Cũ", true);
        when(tagRepository.findById(tagId)).thenReturn(Optional.of(tag));
        when(tagRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Tag deactivated = tagsService.deactivate(user, tagId);

        assertThat(deactivated.isActive()).isFalse();
        verify(documentTagRepository, never()).deleteByDocumentIdAndTagId(any(), any());
    }

    @Test
    void deactivatingADocTypeTagIsRejectedWith400() {
        UUID tagId = UUID.randomUUID();
        when(tagRepository.findById(tagId)).thenReturn(Optional.of(docType(tagId, "văn bản")));

        assertThatThrownBy(() -> tagsService.deactivate(user, tagId)).isInstanceOf(BadRequestException.class);
    }

    // ---- các thao tác trên kind=doc_type qua API danh mục -> 400 -----------

    @Test
    void creatingADocTypeTagThroughThePOSTTagsApiIsRejectedWith400() {
        assertThatThrownBy(() -> tagsService.create(user, TagKinds.DOC_TYPE, "văn bản"))
                .isInstanceOf(BadRequestException.class);
        verify(tagRepository, never()).save(any());
    }

    // ---- gán doc_type khi tài liệu đã có doc_type khác -> thay thế, không 409 ----

    @Test
    void assigningADocTypeReplacesAnyExistingDocTypeLinkInsteadOfConflicting() {
        UUID newTagId = UUID.randomUUID();
        when(tagRepository.findById(newTagId)).thenReturn(Optional.of(docType(newTagId, "hình ảnh")));

        tagsService.assign(user, documentId, newTagId, null);

        verify(documentTagRepository).deleteByDocumentIdAndTagKind(documentId, TagKinds.DOC_TYPE);
        verify(documentTagRepository)
                .insert(documentId, newTagId, TagKinds.DOC_TYPE, user.sub(), user.preferredUsername());
    }

    // ---- gỡ doc_type đơn lẻ (không kèm gán thay thế) -> 400 -----------------

    @Test
    void unassigningADocTypeLinkStandaloneIsRejectedWith400() {
        UUID tagId = UUID.randomUUID();
        DocumentTag link = new DocumentTag();
        link.setDocumentId(documentId);
        link.setTagId(tagId);
        link.setTagKind(TagKinds.DOC_TYPE);
        when(documentTagRepository.findById(new DocumentTagId(documentId, tagId))).thenReturn(Optional.of(link));

        assertThatThrownBy(() -> tagsService.unassign(user, documentId, tagId)).isInstanceOf(BadRequestException.class);

        verify(documentTagRepository, never()).deleteByDocumentIdAndTagId(any(), any());
    }

    // ---- tên topic trùng sau chuẩn hoá -> để DB unique index từ chối (409 ở IT) --
    // (Chuẩn hoá whitespace/hoa-thường ở tầng service; unique thật sự enforce ở DB
    // - xem TagsControllerIT.)

    @Test
    void createTopicNormalizesWhitespaceBeforeSaving() {
        when(tagRepository.save(any())).thenAnswer(invocation -> {
            Tag saved = invocation.getArgument(0);
            setId(saved, UUID.randomUUID());
            return saved;
        });

        Tag created = tagsService.create(user, TagKinds.TOPIC, "  Hợp   đồng   thuê  ");

        assertThat(created.getLabel()).isEqualTo("Hợp đồng thuê");
    }
}
