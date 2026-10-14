'use client';

import { getSession, useSession } from 'next-auth/react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { FolderTree } from '@/components/FolderTree';
import { ApiError, apiFetch, apiJson, apiUploadJson } from '@/lib/api';
import type {
  ChunkAckResponse,
  FolderNode,
  OpenPersonalBatchResponse,
  OpenUploadSessionResponse,
  PersonalFileRow,
  PersonalQuotaExceededError,
  PersonalUploadedFileResponse,
  QuotaInfo,
  UploadSessionStatusResponse,
} from '@/lib/api';
import { AccessDenied } from '@/components/AccessDenied';
import { AppShell } from '@/components/AppShell';
import { useConfirm } from '@/components/ConfirmDialog';
import { PersonalToWarehouseDialog } from '@/components/PersonalToWarehouseDialog';
import { canSeeFeature } from '@/lib/features';
import { usePermissions } from '@/lib/permissions';
import { collectSelection } from '@/lib/personalToWarehouse';
import { collectDroppedFiles } from '@/lib/dropEntries';
import type { DroppedFile } from '@/lib/dropEntries';
import { nfc } from '@/lib/displayName';
import { nameTooltip } from '@/lib/nameTooltip';
import { useLeaveGuard } from '@/lib/useLeaveGuard';
import {
  MAX_SUFFIX_RETRIES,
  TRANSIENT_RETRY_DELAYS_MS,
  generateSuffixName,
  isTransientUploadError,
  isDisplayNameConflict,
  originalNameTooLong,
  resolveDisplayNames,
} from '@/lib/uploadFlow';
import { toast } from '@/lib/toast';

function formatSize(bytes: number): string {
  if (bytes >= 1_073_741_824) return `${(bytes / 1_073_741_824).toFixed(1)} GB`;
  if (bytes >= 1_048_576) return `${(bytes / 1_048_576).toFixed(1)} MB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${bytes} B`;
}

function flattenFolders(
  nodes: FolderNode[],
  depth = 0,
): { id: string; label: string }[] {
  return nodes.flatMap((n) => [
    { id: n.id, label: `${'— '.repeat(depth)}${n.name}` },
    ...flattenFolders(n.children, depth + 1),
  ]);
}

type FileOutcome =
  | { phase: 'queued' }
  | { phase: 'uploading'; loadedBytes: number }
  | { phase: 'done'; displayName?: string }
  | { phase: 'error'; message: string };

interface QueuedFile {
  file: File;
  /** Sub-path inside the dropped folder(s); '' for a loose file (folder-upload-display-name AC-5). */
  relativePath: string;
  /** Unique name inside (folder, relativePath), assigned by name-check right before uploading. */
  displayName: string;
  outcome: FileOutcome;
}

type BatchState =
  | { phase: 'idle' }
  | { phase: 'opening' }
  | { phase: 'running' }
  | { phase: 'done' };

/** personal-storage-quota.md §3: the 409 error body carries usage numbers. */
function isQuotaExceeded(body: unknown): body is PersonalQuotaExceededError {
  return (
    !!body &&
    typeof body === 'object' &&
    (body as { code?: unknown }).code === 'QUOTA_EXCEEDED'
  );
}

