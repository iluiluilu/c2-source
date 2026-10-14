'use client';

import { useSession } from 'next-auth/react';
import { useCallback, useEffect, useState } from 'react';
import { apiJson } from '@/lib/api';
import type { FolderNode, RestoreBatchItem, RestoreResult, TrashList } from '@/lib/api';
import { AccessDenied } from '@/components/AccessDenied';
import { AppShell } from '@/components/AppShell';
import { canSeeFeature } from '@/lib/features';
import { usePermissions } from '@/lib/permissions';
import { RestoreDialog } from '@/components/trash/RestoreDialog';
import type { RestoreDialogInitialError } from '@/components/trash/RestoreDialog';
import { TrashDetailPanel } from '@/components/trash/TrashDetailPanel';
import { folderPathLabel } from '@/lib/folderPath';
import { toast } from '@/lib/toast';

type View = 'own' | 'uploaded';

const LIMIT = 50;

function formatSize(bytes: number): string {
  if (bytes >= 1_073_741_824) return `${(bytes / 1_073_741_824).toFixed(1)} GB`;
  if (bytes >= 1_048_576) return `${(bytes / 1_048_576).toFixed(1)} MB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${bytes} B`;
}

/** "Còn N ngày" / "Đã quá hạn" relative to purgeAfter, with a warn flag at <=30 days. */
function formatRelativeDays(purgeAfter: string): { label: string; warn: boolean } {
  const diffMs = new Date(purgeAfter).getTime() - Date.now();
  const days = Math.ceil(diffMs / (24 * 60 * 60 * 1000));
  if (days <= 0) return { label: 'Đã quá hạn', warn: true };
  return { label: `Còn ${days} ngày`, warn: days <= 30 };
}

/**
 * Maps a restore-batch item's `code` back to the equivalent HTTP status, so
 * RestoreDialog's single-item error-mapping logic (mục 3) can be reused
 * as-is for `initialError` — the batch response only carries `code`, not the
 * original status (TrashService.RestoreBatchItem.failed), so we recover it
 * from the fixed 8-case conflict table instead of guessing from the
 * CONFLICT/ERROR outcome string alone.
 */
function statusForCode(item: RestoreBatchItem): number {
  switch (item.code) {
    case 'FOLDER_MISSING':
    case 'NAME_CONFLICT':
    case 'PURGING':
      return 409;
    case 'GONE':
      return 410;
    case 'TARGET_FOLDER_NOT_FOUND':
      return 400;
    default:
      return item.status === 'CONFLICT' ? 409 : 400;
  }
}

function tabClass(active: boolean): string {
  return `px-4 py-2 rounded-md text-sm font-semibold transition-colors cursor-pointer ${
    active ? 'bg-[#0D2546] text-white' : 'bg-white text-gray-600 border border-gray-200 hover:bg-gray-50'
  }`;
}

