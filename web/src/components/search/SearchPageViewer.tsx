'use client';

import type { Session } from 'next-auth';
import { useEffect, useState } from 'react';
import { apiFetch } from '@/lib/api';
import { boundingBoxPercent } from '@/components/digitization/OcrPageViewer';

export interface SearchPageViewerProps {
  session: Session | null;
  documentId: string;
  pageNo: number;
  /** V26: ratio 0-1 points - the matched segment's own bbox, if any. */
  bboxPolygon: number[][] | null;
}

/**
 * search-fulltext-05-frontend.md §1/§4 bước 3 (Task 7): a small, search-only
 * counterpart to `OcrPageViewer` - just the page image plus 1 bbox highlight,
 * no page navigation/segment list (this is "jump to where the match is
 * found", not the OCR review workflow `OcrPageViewer` is built for).
 * Deliberately a separate component rather than reworking `OcrPageViewer`
 * itself, per the spec's stated preference not to touch that (tested,
 * digitization-review-only) component when a small standalone one suffices.
 * Fetches `GET /documents/{documentId}/pages/{pageNo}/image` (§3.1's general-
 * scope endpoint, not the review-only one `OcrPageViewer` uses) as a Blob -
 * same bearer-token constraint as every other protected media fetch here.
 */
export function SearchPageViewer({ session, documentId, pageNo, bboxPolygon }: SearchPageViewerProps) {
  const [imageUrl, setImageUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    let objectUrl: string | null = null;

    apiFetch(`/documents/${documentId}/pages/${pageNo}/image`, session)
      .then((res) => res.blob())
      .then((blob) => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(blob);
        setImageUrl(objectUrl);
        setError(null);
      })
      .catch((e: Error) => {
        if (!cancelled) setError(e.message);
      });

    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [session, documentId, pageNo]);

  const box = bboxPolygon ? boundingBoxPercent(bboxPolygon) : null;

  if (error) {
    return (
      <div className="bg-red-50 border border-red-200 rounded-md p-3 text-sm text-red-800">
        Không tải được ảnh trang: {error}
      </div>
    );
  }

  return (
    <div className="relative w-full bg-gray-100 rounded-md overflow-hidden" style={{ minHeight: 200 }}>
      {!imageUrl && <div className="p-8 text-center text-sm text-gray-400">Đang tải ảnh trang…</div>}
      {imageUrl && (
        <>
          {/* eslint-disable-next-line @next/next/no-img-element -- Blob object URL, next/image can't optimize it */}
          <img src={imageUrl} alt={`Trang ${pageNo}`} className="w-full h-auto block select-none" />
          {box && (
            <div
              className="absolute border-2 border-[#D11A2A] bg-[#D11A2A]/10"
              style={{ left: `${box.left}%`, top: `${box.top}%`, width: `${box.width}%`, height: `${box.height}%` }}
            />
          )}
        </>
      )}
    </div>
  );
}