function sleep(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * chunked-file-upload-02-personal-chunked-upload.md §4.5: for a file above
 * `quota.chunkThresholdBytes` (AC-1) - mirrors
 * `web/src/app/upload/page.tsx`'s `uploadOneChunked`
 * (chunked-file-upload-01-ingest-chunked-upload.md §4.5) almost verbatim:
 * same 3-attempt/backoff retry, resume-within-this-run via `GET status`
 * (AC-4), and `getSession()` fetched fresh before every request instead of a
 * `session` closed over at the top of `submitUpload()` (Quyết định 5 SA).
 * Differs only in: the init endpoint has no `relativePath`/`note`, and the
 * `complete` result maps to this page's own `FileOutcome` (`'done'`) using
 * `PersonalUploadedFileResponse`/`UploadedFileView`, NOT `IngestFileResult`.
 *
 * Module-level (not a closure inside the component) so it can be unit
 * tested directly (Task F5) without rendering `PersonalPage` - `folderId`
 * and `updateOutcome` are passed in explicitly instead of being closed over.
 */
export async function uploadOneChunked(
  batchId: string,
  item: QueuedFile,
  index: number,
  folderId: string | null,
  updateOutcome: (index: number, outcome: FileOutcome) => void,
): Promise<void> {
  updateOutcome(index, { phase: 'uploading', loadedBytes: 0 });

  const declaredSizeBytes = item.file.size;
  const maxAttempts = TRANSIENT_RETRY_DELAYS_MS.length + 1;
  let sessionId: string | null = null;
  let chunkSizeBytes = 0;
  let renamedTo: string | undefined;

  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      let offset: number;

      if (!sessionId) {
        const initSession = await getSession();
        const opened = await apiJson<OpenUploadSessionResponse>(
          `/personal/batches/${batchId}/upload-sessions`,
          initSession,
          {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              originalName: item.file.name,
              displayName: item.displayName,
              relativePath: item.relativePath,
              declaredSizeBytes,
              folderId: folderId ?? undefined,
              mimeType: item.file.type || undefined,
            }),
          },
        );
        sessionId = opened.sessionId;
        chunkSizeBytes = opened.chunkSizeBytes;
        offset = opened.receivedBytes;
      } else {
        // Resume within this submitUpload() run (AC-4): re-sync with the
        // server instead of re-sending bytes it may already have.
        const statusSession = await getSession();
        const current = await apiJson<UploadSessionStatusResponse>(
          `/upload-sessions/${sessionId}`,
          statusSession,
        );
        offset = current.receivedBytes;
      }

      while (offset < declaredSizeBytes) {
        const chunk = item.file.slice(offset, Math.min(offset + chunkSizeBytes, declaredSizeBytes));
        const chunkSession = await getSession();
        const ack = await apiJson<ChunkAckResponse>(
          `/upload-sessions/${sessionId}/chunks?offset=${offset}`,
          chunkSession,
          {
            method: 'PUT',
            headers: { 'Content-Type': 'application/octet-stream' },
            body: chunk,
          },
        );
        offset = ack.receivedBytes;
        updateOutcome(index, { phase: 'uploading', loadedBytes: offset });
      }

      // §3 "đính chính": the complete response here is `UploadedFileView` (`{status: 'STORED', file}`).
      // folder-upload-display-name AC-14/AC-15: on 409 DISPLAY_NAME_CONFLICT only the commit is repeated
      // with a fresh suffix (max 3) - the session keeps its chunks, nothing is uploaded again.
      let stored: PersonalUploadedFileResponse | undefined;
      for (let renames = 0; ; renames++) {
        try {
          const completeSession = await getSession();
          stored = await apiJson<PersonalUploadedFileResponse>(
            `/upload-sessions/${sessionId}/complete`,
            completeSession,
            {
              method: 'POST',
              ...(renamedTo
                ? {
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ displayName: renamedTo }),
                  }
                : {}),
            },
          );
          break;
        } catch (e) {
          if (isDisplayNameConflict(e) && renames < MAX_SUFFIX_RETRIES) {
            renamedTo = generateSuffixName(item.file.name);
            continue;
          }
          throw e;
        }
      }
      updateOutcome(index, { phase: 'done', displayName: stored?.file?.displayName ?? renamedTo ?? item.displayName });
      return;
    } catch (e) {
      if (isTransientUploadError(e) && attempt < maxAttempts) {
        await sleep(TRANSIENT_RETRY_DELAYS_MS[attempt - 1]);
        continue;
      }
      // 409 QUOTA_EXCEEDED (at complete time, AC-7): reuse the same helper as
      // the whole-file `uploadOne` branch below, no new quota-display logic.
      const msg =
        e instanceof ApiError && isQuotaExceeded(e.body)
          ? `${e.message} (còn trống ${formatSize(e.body.remainingBytes)})`
          : e instanceof Error
            ? e.message
            : 'Lỗi không xác định';
      updateOutcome(index, { phase: 'error', message: msg });
      return;
    }
  }
}

