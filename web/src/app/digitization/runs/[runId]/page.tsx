'use client';

import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import { useSession } from 'next-auth/react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, apiJson } from '@/lib/api';
import type { ReviewRunDetail, ReviewRunSummary, ReviewSegment, ReviewTask } from '@/lib/api';
import { AccessDenied } from '@/components/AccessDenied';
import { AppShell } from '@/components/AppShell';
import { useConfirm } from '@/components/ConfirmDialog';
import { canSeeFeature } from '@/lib/features';
import { usePermissions } from '@/lib/permissions';
import { OcrPageViewer } from '@/components/digitization/OcrPageViewer';
import { SegmentList } from '@/components/digitization/SegmentList';
import type { SegmentListVariant } from '@/components/digitization/SegmentList';
import { SegmentPlayer } from '@/components/digitization/SegmentPlayer';
import type { SegmentSeekRequest } from '@/components/digitization/SegmentPlayer';
import { useHighlightSegmentFromUrl } from '@/components/digitization/highlightSegment';
import { toast } from '@/lib/toast';
import { TASK_LABELS } from '@/lib/digitizationLabels';


const REVIEW_STATE_LABELS: Record<string, string> = {
  pending: 'Đang chờ',
  in_review: 'Đang sửa',
  confirmed: 'Đã xác nhận',
  superseded: 'Đã bị thay thế',
  not_applicable: 'Không áp dụng',
  void: 'Đã huỷ',
};

const SEGMENT_PAGE_SIZE = 100;
/**
 * digitization-review-05-frontend.md §4.3.1: màn rộng, khung ảnh/player +
 * danh sách vừa khít cửa sổ (header AppShell 72px + padding p-6 của <main>),
 * danh sách đoạn cuộn riêng. Màn hẹp giữ cuộn cả trang.
 */
const BODY_HEIGHT = 'lg:h-[calc(100vh-72px-3rem)]';

/**
 * digitization-review-05-frontend.md §4.2.1: `GET /runs/{id}` phân trang
 * `segments` (tối đa 100/lần) - tải lặp tới khi hết dữ liệu, gộp toàn bộ vào
 * 1 mảng trước khi hiển thị bất kỳ thứ gì phụ thuộc phân trang vật lý (OCR
 * pageNo) hay tổng số đoạn.
 */
export async function loadFullRunDetail(
  runId: string,
  session: Parameters<typeof apiJson>[1],
): Promise<{ detail: ReviewRunDetail; allSegments: ReviewSegment[] }> {
  let page = 0;
  let collected: ReviewSegment[] = [];
  let firstPageDetail: ReviewRunDetail | null = null;
  while (true) {
    const resp = await apiJson<ReviewRunDetail>(
      `/digitization-review/runs/${runId}?segmentPage=${page}&segmentSize=${SEGMENT_PAGE_SIZE}`,
      session,
    );
    if (page === 0) firstPageDetail = resp;
    collected = collected.concat(resp.segments.content);
    const donePaging = resp.segments.content.length < resp.segments.size || page >= resp.segments.totalPages - 1;
    if (donePaging) break;
    page += 1;
  }
  return { detail: firstPageDetail as ReviewRunDetail, allSegments: collected };
}

function variantForTask(task: ReviewTask): SegmentListVariant {
  return task;
}

/**
 * digitization-review-05-frontend.md §4.1 (Story 2-6): khung chung màn chi
 * tiết run, điều phối hiển thị theo `task` - OcrPageViewer+SegmentList cho
 * `ocr`, SegmentPlayer+SegmentList cho `stt`/`sed`, chỉ SegmentList cho
 * `video_summary` (không có player, xem §4.1/§4.3).
 */
