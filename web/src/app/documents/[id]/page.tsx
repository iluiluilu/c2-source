'use client';

import { nameTooltip } from '@/lib/nameTooltip';
import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import { useSession } from 'next-auth/react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { ApiError, TAG_KIND_LABELS, apiFetch, apiJson } from '@/lib/api';
import type {
  DocumentNote,
  DocumentRow,
  DocumentShareRow,
  FolderNode,
  GroupRow,
  ReviewRunSummary,
  TagKind,
  TagRow,
  TrashPreviewItem,
} from '@/lib/api';
import { AccessDenied } from '@/components/AccessDenied';
import { AppShell } from '@/components/AppShell';
import { DocumentPreview } from '@/components/DocumentPreview';
import { useConfirm } from '@/components/ConfirmDialog';
import { canSeeFeature } from '@/lib/features';
import { usePermissions } from '@/lib/permissions';
import { downloadDocument } from '@/lib/download';
import { folderPathLabel } from '@/lib/folderPath';
import { TRASH_NOTE } from '@/lib/trashNote';
import { toast } from '@/lib/toast';
import { TASK_LABELS as DIGITIZATION_TASK_LABELS } from '@/lib/digitizationLabels';

const NOTE_MAX_LENGTH = 4000;

const STATUS_LABELS: Record<DocumentRow['status'], string> = {
  pending: 'Đang xử lý',
  clean: 'Sạch',
  infected: 'Nhiễm mã độc',
  rejected: 'Bị từ chối',
};

/** digitization-review-07-confirmed-content-view.md §4: "Nội dung số hoá". */

function formatSize(size: string): string {
  const bytes = Number(size);
  if (bytes >= 1_073_741_824) return `${(bytes / 1_073_741_824).toFixed(1)} GB`;
  if (bytes >= 1_048_576) return `${(bytes / 1_048_576).toFixed(1)} MB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${bytes} B`;
}

