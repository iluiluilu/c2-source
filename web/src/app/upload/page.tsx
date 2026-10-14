'use client';

import Link from 'next/link';
import { getSession, useSession } from 'next-auth/react';
import { useEffect, useRef, useState } from 'react';
import { FolderTree } from '@/components/FolderTree';
import type { Session } from 'next-auth';
import { apiFetch, apiJson, apiUploadJson } from '@/lib/api';
import type {
  AllowedFormat,
  ChunkAckResponse,
  FolderNode,
  IngestFileResult,
  IngestLimits,
  OpenBatchResponse,
  OpenUploadSessionResponse,
  UploadSessionStatusResponse,
} from '@/lib/api';
import { AccessDenied } from '@/components/AccessDenied';
import { AppShell } from '@/components/AppShell';
import { canSeeFeature } from '@/lib/features';
import { usePermissions } from '@/lib/permissions';
import { collectDroppedFiles } from '@/lib/dropEntries';
import type { DroppedFile } from '@/lib/dropEntries';
import { nfc } from '@/lib/displayName';
import { allowedExtensionSet, partitionByExtension } from '@/lib/allowedFormats';
import { useLeaveGuard } from '@/lib/useLeaveGuard';
import { folderPathLabel } from '@/lib/folderPath';
import { useConfirm } from '@/components/ConfirmDialog';
import {
  MAX_SUFFIX_RETRIES,
  TRANSIENT_RETRY_DELAYS_MS,
  generateSuffixName,
  isTransientUploadError,
  isDisplayNameConflict,
  originalNameTooLong,
  resolveDisplayNames,
} from '@/lib/uploadFlow';

type FileOutcome =
  | { phase: 'queued' }
  | { phase: 'uploading'; loadedBytes: number }
  | { phase: 'ingested'; result: IngestFileResult & { status: 'INGESTED' } }
  | { phase: 'error'; message: string };

interface QueuedFile {
  file: File;
  /** Folder path inside the dropped folder(s), '' for a loose file (folder-upload-display-name AC-5). */
  relativePath: string;
  /** Unique name the server will store; assigned by name-check right before uploading (AC-7/AC-8). */
  displayName: string;
  outcome: FileOutcome;
}

type BatchPhase =
  | { phase: 'idle' }
  | { phase: 'opening' }
  | { phase: 'running' }
  | { phase: 'done' }
  | { phase: 'error'; message: string };

/** Above this many files the destination is confirmed once more in a dialog before uploading. */
const CONFIRM_DESTINATION_ABOVE_FILES = 20;

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let value = bytes / 1024;
  let i = 0;
  while (value >= 1024 && i < units.length - 1) {
    value /= 1024;
    i++;
  }
  return `${value.toFixed(1)} ${units[i]}`;
}

