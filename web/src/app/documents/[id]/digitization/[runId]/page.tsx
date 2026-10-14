'use client';

import Link from 'next/link';
import { useParams } from 'next/navigation';
import { useSession } from 'next-auth/react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { apiJson } from '@/lib/api';
import type { ReviewRunDetail, ReviewSegment, ReviewTask } from '@/lib/api';
import { AppShell } from '@/components/AppShell';
import { OcrPageViewer } from '@/components/digitization/OcrPageViewer';
import { SegmentList } from '@/components/digitization/SegmentList';
import type { SegmentListVariant } from '@/components/digitization/SegmentList';
import { SegmentPlayer } from '@/components/digitization/SegmentPlayer';
import type { SegmentSeekRequest } from '@/components/digitization/SegmentPlayer';
import { confirmedViewSourceHref, useHighlightSegmentFromUrl } from '@/components/digitization/highlightSegment';
import { TASK_LABELS } from '@/lib/digitizationLabels';


const SEGMENT_PAGE_SIZE = 100;
/**
 * digitization-review-05-frontend.md §4.3.1: màn rộng, khung ảnh/player +
 * danh sách vừa khít cửa sổ (header AppShell 72px + padding p-6 của <main>),
 * danh sách đoạn cuộn riêng. Màn hẹp giữ cuộn cả trang.
 */
const BODY_HEIGHT = 'lg:h-[calc(100vh-72px-3rem)]';

function variantForTask(task: ReviewTask): SegmentListVariant {
  return task;
}

/**
 * digitization-review-07-confirmed-content-view.md §4: bản chỉ đọc của
 * `/digitization/runs/[runId]` cho nội dung đã `confirmed`, mở từ trang chi
 * tiết tài liệu - dùng lại 3 component hiển thị (OcrPageViewer/SegmentList/
 * SegmentPlayer) nhưng gọi endpoint `.../view` (quyền theo `PERM_document.read`
 * + phạm vi xem tài liệu, không phải `digitization_review.manage`), không có
 * nút sửa/xoá/thêm đoạn/xác nhận.
 */
export default function DigitizationConfirmedViewPage() {
  const params = useParams<{ id: string; runId: string }>();
  const documentId = params.id;
  const runId = params.runId;
  const { data: session, status } = useSession();

  const [runDetail, setRunDetail] = useState<ReviewRunDetail | null>(null);
  const [allSegments, setAllSegments] = useState<ReviewSegment[]>([]);
  const [error, setError] = useState<string | null>(null);

  const [pageNo, setPageNo] = useState(1);
  const pageInitialized = useRef(false);

  const [selectedSegmentId, setSelectedSegmentId] = useState<string | null>(null);
  const [seekRequest, setSeekRequest] = useState<SegmentSeekRequest | null>(null);

  // react-hooks/set-state-in-effect: mọi setState dưới đây chạy trong
  // callback .then()/.catch() bất đồng bộ, không chạy trực tiếp trong effect.
  const load = useCallback(() => {
    let page = 0;
    let collected: ReviewSegment[] = [];
    let firstPageDetail: ReviewRunDetail | null = null;

    const loadPage = async (): Promise<void> => {
      const resp = await apiJson<ReviewRunDetail>(
        `/digitization-review/runs/${runId}/view?segmentPage=${page}&segmentSize=${SEGMENT_PAGE_SIZE}`,
        session,
      );
      if (page === 0) firstPageDetail = resp;
      collected = collected.concat(resp.segments.content);
      const donePaging = resp.segments.content.length < resp.segments.size || page >= resp.segments.totalPages - 1;
      if (!donePaging) {
        page += 1;
        await loadPage();
      }
    };

    loadPage()
      .then(() => {
        setRunDetail(firstPageDetail);
        setAllSegments(collected);
        setError(null);
      })
      .catch((e: Error) => setError(e.message));
  }, [runId, session]);

  useEffect(() => {
    if (session) load();
  }, [session, load]);

  // AC-4 tương tự -05: mặc định mở trang của đoạn đầu tiên, chỉ tính 1 lần.
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

  if (status === 'loading') return null;
  if (!session) return null;

  const visibleSegments = allSegments.filter((s) => !s.isDeleted).sort((a, b) => a.segmentIndex - b.segmentIndex);

  const selectSegment = (segmentId: string, seek: boolean) => {
    setSelectedSegmentId(segmentId);
    if (seek && runDetail && (runDetail.task === 'stt' || runDetail.task === 'sed' || runDetail.task === 'video')) {
      setSeekRequest({ segmentId, nonce: Date.now() });
    }
  };

  const noop = () => {};

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
              readOnly
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
            onSegmentUpdated={noop}
            onSegmentDeleted={noop}
            onSegmentAdded={noop}
            readOnly
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
            onSegmentUpdated={noop}
            onSegmentDeleted={noop}
            onSegmentAdded={noop}
            readOnly
          />
        </div>
      );
    }

    // task === 'video_summary'
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
          onSegmentUpdated={noop}
          onSegmentDeleted={noop}
          onSegmentAdded={noop}
          sourceRunHref={(ref) => confirmedViewSourceHref(documentId, ref)}
          readOnly
        />
      </div>
    );
  };

  return (
    <AppShell title="Nội dung số hoá">
      <div className="max-w-7xl">
        <Link href={`/documents/${documentId}`} className="text-sm text-gray-500 hover:text-[#0D2546]">
          ← Chi tiết tài liệu
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
            <div className="mt-4">
              <h2 className="text-2xl font-bold text-[#0D2546] break-all">{runDetail.documentName}</h2>
              <p className="text-sm text-gray-500 mt-1">
                {TASK_LABELS[runDetail.task]} · {runDetail.segmentCount} đoạn · Đã xác nhận
              </p>
            </div>

            <div className="mt-5">{renderBody()}</div>
          </>
        ) : (
          <p className="mt-6 text-gray-400 text-sm">Không tìm thấy nội dung này.</p>
        )}
      </div>
    </AppShell>
  );
}
