'use client';

import Link from 'next/link';
import type { SemanticResult } from '@/lib/api';

/** mm:ss (or h:mm:ss past 1h) for the source-reference link labels. */
function formatClockMs(ms: number | null): string {
  if (ms == null) return '--:--';
  const totalSeconds = Math.floor(ms / 1000);
  const hh = Math.floor(totalSeconds / 3600);
  const mm = Math.floor((totalSeconds % 3600) / 60);
  const ss = totalSeconds % 60;
  const mmSs = `${String(mm).padStart(hh > 0 ? 2 : 1, '0')}:${String(ss).padStart(2, '0')}`;
  return hh > 0 ? `${hh}:${mmSs}` : mmSs;
}

export interface SemanticResultCardProps {
  result: SemanticResult;
  /** AC-2: jump to the exact page (position.type === 'page'). */
  onViewPage: (result: SemanticResult) => void;
  /** AC-2 / AC-2.5: play from the segment's time (type 'time'), or from the start for a video summary. */
  onPlay: (result: SemanticResult) => void;
}

/**
 * search-semantic AC-2/AC-2.1/AC-2.3/AC-2.5/AC-8 (FR-24): one relevant passage with its citation.
 * Same visual conventions as `SearchResultCard`, but a different shape on purpose: the FR-24
 * response is one row per passage (not a document collapsed to <= 3 inner hits) and carries
 * NO highlight (AC-2.3), so the card shows the whole excerpt as plain text. Copy is Vietnamese
 * only and never says "câu trả lời" (AC-2.2).
 */
export function SemanticResultCard({ result, onViewPage, onPlay }: SemanticResultCardProps) {
  return <SemanticDocumentCard results={[result]} onViewPage={onViewPage} onPlay={onPlay} />;
}

/**
 * UX review 2026-10-13 (UX-28): several passages of the same document are
 * shown in ONE card (header once, passages in rank order) instead of
 * repeating the document as separate cards.
 */
export function SemanticDocumentCard({
  results,
  onViewPage,
  onPlay,
}: {
  results: SemanticResult[];
  onViewPage: (result: SemanticResult) => void;
  onPlay: (result: SemanticResult) => void;
}) {
  const first = results[0];
  const isPendingReview =
    first.documentDigitizationStatus != null && first.documentDigitizationStatus !== 'confirmed';

  return (
    <div className="bg-white rounded-lg border border-gray-200 p-4">
      <h3 className="font-semibold text-[#0D2546]">
        {/* Every hit (incl. title/note matches with no position) can open its document. */}
        <Link href={`/documents/${first.documentId}`} className="hover:text-[#D11A2A] hover:underline">
          {first.documentName}
        </Link>
        {results.length > 1 && (
          <span className="ml-2 text-xs font-normal text-gray-400">{results.length} đoạn liên quan</span>
        )}
      </h3>
      {first.docType && <p className="text-xs text-gray-400 mt-0.5">{first.docType}</p>}
      {isPendingReview && (
        <p className="text-xs text-gray-500 bg-gray-100 inline-block px-2 py-0.5 rounded mt-1">
          Nội dung đang chờ kiểm duyệt, có thể mất tới 24 giờ để tìm được theo nội dung đầy đủ.
        </p>
      )}
      <ul className="mt-3 space-y-3">
        {results.map((result, i) => (
          <li key={i} className="border-t border-gray-100 pt-3 first:border-t-0 first:pt-0">
            <SemanticPassage result={result} onViewPage={onViewPage} onPlay={onPlay} />
          </li>
        ))}
      </ul>
    </div>
  );
}

function SemanticPassage({ result, onViewPage, onPlay }: SemanticResultCardProps) {
  const isSummary = result.contentSource === 'summary';

  return (
    <>
      <p className="text-sm text-gray-700 whitespace-pre-line">{result.excerpt}</p>
      <div className="mt-2 flex flex-wrap items-center gap-2 text-xs">
        {result.confirmSource === 'auto' && (
          <span className="px-1.5 py-0.5 rounded bg-yellow-50 text-yellow-800">
            Nội dung tự động xác nhận — cân nhắc kỹ khi dùng làm chứng cứ
          </span>
        )}
        {result.matchedLexical && (
          <span className="px-1.5 py-0.5 rounded bg-gray-100 text-gray-600">Khớp từ khoá</span>
        )}
        {isSummary && (
          <span className="px-1.5 py-0.5 rounded bg-blue-50 text-blue-700">Tóm tắt nội dung video</span>
        )}

        {result.position.type === 'page' && (
          <button
            onClick={() => onViewPage(result)}
            className="text-[#0D2546] hover:text-[#D11A2A] font-semibold cursor-pointer"
          >
            Xem trên ảnh trang {result.position.pageNo}
            {result.position.lineFrom != null && result.position.lineTo != null
              ? ` (dòng ${result.position.lineFrom}–${result.position.lineTo})`
              : ''}
          </button>
        )}

        {result.position.type === 'time' && (
          <button
            onClick={() => onPlay(result)}
            className="text-[#0D2546] hover:text-[#D11A2A] font-semibold cursor-pointer"
          >
            Nghe/Xem đoạn này ({formatClockMs(result.position.timeStartMs)})
          </button>
        )}

        {result.position.type === 'none' && (
          <>
            {isSummary && (
              <button
                onClick={() => onPlay(result)}
                className="text-[#0D2546] hover:text-[#D11A2A] font-semibold cursor-pointer"
              >
                Mở tài liệu từ đầu
              </button>
            )}
            {isSummary &&
              result.sourceReferences?.map((ref) => (
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
            <span className="px-1.5 py-0.5 rounded bg-gray-100 text-gray-500">Không có vị trí cụ thể</span>
          </>
        )}
      </div>
    </>
  );
}
