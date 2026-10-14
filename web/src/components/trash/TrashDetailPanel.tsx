'use client';

import { useSession } from 'next-auth/react';
import { useEffect, useState } from 'react';
import { apiJson } from '@/lib/api';
import type { RestoreResult, TrashDetail } from '@/lib/api';
import { RestoreDialog } from './RestoreDialog';

function formatSize(bytes: number): string {
  if (bytes >= 1_073_741_824) return `${(bytes / 1_073_741_824).toFixed(1)} GB`;
  if (bytes >= 1_048_576) return `${(bytes / 1_048_576).toFixed(1)} MB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${bytes} B`;
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-4 py-1.5 border-b border-gray-100 last:border-0">
      <span className="text-gray-400">{label}</span>
      <span className="text-gray-800 text-right break-all">{value}</span>
    </div>
  );
}

/**
 * delete-restore-trash-01-xoa-mem-thung-rac.md Story 1 AC-6: xem chi tiết 1
 * item trong thùng rác (không bắt buộc để phục hồi) - fetch GET /trash/{id}.
 * Cũng có nút "Phục hồi" tiện dụng mở thẳng RestoreDialog, không bắt buộc
 * theo AC-6 nhưng hợp lý để không phải đóng panel rồi tìm lại nút "Phục hồi"
 * ở bảng.
 */
export function TrashDetailPanel({
  documentId,
  onClose,
  onRestored,
}: {
  documentId: string;
  onClose: () => void;
  onRestored?: (result: RestoreResult) => void;
}) {
  const { data: session } = useSession();
  const [detail, setDetail] = useState<TrashDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [restoring, setRestoring] = useState(false);

  useEffect(() => {
    apiJson<TrashDetail>(`/trash/${documentId}`, session)
      .then(setDetail)
      .catch((e: Error) => setError(e.message));
  }, [documentId, session]);

  return (
    <>
      <div
        className="fixed inset-0 z-40 flex items-center justify-center bg-black/50 p-4"
        onClick={onClose}
      >
        <div
          role="dialog"
          aria-modal="true"
          aria-label="Chi tiết tài liệu trong thùng rác"
          className="w-full max-w-lg rounded-lg bg-white p-6 shadow-2xl"
          onClick={(e) => e.stopPropagation()}
        >
          <h2 className="text-lg font-bold text-[#0D2546] mb-4">Chi tiết tài liệu</h2>

          {error && <p className="text-sm text-red-700 mb-4">{error}</p>}

          {!detail ? (
            <p className="text-sm text-gray-400">Đang tải…</p>
          ) : (
            <div className="text-sm">
              <Row label="Tên tài liệu" value={detail.originalName} />
              <Row label="Kích thước" value={formatSize(detail.size)} />
              <Row
                label="Thời điểm xóa"
                value={new Date(detail.deletedAt).toLocaleString('vi-VN')}
              />
              <Row
                label="Hạn xóa cứng"
                value={new Date(detail.purgeAfter).toLocaleString('vi-VN')}
              />
              <Row label="Người xóa" value={detail.deletedBySub} />
              <Row label="Người nạp" value={detail.uploaderSub} />
              <Row label="Trạng thái xóa cứng" value={detail.purgeState} />
              <Row
                label="Nhóm còn hiệu lực chia sẻ"
                value={String(detail.activeGroupShareCount)}
              />

              {detail.folderMissing && (
                <div className="mt-3 bg-yellow-50 border border-yellow-200 rounded-md p-3 text-yellow-800 text-xs">
                  Thư mục gốc đã bị xóa.
                  {detail.suggestedFolderId && (
                    <>
                      {' '}
                      Thư mục gợi ý phục hồi:{' '}
                      <span className="font-mono">
                        {detail.suggestedFolderId.slice(0, 8)}…
                      </span>
                    </>
                  )}
                </div>
              )}
            </div>
          )}

          <div className="flex justify-end gap-3 mt-6">
            <button
              type="button"
              onClick={onClose}
              className="rounded-md border border-gray-300 px-4 py-2 text-sm font-semibold text-gray-700 hover:bg-gray-50 cursor-pointer"
            >
              Đóng
            </button>
            {detail && (
              <button
                type="button"
                onClick={() => setRestoring(true)}
                className="rounded-md bg-[#0D2546] px-4 py-2 text-sm font-semibold text-white hover:bg-[#163a6b] cursor-pointer"
              >
                Phục hồi
              </button>
            )}
          </div>
        </div>
      </div>

      {restoring && (
        <RestoreDialog
          documentId={documentId}
          onClose={() => setRestoring(false)}
          onSuccess={(result) => {
            setRestoring(false);
            onRestored?.(result);
          }}
        />
      )}
    </>
  );
}
