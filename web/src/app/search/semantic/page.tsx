'use client';

import Link from 'next/link';
import { useSession } from 'next-auth/react';
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { ApiError, apiJson } from '@/lib/api';
import type {
  FolderNode,
  GroupRow,
  ReviewSegment,
  SemanticResult,
  SemanticSearchRequestBody,
  SemanticSearchResponseBody,
  TagRow,
} from '@/lib/api';
import { AccessDenied } from '@/components/AccessDenied';
import { AppShell } from '@/components/AppShell';
import { canSeeFeature } from '@/lib/features';
import { SearchFilterPanel } from '@/components/search/SearchFilterPanel';
import { SearchPageViewer } from '@/components/search/SearchPageViewer';
import { SemanticDocumentCard } from '@/components/search/SemanticResultCard';
import { SegmentPlayer } from '@/components/digitization/SegmentPlayer';
import { SearchModal } from '@/components/search/SearchModal';
import { EMPTY_FILTER_STATE, MAX_QUERY_LENGTH, buildFiltersBody } from '@/lib/search-request';
import type { SearchFilterState } from '@/lib/search-request';

/** AC-6: at most 2 pages are viewable. */
const MAX_PAGES = 2;
const PAGE_SIZE = 10;

// Verbatim copy from the BA story (all Vietnamese, none says "câu trả lời" - AC-2.2).
const COPY = {
  heading: 'Các đoạn nội dung liên quan',
  hint: 'Tìm theo số hiệu hoặc tên văn bản chính xác?',
  hintLink: 'Dùng trang Tìm kiếm toàn văn.',
  empty:
    'Không tìm thấy đoạn nội dung nào đủ căn cứ trong phạm vi dữ liệu của bạn. Hãy thử diễn đạt lại câu hỏi hoặc điều chỉnh bộ lọc.',
  scope:
    'Không xác định được phạm vi dữ liệu của bạn nên chưa thể tra cứu. Vui lòng thử lại sau hoặc liên hệ quản trị viên.',
  unavailable: 'Dịch vụ tra cứu đang tạm thời không khả dụng, vui lòng thử lại sau.',
  busy: 'Hệ thống tra cứu ngữ nghĩa đang bận, vui lòng thử lại sau ít giây.',
  lexicalOnly:
    'Tìm kiếm theo ngữ nghĩa đang tạm thời không khả dụng. Kết quả dưới đây chỉ khớp theo từ khoá và có thể bỏ sót nội dung liên quan.',
  semanticOnly:
    'Tìm kiếm theo từ khoá đang tạm thời không khả dụng. Kết quả dưới đây chỉ dựa trên tương đồng ngữ nghĩa và có thể bỏ sót kết quả khớp chính xác mã số/tên riêng.',
} as const;

type Banner =
  | { kind: 'error'; text: string } // red: 403 / 503 / feature off / validation
  | { kind: 'busy'; text: string }; // yellow: 429

function flattenFolders(nodes: FolderNode[], depth = 0): { id: string; label: string }[] {
  return nodes.flatMap((n) => [
    { id: n.id, label: `${' '.repeat(depth * 3)}${n.name}` },
    ...flattenFolders(n.children, depth + 1),
  ]);
}

/** Minimal `ReviewSegment` for `SegmentPlayer`, which only reads segmentId/timeStartMs/timeEndMs. */
function toReviewSegmentStub(result: SemanticResult): ReviewSegment {
  return {
    segmentId: result.segmentId,
    segmentIndex: 0,
    origin: 'ai',
    aiText: result.excerpt,
    currentText: result.excerpt,
    currentRevisionNo: 0,
    isEdited: false,
    isDeleted: false,
    confidence: null,
    pageNo: null,
    bboxPolygon: null,
    readingOrder: null,
    // a video summary has no position: open the document from the start (AC-2.5)
    timeStartMs: result.position.type === 'time' ? result.position.timeStartMs : 0,
    timeEndMs: result.position.type === 'time' ? result.position.timeEndMs : null,
    overlapsAdjacent: false,
    soundEventLabel: null,
    sourceReferences: null,
  };
}

