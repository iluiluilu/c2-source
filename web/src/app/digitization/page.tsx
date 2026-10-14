'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useSession } from 'next-auth/react';
import { useCallback, useEffect, useState } from 'react';
import { apiJson } from '@/lib/api';
import type { ReviewEffectiveStatus, ReviewRunSummaryPageResponse, ReviewTask } from '@/lib/api';
import { AccessDenied } from '@/components/AccessDenied';
import { AppShell } from '@/components/AppShell';
import { canSeeFeature } from '@/lib/features';
import { usePermissions } from '@/lib/permissions';
import { TASK_LABELS } from '@/lib/digitizationLabels';

/** digitization-review-05-frontend.md Story 1 AC-2: "Tất cả" + 1 tab/loại, chỉ 1 giá trị task/lần gọi. */
const TASK_TABS: { key: ReviewTask | 'all'; label: string }[] = [
  { key: 'all', label: 'Tất cả' },
  { key: 'ocr', label: 'Văn bản & ảnh' },
  { key: 'stt', label: 'Lời nói (ghi âm)' },
  { key: 'sed', label: 'Âm thanh sự kiện' },
  { key: 'video_summary', label: 'Tóm tắt video' },
  { key: 'video', label: 'Video' },
];


/** Story 1 AC-1: chỉ hiện các trạng thái thực sự xuất hiện ở hàng chờ (pending/in_review + biến thể). */
const STATUS_FILTER_OPTIONS: { key: ReviewEffectiveStatus; label: string }[] = [
  { key: 'pending', label: 'Đang chờ' },
  { key: 'in_review', label: 'Đang sửa' },
  { key: 'stale', label: 'Quá hạn xử lý' },
  { key: 'upcoming_deadline', label: 'Sắp hết hạn' },
];

const STATUS_LABELS: Record<ReviewEffectiveStatus, string> = {
  pending: 'Đang chờ',
  in_review: 'Đang sửa',
  stale: 'Quá hạn xử lý',
  upcoming_deadline: 'Sắp hết hạn',
  confirmed: 'Đã xác nhận',
};

const STATUS_BADGE_CLASS: Record<ReviewEffectiveStatus, string> = {
  pending: 'bg-gray-100 text-gray-700',
  in_review: 'bg-blue-50 text-blue-700',
  stale: 'bg-red-50 text-red-700',
  upcoming_deadline: 'bg-yellow-50 text-yellow-800',
  confirmed: 'bg-green-50 text-green-700',
};

/**
 * digitization-review-06-queue-pending-status.md §4 bước 6: khi
 * `effectiveStatus === 'pending'`, tra nhãn/màu badge theo `processingStatus`
 * thay vì luôn hiện "Đang chờ" cố định (AC-1/AC-2/AC-6). `cancelled` hoặc giá
 * trị lạ chưa lường trước (AC-4/case biên) fallback về nhãn "Đang chờ" cũ -
 * không throw, không chặn render bảng.
 */
function pendingBadge(processingStatus: string): { label: string; className: string } {
  switch (processingStatus) {
    case 'queued':
    case 'running':
      return { label: 'Đang xử lý', className: 'bg-gray-100 text-gray-700' };
    case 'succeeded':
      return { label: 'Chờ duyệt', className: 'bg-yellow-50 text-yellow-800' };
    case 'failed_retryable':
    case 'failed_permanent':
      return { label: 'Lỗi xử lý', className: 'bg-red-50 text-red-700' };
    default:
      return { label: 'Đang chờ', className: 'bg-gray-100 text-gray-700' };
  }
}

const PAGE_SIZE = 20;

/** AC-1: "còn 3 giờ" / "quá hạn 2 ngày" tương đối, không hiện timestamp thô. */
function formatRelativeDeadline(iso: string | null): string {
  if (!iso) return '—';
  const diffMs = new Date(iso).getTime() - Date.now();
  const overdue = diffMs < 0;
  const abs = Math.abs(diffMs);
  const hours = Math.round(abs / (1000 * 60 * 60));
  let amount: string;
  if (hours < 1) {
    amount = 'dưới 1 giờ';
  } else if (hours < 48) {
    amount = `${hours} giờ`;
  } else {
    amount = `${Math.round(hours / 24)} ngày`;
  }
  return overdue ? `quá hạn ${amount}` : `còn ${amount}`;
}

/**
 * AC-2: "Tất cả" không gửi `task` trong query string - để API tự trả mọi
 * loại. Extracted as a pure function so this specific behaviour is testable
 * without rendering the whole page.
 */