function sleep(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * chunked-file-upload-01-ingest-chunked-upload.md §4.5: for a file above
 * `limits.chunkThresholdBytes` (AC-1/AC-2) - splits it with `Blob.slice()` and
 * sends it through the `init -> chunk(s) -> complete` upload-session
 * protocol instead of a single multipart POST. Same 3-attempt/backoff retry
 * shape as `uploadOne` (AC-9): any transient failure anywhere in the attempt
 * aborts that attempt only, backs off, and (from the 2nd attempt on) resyncs
 * `receivedBytes` via `GET status` before resuming (AC-4) - never restarting
 * the file from byte 0.
 *
 * Module-level (not a closure inside the component) so it can be unit
 * tested directly (Task F5) without rendering `UploadPage` - `note` and
 * `updateOutcome` are passed in explicitly instead of being closed over.
 *
 * Quyết định 5 (SA): every request below fetches the access token via
 * `getSession()` right before that specific call, instead of a `session`
 * captured once at the top of `submit()` - a large file can take long
 * enough for NextAuth's background token refresh to invalidate a
 * closed-over value mid-upload.
 */
export async function uploadOneChunked(
  batchId: string,
  item: QueuedFile,
  index: number,
  note: string,
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
          `/ingest/batches/${batchId}/upload-sessions`,
          initSession,
          {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              originalName: item.file.name,
              relativePath: item.relativePath,
              displayName: item.displayName,
              declaredSizeBytes,
              mimeType: item.file.type || undefined,
              note: note.trim() || undefined,
            }),
          },
        );
        sessionId = opened.sessionId;
        chunkSizeBytes = opened.chunkSizeBytes;
        offset = opened.receivedBytes;
      } else {
        // Resume within this submit() run (AC-4): re-sync with the server
        // instead of re-sending bytes it may already have.
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

      // AC-14/AC-15: on 409 DISPLAY_NAME_CONFLICT only the commit is repeated with a fresh suffix -
      // the session keeps its chunks, nothing is uploaded again (max 3 renames per file).
      let res: Response | undefined;
      for (let renames = 0; ; renames++) {
        try {
          const completeSession = await getSession();
          res = await apiFetch(`/upload-sessions/${sessionId}/complete`, completeSession, {
            method: 'POST',
            ...(renamedTo
              ? {
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ displayName: renamedTo }),
                }
              : {}),
          });
          break;
        } catch (e) {
          if (isDisplayNameConflict(e) && renames < MAX_SUFFIX_RETRIES) {
            renamedTo = generateSuffixName(item.file.name);
            continue;
          }
          throw e;
        }
      }
      const body = (await res.json()) as IngestFileResult;
      updateOutcome(index, { phase: 'ingested', result: body });
      return;
    } catch (e) {
      if (isTransientUploadError(e) && attempt < maxAttempts) {
        await sleep(TRANSIENT_RETRY_DELAYS_MS[attempt - 1]);
        continue;
      }
      // AC-9: exactly one phase transition to 'error' - no intermediate
      // "retrying" outcome is ever set above.
      updateOutcome(index, {
        phase: 'error',
        message: e instanceof Error ? e.message : 'Lỗi không xác định',
      });
      return;
    }
  }
}

/**
 * Whole-file (multipart) path. Story 4 AC-3: a small retry-with-backoff queue for transient failures
 * (network / 503) only. folder-upload-display-name AC-14/AC-15: a 409 DISPLAY_NAME_CONFLICT resends the
 * whole file with a freshly generated suffix, at most 3 times per file, without consuming a transient
 * retry attempt.
 */
export async function uploadOneWhole(
  batchId: string,
  item: QueuedFile,
  index: number,
  note: string,
  session: Session | null,
  updateOutcome: (index: number, outcome: FileOutcome) => void,
): Promise<void> {
  updateOutcome(index, { phase: 'uploading', loadedBytes: 0 });

  const maxAttempts = TRANSIENT_RETRY_DELAYS_MS.length + 1;
  let displayName = item.displayName;
  let renames = 0;
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    const form = new FormData();
    form.append('file', item.file);
    form.append('relativePath', item.relativePath);
    form.append('displayName', displayName);
    if (note.trim()) form.append('note', note.trim());
    try {
      const body = await apiUploadJson<IngestFileResult>(
        `/ingest/batches/${batchId}/files`,
        session,
        form,
        (loadedBytes) => updateOutcome(index, { phase: 'uploading', loadedBytes }),
      );
      updateOutcome(index, { phase: 'ingested', result: body });
      return;
    } catch (e) {
      if (isDisplayNameConflict(e) && renames < MAX_SUFFIX_RETRIES) {
        renames++;
        displayName = generateSuffixName(item.file.name);
        attempt--; // a clash is not a transient failure
        continue;
      }
      if (isTransientUploadError(e) && attempt < maxAttempts) {
        await sleep(TRANSIENT_RETRY_DELAYS_MS[attempt - 1]);
        continue;
      }
      updateOutcome(index, {
        phase: 'error',
        message: e instanceof Error ? e.message : 'Lỗi không xác định',
      });
      return;
    }
  }
}

