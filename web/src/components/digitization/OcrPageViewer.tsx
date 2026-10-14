'use client';

import type { Session } from 'next-auth';
import { useEffect, useRef, useState } from 'react';
import { apiFetch } from '@/lib/api';
import type { ReviewSegment } from '@/lib/api';

/** AC-9: warn if the page image takes noticeably long, without blocking the text list. */
const SLOW_LOAD_MS = 3000;

/** AC-1/AC-3: bounding box of a polygon (ratio 0-1 points, V26) - no need to draw the true polygon shape for MVP. */
export function boundingBoxPercent(polygon: number[][]): { left: number; top: number; width: number; height: number } | null {
  if (!polygon || polygon.length === 0) return null;
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  for (const point of polygon) {
    const [x, y] = point;
    if (typeof x !== 'number' || typeof y !== 'number') continue;
    minX = Math.min(minX, x);
    minY = Math.min(minY, y);
    maxX = Math.max(maxX, x);
    maxY = Math.max(maxY, y);
  }
  if (!Number.isFinite(minX) || !Number.isFinite(minY) || !Number.isFinite(maxX) || !Number.isFinite(maxY)) {
    return null;
  }
  return { left: minX * 100, top: minY * 100, width: (maxX - minX) * 100, height: (maxY - minY) * 100 };
}

export interface OcrPageViewerProps {
  session: Session | null;
  runId: string;
  pageNo: number;
  maxPageNo: number;
  onPageChange: (pageNo: number) => void;
  /** Segments already filtered by the caller to this pageNo (digitization-review-05-frontend.md §4.2). */
  segments: ReviewSegment[];
  selectedSegmentId: string | null;
  onSelectSegment: (segmentId: string) => void;
  /**
   * digitization-review-07-confirmed-content-view.md §4: trang xem nội dung
   * đã confirmed (ngoài luồng review) dùng endpoint ảnh read-only
   * (`.../runs/{runId}/view/pages/{pageNo}/image`, gate `PERM_document.read`)
   * thay vì `.../runs/{runId}/pages/{pageNo}/image` (gate
   * `digitization_review.manage`). Mặc định `false` (giữ nguyên hành vi cũ) -
   * một boolean thay vì truyền hẳn URL để tránh identity của hàm callback
   * làm effect dưới đây chạy lại mỗi lần cha re-render.
   */
  readOnly?: boolean;
}

/**
 * digitization-review-05-frontend.md §4.2 (Story 2): renders the current
 * page's original image (via Blob, never a bare <img src="API URL">, the
 * endpoint needs a bearer token) with bbox overlays, and page navigation.
 */
export function OcrPageViewer({
  session,
  runId,
  pageNo,
  maxPageNo,
  onPageChange,
  segments,
  selectedSegmentId,
  onSelectSegment,
  readOnly = false,
}: OcrPageViewerProps) {
  const [imageUrl, setImageUrl] = useState<string | null>(null);
  const [slow, setSlow] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pageInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let cancelled = false;
    let objectUrl: string | null = null;
    // react-hooks/set-state-in-effect: every setState below runs inside an
    // async callback (fetch .then/.catch/.finally, setTimeout), never
    // synchronously in the effect body itself.
    const slowTimer = setTimeout(() => {
      if (!cancelled) setSlow(true);
    }, SLOW_LOAD_MS);

    const path = readOnly
      ? `/digitization-review/runs/${runId}/view/pages/${pageNo}/image`
      : `/digitization-review/runs/${runId}/pages/${pageNo}/image`;
    apiFetch(path, session)
      .then((res) => res.blob())
      .then((blob) => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(blob);
        setImageUrl(objectUrl);
        setError(null);
      })
      .catch((e: Error) => {
        if (!cancelled) setError(e.message);
      })
      .finally(() => {
        if (!cancelled) setSlow(false);
        clearTimeout(slowTimer);
      });

    return () => {
      cancelled = true;
      clearTimeout(slowTimer);
      // digitization-review-05-frontend.md §4.2: revoke on page change/unmount to avoid leaking memory.
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [session, runId, pageNo, readOnly]);

  const goToPage = (next: number) => {
    const clamped = Math.max(1, Math.min(next, maxPageNo));
    onPageChange(clamped);
  };

  return (
    <div className="bg-white rounded-lg border border-gray-200 p-3">
      <div className="flex items-center justify-between mb-2 text-sm">
        <div className="flex items-center gap-2">
          <button
            onClick={() => goToPage(pageNo - 1)}
            disabled={pageNo <= 1}
            className="bg-gray-200 hover:bg-gray-300 disabled:opacity-50 text-gray-800 font-semibold px-2 py-1 rounded-md text-xs cursor-pointer disabled:cursor-not-allowed"
          >
            ← Trước
          </button>
          <span className="text-gray-600">
            Trang{' '}
            <input
              ref={pageInputRef}
              type="number"
              min={1}
              max={maxPageNo}
              defaultValue={pageNo}
              key={pageNo}
              onBlur={(e) => {
                const v = Number(e.target.value);
                if (Number.isFinite(v)) goToPage(v);
              }}
              onKeyDown={(e) => {
                if (e.key === 'Enter') pageInputRef.current?.blur();
              }}
              className="w-14 border border-gray-300 rounded-md px-1 py-0.5 text-center"
            />{' '}
            / {maxPageNo}
          </span>
          <button
            onClick={() => goToPage(pageNo + 1)}
            disabled={pageNo >= maxPageNo}
            className="bg-gray-200 hover:bg-gray-300 disabled:opacity-50 text-gray-800 font-semibold px-2 py-1 rounded-md text-xs cursor-pointer disabled:cursor-not-allowed"
          >
            Sau →
          </button>
        </div>
        {slow && <span className="text-xs text-yellow-700">Ảnh đang tải hơi lâu…</span>}
      </div>

      {error ? (
        <div className="bg-red-50 border border-red-200 rounded-md p-3 text-sm text-red-800">
          Không tải được ảnh trang: {error}. Danh sách đoạn văn bản bên dưới vẫn dùng được bình thường.
        </div>
      ) : (
        <div className="relative w-full bg-gray-100 rounded-md overflow-hidden" style={{ minHeight: 200 }}>
          {!imageUrl && (
            <div className="p-8 text-center text-sm text-gray-400">Đang tải ảnh trang…</div>
          )}
          {imageUrl && (
            <>
              {/* eslint-disable-next-line @next/next/no-img-element -- Blob object URL, next/image can't optimize it */}
              <img src={imageUrl} alt={`Trang ${pageNo}`} className="w-full h-auto block select-none" />
              {segments.map((segment) => {
                if (!segment.bboxPolygon || segment.bboxPolygon.length === 0) return null;
                const box = boundingBoxPercent(segment.bboxPolygon);
                if (!box) return null;
                const isSelected = segment.segmentId === selectedSegmentId;
                return (
                  <div
                    key={segment.segmentId}
                    onClick={() => onSelectSegment(segment.segmentId)}
                    title={segment.currentText}
                    className={`absolute border-2 cursor-pointer transition-colors ${
                      isSelected ? 'border-[#D11A2A] bg-[#D11A2A]/10' : 'border-blue-400/70 bg-blue-400/5 hover:border-blue-500'
                    }`}
                    style={{
                      left: `${box.left}%`,
                      top: `${box.top}%`,
                      width: `${box.width}%`,
                      height: `${box.height}%`,
                    }}
                  />
                );
              })}
            </>
          )}
        </div>
      )}
    </div>
  );
}