/** FR-14: private per-user storage — folder tree, files, quota. All roles. */
export default function PersonalPage() {
  const { data: session, status } = useSession();
  const confirm = useConfirm();
  const [quota, setQuota] = useState<QuotaInfo | null>(null);
  const [tree, setTree] = useState<FolderNode[] | null>(null);
  const [files, setFiles] = useState<PersonalFileRow[] | null>(null);
  const [selectedFolder, setSelectedFolder] = useState<string | null>(null);
  const [queue, setQueue] = useState<QueuedFile[]>([]);
  const [batchState, setBatchState] = useState<BatchState>({ phase: 'idle' });
  const [error, setError] = useState<string | null>(null);

  // personal-to-warehouse.md: selection for "Bổ sung vào kho" (hand-picked files + optionally the open folder).
  const [pickedFileIds, setPickedFileIds] = useState<string[]>([]);
  const [includeFolder, setIncludeFolder] = useState(false);
  const [p2wOpen, setP2wOpen] = useState(false);

  const [newFolderName, setNewFolderName] = useState('');
  const [renameValue, setRenameValue] = useState('');
  const fileRef = useRef<HTMLInputElement>(null);
  const [dragActive, setDragActive] = useState(false);
  /** feedbacks D-13: a dropped folder (or selection) that contained no file at all. */
  const [emptyNotice, setEmptyNotice] = useState(false);

  const canView = canSeeFeature(session, 'personal');
  // AC-1: the button only exists for users allowed to upload to the warehouse (R1); hidden for R2/R3.
  const perms = usePermissions();
  const canAddToWarehouse = canSeeFeature(session, 'upload', perms);

  const load = useCallback(() => {
    apiJson<QuotaInfo>('/personal/quota', session)
      .then(setQuota)
      .catch((e: Error) => setError(e.message));
    apiJson<FolderNode[]>('/personal/folders/tree', session)
      .then(setTree)
      .catch(() => setTree([]));
    apiJson<PersonalFileRow[]>('/personal/files', session)
      .then(setFiles)
      .catch((e: Error) => setError(e.message));
  }, [session]);

  /** feedbacks P-07: files + quota only, called while a batch is running so stored files show up right away. */
  const refreshFilesAndQuota = useCallback(() => {
    apiJson<QuotaInfo>('/personal/quota', session).then(setQuota).catch(() => undefined);
    apiJson<PersonalFileRow[]>('/personal/files', session).then(setFiles).catch(() => undefined);
  }, [session]);

  useLeaveGuard(batchState.phase === 'opening' || batchState.phase === 'running');

  useEffect(() => {
    if (!canView) return;
    load();
  }, [canView, load]);

  if (status === 'loading') return null;
  if (!session || !canView) {
    return <AccessDenied featureKey="personal" />;
  }

  const folderOptions = flattenFolders(tree ?? []);
  const visibleFiles = (files ?? []).filter(
    (f) => f.folderId === selectedFolder,
  );
  const usedPercent = quota
    ? Math.min(100, Math.round((quota.usedBytes / quota.quotaBytes) * 100))
    : 0;
  const p2wFolderIds = includeFolder && selectedFolder ? [selectedFolder] : [];
  const p2wFiles = collectSelection(tree ?? [], files ?? [], pickedFileIds, p2wFolderIds);
  const isRunning = batchState.phase === 'opening' || batchState.phase === 'running';
  const isDone = batchState.phase === 'done';

  // Aggregate byte-level progress across the whole queue, for the overall bar.
  const totalQueueBytes = queue.reduce((s, q) => s + q.file.size, 0);
  const uploadedQueueBytes = queue.reduce((s, q) => {
    const o = q.outcome;
    if (o.phase === 'uploading') return s + o.loadedBytes;
    if (o.phase === 'done') return s + q.file.size;
    return s;
  }, 0);
  const overallPercent =
    totalQueueBytes > 0 ? Math.min(100, Math.round((uploadedQueueBytes / totalQueueBytes) * 100)) : 0;

  const run = async (action: () => Promise<void>, ok: string) => {
    setError(null);
    try {
      await action();
      toast.success(ok);
      load();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  // ---- folders ----

  const createFolder = () => {
    const name = newFolderName.trim();
    if (!name) return;
    void run(async () => {
      await apiFetch('/personal/folders', session, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name,
          ...(selectedFolder ? { parentId: selectedFolder } : {}),
        }),
      });
      setNewFolderName('');
    }, 'Đã tạo thư mục.');
  };

  const renameFolder = () => {
    const name = renameValue.trim();
    if (!name || !selectedFolder) return;
    void run(async () => {
      await apiFetch(`/personal/folders/${selectedFolder}`, session, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name }),
      });
      setRenameValue('');
    }, 'Đã đổi tên thư mục.');
  };

  const moveFolder = (parentId: string | null) => {
    if (!selectedFolder) return;
    void run(
      () =>
        apiFetch(`/personal/folders/${selectedFolder}`, session, {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ parentId }),
        }).then(() => undefined),
      'Đã chuyển thư mục.',
    );
  };

  const deleteFolder = async () => {
    if (!selectedFolder) return;
    const ok = await confirm({
      title: 'Xóa thư mục',
      message: 'Xóa thư mục này? (Thư mục phải trống)',
      confirmLabel: 'Xóa',
      tone: 'danger',
    });
    if (!ok) return;
    void run(async () => {
      await apiFetch(`/personal/folders/${selectedFolder}`, session, {
        method: 'DELETE',
      });
      setSelectedFolder(null);
    }, 'Đã xóa thư mục.');
  };

  // ---- files ----

  /** `items` come from a drop (files + folders, AC-1) or from "Chọn tệp" (loose files only, AC-2). */
  const selectFiles = (items: DroppedFile[]) => {
    setBatchState({ phase: 'idle' });
    setError(null);
    setEmptyNotice(items.length === 0);
    if (items.length === 0) {
      setQueue([]);
      return;
    }
    const tooLong = items.filter((i) => originalNameTooLong(i.file.name)).length;
    if (tooLong > 0) {
      setError(`${tooLong} tệp có tên dài quá 512 ký tự, không thể tải lên.`);
    }
    setQueue(
      items.map(({ file, relativePath }) => ({
        file,
        relativePath,
        displayName: nfc(file.name),
        outcome: { phase: 'queued' as const },
      })),
    );
  };

  const onDrop = (e: React.DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setDragActive(false);
    if (isRunning) return;
    // webkitGetAsEntry() must be read synchronously in the drop handler (see lib/dropEntries).
    void collectDroppedFiles(e.dataTransfer).then(selectFiles);
  };

  const updateOutcome = (index: number, outcome: FileOutcome) => {
    setQueue((prev) => prev.map((q, i) => (i === index ? { ...q, outcome } : q)));
  };

  /**
   * personal-storage-quota.md §3/Task 8: send one file tagged with an already-open batchId.
   * folder-upload-display-name AC-14/AC-15: a 409 DISPLAY_NAME_CONFLICT resends the file with a fresh suffix,
   * at most 3 times per file.
   */
  const uploadOne = async (batchId: string, item: QueuedFile, index: number) => {
    updateOutcome(index, { phase: 'uploading', loadedBytes: 0 });
    let displayName = item.displayName;
    let transientFailures = 0;
    for (let renames = 0; ; renames++) {
      const form = new FormData();
      form.append('file', item.file);
      form.append('relativePath', item.relativePath);
      form.append('displayName', displayName);
      if (selectedFolder) form.append('folderId', selectedFolder);

      try {
        await apiUploadJson(
          `/personal/batches/${batchId}/files`,
          session,
          form,
          (loadedBytes) => updateOutcome(index, { phase: 'uploading', loadedBytes }),
        );
        updateOutcome(index, { phase: 'done', displayName });
        return;
      } catch (e) {
        if (isDisplayNameConflict(e) && renames < MAX_SUFFIX_RETRIES) {
          displayName = generateSuffixName(item.file.name);
          continue;
        }
        // feedbacks P-06: this whole-file path used to fail on the first 503 (scanner restarting).
        if (isTransientUploadError(e) && transientFailures < TRANSIENT_RETRY_DELAYS_MS.length) {
          await sleep(TRANSIENT_RETRY_DELAYS_MS[transientFailures++]);
          renames--; // a transient failure is not a rename
          continue;
        }
        // 409 QUOTA_EXCEEDED (mid-batch, Story 3 AC-2): body carries usage numbers.
        const msg =
          e instanceof ApiError && isQuotaExceeded(e.body)
            ? `${e.message} (còn trống ${formatSize(e.body.remainingBytes)})`
            : e instanceof Error
              ? e.message
              : 'Lỗi không xác định';
        updateOutcome(index, { phase: 'error', message: msg });
        return;
      }
    }
  };

  /**
   * personal-storage-quota.md §3/Task 8: 2-step batch upload - open a batch
   * declaring every queued file (a plain selection or a whole directory),
   * then send each one tagged with that batchId.
   */
  const submitUpload = async () => {
    if (queue.length === 0) return;
    if (queue.some((q) => originalNameTooLong(q.file.name))) return;
    setError(null);
    setBatchState({ phase: 'opening' });

    // AC-6/AC-7: ask which names are taken in the target folder, then decide every display name.
    const displayNames = await resolveDisplayNames(
      '/personal/name-check',
      selectedFolder,
      queue.map((q) => ({ relativePath: q.relativePath, originalName: q.file.name })),
    );
    const named = queue.map((q, i) => ({ ...q, displayName: displayNames[i] }));
    setQueue(named);

    let batchId: string;
    try {
      const totalBytes = queue.reduce((s, q) => s + q.file.size, 0);
      const opened = await apiJson<OpenPersonalBatchResponse>(
        '/personal/batches',
        session,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            ...(selectedFolder ? { folderId: selectedFolder } : {}),
            declaredFileCount: queue.length,
            declaredTotalBytes: totalBytes,
          }),
        },
      );
      batchId = opened.batchId;
    } catch (e) {
      setBatchState({ phase: 'idle' });
      // 409 QUOTA_EXCEEDED (Story 3 AC-1): rejected before any bytes are sent.
      if (e instanceof ApiError && isQuotaExceeded(e.body)) {
        setError(
          `${e.message} (còn trống ${formatSize(e.body.remainingBytes)})`,
        );
      } else {
        setError((e as Error).message);
      }
      return;
    }

    setBatchState({ phase: 'running' });
    // Sequential on purpose: quota is re-checked and reserved per file,
    // server-side, on every call - no benefit fighting that with parallelism.
    let lastRefreshAt = 0;
    for (let i = 0; i < named.length; i++) {
      // AC-1: only a file above the chunk threshold goes through the
      // chunked-upload session protocol - everything else keeps using the
      // existing whole-file path unchanged.
      if (quota && named[i].file.size > quota.chunkThresholdBytes) {
        await uploadOneChunked(batchId, named[i], i, selectedFolder, updateOutcome);
      } else {
        await uploadOne(batchId, named[i], i);
      }
      // P-07: show stored files in the table during the batch, throttled so a 500-file batch is not 500 list calls.
      if (Date.now() - lastRefreshAt > 1500) {
        lastRefreshAt = Date.now();
        refreshFilesAndQuota();
      }
    }
    await apiFetch(`/personal/batches/${batchId}/close`, session, { method: 'POST' }).catch(
      () => undefined,
    );
    setBatchState({ phase: 'done' });
    toast.success('Đã xử lý xong lượt tải lên.');
    load();
  };

  const download = async (file: PersonalFileRow) => {
    try {
      // Programmatic download: a plain <a href> cannot carry the bearer token.
      const res = await apiFetch(`/personal/files/${file.id}/content`, session);
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = file.originalName;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const moveFile = (fileId: string, folderId: string | null) =>
    void run(
      () =>
        apiFetch(`/personal/files/${fileId}`, session, {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ folderId }),
        }).then(() => undefined),
      'Đã chuyển tệp.',
    );

  const deleteFile = async (file: PersonalFileRow) => {
    const ok = await confirm({
      title: 'Xóa tệp vĩnh viễn',
      message: `Xóa vĩnh viễn "${file.originalName}"?`,
      confirmLabel: 'Xóa',
      tone: 'danger',
    });
    if (!ok) return;
    void run(
      () =>
        apiFetch(`/personal/files/${file.id}`, session, {
          method: 'DELETE',
        }).then(() => undefined),
      'Đã xóa tệp.',
    );
  };

  return (
    <AppShell title="Lưu trữ cá nhân">
      <div className="max-w-7xl">
        <div className="mb-6">
          <h2 className="text-2xl font-bold text-[#0D2546]">
            Lưu trữ cá nhân
          </h2>
          <p className="text-sm text-gray-500 mt-1">
            Không gian riêng theo hạn mức — chỉ lưu trữ, không số hóa và không
            tìm kiếm được nội dung.
          </p>
        </div>

        {/* Quota bar */}
        <div className="bg-white rounded-lg border border-gray-200 p-4 mb-5">
          {quota ? (
            <>
              <div className="flex items-center justify-between text-sm mb-2">
                <span className="font-semibold text-[#0D2546]">
                  Dung lượng đã dùng
                </span>
                <span className="text-gray-600">
                  {formatSize(quota.usedBytes)} / {formatSize(quota.quotaBytes)}{' '}
                  ({usedPercent}%)
                </span>
              </div>
              <div className="w-full bg-gray-100 rounded-full h-2.5">
                <div
                  className={`h-2.5 rounded-full transition-all ${
                    usedPercent >= 90 ? 'bg-[#D11A2A]' : 'bg-[#0D2546]'
                  }`}
                  style={{ width: `${usedPercent}%` }}
                />
              </div>
            </>
          ) : (
            <p className="text-sm text-gray-400">Đang tải hạn mức…</p>
          )}
        </div>

        {error && (
          <div className="mb-4 bg-red-50 border border-red-200 rounded-md p-3 text-sm text-red-800">
            {error}
          </div>
        )}

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-5">
          {/* Folder tree + folder ops */}
          <aside className="bg-white rounded-lg border border-gray-200 p-4 h-fit">
            <h3 className="font-semibold text-[#0D2546] mb-3 text-sm">
              Thư mục của tôi
            </h3>
            <button
              onClick={() => setSelectedFolder(null)}
              className={`w-full text-left px-2 py-1 rounded text-sm mb-1 cursor-pointer ${
                selectedFolder === null
                  ? 'bg-[#0D2546] text-white'
                  : 'hover:bg-gray-100 text-gray-800'
              }`}
            >
              🏠 Gốc
            </button>
            {tree === null ? (
              <p className="text-sm text-gray-400">Đang tải…</p>
            ) : (
              <FolderTree
                nodes={tree}
                selectedId={selectedFolder}
                onSelect={setSelectedFolder}
              />
            )}

            <div className="mt-4 pt-4 border-t border-gray-100 space-y-3">
              <div>
                <p className="text-xs font-semibold uppercase tracking-wide text-gray-400 mb-1.5">
                  Tạo thư mục con {selectedFolder ? '' : '(ở gốc)'}
                </p>
                <div className="flex gap-2">
                  <input
                    value={newFolderName}
                    onChange={(e) => setNewFolderName(e.target.value)}
                    maxLength={255}
                    placeholder="Tên thư mục"
                    className="flex-1 min-w-0 border border-gray-300 rounded-md px-2 py-1.5 text-sm text-gray-800"
                  />
                  <button
                    onClick={createFolder}
                    disabled={!newFolderName.trim()}
                    className="bg-[#0D2546] hover:bg-[#163a6b] disabled:bg-gray-300 text-white font-semibold px-3 py-1.5 rounded-md text-sm cursor-pointer disabled:cursor-not-allowed"
                  >
                    Tạo
                  </button>
                </div>
              </div>

              {selectedFolder && (
                <>
                  <div>
                    <p className="text-xs font-semibold uppercase tracking-wide text-gray-400 mb-1.5">
                      Đổi tên thư mục đang chọn
                    </p>
                    <div className="flex gap-2">
                      <input
                        value={renameValue}
                        onChange={(e) => setRenameValue(e.target.value)}
                        maxLength={255}
                        placeholder="Tên mới"
                        className="flex-1 min-w-0 border border-gray-300 rounded-md px-2 py-1.5 text-sm text-gray-800"
                      />
                      <button
                        onClick={renameFolder}
                        disabled={!renameValue.trim()}
                        className="bg-[#0D2546] hover:bg-[#163a6b] disabled:bg-gray-300 text-white font-semibold px-3 py-1.5 rounded-md text-sm cursor-pointer disabled:cursor-not-allowed"
                      >
                        Lưu
                      </button>
                    </div>
                  </div>
                  <div>
                    <p className="text-xs font-semibold uppercase tracking-wide text-gray-400 mb-1.5">
                      Chuyển thư mục đang chọn vào
                    </p>
                    <select
                      value=""
                      onChange={(e) =>
                        moveFolder(e.target.value === '' ? null : e.target.value)
                      }
                      className="w-full border border-gray-300 rounded-md px-2 py-1.5 text-sm text-gray-800"
                    >
                      <option value="" disabled>
                        — Chọn đích —
                      </option>
                      <option value="">Gốc</option>
                      {folderOptions
                        .filter((f) => f.id !== selectedFolder)
                        .map((f) => (
                          <option key={f.id} value={f.id}>
                            {f.label}
                          </option>
                        ))}
                    </select>
                  </div>
                  <button
                    onClick={() => void deleteFolder()}
                    className="w-full bg-[#D11A2A] hover:bg-red-700 text-white font-semibold px-3 py-1.5 rounded-md text-sm cursor-pointer"
                  >
                    Xóa thư mục đang chọn
                  </button>
                </>
              )}
            </div>
          </aside>

          {/* Upload + file table */}
          <div className="lg:col-span-2 space-y-5">
            <div
              onDragOver={(e) => {
                e.preventDefault();
                setDragActive(true);
              }}
              onDragLeave={() => setDragActive(false)}
              onDrop={onDrop}
              className={`bg-white rounded-lg border-2 p-5 transition-colors ${
                dragActive ? 'border-dashed border-[#0D2546] bg-[#0D2546]/5' : 'border-gray-200'
              }`}
            >
              <h3 className="font-semibold text-[#0D2546] mb-3 text-sm">
                Tải tệp lên{' '}
                {selectedFolder ? 'thư mục đang chọn' : 'thư mục gốc'}
              </h3>
              <div className="flex flex-wrap items-center gap-3">
                <input
                  ref={fileRef}
                  type="file"
                  multiple
                  className="hidden"
                  onChange={(e) => {
                    selectFiles(Array.from(e.target.files ?? []).map((file) => ({ file, relativePath: '' })));
                    e.target.value = ''; // picking the same file again must still fire onChange
                  }}
                />
                <button
                  type="button"
                  onClick={() => fileRef.current?.click()}
                  disabled={isRunning}
                  className="shrink-0 bg-[#0D2546] hover:bg-[#163a6b] disabled:bg-gray-300 text-white font-semibold px-4 py-2 rounded-md text-sm cursor-pointer disabled:cursor-not-allowed"
                >
                  Chọn tệp
                </button>
                <span className="flex-1 min-w-0 truncate text-sm text-gray-500">
                  {queue.length === 0
                    ? 'Chưa chọn tệp nào'
                    : `Đã chọn ${queue.length} tệp (${formatSize(queue.reduce((s, q) => s + q.file.size, 0))})`}
                </span>
                <button
                  onClick={() => void submitUpload()}
                  disabled={queue.length === 0 || isRunning || isDone}
                  className="shrink-0 bg-[#D11A2A] hover:bg-red-700 disabled:bg-gray-300 text-white font-semibold px-4 py-2 rounded-md text-sm cursor-pointer disabled:cursor-not-allowed"
                >
                  {batchState.phase === 'opening'
                    ? 'Đang mở lượt tải…'
                    : batchState.phase === 'running'
                      ? `Đang tải lên… (${queue.filter((q) => q.outcome.phase === 'done' || q.outcome.phase === 'error').length}/${queue.length})`
                      : isDone
                        ? 'Đã tải lên xong ✓'
                        : 'Tải lên'}
                </button>
              </div>

              {isRunning && (
                <div className="mt-3">
                  <div className="h-2 w-full rounded-full bg-gray-200 overflow-hidden">
                    <div
                      className="h-full rounded-full bg-[#0D2546] transition-[width] duration-200"
                      style={{ width: `${overallPercent}%` }}
                    />
                  </div>
                  <p className="mt-1.5 text-xs text-gray-500 tabular-nums">
                    {overallPercent}% · {formatSize(uploadedQueueBytes)} / {formatSize(totalQueueBytes)}
                  </p>
                </div>
              )}

              {emptyNotice && (
                <p className="mt-3 text-xs text-amber-700">
                  Không có tệp nào để tải lên: thư mục được chọn đang trống.
                </p>
              )}

              {queue.length > 0 && (
                <ul className="mt-3 pt-3 border-t border-gray-100 space-y-1.5 max-h-48 overflow-y-auto">
                  {queue.map((q, i) => (
                    <li key={i} className="flex items-center justify-between gap-3 text-sm">
                      <span
                        className="truncate text-gray-700"
                        title={`Tên hiển thị: ${q.outcome.phase === 'done' && q.outcome.displayName ? q.outcome.displayName : q.displayName}`}
                      >
                        {q.relativePath ? `${q.relativePath}/` : ''}
                        {q.file.name}
                        <span className="ml-2 text-xs text-gray-400 tabular-nums">{formatSize(q.file.size)}</span>
                      </span>
                      <FileOutcomeBadge outcome={q.outcome} fileSize={q.file.size} originalName={q.file.name} />
                    </li>
                  ))}
                </ul>
              )}

              <p className="text-xs text-gray-400 mt-3">
                Chọn nhiều tệp hoặc kéo-thả cả một thư mục vào khung này. Tệp vẫn được kiểm
                tra an toàn &amp; toàn vẹn nhưng không được số hóa
                hay lập chỉ mục.
              </p>
            </div>

            <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
              <div className="px-5 py-3 border-b border-gray-200 flex flex-wrap items-center justify-between gap-3">
                <h3 className="font-semibold text-[#0D2546] text-sm">
                  Tệp trong {selectedFolder ? 'thư mục đang chọn' : 'thư mục gốc'}{' '}
                  ({visibleFiles.length})
                </h3>
                {canAddToWarehouse && (
                  <div className="flex items-center gap-3">
                    {selectedFolder && (
                      <label className="flex items-center gap-1.5 text-xs text-gray-600">
                        <input
                          type="checkbox"
                          checked={includeFolder}
                          onChange={(e) => setIncludeFolder(e.target.checked)}
                        />
                        Kèm cả thư mục đang chọn
                      </label>
                    )}
                    <button
                      type="button"
                      onClick={() => setP2wOpen(true)}
                      disabled={p2wFiles.length === 0}
                      className="bg-[#0D2546] hover:bg-[#163a6b] disabled:bg-gray-300 text-white font-semibold px-3 py-1.5 rounded-md text-sm cursor-pointer disabled:cursor-not-allowed"
                    >
                      Bổ sung vào kho
                    </button>
                  </div>
                )}
              </div>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-left text-xs uppercase tracking-wide text-gray-400 border-b border-gray-200">
                      {canAddToWarehouse && <th className="py-3 px-2 w-8" aria-label="Chọn" />}
                      <th className="py-3 px-4 font-medium">Tên tệp</th>
                      <th className="py-3 px-4 font-medium">Kích thước</th>
                      <th className="py-3 px-4 font-medium">Thời gian</th>
                      <th className="py-3 px-4 font-medium">Di chuyển tệp</th>
                      <th className="py-3 px-4 font-medium text-right">
                        Thao tác
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {files === null ? (
                      <tr>
                        <td
                          colSpan={canAddToWarehouse ? 6 : 5}
                          className="py-8 text-center text-gray-400"
                        >
                          Đang tải…
                        </td>
                      </tr>
                    ) : visibleFiles.length === 0 ? (
                      <tr>
                        <td
                          colSpan={canAddToWarehouse ? 6 : 5}
                          className="py-8 text-center text-gray-400"
                        >
                          Chưa có tệp nào trong thư mục này.
                        </td>
                      </tr>
                    ) : (
                      visibleFiles.map((f) => (
                        <tr
                          key={f.id}
                          className="border-b border-gray-100 last:border-0 text-gray-800 hover:bg-gray-50"
                        >
                          {canAddToWarehouse && (
                            <td className="py-3 px-2">
                              <input
                                type="checkbox"
                                aria-label={`Chọn ${f.originalName}`}
                                checked={pickedFileIds.includes(f.id)}
                                onChange={(e) =>
                                  setPickedFileIds((prev) =>
                                    e.target.checked
                                      ? [...prev, f.id]
                                      : prev.filter((id) => id !== f.id),
                                  )
                                }
                              />
                            </td>
                          )}
                          <td
                            className="py-3 px-4 max-w-xs truncate font-medium text-[#0D2546]"
                            title={nameTooltip({
                              originalName: f.originalName,
                              displayName: f.displayName,
                              relativePath: f.relativePath,
                            })}
                          >
                            {f.originalName}
                            {f.addedToWarehouse && (
                              <span className="ml-2 text-xs font-semibold text-green-700 bg-green-50 px-2 py-0.5 rounded">
                                Đã bổ sung vào kho
                              </span>
                            )}
                          </td>
                          <td className="py-3 px-4 text-gray-600">
                            {formatSize(Number(f.size))}
                          </td>
                          <td className="py-3 px-4 text-gray-600">
                            {new Date(f.createdAt).toLocaleString('vi-VN')}
                          </td>
                          <td className="py-3 px-4">
                            <select
                              value=""
                              onChange={(e) =>
                                moveFile(
                                  f.id,
                                  e.target.value === '' ? null : e.target.value,
                                )
                              }
                              aria-label={`Di chuyển ${f.displayName} đến thư mục`}
                              title="Chọn thư mục đích - tệp được chuyển ngay"
                              className="border border-gray-300 rounded-md px-2 py-1 text-xs text-gray-800 max-w-[10rem]"
                            >
                              <option value="" disabled>
                                Chuyển đến…
                              </option>
                              {f.folderId !== null && (
                                <option value="">Gốc</option>
                              )}
                              {folderOptions
                                .filter((o) => o.id !== f.folderId)
                                .map((o) => (
                                  <option key={o.id} value={o.id}>
                                    {o.label}
                                  </option>
                                ))}
                            </select>
                          </td>
                          <td className="py-3 px-4 text-right whitespace-nowrap">
                            <button
                              onClick={() => download(f)}
                              className="text-[#0D2546] hover:text-[#163a6b] font-semibold mr-3 cursor-pointer"
                            >
                              Tải xuống
                            </button>
                            <button
                              onClick={() => void deleteFile(f)}
                              className="text-[#D11A2A] hover:text-red-700 font-semibold cursor-pointer"
                            >
                              Xóa
                            </button>
                          </td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      </div>
      {p2wOpen && (
        <PersonalToWarehouseDialog
          session={session}
          files={p2wFiles}
          fileIds={pickedFileIds}
          folderIds={p2wFolderIds}
          onClose={() => setP2wOpen(false)}
          onFinished={() => {
            setPickedFileIds([]);
            setIncludeFolder(false);
            load(); // refresh addedToWarehouse badges (AC-21)
          }}
        />
      )}
    </AppShell>
  );
}

function FileOutcomeBadge({
  outcome,
  fileSize,
  originalName,
}: {
  outcome: FileOutcome;
  fileSize: number;
  originalName: string;
}) {
  switch (outcome.phase) {
    case 'queued':
      return <span className="text-xs text-gray-400 shrink-0">Đang chờ…</span>;
    case 'uploading': {
      const percent = fileSize > 0 ? Math.min(100, Math.round((outcome.loadedBytes / fileSize) * 100)) : 100;
      return (
        <span className="flex items-center gap-2 shrink-0">
          <span className="h-1.5 w-20 shrink-0 rounded-full bg-gray-200 overflow-hidden">
            <span
              className="block h-full rounded-full bg-[#0D2546] transition-[width] duration-200"
              style={{ width: `${percent}%` }}
            />
          </span>
          <span className="text-xs text-gray-500 tabular-nums">{percent}%</span>
        </span>
      );
    }
    case 'done':
      return (
        <span className="flex items-center gap-2 shrink-0">
          <span className="text-xs font-semibold text-green-700 bg-green-50 px-2 py-0.5 rounded">Đã lưu</span>
          {outcome.displayName && outcome.displayName !== nfc(originalName) && (
            <span
              className="text-xs text-amber-700 bg-amber-50 px-2 py-0.5 rounded"
              title={`Tên hiển thị: ${outcome.displayName}`}
            >
              Đã đổi tên (trùng)
            </span>
          )}
        </span>
      );
    case 'error':
      return (
        <span className="text-xs text-[#D11A2A] shrink-0" title={outcome.message}>
          Lỗi: {outcome.message}
        </span>
      );
    default:
      return null;
  }
}