/** FR-11: R1 uploads into a mandatory warehouse folder (single file or a whole folder), FR-12 result shown. */
export default function UploadPage() {
  const { data: session, status } = useSession();
  const confirm = useConfirm();
  const [tree, setTree] = useState<FolderNode[] | null>(null);
  // `?folder=<id>` ("Nạp vào thư mục này" in Kho tài liệu) preselects the destination.
  const [folderId, setFolderId] = useState<string | null>(() =>
    typeof window === 'undefined' ? null : new URLSearchParams(window.location.search).get('folder'),
  );
  const [note, setNote] = useState('');
  const [limits, setLimits] = useState<IngestLimits | null>(null);
  const [accept, setAccept] = useState<string>('');
  /** null = allowlist not loaded: nothing is filtered client-side, the server still rejects. */
  const [allowedExts, setAllowedExts] = useState<Set<string> | null>(null);
  /** Files dropped/picked but not queued because their extension is not allowed (feedbacks D-03). */
  const [skippedNames, setSkippedNames] = useState<string[]>([]);
  /** feedbacks D-13: a dropped folder (or selection) that contained no file at all. */
  const [emptyNotice, setEmptyNotice] = useState(false);
  const [queue, setQueue] = useState<QueuedFile[]>([]);
  const [batch, setBatch] = useState<BatchPhase>({ phase: 'idle' });
  const [precheckError, setPrecheckError] = useState<string | null>(null);
  const [dragActive, setDragActive] = useState(false);

  const fileInputRef = useRef<HTMLInputElement>(null);

  const perms = usePermissions();
  const canUpload = canSeeFeature(session, 'upload', perms);

  useEffect(() => {
    if (!canUpload) return;
    apiJson<FolderNode[]>('/folders/tree', session)
      .then(setTree)
      .catch(() => setTree([]));
    apiJson<IngestLimits>('/ingest/limits', session)
      .then(setLimits)
      .catch(() => setLimits(null));
    apiJson<AllowedFormat[]>('/ingest/allowed-formats', session)
      .then((formats) => {
        setAccept(formats.flatMap((f) => f.extensions.map((ext) => `.${ext}`)).join(','));
        setAllowedExts(allowedExtensionSet(formats));
      })
      .catch(() => {
        setAccept('');
        setAllowedExts(null);
      });
  }, [session, canUpload]);

  useLeaveGuard(batch.phase === 'opening' || batch.phase === 'running');

  // Fail-closed, but avoid flashing AccessDenied while /me/permissions is in flight.
  if (status === 'loading' || (session && perms.status === 'loading')) return null;
  if (!session || !canUpload) {
    return <AccessDenied featureKey="upload" />;
  }

  const targetPath = folderPathLabel(tree ?? [], folderId);
  const needsFolder = !folderId;

  /** `items` come from a drop (files + folders, AC-1) or from the "Chọn tệp" button (loose files only, AC-2). */
  const selectFiles = (picked: DroppedFile[]) => {
    // A new selection after a finished batch starts the next one (replaces the old "Nạp lượt mới" button).
    if (batch.phase === 'done') setNote('');
    setBatch({ phase: 'idle' });
    setPrecheckError(null);
    setEmptyNotice(picked.length === 0);
    // D-03: drop a disallowed extension here, before it is listed or uploaded (a dropped folder skips `accept`).
    const { accepted: items, skipped } = partitionByExtension(picked, allowedExts);
    setSkippedNames(skipped.map(({ file, relativePath }) => (relativePath ? `${relativePath}/${file.name}` : file.name)));
    if (items.length === 0) {
      setQueue([]);
      return;
    }
    const files: QueuedFile[] = items.map(({ file, relativePath }) => ({
      file,
      relativePath,
      displayName: nfc(file.name),
      outcome: { phase: 'queued' as const },
    }));
    setQueue(files);

    // Story 2 AC-3a: block in the browser before ever calling the API.
    if (limits) {
      const totalBytes = files.reduce((s, f) => s + f.file.size, 0);
      const problems: string[] = [];
      if (files.length > limits.maxFilesPerBatch) {
        problems.push(`Số tệp (${files.length}) vượt quá ${limits.maxFilesPerBatch} tệp/lượt.`);
      }
      if (totalBytes > limits.maxBatchSizeBytes) {
        problems.push(
          `Tổng dung lượng (${formatBytes(totalBytes)}) vượt quá ${formatBytes(limits.maxBatchSizeBytes)}/lượt.`,
        );
      }
      const tooLarge = files.filter((f) => f.file.size > limits.maxFileSizeBytes);
      const nameTooLong = files.filter((f) => originalNameTooLong(f.file.name));
      if (nameTooLong.length > 0) {
        problems.push(`${nameTooLong.length} tệp có tên dài quá 512 ký tự.`);
      }
      if (tooLarge.length > 0) {
        problems.push(
          `${tooLarge.length} tệp vượt quá dung lượng tối đa mỗi tệp (${formatBytes(limits.maxFileSizeBytes)}).`,
        );
      }
      if (problems.length > 0) {
        setPrecheckError(problems.join(' '));
      }
    }
  };

  const onDrop = (e: React.DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setDragActive(false);
    if (batch.phase === 'opening' || batch.phase === 'running') return;
    // webkitGetAsEntry() must be read synchronously in the drop handler (see lib/dropEntries).
    void collectDroppedFiles(e.dataTransfer).then(selectFiles);
  };

  const updateOutcome = (index: number, outcome: FileOutcome) => {
    setQueue((prev) => prev.map((q, i) => (i === index ? { ...q, outcome } : q)));
  };
  /** Drop one picked file before the batch starts; re-runs the size/count pre-check. */
  const removeQueued = (index: number) => {
    const rest = queue.filter((_, i) => i !== index);
    if (rest.length === 0) {
      setQueue([]);
      setPrecheckError(null);
      return;
    }
    selectFiles(rest.map(({ file, relativePath }) => ({ file, relativePath })));
  };


  const submit = async () => {
    if (!folderId || queue.length === 0 || precheckError) return;
    // The destination is already spelled out on the button; a large batch gets one explicit confirmation on top.
    if (queue.length > CONFIRM_DESTINATION_ABOVE_FILES) {
      const ok = await confirm({
        title: 'Xác nhận thư mục đích',
        message: `Tải ${queue.length} tệp vào thư mục "${targetPath ?? ''}"?`,
        confirmLabel: 'Tải lên',
      });
      if (!ok) return;
    }

    setBatch({ phase: 'opening' });
    try {
      // AC-6/AC-7: ask which names are taken, then decide every display name before uploading.
      const displayNames = await resolveDisplayNames(
        '/ingest/name-check',
        folderId,
        queue.map((q) => ({ relativePath: q.relativePath, originalName: q.file.name })),
      );
      const named = queue.map((q, i) => ({ ...q, displayName: displayNames[i] }));
      setQueue(named);

      const totalBytes = queue.reduce((s, q) => s + q.file.size, 0);
      const opened = await apiJson<OpenBatchResponse>('/ingest/batches', session, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          targetFolderId: folderId,
          declaredFileCount: queue.length,
          declaredTotalBytes: totalBytes,
        }),
      });

      setBatch({ phase: 'running' });
      // Sequential on purpose: batch counters/warehouse-capacity pre-checks are
      // per-file and stateful server-side (data-ingestion.md §4.2/§4.5) - no
      // benefit fighting that with client-side parallelism.
      for (let i = 0; i < named.length; i++) {
        // AC-1/AC-2: only a file above the chunk threshold goes through the
        // chunked-upload session protocol - everything else keeps using the
        // existing whole-file path unchanged.
        if (limits && named[i].file.size > limits.chunkThresholdBytes) {
          await uploadOneChunked(opened.batchId, named[i], i, note, updateOutcome);
        } else {
          await uploadOneWhole(opened.batchId, named[i], i, note, session, updateOutcome);
        }
      }

      await apiFetch(`/ingest/batches/${opened.batchId}/close`, session, { method: 'POST' }).catch(
        () => undefined,
      );
      setBatch({ phase: 'done' });
    } catch (e) {
      setBatch({
        phase: 'error',
        message: e instanceof Error ? e.message : 'Không thể mở lượt tải',
      });
    }
  };

  const isRunning = batch.phase === 'opening' || batch.phase === 'running';
  const isDone = batch.phase === 'done';

  // Aggregate byte-level progress across the whole queue, for the overall bar.
  const totalQueueBytes = queue.reduce((s, q) => s + q.file.size, 0);
  const uploadedQueueBytes = queue.reduce((s, q) => {
    const o = q.outcome;
    if (o.phase === 'uploading') return s + o.loadedBytes;
    if (o.phase === 'ingested') return s + q.file.size;
    return s;
  }, 0);
  // AC-11/AC-27: how many files got a suffix because their name was already taken.
  const renamedCount = queue.filter(
    (q) => q.outcome.phase === 'ingested' && q.outcome.result.document.displayName !== nfc(q.file.name),
  ).length;
  const overallPercent =
    totalQueueBytes > 0 ? Math.min(100, Math.round((uploadedQueueBytes / totalQueueBytes) * 100)) : 0;

  return (
    <AppShell title="Nạp dữ liệu">
      <div className="max-w-7xl">
        <div className="mb-6">
          <h2 className="text-2xl font-bold text-[#0D2546]">
            Nạp &amp; kiểm tra dữ liệu đầu vào
          </h2>
          <p className="text-sm text-gray-500 mt-1">
            Bắt buộc chọn thư mục đích theo cấu trúc kho; mọi tệp qua kiểm tra an
            toàn &amp; toàn vẹn trước khi vào kho chính. Chọn một hoặc
            nhiều tệp, hoặc kéo-thả cả một thư mục.
          </p>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-5">
          {/* Ingest form */}
          <div className="lg:col-span-2 bg-white rounded-lg border border-gray-200 p-6 space-y-5">
            <div>
              <label className="block text-xs font-semibold uppercase tracking-wide text-gray-500 mb-2">
                Thư mục đích <span className="text-[#D11A2A]">* bắt buộc</span>
              </label>
              {needsFolder && (
                <p className="text-xs text-amber-700 mb-2">
                  Bấm chọn thư mục sẽ chứa các tệp tải lên. Nút tải lên chỉ mở khi đã chọn thư mục.
                </p>
              )}
              <div
                className={`rounded-md p-3 max-h-56 overflow-y-auto border ${
                  needsFolder && queue.length > 0 ? 'border-amber-400 ring-2 ring-amber-200' : 'border-gray-200'
                }`}
              >
                {tree === null ? (
                  <p className="text-sm text-gray-400">Đang tải cây thư mục…</p>
                ) : (
                  <FolderTree
                    nodes={tree}
                    selectedId={folderId}
                    onSelect={setFolderId}
                  />
                )}
              </div>
              {targetPath && (
                <p className="text-xs text-gray-600 mt-2">
                  Đã chọn: <span className="font-semibold text-[#0D2546]">{targetPath}</span>
                </p>
              )}
            </div>

            <div
              onDragOver={(e) => {
                e.preventDefault();
                setDragActive(true);
              }}
              onDragLeave={() => setDragActive(false)}
              onDrop={onDrop}
              className={`border-2 border-dashed rounded-lg p-8 text-center space-y-3 transition-colors ${
                dragActive
                  ? 'border-[#0D2546] bg-[#0D2546]/5'
                  : 'border-gray-300'
              }`}
            >
              <p className="text-sm font-semibold text-[#0D2546]">
                {dragActive ? 'Thả tệp hoặc thư mục vào đây' : 'Kéo-thả tệp hoặc thư mục để nạp'}
              </p>
              <p className="text-xs text-gray-400">
                Kéo-thả tệp hoặc cả thư mục vào đây (giữ nguyên cấu trúc thư mục con), hoặc dùng nút chọn tệp &middot;{' '}
                {limits
                  ? `tối đa ${limits.maxFilesPerBatch} tệp, ${formatBytes(limits.maxBatchSizeBytes)}/lượt, ${formatBytes(limits.maxFileSizeBytes)}/tệp`
                  : 'JPEG/PNG/TIFF · WAV/FLAC · MP4 · PDF'}
              </p>
              <div className="flex flex-wrap justify-center gap-3">
                <input
                  ref={fileInputRef}
                  type="file"
                  multiple
                  accept={accept || undefined}
                  className="hidden"
                  onChange={(e) => {
                    selectFiles(Array.from(e.target.files ?? []).map((file) => ({ file, relativePath: '' })));
                    e.target.value = ''; // picking the same file again must still fire onChange
                  }}
                />
                <button
                  type="button"
                  onClick={() => fileInputRef.current?.click()}
                  disabled={isRunning}
                  className="text-sm bg-[#0D2546] disabled:bg-gray-300 disabled:cursor-not-allowed text-white px-4 py-2 rounded-md cursor-pointer"
                >
                  Chọn tệp
                </button>
              </div>
              {queue.length > 0 && (
                <p className="text-xs text-gray-500">
                  {isDone
                    ? `Đã tải lên ${queue.length} tệp — chọn hoặc kéo-thả tệp mới để nạp lượt tiếp theo.`
                    : `Đã chọn ${queue.length} tệp (${formatBytes(queue.reduce((s, q) => s + q.file.size, 0))})`}
                </p>
              )}
              {emptyNotice && (
                <p className="text-xs text-amber-700">
                  Không có tệp nào để nạp: thư mục được chọn đang trống.
                </p>
              )}
              {skippedNames.length > 0 && (
                <p className="text-xs text-amber-700" title={skippedNames.join('\n')}>
                  Bỏ qua {skippedNames.length} tệp không thuộc định dạng được phép nạp:{' '}
                  {skippedNames.slice(0, 5).join(', ')}
                  {skippedNames.length > 5 ? ` và ${skippedNames.length - 5} tệp khác` : ''}.
                </p>
              )}
              {precheckError && (
                <p className="text-xs text-[#D11A2A]">{precheckError}</p>
              )}
            </div>

            <div>
              <label className="block text-xs font-semibold uppercase tracking-wide text-gray-500 mb-2">
                Ghi chú (tùy chọn, áp dụng cho mọi tệp trong lượt này)
              </label>
              <textarea
                value={note}
                onChange={(e) => setNote(e.target.value)}
                maxLength={4000}
                rows={2}
                placeholder="Ghi chú kèm theo tệp khi nạp…"
                className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm text-gray-800"
              />
            </div>

            {isRunning && (
              <div>
                <div className="h-2 w-full rounded-full bg-gray-200 overflow-hidden">
                  <div
                    className="h-full rounded-full bg-[#0D2546] transition-[width] duration-200"
                    style={{ width: `${overallPercent}%` }}
                  />
                </div>
                <p className="mt-1.5 text-xs text-gray-500 tabular-nums">
                  {overallPercent}% · {formatBytes(uploadedQueueBytes)} / {formatBytes(totalQueueBytes)}
                </p>
              </div>
            )}

            <button
              onClick={() => void submit()}
              disabled={!folderId || queue.length === 0 || !!precheckError || isRunning || isDone}
              className="w-full bg-[#D11A2A] hover:bg-red-700 disabled:bg-gray-300 disabled:cursor-not-allowed cursor-pointer text-white font-semibold py-3 rounded-md transition-colors"
            >
              {batch.phase === 'opening'
                ? 'Đang mở lượt tải…'
                : batch.phase === 'running'
                  ? `Đang tải lên… (${queue.filter((q) => q.outcome.phase !== 'queued' && q.outcome.phase !== 'uploading').length}/${queue.length})`
                  : isDone
                    ? 'Đã tải lên xong ✓'
                    : needsFolder
                      ? 'Chọn thư mục đích để tải lên'
                      : queue.length === 0
                        ? `Tải lên vào ${targetPath ?? ''}`
                        : `Tải lên ${queue.length} tệp vào ${targetPath ?? ''}`}
            </button>

            {batch.phase === 'error' && (
              <p className="text-sm text-[#D11A2A]">{batch.message}</p>
            )}
          </div>

          {/* Result panel */}
          <div className="bg-white rounded-lg border border-gray-200 p-6">
            <h3 className="font-semibold text-[#0D2546] mb-4">Kết quả nạp</h3>

            {queue.length === 0 && (
              <p className="text-sm text-gray-400">
                Chọn thư mục và tệp, sau đó tải lên để xem kết quả kiểm tra.
              </p>
            )}

            {queue.length > 0 && (
              <ul className="space-y-3 max-h-[32rem] overflow-y-auto">
                {queue.map((q, i) => (
                  <li key={i} className="text-sm border-b border-gray-100 pb-2 last:border-0">
                    <p
                      className="font-medium text-[#0D2546] break-all"
                      title={`Tên hiển thị: ${
                        q.outcome.phase === 'ingested' ? q.outcome.result.document.displayName : q.displayName
                      }`}
                    >
                      {q.relativePath ? `${q.relativePath}/` : ''}
                      {q.file.name}
                      <span className="ml-2 text-xs font-normal text-gray-400 tabular-nums">{formatBytes(q.file.size)}</span>
                    </p>
                    <div className="flex items-center justify-between gap-2">
                      <OutcomeBadge
                        outcome={q.outcome}
                        fileSize={q.file.size}
                        originalName={q.file.name}
                        started={batch.phase !== 'idle'}
                      />
                      {batch.phase === 'idle' && (
                        <button
                          type="button"
                          onClick={() => removeQueued(i)}
                          aria-label={`Bỏ ${q.file.name}`}
                          className="cursor-pointer text-xs text-gray-400 hover:text-[#D11A2A]"
                        >
                          Bỏ ✕
                        </button>
                      )}
                    </div>
                  </li>
                ))}
              </ul>
            )}

            {batch.phase === 'done' && folderId && (
              <Link
                href={`/documents?folder=${folderId}`}
                className="mt-4 inline-block text-sm font-semibold text-[#0D2546] hover:underline"
              >
                Mở thư mục {targetPath ?? ''} trong Kho tài liệu →
              </Link>
            )}
            {batch.phase === 'done' && (
              <p className="text-xs text-gray-400 mt-4">
                Đã xử lý xong toàn bộ lượt tải.
                {renamedCount > 0 && (
                  <span className="block text-amber-700">
                    {renamedCount} tệp bị đổi tên hiển thị do trùng tên trong thư mục đích.
                  </span>
                )}
              </p>
            )}
          </div>
        </div>
      </div>
    </AppShell>
  );
}