/** Document detail: metadata + tags (FR-15) + notes (FR-16). R1 edits, R2 reads. */
export default function DocumentDetailPage() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const { data: session, status } = useSession();
  const router = useRouter();
  const confirm = useConfirm();
  const [doc, setDoc] = useState<DocumentRow | null>(null);
  const [docTags, setDocTags] = useState<TagRow[]>([]);
  const [allTags, setAllTags] = useState<TagRow[]>([]);
  const [notes, setNotes] = useState<DocumentNote[]>([]);
  const [shares, setShares] = useState<DocumentShareRow[]>([]);
  const [myGroups, setMyGroups] = useState<GroupRow[]>([]);
  const [confirmedRuns, setConfirmedRuns] = useState<ReviewRunSummary[]>([]);
  const [folders, setFolders] = useState<FolderNode[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [savingNote, setSavingNote] = useState(false);

  // Share panel controls (group-management-sharing-02-document-sharing.md §4.6)
  const [shareGroupId, setShareGroupId] = useState('');

  // Tag controls (R1)
  const [assignId, setAssignId] = useState('');
  const [newTagKind, setNewTagKind] = useState<TagKind>('topic');
  const [newTagLabel, setNewTagLabel] = useState('');

  // Note controls (R1)
  const [newNote, setNewNote] = useState('');
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editBody, setEditBody] = useState('');
  const [editBaseline, setEditBaseline] = useState('');

  const canView = canSeeFeature(session, 'documents');
  const perms = usePermissions();
  const canDeleteDoc = perms.can('trash.manage');
  const canManageTags = perms.can('tag.manage');
  const canWriteNotes = perms.can('note.write');
  const canShareGroup = perms.can('document.share');
  // Rejected/infected rows are FR-12 evidence only: the backend refuses new tags/notes/shares (409), so hide the forms.
  const docClean = doc?.status === 'clean';

  const load = useCallback(() => {
    apiJson<DocumentRow>(`/documents/${id}`, session)
      .then(setDoc)
      .catch((e: Error) => setError(e.message));
    apiJson<TagRow[]>(`/documents/${id}/tags`, session)
      .then(setDocTags)
      .catch(() => setDocTags([]));
    apiJson<DocumentNote[]>(`/documents/${id}/notes`, session)
      .then(setNotes)
      .catch(() => setNotes([]));
    apiJson<TagRow[]>('/tags', session)
      .then(setAllTags)
      .catch(() => setAllTags([]));
    apiJson<DocumentShareRow[]>(`/documents/${id}/shares`, session)
      .then(setShares)
      .catch(() => setShares([]));
    apiJson<GroupRow[]>('/groups', session)
      .then(setMyGroups)
      .catch(() => setMyGroups([]));
    apiJson<ReviewRunSummary[]>(`/digitization-review/documents/${id}/confirmed-runs`, session)
      .then(setConfirmedRuns)
      .catch(() => setConfirmedRuns([]));
    // Only for the "Thư mục" line - a failure just hides it.
    apiJson<FolderNode[]>('/folders/tree', session)
      .then(setFolders)
      .catch(() => setFolders([]));
  }, [session, id]);

  useEffect(() => {
    if (!canView) return;
    load();
  }, [canView, load]);

  const assignedIds = useMemo(() => new Set(docTags.map((t) => t.id)), [docTags]);
  const assignable = allTags.filter((t) => !assignedIds.has(t.id));

  const sharedGroupIds = useMemo(() => new Set(shares.map((s) => s.groupId)), [shares]);
  const shareableGroups = myGroups.filter((g) => !sharedGroupIds.has(g.id));

  if (status === 'loading') return null;
  if (!session || !canView) {
    return <AccessDenied featureKey="documents" />;
  }

  const friendlyError = (e: unknown): string => {
    if (e instanceof ApiError && e.status === 403) {
      return 'Chỉ chủ nhóm, người đã chia sẻ, hoặc người nạp tài liệu mới có thể gỡ chia sẻ này.';
    }
    return e instanceof Error ? e.message : String(e);
  };

  const run = async (action: () => Promise<void>, ok: string) => {
    setError(null);
    setBusy(true);
    try {
      await action();
      toast.success(ok);
      load();
    } catch (e) {
      setError(friendlyError(e));
    } finally {
      setBusy(false);
    }
  };

  // ---- FR-15 tag actions ----

  const assignTag = () => {
    if (!assignId) return;
    void run(async () => {
      await apiFetch(`/documents/${id}/tags`, session, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ tagId: assignId }),
      });
      setAssignId('');
    }, 'Đã gắn nhãn.');
  };

  const createAndAssignTag = () => {
    const label = newTagLabel.trim();
    if (!label) return;
    void run(async () => {
      // Create-on-the-fly, then assign to this document.
      const tag = await apiJson<TagRow>('/tags', session, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ kind: newTagKind, label }),
      });
      await apiFetch(`/documents/${id}/tags`, session, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ tagId: tag.id }),
      });
      setNewTagLabel('');
    }, 'Đã tạo và gắn nhãn mới.');
  };

  const unassignTag = (tagId: string) =>
    void run(
      () =>
        apiFetch(`/documents/${id}/tags/${tagId}`, session, {
          method: 'DELETE',
        }).then(() => undefined),
      'Đã gỡ nhãn.',
    );

  // ---- group-management-sharing-02-document-sharing.md §4.6: chia sẻ ----

  const shareToGroup = () => {
    if (!shareGroupId) return;
    void run(async () => {
      await apiFetch(`/documents/${id}/shares`, session, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ groupId: shareGroupId }),
      });
      setShareGroupId('');
    }, 'Đã chia sẻ tài liệu vào nhóm.');
  };

  const unshareFromGroup = async (groupId: string) => {
    const ok = await confirm({
      title: 'Gỡ chia sẻ',
      message: 'Xác nhận gỡ chia sẻ tài liệu này khỏi nhóm?',
    });
    if (!ok) return;
    void run(
      () =>
        apiFetch(`/documents/${id}/shares/${groupId}`, session, {
          method: 'DELETE',
        }).then(() => undefined),
      'Đã gỡ chia sẻ.',
    );
  };

  // ---- FR-16 note actions ----

  const addNote = () => {
    const body = newNote.trim();
    if (!body) return;
    void run(async () => {
      await apiFetch(`/documents/${id}/notes`, session, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ body }),
      });
      setNewNote('');
    }, 'Đã thêm ghi chú.');
  };

  const startEdit = (note: DocumentNote) => {
    setEditingId(note.id);
    setEditBody(note.body);
    setEditBaseline(note.updatedAt);
  };

  // ---- delete-restore-trash-01-xoa-mem-thung-rac.md Story 2: xóa mềm ----

  const deleteDocument = async () => {
    if (!doc) return;
    let totalGroups: number | null = null;
    try {
      const preview = await apiJson<TrashPreviewItem[]>('/documents/trash-preview', session, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ documentIds: [id] }),
      });
      totalGroups = preview.reduce((s, p) => s + p.activeGroupShareCount, 0);
    } catch {
      totalGroups = null; // AC-7: preview lỗi/chậm không chặn xóa
    }

    let confirmMessage = doc.originalName;
    if (totalGroups === null) {
      confirmMessage += '\n\nKhông tải được số nhóm bị ảnh hưởng.';
    } else if (totalGroups > 0) {
      confirmMessage += `\n\n${totalGroups} nhóm sẽ mất quyền xem tài liệu này.`;
    }
    // totalGroups === 0: không thêm dòng nào (Quyết định 1)
    confirmMessage += `\n\n${TRASH_NOTE}`;

    const ok = await confirm({
      title: 'Xóa tài liệu',
      message: confirmMessage,
      tone: 'danger',
      confirmLabel: 'Xóa',
    });
    if (!ok) return;

    setError(null);
    setDeleting(true);
    try {
      await apiFetch('/documents/trash', session, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ documentIds: [id] }),
      });
      toast.success('Đã chuyển tài liệu vào thùng rác. Đang quay lại kho tài liệu…');
      setTimeout(() => router.push('/documents'), 1500);
    } catch (e) {
      setError((e as Error).message); // AC-6: dialog đã đóng nhưng thao tác không thành công
      setDeleting(false);
    }
  };

  const saveEdit = () => {
    const body = editBody.trim();
    if (!editingId || !body) return;
    const noteId = editingId;
    setError(null);
    setSavingNote(true);
    apiFetch(`/documents/${id}/notes/${noteId}`, session, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      // Optimistic concurrency: reject if someone edited since we loaded.
      body: JSON.stringify({ body, expectedUpdatedAt: editBaseline }),
    })
      .then(() => {
        setEditingId(null);
        toast.success('Đã lưu ghi chú.');
        load();
      })
      .catch((e: Error) => {
        if (e instanceof ApiError && e.status === 409) {
          setError(`${e.message} — nội dung mới nhất đã được tải lại.`);
          setEditingId(null);
          load();
        } else {
          setError(e.message);
        }
      })
      .finally(() => setSavingNote(false));
  };

  return (
    <AppShell title="Kho tài liệu">
      <div className="max-w-7xl">
        <Link
          href="/documents"
          className="text-sm text-gray-500 hover:text-[#0D2546]"
        >
          ← Kho tài liệu
        </Link>

        {error && (
          <div className="mt-4 bg-red-50 border border-red-200 rounded-md p-3 text-sm text-red-800">
            {error}
          </div>
        )}

        {!doc ? (
          <p className="mt-6 text-gray-400 text-sm">Đang tải…</p>
        ) : (
          <>
            {/* Header + metadata */}
            <div className="mt-4 flex items-center gap-3">
              <h2
                className="text-2xl font-bold text-[#0D2546] break-all"
                title={nameTooltip({
                  originalName: doc.originalName,
                  displayName: doc.displayName,
                  relativePath: doc.relativePath,
                })}
              >
                {doc.originalName}
              </h2>
              <span
                className={`text-xs font-semibold px-2 py-0.5 rounded ${
                  doc.status === 'clean'
                    ? 'text-green-700 bg-green-50'
                    : 'text-red-700 bg-red-50'
                }`}
              >
                {STATUS_LABELS[doc.status]}
              </span>
              <div className="ml-auto flex items-center gap-3">
                {docClean && (
                  <button
                    onClick={() =>
                      void downloadDocument(session, doc.id, doc.originalName).catch((e: Error) =>
                        setError(e.message),
                      )
                    }
                    className="rounded-md bg-[#0D2546] px-4 py-2 text-sm font-semibold text-white hover:bg-[#163a6b] cursor-pointer"
                  >
                    Tải xuống
                  </button>
                )}
                {canDeleteDoc && (
                  <button
                    onClick={() => void deleteDocument()}
                    disabled={deleting}
                    className="rounded-md border border-red-200 px-4 py-2 text-sm font-semibold text-[#D11A2A] hover:bg-red-50 disabled:opacity-60 cursor-pointer disabled:cursor-not-allowed"
                  >
                    {deleting ? 'Đang xóa…' : 'Xóa'}
                  </button>
                )}
              </div>
            </div>

            <div className="mt-5 bg-white rounded-lg border border-gray-200 p-5 text-sm">
              <div className="grid md:grid-cols-2 gap-y-4 gap-x-8">
                <Meta label="Thư mục" value={folderPathLabel(folders, doc.folderId) ?? '—'} />
                <Meta label="Kích thước" value={formatSize(doc.size)} />
                <Meta label="Người tải" value={doc.uploaderUsername} />
                <Meta
                  label="Thời gian nạp"
                  value={new Date(doc.createdAt).toLocaleString('vi-VN')}
                />
              </div>
              {/* Integrity details matter for audits, not day-to-day reading. */}
              <details className="mt-4 border-t border-gray-100 pt-3">
                <summary className="cursor-pointer text-xs font-semibold text-gray-500 hover:text-[#0D2546]">
                  Thông tin kỹ thuật
                </summary>
                <div className="mt-3 grid gap-y-4">
                  <Meta label="Định dạng" value={doc.mimeType} />
                  <Meta
                    label="SHA-256"
                    value={doc.sha256 ?? 'Không có (tệp bị từ chối do sai định dạng)'}
                    mono
                  />
                </div>
              </details>
            </div>

            {docClean && (
              <div className="mt-6 bg-white rounded-lg border border-gray-200 p-5">
                <h3 className="font-semibold text-[#0D2546] mb-3 text-sm">Xem trước</h3>
                <DocumentPreview
                  session={session}
                  documentId={doc.id}
                  mimeType={doc.mimeType}
                  size={Number(doc.size)}
                />
              </div>
            )}

            {/* digitization-review-07-confirmed-content-view.md §4: chỉ hiện khi có ít nhất 1 run đã duyệt */}
            {confirmedRuns.length > 0 && (
              <div className="mt-6 bg-white rounded-lg border border-gray-200 p-5">
                <h3 className="font-semibold text-[#0D2546] mb-3 text-sm">Nội dung số hoá</h3>
                <ul className="divide-y divide-gray-100">
                  {confirmedRuns.map((run) => (
                    <li key={run.runId}>
                      <Link
                        href={`/documents/${id}/digitization/${run.runId}`}
                        className="flex items-center justify-between py-2 text-sm hover:text-[#0D2546]"
                      >
                        <span className="text-gray-700">{DIGITIZATION_TASK_LABELS[run.task]}</span>
                        <span className="text-gray-400">{run.segmentCount} đoạn · Xem →</span>
                      </Link>
                    </li>
                  ))}
                </ul>
              </div>
            )}

            {/* FR-15: tags */}
            <div className="mt-6 bg-white rounded-lg border border-gray-200 p-5">
              <h3 className="font-semibold text-[#0D2546] mb-3 text-sm">
                Nhãn phân loại
              </h3>
              <div className="flex flex-wrap gap-2">
                {docTags.length === 0 && (
                  <p className="text-sm text-gray-400">Chưa có nhãn nào.</p>
                )}
                {docTags.map((t) => (
                  <span
                    key={t.id}
                    className="inline-flex items-center gap-1.5 text-sm px-2.5 py-1 rounded-full bg-[#0D2546]/5 text-[#0D2546]"
                  >
                    <span className="text-xs text-gray-400">
                      {TAG_KIND_LABELS[t.kind]}:
                    </span>
                    {t.label}
                    {canManageTags && (
                      <button
                        onClick={() => unassignTag(t.id)}
                        disabled={busy}
                        title="Gỡ nhãn"
                        className="text-gray-400 hover:text-[#D11A2A] disabled:opacity-60 font-bold cursor-pointer disabled:cursor-not-allowed"
                      >
                        ×
                      </button>
                    )}
                  </span>
                ))}
              </div>

              {canManageTags && docClean && (
                <div className="mt-4 grid md:grid-cols-2 gap-4">
                  <div>
                    <p className="text-xs font-semibold uppercase tracking-wide text-gray-400 mb-1.5">
                      Gắn nhãn có sẵn
                    </p>
                    <div className="flex gap-2">
                      <select
                        value={assignId}
                        onChange={(e) => setAssignId(e.target.value)}
                        className="flex-1 border border-gray-300 rounded-md px-2 py-1.5 text-sm text-gray-800"
                      >
                        <option value="">
                          {assignable.length
                            ? '— Chọn nhãn —'
                            : 'Không còn nhãn để gắn'}
                        </option>
                        {(Object.keys(TAG_KIND_LABELS) as TagKind[]).map(
                          (kind) => {
                            const group = assignable.filter(
                              (t) => t.kind === kind,
                            );
                            if (group.length === 0) return null;
                            return (
                              <optgroup
                                key={kind}
                                label={TAG_KIND_LABELS[kind]}
                              >
                                {group.map((t) => (
                                  <option key={t.id} value={t.id}>
                                    {t.label}
                                  </option>
                                ))}
                              </optgroup>
                            );
                          },
                        )}
                      </select>
                      <button
                        onClick={assignTag}
                        disabled={!assignId || busy}
                        className="bg-[#0D2546] hover:bg-[#163a6b] disabled:bg-gray-300 text-white font-semibold px-3 py-1.5 rounded-md text-sm cursor-pointer disabled:cursor-not-allowed"
                      >
                        Gắn
                      </button>
                    </div>
                  </div>
                  <div>
                    <p className="text-xs font-semibold uppercase tracking-wide text-gray-400 mb-1.5">
                      Tạo nhãn mới
                    </p>
                    <div className="flex gap-2">
                      <select
                        value={newTagKind}
                        onChange={(e) =>
                          setNewTagKind(e.target.value as TagKind)
                        }
                        className="border border-gray-300 rounded-md px-2 py-1.5 text-sm text-gray-800"
                      >
                        {/* POST /tags only ever creates a topic (doc_type is fixed, US3 AC-6). */}
                        {(['topic'] as TagKind[]).map(
                          (kind) => (
                            <option key={kind} value={kind}>
                              {TAG_KIND_LABELS[kind]}
                            </option>
                          ),
                        )}
                      </select>
                      <input
                        value={newTagLabel}
                        onChange={(e) => setNewTagLabel(e.target.value)}
                        maxLength={128}
                        placeholder="Tên nhãn"
                        className="flex-1 border border-gray-300 rounded-md px-2 py-1.5 text-sm text-gray-800"
                      />
                      <button
                        onClick={createAndAssignTag}
                        disabled={!newTagLabel.trim() || busy}
                        className="bg-[#0D2546] hover:bg-[#163a6b] disabled:bg-gray-300 text-white font-semibold px-3 py-1.5 rounded-md text-sm cursor-pointer disabled:cursor-not-allowed"
                      >
                        Tạo &amp; gắn
                      </button>
                    </div>
                  </div>
                </div>
              )}
            </div>

            {/* group-management-sharing-02-document-sharing.md §4.6: chia sẻ */}
            <div className="mt-6 bg-white rounded-lg border border-gray-200 p-5">
              <h3 className="font-semibold text-[#0D2546] mb-3 text-sm">
                Chia sẻ với nhóm ({shares.length})
              </h3>
              <div className="flex flex-wrap gap-2">
                {shares.length === 0 && (
                  <p className="text-sm text-gray-400">Chưa chia sẻ tới nhóm nào.</p>
                )}
                {shares.map((s) => (
                  <span
                    key={s.groupId}
                    className="inline-flex items-center gap-1.5 text-sm px-2.5 py-1 rounded-full bg-[#0D2546]/5 text-[#0D2546]"
                  >
                    {s.groupName ?? s.groupId}
                    <button
                      onClick={() => void unshareFromGroup(s.groupId)}
                      disabled={busy}
                      title="Gỡ chia sẻ"
                      className="text-gray-400 hover:text-[#D11A2A] disabled:opacity-60 font-bold cursor-pointer disabled:cursor-not-allowed"
                    >
                      ×
                    </button>
                  </span>
                ))}
              </div>

              {canShareGroup && docClean && (
                <div className="mt-4 flex gap-2">
                  <select
                    value={shareGroupId}
                    onChange={(e) => setShareGroupId(e.target.value)}
                    className="flex-1 border border-gray-300 rounded-md px-2 py-1.5 text-sm text-gray-800"
                  >
                    <option value="">
                      {shareableGroups.length
                        ? '— Chọn nhóm —'
                        : 'Không còn nhóm để chia sẻ thêm'}
                    </option>
                    {shareableGroups.map((g) => (
                      <option key={g.id} value={g.id}>
                        {g.name}
                      </option>
                    ))}
                  </select>
                  <button
                    onClick={shareToGroup}
                    disabled={!shareGroupId || busy}
                    className="bg-[#0D2546] hover:bg-[#163a6b] disabled:bg-gray-300 text-white font-semibold px-3 py-1.5 rounded-md text-sm cursor-pointer disabled:cursor-not-allowed"
                  >
                    Chia sẻ
                  </button>
                </div>
              )}
            </div>

            {/* FR-16: notes */}
            <div className="mt-6 bg-white rounded-lg border border-gray-200 p-5">
              <h3 className="font-semibold text-[#0D2546] mb-3 text-sm">
                Ghi chú ({notes.length})
              </h3>

              {canWriteNotes && docClean && (
                <div className="mb-4">
                  <textarea
                    value={newNote}
                    onChange={(e) => setNewNote(e.target.value)}
                    maxLength={NOTE_MAX_LENGTH}
                    rows={3}
                    placeholder="Thêm ghi chú cho tệp dữ liệu…"
                    className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm text-gray-800"
                  />
                  <div className="flex items-center justify-between mt-1.5">
                    <p
                      className={`text-xs ${
                        newNote.length > NOTE_MAX_LENGTH - 200
                          ? 'text-[#D11A2A]'
                          : 'text-gray-400'
                      }`}
                    >
                      {newNote.length}/{NOTE_MAX_LENGTH} ký tự
                    </p>
                    <button
                      onClick={addNote}
                      disabled={!newNote.trim() || busy}
                      className="bg-[#0D2546] hover:bg-[#163a6b] disabled:bg-gray-300 text-white font-semibold px-4 py-1.5 rounded-md text-sm cursor-pointer disabled:cursor-not-allowed"
                    >
                      {busy ? 'Đang xử lý…' : 'Thêm ghi chú'}
                    </button>
                  </div>
                </div>
              )}

              {notes.length === 0 ? (
                <p className="text-sm text-gray-400">Chưa có ghi chú nào.</p>
              ) : (
                <ul className="space-y-3">
                  {notes.map((note) => (
                    <li
                      key={note.id}
                      className="border border-gray-100 rounded-md p-3 bg-gray-50/50"
                    >
                      {editingId === note.id ? (
                        <div>
                          <textarea
                            value={editBody}
                            onChange={(e) => setEditBody(e.target.value)}
                            maxLength={NOTE_MAX_LENGTH}
                            rows={3}
                            className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm text-gray-800 bg-white"
                          />
                          <div className="flex gap-2 mt-2">
                            <button
                              onClick={saveEdit}
                              disabled={!editBody.trim() || savingNote}
                              className="bg-[#0D2546] hover:bg-[#163a6b] disabled:bg-gray-300 text-white font-semibold px-3 py-1 rounded-md text-sm cursor-pointer disabled:cursor-not-allowed"
                            >
                              {savingNote ? 'Đang lưu…' : 'Lưu'}
                            </button>
                            <button
                              onClick={() => setEditingId(null)}
                              disabled={savingNote}
                              className="text-gray-500 hover:text-gray-700 disabled:opacity-60 text-sm font-semibold px-2 cursor-pointer disabled:cursor-not-allowed"
                            >
                              Hủy
                            </button>
                          </div>
                        </div>
                      ) : (
                        <>
                          <p className="text-sm text-gray-800 whitespace-pre-wrap">
                            {note.body}
                          </p>
                          <div className="flex items-center justify-between mt-2">
                            <p className="text-xs text-gray-400">
                              {note.createdByUsername} ·{' '}
                              {new Date(note.createdAt).toLocaleString('vi-VN')}
                              {note.updatedByUsername && (
                                <>
                                  {' '}
                                  · sửa bởi {note.updatedByUsername} lúc{' '}
                                  {new Date(note.updatedAt).toLocaleString(
                                    'vi-VN',
                                  )}
                                </>
                              )}
                            </p>
                            {canWriteNotes && docClean && (
                              <button
                                onClick={() => startEdit(note)}
                                className="text-[#0D2546] hover:text-[#D11A2A] text-xs font-semibold cursor-pointer"
                              >
                                Sửa
                              </button>
                            )}
                          </div>
                        </>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </>
        )}
      </div>
    </AppShell>
  );
}

function Meta({
  label,
  value,
  mono = false,
}: {
  label: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <div>
      <p className="text-xs font-semibold uppercase tracking-wide text-gray-400">
        {label}
      </p>
      <p
        className={`text-gray-800 mt-0.5 break-all ${mono ? 'font-mono text-xs' : ''}`}
      >
        {value}
      </p>
    </div>
  );
}