export default function DigitizationRunDetailPage() {
  const params = useParams<{ runId: string }>();
  const runId = params.runId;
  const { data: session, status } = useSession();
  const router = useRouter();
  const confirm = useConfirm();

  const [runDetail, setRunDetail] = useState<ReviewRunDetail | null>(null);
  const [allSegments, setAllSegments] = useState<ReviewSegment[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);

  const [pageNo, setPageNo] = useState(1);
  const pageInitialized = useRef(false);

  const [selectedSegmentId, setSelectedSegmentId] = useState<string | null>(null);
  const [seekRequest, setSeekRequest] = useState<SegmentSeekRequest | null>(null);

  const perms = usePermissions();
  const canView = canSeeFeature(session, 'digitization', perms);

  // react-hooks/set-state-in-effect: every setState below runs inside the
  // async loadFullRunDetail().then()/.catch() callback, never synchronously.
  const load = useCallback(() => {
    loadFullRunDetail(runId, session)
      .then(({ detail, allSegments: segments }) => {
        setRunDetail(detail);
        setAllSegments(segments);
        setError(null);
      })
      .catch((e: Error) => setError(e.message));
  }, [runId, session]);

  useEffect(() => {
    if (canView) load();
  }, [canView, load]);

  // AC-4 (-05 Story 2): mặc định mở trang của đoạn đầu tiên, chỉ tính 1 lần
  // sau khi tải xong (không reset lại pageNo mỗi lần allSegments đổi vì sửa/xoá).
  useEffect(() => {
    if (pageInitialized.current || runDetail?.task !== 'ocr' || allSegments.length === 0) return;
    const withPage = allSegments.filter((s) => s.pageNo != null && !s.isDeleted);
    const firstPage = withPage.length > 0 ? Math.min(...withPage.map((s) => s.pageNo as number)) : 1;
    setPageNo(firstPage);
    pageInitialized.current = true;
  }, [allSegments, runDetail]);

  // -04-sed-summary.md §4 bước 5: deep-link `?highlightSegmentIndex=` từ nguồn
  // tham chiếu của tóm tắt video - chọn đoạn (danh sách tự cuộn) + tua player
  // (không tự phát). Khai báo sau effect trang mặc định để ghi đè pageNo.
  useHighlightSegmentFromUrl(runDetail, allSegments, (segment) => {
    if (runDetail?.task === 'ocr' && segment.pageNo != null) setPageNo(segment.pageNo);
    setSelectedSegmentId(segment.segmentId);
    if (runDetail?.task === 'stt' || runDetail?.task === 'sed' || runDetail?.task === 'video') {
      setSeekRequest({ segmentId: segment.segmentId, nonce: Date.now(), play: false });
    }
  });

  // Fail-closed, but avoid flashing AccessDenied while /me/permissions is in flight.
  if (status === 'loading' || (session && perms.status === 'loading')) return null;
  if (!session || !canView) {
    return <AccessDenied featureKey="digitization" />;
  }

  const visibleSegments = allSegments.filter((s) => !s.isDeleted).sort((a, b) => a.segmentIndex - b.segmentIndex);

  const selectSegment = (segmentId: string, seek: boolean) => {
    setSelectedSegmentId(segmentId);
    if (seek && runDetail && (runDetail.task === 'stt' || runDetail.task === 'sed' || runDetail.task === 'video')) {
      setSeekRequest({ segmentId, nonce: Date.now() });
    }
  };

  const handleSegmentUpdated = (updated: ReviewSegment) => {
    setAllSegments((prev) => prev.map((s) => (s.segmentId === updated.segmentId ? updated : s)));
  };

  const handleSegmentDeleted = (updated: ReviewSegment) => {
    setAllSegments((prev) => prev.map((s) => (s.segmentId === updated.segmentId ? updated : s)));
    if (selectedSegmentId === updated.segmentId) setSelectedSegmentId(null);
  };

  const handleSegmentAdded = () => {
    // Server-side manual-insert renumbers every following segment_index
    // (DigitizationRunLifecycleServiceImpl.addManualSegment) - a local splice
    // would leave stale indices, so re-run the full paginated load instead.
    load();
  };

  const doConfirm = async () => {
    const ok = await confirm({
      title: 'Xác nhận kết quả số hoá',
      message: 'Xác nhận đưa kết quả này vào chỉ mục chính thức? Bạn vẫn có thể sửa tiếp sau khi xác nhận.',
    });
    if (!ok) return;
    setConfirming(true);
    setError(null);
    try {
      const summary = await apiJson<ReviewRunSummary>(`/digitization-review/runs/${runId}/confirm`, session, {
        method: 'POST',
      });
      setRunDetail((prev) =>
        prev ? { ...prev, reviewState: summary.reviewState, effectiveStatus: summary.effectiveStatus } : prev,
      );
      toast.success('Đã xác nhận. Đang quay lại hàng chờ…');
      setTimeout(() => router.push('/digitization'), 1500);
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        setError('Trạng thái vừa thay đổi bởi một yêu cầu khác — đã tải lại dữ liệu mới nhất.');
        load();
      } else {
        setError((e as Error).message);
      }
    } finally {
      setConfirming(false);
    }
  };

  const canConfirm = runDetail && (runDetail.reviewState === 'pending' || runDetail.reviewState === 'in_review');

  const renderBody = () => {
    if (!runDetail) return null;
    const task = runDetail.task;

    if (task === 'ocr') {
      const maxPageNo = Math.max(1, ...visibleSegments.map((s) => s.pageNo ?? 1));
      const segmentsOnPage = visibleSegments.filter((s) => s.pageNo === pageNo);
      return (
        <div className={`grid lg:grid-cols-2 lg:grid-rows-[minmax(0,1fr)] lg:items-start gap-4 ${BODY_HEIGHT}`}>
          <div className="lg:max-h-full lg:overflow-y-auto">
            <OcrPageViewer
              session={session}
              runId={runId}
              pageNo={pageNo}
              maxPageNo={maxPageNo}
              onPageChange={setPageNo}
              segments={segmentsOnPage}
              selectedSegmentId={selectedSegmentId}
              onSelectSegment={(id) => selectSegment(id, false)}
            />
          </div>
          <SegmentList
            session={session}
            runId={runId}
            variant="ocr"
            segments={segmentsOnPage}
            selectedSegmentId={selectedSegmentId}
            onSelectSegment={(id) => selectSegment(id, false)}
            sourceDurationMs={runDetail.sourceDurationMs}
            onSegmentUpdated={handleSegmentUpdated}
            onSegmentDeleted={handleSegmentDeleted}
            onSegmentAdded={handleSegmentAdded}
          />
        </div>
      );
    }

    // digitization-pipeline-05-video-review.md: `video` dùng player + danh sách như stt.
    if (task === 'stt' || task === 'sed' || task === 'video') {
      return (
        <div className={`flex flex-col gap-4 ${BODY_HEIGHT}`}>
          <div className="shrink-0">
            <SegmentPlayer
              session={session}
              documentId={runDetail.documentId}
              segments={visibleSegments}
              seekRequest={seekRequest}
              onActiveSegmentChange={(id) => setSelectedSegmentId(id)}
            />
          </div>
          <SegmentList
            session={session}
            runId={runId}
            variant={variantForTask(task)}
            segments={visibleSegments}
            selectedSegmentId={selectedSegmentId}
            onSelectSegment={(id) => selectSegment(id, true)}
            sourceDurationMs={runDetail.sourceDurationMs}
            onSegmentUpdated={handleSegmentUpdated}
            onSegmentDeleted={handleSegmentDeleted}
            onSegmentAdded={handleSegmentAdded}
          />
        </div>
      );
    }

    // task === 'video_summary' - §4.1: không có player riêng.
    return (
      <div className="space-y-4">
        {runDetail.staleWarning && (
          <div className="bg-yellow-50 border border-yellow-200 rounded-md p-3 text-sm text-yellow-800">
            Tóm tắt này có thể đã lỗi thời — có thay đổi mới hơn ở các nguồn tham chiếu sau khi tóm tắt được xác nhận.
          </div>
        )}
        <SegmentList
          session={session}
          runId={runId}
          variant="video_summary"
          segments={visibleSegments}
          selectedSegmentId={selectedSegmentId}
          onSelectSegment={(id) => selectSegment(id, false)}
          sourceDurationMs={null}
          onSegmentUpdated={handleSegmentUpdated}
          onSegmentDeleted={handleSegmentDeleted}
          onSegmentAdded={handleSegmentAdded}
        />
      </div>
    );
  };

  return (
    <AppShell title="Kiểm duyệt số hoá">
      <div className="max-w-7xl">
        <Link href="/digitization" className="text-sm text-gray-500 hover:text-[#0D2546]">
          ← Hàng chờ kiểm duyệt
        </Link>

        {error && (
          <div className="mt-4 bg-red-50 border border-red-200 rounded-md p-3 text-sm text-red-800">
            {error}
          </div>
        )}

        {!runDetail && !error ? (
          <p className="mt-6 text-gray-400 text-sm">Đang tải…</p>
        ) : runDetail ? (
          <>
            <div className="mt-4 flex items-center justify-between gap-3 flex-wrap">
              <div>
                <h2 className="text-2xl font-bold text-[#0D2546] break-all">{runDetail.documentName}</h2>
                <p className="text-sm text-gray-500 mt-1">
                  {TASK_LABELS[runDetail.task]} · {runDetail.segmentCount} đoạn
                  {runDetail.isReplacingConfirmedRun && (
                    <span className="ml-2 text-[10px] uppercase tracking-wide bg-purple-50 text-purple-700 px-1.5 py-0.5 rounded">
                      Thay thế bản đã duyệt
                    </span>
                  )}
                </p>
              </div>
              <div>
                {canConfirm ? (
                  <button
                    onClick={() => void doConfirm()}
                    disabled={confirming}
                    className="bg-[#D11A2A] hover:bg-red-700 disabled:opacity-50 text-white font-semibold px-5 py-2 rounded-md text-sm transition-colors cursor-pointer disabled:cursor-not-allowed"
                  >
                    {confirming ? 'Đang xác nhận…' : 'Xác nhận'}
                  </button>
                ) : (
                  <span className="text-xs font-semibold px-3 py-1.5 rounded-full bg-green-50 text-green-700">
                    {REVIEW_STATE_LABELS[runDetail.reviewState] ?? runDetail.reviewState}
                  </span>
                )}
              </div>
            </div>

            <div className="mt-5">{renderBody()}</div>
          </>
        ) : (
          <p className="mt-6 text-gray-400 text-sm">Không tìm thấy tài liệu này.</p>
        )}
      </div>
    </AppShell>
  );
}