function OutcomeBadge({
  outcome,
  fileSize,
  originalName,
  started,
}: {
  outcome: FileOutcome;
  fileSize: number;
  originalName: string;
  /** False until "Tải lên" is pressed - a picked file is not "waiting" yet. */
  started: boolean;
}) {
  switch (outcome.phase) {
    case 'queued':
      return <span className="text-xs text-gray-400">{started ? 'Đang chờ…' : 'Chưa tải lên'}</span>;
    case 'uploading': {
      const percent = fileSize > 0 ? Math.min(100, Math.round((outcome.loadedBytes / fileSize) * 100)) : 100;
      return (
        <span className="flex items-center gap-2">
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
    case 'ingested': {
      const renamed = outcome.result.document.displayName !== nfc(originalName);
      return (
        <span className="flex flex-wrap items-center gap-2">
          <span className="text-xs font-semibold text-green-700 bg-green-50 px-2 py-0.5 rounded">Vào kho</span>
          {outcome.result.duplicateOf.length > 0 && (
            // feedbacks D-09: say what "duplicate" means - same SHA-256 bytes, regardless of the file name.
            <span
              className="text-xs font-semibold text-amber-700 bg-amber-50 px-2 py-0.5 rounded cursor-help"
              title={`Nội dung tệp giống hệt (cùng mã băm SHA-256) với ${outcome.result.duplicateOf.length} tài liệu đang có trong kho, không phụ thuộc tên tệp. Tệp vẫn được lưu vào kho.`}
            >
              ⚠ Trùng nội dung với {outcome.result.duplicateOf.length} tài liệu
            </span>
          )}
          {renamed && (
            <span
              className="text-xs text-amber-700 bg-amber-50 px-2 py-0.5 rounded"
              title={`Tên hiển thị: ${outcome.result.document.displayName}`}
            >
              Đã đổi tên hiển thị (trùng tên)
            </span>
          )}
        </span>
      );
    }
    case 'error':
      return (
        <span className="text-xs text-[#D11A2A]" title={outcome.message}>
          Từ chối: {outcome.message}
        </span>
      );
    default:
      return null;
  }
}
