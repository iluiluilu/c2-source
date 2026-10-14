'use client';

import { useEffect, useRef } from 'react';
import type { ReviewRunDetail, ReviewSegment, ReviewSourceReference } from '@/lib/api';

/**
 * digitization-review-04-sed-summary.md §4 bước 5 (chỉnh 2026-09-23):
 * deep-link từ "nguồn tham chiếu" của tóm tắt video tới đúng đoạn STT nguồn.
 */
export const HIGHLIGHT_SEGMENT_PARAM = 'highlightSegmentIndex';

/** Link mặc định (màn duyệt) - mở cả run `superseded` (Story 10 AC-4). */
export function reviewSourceHref(ref: ReviewSourceReference): string {
  return `/digitization/runs/${ref.sourceRunId}?${HIGHLIGHT_SEGMENT_PARAM}=${ref.segmentIndex}`;
}

/**
 * Link trên màn xem đã xác nhận (-07): run nguồn luôn cùng document; endpoint
 * `/runs/{id}/view` chỉ trả run `confirmed` nên trạng thái khác → `null`
 * (hiện chữ, không bấm được).
 */
export function confirmedViewSourceHref(documentId: string, ref: ReviewSourceReference): string | null {
  if (ref.sourceRunReviewState !== 'confirmed') return null;
  return `/documents/${documentId}/digitization/${ref.sourceRunId}?${HIGHLIGHT_SEGMENT_PARAM}=${ref.segmentIndex}`;
}

function highlightSegmentIndexFromUrl(): number | null {
  const raw = new URLSearchParams(window.location.search).get(HIGHLIGHT_SEGMENT_PARAM);
  if (raw == null || raw.trim() === '') return null;
  const index = Number(raw);
  return Number.isInteger(index) && index >= 0 ? index : null;
}

/**
 * Chạy đúng 1 lần sau khi run + toàn bộ đoạn đã tải: nếu URL có
 * `?highlightSegmentIndex=` và đoạn đó còn (chưa xoá) → gọi `onHighlight`.
 * Không tìm thấy → bỏ qua, không báo lỗi.
 */
export function useHighlightSegmentFromUrl(
  runDetail: ReviewRunDetail | null,
  allSegments: ReviewSegment[],
  onHighlight: (segment: ReviewSegment) => void,
) {
  const applied = useRef(false);
  useEffect(() => {
    if (applied.current || !runDetail || allSegments.length === 0) return;
    applied.current = true;
    const index = highlightSegmentIndexFromUrl();
    if (index == null) return;
    const target = allSegments.find((s) => s.segmentIndex === index && !s.isDeleted);
    if (target) onHighlight(target);
    // onHighlight chỉ đọc lúc áp dụng lần duy nhất - không cần theo dõi identity.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runDetail, allSegments]);
}
