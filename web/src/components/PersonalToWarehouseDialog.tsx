'use client';

import type { Session } from 'next-auth';
import { useEffect, useRef, useState } from 'react';
import { FolderTree } from '@/components/FolderTree';
import { apiJson } from '@/lib/api';
import type {
  AllowedFormat,
  FolderNode,
  FromPersonalItem,
  FromPersonalNameCheckItem,
  FromPersonalResult,
  FromPersonalStartResponse,
  IngestLimits,
  PersonalFileRow,
} from '@/lib/api';
import { assignDisplayNames, generateSuffixName } from '@/lib/displayName';
import {
  FROM_PERSONAL_POLL_MS,
  MAX_ITEM_RENAMES,
  RESULT_CODE_LABELS,
  classifyFile,
  folderPathNames,
  limitWarning,
} from '@/lib/personalToWarehouse';

const NOTE_MAX_LENGTH = 4000;

type Step = 'pick' | 'confirm' | 'running';

function formatSize(bytes: number): string {
  if (bytes >= 1_073_741_824) return `${(bytes / 1_073_741_824).toFixed(1)} GB`;
  if (bytes >= 1_048_576) return `${(bytes / 1_048_576).toFixed(1)} MB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${bytes} B`;
}

/**
 * personal-to-warehouse.md §4 step 7: "Bổ sung vào kho" flow - choose the
 * warehouse destination (+ optional note), review the files (client-side
 * eligibility + early limit warning), start the server-side run and poll its
 * result every 2s until the batch is CLOSED or the dialog unmounts.
 */