function BatchReportPanel({
  results,
  onResolve,
  onDismiss,
}: {
  results: RestoreBatchItem[];
  onResolve: (item: RestoreBatchItem) => void;
  onDismiss: () => void;
}) {
  const restoredCount = results.filter((r) => r.status === 'RESTORED').length;
  const issues = results.filter((r) => r.status !== 'RESTORED');

  return (
    <div className="mb-4 bg-white border border-gray-200 rounded-lg p-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-gray-700">
          Đã phục hồi {restoredCount}/{results.length} tài liệu.
        </p>
        <button
          onClick={onDismiss}
          className="text-xs text-gray-400 hover:text-gray-600 cursor-pointer"
        >
          Đóng báo cáo
        </button>
      </div>
      {issues.length > 0 && (
        <ul className="mt-3 space-y-2">
          {issues.map((item) => (
            <li
              key={item.documentId}
              className="flex items-center justify-between text-sm border-t border-gray-100 pt-2"
            >
              <span className="text-gray-600">
                {item.documentId} —{' '}
                {item.status === 'CONFLICT' ? 'Xung đột' : 'Lỗi'}
                {item.code ? ` (${item.code})` : ''}
              </span>
              <button
                onClick={() => onResolve(item)}
                className="text-[#0D2546] hover:text-[#D11A2A] font-semibold text-xs cursor-pointer"
              >
                Xử lý
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** The restore dialog closes as soon as it succeeds - this toast is the confirmation. */
function notifyRestored(r: RestoreResult) {
  const lost =
    r.groupsLostEffect.length > 0
      ? ` ${r.groupsLostEffect.length} nhóm từng được chia sẻ đã bị xóa nên không còn hiệu lực.`
      : '';
  toast.success(`Đã phục hồi "${r.restoredName}".${lost}`, {
    label: 'Mở tài liệu',
    href: `/documents/${r.documentId}`,
  });
}

/** delete-restore-trash-01-xoa-mem-thung-rac.md Story 1: danh sách thùng rác (R1). */
export default function TrashPage() {
  const { data: session, status } = useSession();
  const [view, setView] = useState<View>('own');
  const [offset, setOffset] = useState(0);
  const [rows, setRows] = useState<TrashList | null>(null);
  const [folders, setFolders] = useState<FolderNode[]>([]);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [error, setError] = useState<string | null>(null);
  const [detailId, setDetailId] = useState<string | null>(null);
  const [singleRestoreId, setSingleRestoreId] = useState<string | null>(null);
  const [batchReport, setBatchReport] = useState<RestoreBatchItem[] | null>(null);
  const [batchBusy, setBatchBusy] = useState(false);
  const [conflictRestore, setConflictRestore] = useState<{
    documentId: string;
    initialError: RestoreDialogInitialError;
  } | null>(null);
  // AC-23: files of the last restore-batch that stopped on NAME_CONFLICT, asked one popup at a time.
  const [nameConflictQueue, setNameConflictQueue] = useState<string[]>([]);

  const perms = usePermissions();
  const canView = canSeeFeature(session, 'trash', perms);

  const load = useCallback(() => {
    // For the "Thư mục" column (where a restore lands).
    apiJson<FolderNode[]>('/folders/tree', session)
      .then(setFolders)
      .catch(() => setFolders([]));
    apiJson<TrashList>(`/trash?view=${view}&limit=${LIMIT}&offset=${offset}`, session)
      .then((r) => {
        setRows(r);
        setError(null);
      })
      .catch((e: Error) => setError(e.message));
  }, [session, view, offset]);

  useEffect(() => {
    if (!canView) return;
    load();
  }, [canView, load]);

  // Fail-closed, but avoid flashing AccessDenied while /me/permissions is in flight.
  if (status === 'loading' || (session && perms.status === 'loading')) return null;
  if (!session || !canView) {
    return <AccessDenied featureKey="trash" />;
  }

  const changeView = (v: View) => {
    setView(v);
    setOffset(0);
    setSelectedIds(new Set());
    setBatchReport(null);
  };

  const toggleSelect = (id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  };

  const restoreSelected = async () => {
    if (selectedIds.size === 0) return;
    setBatchBusy(true);
    setError(null);
    try {
      const res = await apiJson<{ results: RestoreBatchItem[] }>('/trash/restore-batch', session, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ documentIds: [...selectedIds] }),
      });
      setBatchReport(res.results);
      setNameConflictQueue(
        res.results.filter((r) => r.status !== 'RESTORED' && r.code === 'NAME_CONFLICT').map((r) => r.documentId),
      );
      setSelectedIds(new Set());
      // AC-9: loại các item RESTORED khỏi danh sách bằng cách tải lại từ nguồn.
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBatchBusy(false);
    }
  };

  /**
   * "Áp dụng cho tất cả" ticked: ONE restore-batch for every file still waiting on a name conflict, with
   * "Đổi tên" (AUTO_RENAME) or "Dừng khôi phục" (CANCEL).
   */
  const applyToAllConflicts = async (action: 'AUTO_RENAME' | 'CANCEL') => {
    const ids = nameConflictQueue;
    setNameConflictQueue([]);
    if (ids.length === 0) return;
    setBatchBusy(true);
    setError(null);
    try {
      const res = await apiJson<{ results: RestoreBatchItem[] }>('/trash/restore-batch', session, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ documentIds: ids, onNameConflict: action }),
      });
      setBatchReport((prev) => [
        ...(prev ?? []).filter((r) => !ids.includes(r.documentId)),
        ...res.results,
      ]);
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBatchBusy(false);
    }
  };

  const total = rows?.total ?? 0;
  const hasPrev = offset > 0;
  const hasNext = offset + LIMIT < total;
  const colCount = view === 'uploaded' ? 8 : 7;

  return (
    <AppShell title="Thùng rác">
      <div className="max-w-7xl">
        <div className="mb-6">
          <h2 className="text-2xl font-bold text-[#0D2546]">Thùng rác</h2>
          <p className="text-sm text-gray-500 mt-1">
            Tài liệu đã xóa có thể phục hồi trong thời hạn lưu, sau đó sẽ bị xóa vĩnh viễn.
          </p>
        </div>

        <div className="flex gap-2 mb-4">
          <button onClick={() => changeView('own')} className={tabClass(view === 'own')}>
            Đã xóa bởi tôi
          </button>
          <button onClick={() => changeView('uploaded')} className={tabClass(view === 'uploaded')}>
            Tài liệu tôi nạp bị người khác xóa
          </button>
        </div>

        {error && (
          <div className="mb-4 bg-red-50 border border-red-200 rounded-md p-3 text-sm text-red-800">
            {error}
          </div>
        )}

        {batchReport && (
          <BatchReportPanel
            results={batchReport}
            onResolve={(item) =>
              setConflictRestore({
                documentId: item.documentId,
                initialError: { status: statusForCode(item), body: { code: item.code ?? undefined } },
              })
            }
            onDismiss={() => setBatchReport(null)}
          />
        )}

        {selectedIds.size > 0 && (
          <div className="mb-4 flex items-center justify-between bg-white border border-gray-200 rounded-lg px-4 py-3">
            <span className="text-sm text-gray-600">{selectedIds.size} tài liệu đã chọn</span>
            <button
              onClick={() => void restoreSelected()}
              disabled={batchBusy}
              className="bg-[#0D2546] hover:bg-[#163a6b] disabled:bg-gray-300 text-white font-semibold px-4 py-1.5 rounded-md text-sm cursor-pointer disabled:cursor-not-allowed"
            >
              {batchBusy ? 'Đang phục hồi…' : 'Phục hồi đã chọn'}
            </button>
          </div>
        )}

        <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs uppercase tracking-wide text-gray-400 border-b border-gray-200">
                  <th className="py-3 px-4 font-medium">
                    <span className="sr-only">Chọn</span>
                  </th>
                  <th className="py-3 px-4 font-medium">Tên tài liệu</th>
                  <th className="py-3 px-4 font-medium">Thư mục</th>
                  <th className="py-3 px-4 font-medium">Ngày xóa</th>
                  <th className="py-3 px-4 font-medium">Hạn xóa cứng</th>
                  <th className="py-3 px-4 font-medium">Dung lượng</th>
                  {view === 'uploaded' && <th className="py-3 px-4 font-medium">Người xóa</th>}
                  <th className="py-3 px-4 font-medium text-right">Thao tác</th>
                </tr>
              </thead>
              <tbody>
                {rows === null ? (
                  <tr>
                    <td colSpan={colCount} className="py-8 text-center text-gray-400">
                      Đang tải…
                    </td>
                  </tr>
                ) : rows.items.length === 0 ? (
                  <tr>
                    <td colSpan={colCount} className="py-8 text-center text-gray-400">
                      {view === 'own'
                        ? 'Thùng rác của bạn đang trống.'
                        : 'Không có tài liệu nào bạn nạp bị người khác xóa.'}
                    </td>
                  </tr>
                ) : (
                  rows.items.map((item) => {
                    const rel = formatRelativeDays(item.purgeAfter);
                    return (
                      <tr
                        key={item.documentId}
                        className="border-b border-gray-100 text-gray-800 hover:bg-gray-50 cursor-pointer"
                        onClick={() => setDetailId(item.documentId)}
                      >
                        <td className="py-3 px-4" onClick={(e) => e.stopPropagation()}>
                          <input
                            type="checkbox"
                            checked={selectedIds.has(item.documentId)}
                            onChange={() => toggleSelect(item.documentId)}
                            aria-label={`Chọn ${item.originalName}`}
                          />
                        </td>
                        <td className="py-3 px-4 max-w-xs truncate font-medium" title={item.originalName}>
                          {item.originalName}
                        </td>
                        <td className="py-3 px-4 text-gray-600">
                          {folderPathLabel(folders, item.folderId ?? null) ??
                            (folders.length > 0 && item.folderId ? (
                              <span className="text-gray-400">Thư mục đã bị xóa</span>
                            ) : (
                              '—'
                            ))}
                        </td>
                        <td className="py-3 px-4 text-gray-600">
                          {new Date(item.deletedAt).toLocaleString('vi-VN')}
                        </td>
                        <td className={`py-3 px-4 ${rel.warn ? 'text-red-600 font-semibold' : 'text-gray-600'}`}>
                          {rel.label}
                        </td>
                        <td className="py-3 px-4 text-gray-600">{formatSize(item.size)}</td>
                        {view === 'uploaded' && (
                          <td className="py-3 px-4 text-gray-600">{item.deletedByUsername}</td>
                        )}
                        <td className="py-3 px-4 text-right" onClick={(e) => e.stopPropagation()}>
                          <button
                            onClick={() => setSingleRestoreId(item.documentId)}
                            className="text-[#0D2546] hover:text-[#D11A2A] font-semibold cursor-pointer"
                          >
                            Phục hồi
                          </button>
                        </td>
                      </tr>
                    );
                  })
                )}
              </tbody>
            </table>
          </div>
        </div>

        {rows && total > 0 && (
          <div className="mt-4 flex items-center justify-between text-sm text-gray-500">
            <span>
              Hiển thị {offset + 1}–{Math.min(offset + LIMIT, total)} / {total}
            </span>
            <div className="flex gap-2">
              <button
                onClick={() => setOffset((o) => Math.max(o - LIMIT, 0))}
                disabled={!hasPrev}
                className="bg-gray-200 hover:bg-gray-300 disabled:opacity-50 text-gray-800 font-semibold px-3 py-1.5 rounded-md text-sm transition-colors cursor-pointer disabled:cursor-not-allowed"
              >
                ← Trang trước
              </button>
              <button
                onClick={() => setOffset((o) => o + LIMIT)}
                disabled={!hasNext}
                className="bg-gray-200 hover:bg-gray-300 disabled:opacity-50 text-gray-800 font-semibold px-3 py-1.5 rounded-md text-sm transition-colors cursor-pointer disabled:cursor-not-allowed"
              >
                Trang sau →
              </button>
            </div>
          </div>
        )}
      </div>

      {singleRestoreId && (
        <RestoreDialog
          documentId={singleRestoreId}
          onClose={() => setSingleRestoreId(null)}
          onSuccess={(r) => {
            notifyRestored(r);
            setSingleRestoreId(null);
            load();
          }}
        />
      )}

      {!conflictRestore && !singleRestoreId && nameConflictQueue.length > 0 && (
        <RestoreDialog
          key={nameConflictQueue[0]}
          documentId={nameConflictQueue[0]}
          initialError={{ status: 409, body: { code: 'NAME_CONFLICT' } }}
          otherConflictCount={nameConflictQueue.length - 1}
          onApplyAll={(action) => void applyToAllConflicts(action)}
          onClose={() => setNameConflictQueue([])}
          onSuccess={(r) => {
            notifyRestored(r);
            setNameConflictQueue((q) => q.slice(1));
            load();
          }}
        />
      )}

      {conflictRestore && (
        <RestoreDialog
          documentId={conflictRestore.documentId}
          initialError={conflictRestore.initialError}
          onClose={() => setConflictRestore(null)}
          onSuccess={(r) => {
            notifyRestored(r);
            setConflictRestore(null);
            load();
          }}
        />
      )}

      {detailId && (
        <TrashDetailPanel
          documentId={detailId}
          onClose={() => setDetailId(null)}
          onRestored={() => {
            setDetailId(null);
            load();
          }}
        />
      )}
    </AppShell>
  );
}