export function buildQueueQuery(
  taskTab: ReviewTask | 'all',
  statusFilter: ReadonlySet<ReviewEffectiveStatus>,
  qualityFlagOnly: boolean,
  page: number,
  size: number,
): URLSearchParams {
  const params = new URLSearchParams();
  if (taskTab !== 'all') params.set('task', taskTab);
  if (statusFilter.size > 0) params.set('status', Array.from(statusFilter).join(','));
  if (qualityFlagOnly) params.set('qualityFlag', 'poor');
  params.set('page', String(page));
  params.set('size', String(size));
  return params;
}

/**
 * digitization-review-05-frontend.md Story 1: hàng chờ kiểm duyệt cho
 * GET /digitization-review/queue - dùng chung cho cả 4 loại task.
 */
export default function DigitizationQueuePage() {
  const { data: session, status } = useSession();
  const router = useRouter();

  const [taskTab, setTaskTab] = useState<ReviewTask | 'all'>('all');
  const [statusFilter, setStatusFilter] = useState<Set<ReviewEffectiveStatus>>(new Set());
  const [qualityFlagOnly, setQualityFlagOnly] = useState(false);
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<ReviewRunSummaryPageResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const perms = usePermissions();
  const canView = canSeeFeature(session, 'digitization', perms);

  const load = useCallback(() => {
    const params = buildQueueQuery(taskTab, statusFilter, qualityFlagOnly, page, PAGE_SIZE);
    apiJson<ReviewRunSummaryPageResponse>(`/digitization-review/queue?${params.toString()}`, session)
      .then((r) => {
        setResult(r);
        setError(null);
      })
      .catch((e: Error) => setError(e.message));
  }, [session, taskTab, statusFilter, qualityFlagOnly, page]);

  useEffect(() => {
    if (canView) load();
  }, [canView, load]);

  // Fail-closed, but avoid flashing AccessDenied while /me/permissions is in flight.
  if (status === 'loading' || (session && perms.status === 'loading')) return null;
  if (!session || !canView) {
    return <AccessDenied featureKey="digitization" />;
  }

  const selectTask = (key: ReviewTask | 'all') => {
    setTaskTab(key);
    setPage(0);
  };

  const toggleStatus = (key: ReviewEffectiveStatus) => {
    setStatusFilter((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
    setPage(0);
  };

  const toggleQualityFlag = () => {
    setQualityFlagOnly((v) => !v);
    setPage(0);
  };

  return (
    <AppShell title="Kiểm duyệt số hoá">
      <div className="max-w-7xl">
        <div className="mb-5">
          <h2 className="text-2xl font-bold text-[#0D2546]">Kiểm duyệt số hoá</h2>
          <p className="text-sm text-gray-500 mt-1">
            Hàng chờ duyệt OCR/STT/âm thanh sự kiện/tóm tắt video cho tài liệu do bạn nạp.
          </p>
        </div>

        {/* AC-2: tab loại task */}
        <div className="flex gap-1 border-b border-gray-200 mb-4 text-sm">
          {TASK_TABS.map((t) => (
            <button
              key={t.key}
              onClick={() => selectTask(t.key)}
              className={`px-3 pb-2 -mb-px border-b-2 transition-colors cursor-pointer ${
                taskTab === t.key
                  ? 'border-[#D11A2A] text-[#0D2546] font-semibold'
                  : 'border-transparent text-gray-500 hover:text-[#0D2546]'
              }`}
            >
              {t.label}
            </button>
          ))}
        </div>

        <div className="mb-5 bg-white rounded-lg border border-gray-200 p-4 flex flex-wrap items-center gap-4">
          <span className="text-xs font-semibold uppercase tracking-wide text-gray-500">
            Trạng thái
          </span>
          {STATUS_FILTER_OPTIONS.map((s) => (
            <label key={s.key} className="flex items-center gap-1.5 text-sm text-gray-700">
              <input
                type="checkbox"
                checked={statusFilter.has(s.key)}
                onChange={() => toggleStatus(s.key)}
              />
              {s.label}
            </label>
          ))}
          <span className="h-4 border-l border-gray-200" />
          <label className="flex items-center gap-1.5 text-sm text-gray-700">
            <input type="checkbox" checked={qualityFlagOnly} onChange={toggleQualityFlag} />
            Chỉ hiện chất lượng kém
          </label>
        </div>

        {error && (
          <div className="mb-4 bg-red-50 border border-red-200 rounded-md p-3 text-sm text-red-800">
            {error}
          </div>
        )}

        <div className="bg-white rounded-lg border border-gray-200 overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-xs uppercase tracking-wide text-gray-500 border-b border-gray-200">
                <th className="px-4 py-3">Tài liệu</th>
                <th className="px-4 py-3">Loại</th>
                <th className="px-4 py-3">Trạng thái</th>
                <th className="px-4 py-3">Chất lượng</th>
                <th className="px-4 py-3">Số đoạn</th>
                <th className="px-4 py-3">Hạn duyệt</th>
              </tr>
            </thead>
            <tbody>
              {result === null ? (
                <tr>
                  <td colSpan={6} className="px-4 py-6 text-center text-gray-400">
                    Đang tải…
                  </td>
                </tr>
              ) : result.content.length === 0 ? (
                <tr>
                  <td colSpan={6} className="px-4 py-6 text-center text-gray-400">
                    Không có tài liệu nào cần duyệt.
                    <span className="mt-1 block text-xs">
                      Nội dung đã duyệt xem ở mục “Nội dung số hoá” trên trang chi tiết tài liệu trong{' '}
                      <Link href="/documents" className="font-semibold text-[#0D2546] hover:underline">
                        Kho tài liệu
                      </Link>
                      .
                    </span>
                  </td>
                </tr>
              ) : (
                result.content.map((row) => (
                  <tr
                    key={row.runId}
                    onClick={() => router.push(`/digitization/runs/${row.runId}`)}
                    className="border-b border-gray-100 last:border-0 cursor-pointer hover:bg-gray-50"
                  >
                    <td className="px-4 py-3 font-semibold text-[#0D2546] max-w-xs truncate" title={row.documentName}>
                      {row.documentName}
                      {row.isReplacingConfirmedRun && (
                        <span
                          title="Đang thay thế một run đã xác nhận trước đó"
                          className="ml-2 text-[10px] uppercase tracking-wide bg-purple-50 text-purple-700 px-1.5 py-0.5 rounded"
                        >
                          Thay thế bản đã duyệt
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-gray-700">{TASK_LABELS[row.task]}</td>
                    <td className="px-4 py-3">
                      {(() => {
                        const badge =
                          row.effectiveStatus === 'pending'
                            ? pendingBadge(row.processingStatus)
                            : { label: STATUS_LABELS[row.effectiveStatus], className: STATUS_BADGE_CLASS[row.effectiveStatus] };
                        return (
                          <span className={`text-xs font-semibold px-2 py-1 rounded-full ${badge.className}`}>
                            {badge.label}
                          </span>
                        );
                      })()}
                    </td>
                    <td className="px-4 py-3">
                      {row.outputQualityFlag === 'poor' ? (
                        <span className="text-xs font-semibold px-2 py-1 rounded-full bg-red-50 text-red-700">
                          Kém {row.lowConfidenceCount > 0 ? `(${row.lowConfidenceCount})` : ''}
                        </span>
                      ) : (
                        <span className="text-gray-300">—</span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-gray-700">{row.segmentCount}</td>
                    <td className="px-4 py-3 text-gray-500 whitespace-nowrap">
                      {formatRelativeDeadline(row.reviewDeadlineAt)}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>

        {result && result.totalElements > 0 && (
          <div className="mt-4 flex items-center justify-between text-sm text-gray-500">
            <span>
              Trang {result.page + 1}/{Math.max(result.totalPages, 1)} — tổng {result.totalElements} tài liệu
            </span>
            <div className="flex gap-2">
              <button
                onClick={() => setPage((p) => Math.max(p - 1, 0))}
                disabled={result.page <= 0}
                className="bg-gray-200 hover:bg-gray-300 disabled:opacity-50 text-gray-800 font-semibold px-3 py-1.5 rounded-md text-sm transition-colors cursor-pointer disabled:cursor-not-allowed"
              >
                ← Trước
              </button>
              <button
                onClick={() => setPage((p) => p + 1)}
                disabled={result.page + 1 >= result.totalPages}
                className="bg-gray-200 hover:bg-gray-300 disabled:opacity-50 text-gray-800 font-semibold px-3 py-1.5 rounded-md text-sm transition-colors cursor-pointer disabled:cursor-not-allowed"
              >
                Sau →
              </button>
            </div>
          </div>
        )}
      </div>
    </AppShell>
  );
}