interface SubmittedSearch {
  query: string;
  filters: SearchFilterState;
}

/**
 * search-semantic AC-1..AC-8 (FR-24), `/search/semantic`: always-hybrid natural-language lookup.
 * There is deliberately NO control to force "chỉ ngữ nghĩa"/"chỉ từ khoá" (AC-1.2), no "tìm rộng hơn"
 * (AC-3.2) and no way to page past page 2 (AC-6.1). Degradation is decided by the backend and only
 * ever shown as a yellow banner (AC-5.4).
 */
export default function SemanticSearchPage() {
  const { data: session, status } = useSession();
  const canView = canSeeFeature(session, 'semantic-search');

  const [query, setQuery] = useState('');
  const [filters, setFilters] = useState<SearchFilterState>(EMPTY_FILTER_STATE);
  const [response, setResponse] = useState<SemanticSearchResponseBody | null>(null);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(false);
  const [banner, setBanner] = useState<Banner | null>(null);
  // The exact query+filters of the last request, so "Thử lại" resends the same thing (AC-5.4).
  const [lastSubmitted, setLastSubmitted] = useState<SubmittedSearch | null>(null);

  const [docTypeOptions, setDocTypeOptions] = useState<TagRow[]>([]);
  const [topicOptions, setTopicOptions] = useState<TagRow[]>([]);
  const [groupOptions, setGroupOptions] = useState<GroupRow[]>([]);
  const [folderOptions, setFolderOptions] = useState<{ id: string; label: string }[]>([]);

  const [pageViewer, setPageViewer] = useState<{
    documentId: string;
    documentName: string;
    pageNo: number;
    bboxPolygon: number[][] | null;
  } | null>(null);
  const [player, setPlayer] = useState<{ documentId: string; documentName: string; segment: ReviewSegment } | null>(
    null,
  );
  const [seekNonce, setSeekNonce] = useState(0);

  useEffect(() => {
    if (!canView) return;
    apiJson<TagRow[]>('/tags?kind=doc_type', session).then(setDocTypeOptions).catch(() => setDocTypeOptions([]));
    apiJson<TagRow[]>('/tags?kind=topic', session).then(setTopicOptions).catch(() => setTopicOptions([]));
    apiJson<GroupRow[]>('/groups', session).then(setGroupOptions).catch(() => setGroupOptions([]));
    apiJson<FolderNode[]>('/folders/tree', session)
      .then((tree) => setFolderOptions(flattenFolders(tree)))
      .catch(() => setFolderOptions([]));
  }, [session, canView]);

  const run = useCallback(
    async (submitted: SubmittedSearch, targetPage: number) => {
      const trimmed = submitted.query.trim();
      if (trimmed.length === 0) {
        setBanner({ kind: 'error', text: 'Vui lòng nhập câu hỏi hoặc mô tả ý cần tra cứu.' });
        setResponse(null);
        return;
      }
      if (trimmed.length > MAX_QUERY_LENGTH) {
        setBanner({ kind: 'error', text: `Câu hỏi vượt quá ${MAX_QUERY_LENGTH} ký tự.` });
        setResponse(null);
        return;
      }
      setLastSubmitted(submitted);
      setBanner(null);
      setLoading(true);
      try {
        const body: SemanticSearchRequestBody = {
          query: trimmed,
          filters: buildFiltersBody(submitted.filters),
          page: targetPage,
          size: PAGE_SIZE,
        };
        const res = await apiJson<SemanticSearchResponseBody>('/search/semantic', session, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        });
        setResponse(res);
        setPage(targetPage);
      } catch (e) {
        // AC-4.2/5.2: the old result list is cleared on any failure - never a stale or partial list.
        setResponse(null);
        const apiStatus = e instanceof ApiError ? e.status : 0;
        if (apiStatus === 403) setBanner({ kind: 'error', text: COPY.scope });
        else if (apiStatus === 429) setBanner({ kind: 'busy', text: COPY.busy });
        else if (apiStatus === 503 || apiStatus === 404 || apiStatus === 0) {
          setBanner({ kind: 'error', text: COPY.unavailable });
        } else setBanner({ kind: 'error', text: (e as Error).message });
      } finally {
        setLoading(false);
      }
    },
    [session],
  );

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    void run({ query, filters }, 0);
  };

  const retry = () => {
    if (lastSubmitted) void run(lastSubmitted, 0);
  };

  if (status === 'loading') return null;
  if (!session || !canView) {
    return <AccessDenied featureKey="semantic-search" />;
  }

  const degradedText =
    response?.degradedMode === 'lexical_only'
      ? COPY.lexicalOnly
      : response?.degradedMode === 'semantic_only'
        ? COPY.semanticOnly
        : null;
  const hasNextPage = response != null && page + 1 < MAX_PAGES && response.totalApprox > (page + 1) * PAGE_SIZE;

  const retryButton = (
    <button
      type="button"
      onClick={retry}
      disabled={loading}
      className="ml-3 text-xs font-semibold border border-current rounded px-2 py-0.5 disabled:opacity-50 cursor-pointer disabled:cursor-not-allowed"
    >
      Thử lại
    </button>
  );

  return (
    <AppShell title="Tra cứu ngữ nghĩa">
      <div className="max-w-7xl">
        <div className="mb-6">
          <h2 className="text-2xl font-bold text-[#0D2546]">Tra cứu ngữ nghĩa</h2>
          <p className="text-sm text-gray-500 mt-1">
            Nhập câu hỏi hoặc mô tả ý bằng ngôn ngữ tự nhiên để tìm các đoạn nội dung liên quan trong phạm vi dữ liệu
            của bạn.
          </p>
        </div>

        <form onSubmit={onSubmit} className="mb-5">
          <div className="flex flex-col sm:flex-row gap-2">
            <textarea
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              rows={2}
              maxLength={MAX_QUERY_LENGTH}
              aria-label="Câu hỏi hoặc mô tả ý"
              placeholder="Ví dụ: quy định về thời hạn nộp báo cáo tài chính hằng năm"
              className="flex-1 border border-gray-300 rounded-md px-3 py-2 text-sm text-gray-800"
            />
            <button
              type="submit"
              disabled={loading}
              className="self-start bg-[#0D2546] hover:bg-[#163a6b] disabled:opacity-50 text-white font-semibold px-4 py-2 rounded-md text-sm cursor-pointer disabled:cursor-not-allowed"
            >
              {loading ? 'Đang tìm…' : 'Tìm kiếm'}
            </button>
          </div>
          <p className="text-xs text-gray-500 mt-2">
            {COPY.hint}{' '}
            <Link href="/search" className="text-[#D11A2A] font-semibold">
              {COPY.hintLink}
            </Link>
          </p>
        </form>

        <div className="grid grid-cols-1 lg:grid-cols-4 gap-5">
          <SearchFilterPanel
            filters={filters}
            onChange={setFilters}
            docTypeOptions={docTypeOptions}
            topicOptions={topicOptions}
            groupOptions={groupOptions}
            folderOptions={folderOptions}
          />

          <div className="lg:col-span-3 space-y-4">
            {banner?.kind === 'error' && (
              <div role="alert" className="bg-red-50 border border-red-200 rounded-md p-3 text-sm text-red-800">
                {banner.text}
              </div>
            )}
            {banner?.kind === 'busy' && (
              <div role="alert" className="bg-yellow-50 border border-yellow-200 rounded-md p-3 text-sm text-yellow-800">
                {banner.text}
                {retryButton}
              </div>
            )}
            {degradedText && (
              <div role="status" className="bg-yellow-50 border border-yellow-200 rounded-md p-3 text-sm text-yellow-800">
                {degradedText}
                {retryButton}
              </div>
            )}

            {response && response.results.length === 0 && response.insufficientEvidence && (
              <div className="bg-white rounded-lg border border-gray-200 p-6 text-center text-sm text-gray-600">
                {COPY.empty}
              </div>
            )}

            {response && response.results.length > 0 && (
              <>
                <div className="flex items-baseline justify-between">
                  <h3 className="text-base font-semibold text-[#0D2546]">{COPY.heading}</h3>
                  <p className="text-xs text-gray-400">Khoảng {response.totalApprox} đoạn (số liệu gần đúng)</p>
                </div>
                {groupByDocument(response.results).map((group) => (
                  <SemanticDocumentCard
                    key={group[0].documentId}
                    results={group}
                    onViewPage={(r) =>
                      setPageViewer({
                        documentId: r.documentId,
                        documentName: r.documentName,
                        pageNo: r.position.pageNo ?? 1,
                        bboxPolygon: r.position.bboxPolygon,
                      })
                    }
                    onPlay={(r) => {
                      setPlayer({ documentId: r.documentId, documentName: r.documentName, segment: toReviewSegmentStub(r) });
                      setSeekNonce((n) => n + 1);
                    }}
                  />
                ))}

                <div className="flex items-center justify-between text-sm">
                  <button
                    type="button"
                    disabled={loading || page === 0 || !lastSubmitted}
                    onClick={() => lastSubmitted && void run(lastSubmitted, page - 1)}
                    className="text-[#0D2546] disabled:text-gray-300 font-semibold cursor-pointer disabled:cursor-not-allowed"
                  >
                    ← Trang trước
                  </button>
                  <span className="text-xs text-gray-400">
                    Trang {page + 1}/{MAX_PAGES}
                  </span>
                  <button
                    type="button"
                    disabled={loading || !hasNextPage || !lastSubmitted}
                    onClick={() => lastSubmitted && void run(lastSubmitted, page + 1)}
                    className="text-[#0D2546] disabled:text-gray-300 font-semibold cursor-pointer disabled:cursor-not-allowed"
                  >
                    Trang sau →
                  </button>
                </div>

                {response.hasMore && (
                  <p className="text-xs text-gray-500">
                    Còn nhiều đoạn liên quan hơn mức có thể xem. Hãy thu hẹp bộ lọc hoặc diễn đạt cụ thể hơn câu hỏi.
                  </p>
                )}
              </>
            )}

            {response === null && !loading && !banner && (
              <div className="bg-white rounded-lg border border-gray-200 p-8 text-center text-sm text-gray-400">
                Nhập câu hỏi hoặc mô tả ý rồi bấm Tìm kiếm.
              </div>
            )}
          </div>
        </div>
      </div>

      {pageViewer && (
        <SearchModal title={`${pageViewer.documentName} — Trang ${pageViewer.pageNo}`} documentHref={`/documents/${pageViewer.documentId}`}
          onClose={() => setPageViewer(null)}>
          <SearchPageViewer
            session={session}
            documentId={pageViewer.documentId}
            pageNo={pageViewer.pageNo}
            bboxPolygon={pageViewer.bboxPolygon}
          />
        </SearchModal>
      )}

      {player && (
        <SearchModal title={player.documentName} documentHref={`/documents/${player.documentId}`}
          onClose={() => setPlayer(null)}>
          <SegmentPlayer
            session={session}
            documentId={player.documentId}
            segments={[player.segment]}
            seekRequest={{ segmentId: player.segment.segmentId, nonce: seekNonce }}
            onActiveSegmentChange={() => {}}
          />
        </SearchModal>
      )}
    </AppShell>
  );
}

/** Passages of the same document together, documents in the order of their best passage. */
function groupByDocument(results: SemanticResult[]): SemanticResult[][] {
  const groups = new Map<string, SemanticResult[]>();
  for (const r of results) groups.set(r.documentId, [...(groups.get(r.documentId) ?? []), r]);
  return [...groups.values()];
}
