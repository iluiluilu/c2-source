'use client';

import Link from 'next/link';
import type { SearchResult, SearchResultSegment } from '@/lib/api';
import { Highlight } from '@/lib/search-highlight';

/** mm:ss (or h:mm:ss past 1h) - Story 3 AC-4's "Đoạn #N, mm:ss" link label. */
function formatClockMs(ms: number | null): string {
  if (ms == null) return '--:--';
  const totalSeconds = Math.floor(ms / 1000);
  const hh = Math.floor(totalSeconds / 3600);
  const mm = Math.floor((totalSeconds % 3600) / 60);
  const ss = totalSeconds % 60;
  const mmSs = `${String(mm).padStart(hh > 0 ? 2 : 1, '0')}:${String(ss).padStart(2, '0')}`;
  return hh > 0 ? `${hh}:${mmSs}` : mmSs;
}

export interface SearchResultCardProps {
  result: SearchResult;
  /** BA Story 3 AC-1 - "Xem trên ảnh trang" (position.type === 'page'). */
  onViewPage: (result: SearchResult, segment: SearchResultSegment) => void;
  /** BA Story 3 AC-2 - "Nghe/Xem đoạn này" (position.type === 'time'). */
  onPlaySegment: (result: SearchResult, segment: SearchResultSegment) => void;
  /** The submitted keywords - fallback highlighting when a segment has no server markers. */
  query?: string;
  /** Path of result.folderId ("Kho A / Hợp đồng"), resolved by the page from the folder tree. */
  folderLabel?: string | null;
}

/**
 * search-fulltext-05-frontend.md §4 bước 1.3/1.6/1.7, §5 (Task 4/5/8): one
 * matched document, collapsed to its (up to 3) matched segments - highlight
 * rendering (Task 5, never raw HTML), the "đang chờ kiểm duyệt" note (BA
 * Story 4 AC-1, driven only by `documentDigitizationStatus`, never
 * `contentSource`), and the per-segment position/source-reference badges (BA
 * Story 3 AC-3/AC-4/AC-5).
 */
export function SearchResultCard({ result, onViewPage, onPlaySegment, query, folderLabel }: SearchResultCardProps) {
  const isPendingReview =
    result.documentDigitizationStatus != null && result.documentDigitizationStatus !== 'confirmed';

  return (
    <div className="bg-white rounded-lg border border-gray-200 p-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h3 className="font-semibold text-[#0D2546]">
            {/* Every hit (incl. title/note matches with no position) can open its document. */}
            <Link href={`/documents/${result.documentId}`} className="hover:text-[#D11A2A] hover:underline">
              {result.documentName}
            </Link>
          </h3>
          <p className="text-xs text-gray-400 mt-0.5">
            {[
              result.docType,
              folderLabel,
              result.uploaderUsername && `Nạp bởi ${result.uploaderUsername}`,
              result.createdAt && new Date(result.createdAt).toLocaleDateString('vi-VN'),
            ]
              .filter(Boolean)
              .join(' · ')}
          </p>
          {isPendingReview && (
            <p className="text-xs text-yellow-800 bg-yellow-50 inline-block px-2 py-0.5 rounded mt-1">
              Nội dung đang chờ kiểm duyệt, kết quả tìm kiếm có thể chưa đầy đủ/chính xác.
            </p>
          )}
        </div>
      </div>

      <ul className="mt-3 space-y-3">
        {result.segments.map((segment) => (
          <li key={segment.segmentId} className="border-t border-gray-100 pt-3 first:border-t-0 first:pt-0">
            <p className="text-sm text-gray-700">
              <Highlight text={segment.highlight} query={query} />
            </p>
            <div className="mt-1.5 flex flex-wrap items-center gap-2 text-xs">
              {segment.confirmSource === 'auto' && (
                <span className="px-1.5 py-0.5 rounded bg-blue-50 text-blue-700">Tự động xác nhận</span>
              )}

              {segment.position.type === 'page' && (
                <button
                  onClick={() => onViewPage(result, segment)}
                  className="text-[#0D2546] hover:text-[#D11A2A] font-semibold cursor-pointer"
                >
                  Xem trên ảnh trang {segment.position.pageNo}
                </button>
              )}

              {segment.position.type === 'time' && (
                <button
                  onClick={() => onPlaySegment(result, segment)}
                  className="text-[#0D2546] hover:text-[#D11A2A] font-semibold cursor-pointer"
                >
                  Nghe/Xem đoạn này
                </button>
              )}

              {segment.position.type === 'none' &&
                (segment.sourceReferences && segment.sourceReferences.length > 0 ? (
                  <span className="flex flex-wrap gap-2">
                    {segment.sourceReferences.map((ref) => (
                      <a
                        key={ref.sourceSegmentId}
                        href={`/digitization/runs/${ref.sourceRunId}`}
                        target="_blank"
                        rel="noreferrer"
                        className="text-[#0D2546] hover:text-[#D11A2A] font-semibold"
                      >
                        Đoạn #{ref.segmentIndex}, {formatClockMs(ref.timeStartMs)}
                      </a>
                    ))}
                  </span>
                ) : (
                  <span className="px-1.5 py-0.5 rounded bg-gray-100 text-gray-500">
                    Chỉ định vị được ở cấp tài liệu
                  </span>
                ))}
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}