export function PersonalToWarehouseDialog({
  session,
  files,
  fileIds,
  folderIds,
  onClose,
  onFinished,
}: {
  session: Session | null;
  /** The files the selection resolves to (hand-picked + folder subtrees). */
  files: PersonalFileRow[];
  fileIds: string[];
  folderIds: string[];
  onClose: () => void;
  onFinished: () => void;
}) {
  const [step, setStep] = useState<Step>('pick');
  const [warehouseTree, setWarehouseTree] = useState<FolderNode[] | null>(null);
  const [target, setTarget] = useState<string | null>(null);
  const [note, setNote] = useState('');
  const [formats, setFormats] = useState<AllowedFormat[]>([]);
  const [limits, setLimits] = useState<IngestLimits | null>(null);
  const [result, setResult] = useState<FromPersonalResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const finishedRef = useRef(false);

  useEffect(() => {
    apiJson<FolderNode[]>('/folders/tree', session)
      .then(setWarehouseTree)
      .catch(() => setWarehouseTree([]));
    apiJson<IngestLimits>('/ingest/limits', session)
      .then(setLimits)
      .catch(() => setLimits(null));
    apiJson<AllowedFormat[]>('/ingest/allowed-formats', session)
      .then(setFormats)
      .catch(() => setFormats([]));
  }, [session]);

  const classified = files.map((f) => ({ file: f, eligibility: classifyFile(f, formats, limits) }));
  const eligible = classified.filter((c) => c.eligibility === 'OK').map((c) => c.file);
  const warning = limitWarning(eligible, limits);
  const overLimit = warning.overFileCount || warning.overTotalBytes;
  const noteTooLong = note.length > NOTE_MAX_LENGTH;

  /**
   * AC-18: name-check the expanded selection against the target folder and pick a display name for every
   * file. If name-check fails (429, network...) every file is treated as "taken" so it gets a suffix;
   * the server's unique index stays the last line of defence (DISPLAY_NAME_CONFLICT items can be renamed).
   */
  const chooseDisplayNames = async (): Promise<{ personalFileId: string; displayName: string }[]> => {
    let expanded: { personalFileId: string; relativePath: string; originalName: string; taken: boolean }[];
    try {
      expanded = await apiJson<FromPersonalNameCheckItem[]>('/ingest/from-personal/name-check', session, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ targetFolderId: target, fileIds, folderIds }),
      });
    } catch {
      expanded = files.map((f) => ({
        personalFileId: f.id,
        relativePath: f.relativePath ?? '',
        originalName: f.originalName,
        taken: true,
      }));
    }
    const names = assignDisplayNames(
      expanded.map((e) => ({ relativePath: e.relativePath, originalName: e.originalName })),
      expanded.map((e) => e.taken),
    );
    return expanded.map((e, i) => ({ personalFileId: e.personalFileId, displayName: names[i] }));
  };

  const [renaming, setRenaming] = useState<string | null>(null);

  /** AC-20: an item failed with DISPLAY_NAME_CONFLICT - pick a fresh suffix name (max 3 times per item). */
  const renameItem = async (item: FromPersonalItem) => {
    if (!result || !item.itemId) return;
    setError(null);
    setRenaming(item.itemId);
    try {
      await apiJson<FromPersonalItem>(
        `/ingest/from-personal/${result.batchId}/items/${item.itemId}/display-name`,
        session,
        {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ displayName: generateSuffixName(item.name) }),
        },
      );
      // the server re-opened the batch to re-run this item: resume polling.
      setResult({
        ...result,
        status: 'OPEN',
        done: Math.max(0, result.done - 1),
        items: result.items.map((i) =>
          i.itemId === item.itemId
            ? { ...i, status: 'PENDING', code: null, nameRetryCount: (i.nameRetryCount ?? 0) + 1 }
            : i,
        ),
      });
      finishedRef.current = false;
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setRenaming(null);
    }
  };

  const submit = async () => {
    if (!target) return;
    setError(null);
    setSubmitting(true);
    try {
      const displayNames = await chooseDisplayNames();
      const started = await apiJson<FromPersonalStartResponse>('/ingest/from-personal', session, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          targetFolderId: target,
          ...(note.trim() ? { note: note.trim() } : {}),
          fileIds,
          folderIds,
          displayNames,
        }),
      });
      setStep('running');
      setResult({
        batchId: started.batchId,
        status: 'OPEN',
        done: started.ineligible.length,
        total: started.total,
        items: [],
      });
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSubmitting(false);
    }
  };

  // Poll every 2s while the batch is OPEN; the cleanup stops it on unmount / dialog close (AC-17).
  const batchId = result?.batchId;
  const closed = result?.status === 'CLOSED';
  useEffect(() => {
    if (step !== 'running' || !batchId || closed) return;
    let cancelled = false;
    const tick = async () => {
      try {
        const r = await apiJson<FromPersonalResult>(`/ingest/from-personal/${batchId}`, session);
        if (!cancelled) setResult(r);
      } catch (e) {
        if (!cancelled) setError((e as Error).message);
      }
    };
    void tick();
    const timer = setInterval(() => void tick(), FROM_PERSONAL_POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [step, batchId, closed, session]);

  useEffect(() => {
    if (closed && !finishedRef.current) {
      finishedRef.current = true;
      onFinished();
    }
  }, [closed, onFinished]);

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Bổ sung vào kho"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
    >
      <div className="bg-white rounded-lg shadow-xl w-full max-w-2xl max-h-[90vh] overflow-y-auto p-5">
        <h3 className="text-lg font-bold text-[#0D2546] mb-3">Bổ sung vào kho</h3>

        {error && (
          <div className="mb-3 bg-red-50 border border-red-200 rounded-md p-3 text-sm text-red-800">
            {error}
          </div>
        )}

        {step === 'pick' && (
          <>
            <p className="text-sm text-gray-600 mb-2">
              Chọn thư mục đích trong kho (bắt buộc). Tệp được sao chép; bản trong lưu trữ cá nhân
              vẫn được giữ nguyên.
            </p>
            <div className="border border-gray-200 rounded-md p-2 mb-3 max-h-64 overflow-y-auto">
              {warehouseTree === null ? (
                <p className="text-sm text-gray-400">Đang tải…</p>
              ) : (
                <FolderTree nodes={warehouseTree} selectedId={target} onSelect={setTarget} />
              )}
            </div>
            <label className="block text-sm text-gray-700 mb-1" htmlFor="p2w-note">
              Ghi chú (tuỳ chọn)
            </label>
            <textarea
              id="p2w-note"
              value={note}
              onChange={(e) => setNote(e.target.value)}
              rows={3}
              className="w-full border border-gray-300 rounded-md px-2 py-1.5 text-sm text-gray-800"
            />
            {noteTooLong && (
              <p className="text-xs text-[#D11A2A] mt-1">Ghi chú tối đa {NOTE_MAX_LENGTH} ký tự.</p>
            )}
            <div className="flex justify-end gap-2 mt-4">
              <button
                type="button"
                onClick={onClose}
                className="px-4 py-2 rounded-md text-sm border border-gray-300 text-gray-700 cursor-pointer"
              >
                Huỷ
              </button>
              <button
                type="button"
                disabled={!target || noteTooLong}
                onClick={() => setStep('confirm')}
                className="px-4 py-2 rounded-md text-sm bg-[#0D2546] text-white disabled:bg-gray-300 cursor-pointer disabled:cursor-not-allowed"
              >
                Tiếp tục
              </button>
            </div>
          </>
        )}

        {step === 'confirm' && (
          <>
            <p className="text-sm text-gray-600 mb-2">
              {eligible.length} / {files.length} tệp đủ điều kiện sẽ được bổ sung vào thư mục đích:
            </p>
            <p className="text-sm font-medium text-gray-900 mb-2" data-testid="p2w-target-path">
              {(target && warehouseTree && folderPathNames(warehouseTree, target)?.join(' / ')) ?? '—'}
            </p>
            {warning.overFileCount && limits && (
              <p className="mb-2 text-sm text-[#D11A2A]" role="alert">
                Vượt giới hạn {limits.maxFilesPerBatch} tệp mỗi lượt - hãy bỏ bớt tệp.
              </p>
            )}
            {warning.overTotalBytes && limits && (
              <p className="mb-2 text-sm text-[#D11A2A]" role="alert">
                Vượt giới hạn {formatSize(limits.maxBatchSizeBytes)} mỗi lượt - hãy bỏ bớt tệp.
              </p>
            )}
            <ul className="border border-gray-200 rounded-md divide-y divide-gray-100 max-h-64 overflow-y-auto text-sm">
              {classified.map(({ file, eligibility }) => (
                <li key={file.id} className="flex items-center justify-between gap-3 px-3 py-1.5">
                  <span className="truncate text-gray-800" title={file.originalName}>
                    {file.originalName}
                  </span>
                  {eligibility === 'OK' ? (
                    <span className="text-xs text-gray-500 shrink-0">
                      {formatSize(Number(file.size))}
                    </span>
                  ) : (
                    <span className="text-xs text-[#D11A2A] shrink-0">
                      Không đủ điều kiện: {RESULT_CODE_LABELS[eligibility]}
                    </span>
                  )}
                </li>
              ))}
            </ul>
            <div className="flex justify-end gap-2 mt-4">
              <button
                type="button"
                onClick={() => setStep('pick')}
                className="px-4 py-2 rounded-md text-sm border border-gray-300 text-gray-700 cursor-pointer"
              >
                Quay lại
              </button>
              <button
                type="button"
                disabled={eligible.length === 0 || overLimit || submitting}
                onClick={() => void submit()}
                className="px-4 py-2 rounded-md text-sm bg-[#D11A2A] text-white disabled:bg-gray-300 cursor-pointer disabled:cursor-not-allowed"
              >
                {submitting ? 'Đang gửi…' : 'Bổ sung vào kho'}
              </button>
            </div>
          </>
        )}

        {step === 'running' && result && (
          <>
            <p className="text-sm text-gray-600 mb-2" data-testid="p2w-progress">
              {closed ? 'Đã xử lý xong' : 'Đang xử lý'}: {result.done} / {result.total} tệp
            </p>
            <ul className="border border-gray-200 rounded-md divide-y divide-gray-100 max-h-64 overflow-y-auto text-sm">
              {result.items.map((item) => (
                <li key={item.personalFileId} className="flex items-center justify-between gap-3 px-3 py-1.5">
                  <span className="truncate text-gray-800" title={item.name}>
                    {item.name}
                  </span>
                  <span
                    className={`text-xs shrink-0 ${
                      item.status === 'INGESTED'
                        ? 'text-green-700'
                        : item.status === 'FAILED'
                          ? 'text-[#D11A2A]'
                          : 'text-gray-500'
                    }`}
                  >
                    {item.status === 'INGESTED'
                      ? 'Đã bổ sung'
                      : item.status === 'PENDING'
                        ? 'Đang chờ…'
                        : (item.code && RESULT_CODE_LABELS[item.code]) || item.code || item.status}
                    {item.status === 'FAILED' &&
                      item.code === 'DISPLAY_NAME_CONFLICT' &&
                      closed &&
                      (item.nameRetryCount ?? 0) < MAX_ITEM_RENAMES && (
                        <button
                          type="button"
                          disabled={renaming === item.itemId}
                          onClick={() => void renameItem(item)}
                          className="ml-2 underline text-[#0D2546] cursor-pointer disabled:opacity-50"
                        >
                          Đổi tên &amp; thử lại
                        </button>
                      )}
                  </span>
                </li>
              ))}
            </ul>
            <div className="flex justify-end mt-4">
              <button
                type="button"
                onClick={onClose}
                className="px-4 py-2 rounded-md text-sm bg-[#0D2546] text-white cursor-pointer"
              >
                {closed ? 'Đóng' : 'Đóng (tiếp tục chạy nền)'}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
