'use client';

import Link from 'next/link';
import { useSession } from 'next-auth/react';
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { apiJson } from '@/lib/api';
import type {
  FolderNode,
  GroupRow,
  ReviewSegment,
  SearchResponseBody,
  SearchResultSegment,
  TagRow,
} from '@/lib/api';
import { AccessDenied } from '@/components/AccessDenied';
import { AppShell } from '@/components/AppShell';
import { canSeeFeature } from '@/lib/features';
import { SearchFilterPanel } from '@/components/search/SearchFilterPanel';
import { SearchResultCard } from '@/components/search/SearchResultCard';
import { SearchPageViewer } from '@/components/search/SearchPageViewer';
import { SegmentPlayer } from '@/components/digitization/SegmentPlayer';
import { SearchModal } from '@/components/search/SearchModal';
import { EMPTY_FILTER_STATE, buildSearchRequest, validateSearchInput } from '@/lib/search-request';
import type { SearchFilterState } from '@/lib/search-request';

function flattenFolders(nodes: FolderNode[], depth = 0): { id: string; label: string }[] {
  return nodes.flatMap((n) => [
    { id: n.id, label: `${' '.repeat(depth * 3)}${n.name}` },
    ...flattenFolders(n.children, depth + 1),
  ]);
}

/** §3.2/§4 step 2 (-02) - the 5 diagnostic groups a refine suggestion can name. */
const REFINE_FILTER_LABELS: Record<string, string> = {
  docType: 'Loại tài liệu',
  topicTags: 'Nhãn chủ đề',
  'uploadedFrom/uploadedTo': 'Khoảng ngày nạp',
  groupIds: 'Nhóm',
  'folderId/mediaType/confirmSource': 'Thư mục / Loại nội dung / Nguồn xác nhận',
};

function clearRefinedFilterGroup(filters: SearchFilterState, removedFilter: string): SearchFilterState {
  switch (removedFilter) {
    case 'docType':
      return { ...filters, docType: '' };
    case 'topicTags':
      return { ...filters, topicTags: [] };
    case 'uploadedFrom/uploadedTo':
      return { ...filters, uploadedFrom: '', uploadedTo: '' };
    case 'groupIds':
      return { ...filters, groupIds: [] };
    case 'folderId/mediaType/confirmSource':
      return { ...filters, folderId: '', mediaType: [], confirmSource: '' };
    default:
      return filters;
  }
}

/**
 * §4 bước 4 (BA AC-2 Story 3) - a minimal `ReviewSegment` stub for
 * `SegmentPlayer`, which only ever reads `segmentId`/`timeStartMs`/
 * `timeEndMs` off it; every other field gets a harmless default (this is not
 * a review-queue segment, none of the OCR/edit fields apply here).
 */
function toReviewSegmentStub(segment: SearchResultSegment): ReviewSegment {
  return {
    segmentId: segment.segmentId,
    segmentIndex: 0,
    origin: 'ai',
    aiText: segment.highlight ?? '',
    currentText: segment.highlight ?? '',
    currentRevisionNo: 0,
    isEdited: false,
    isDeleted: false,
    confidence: null,
    pageNo: segment.position.pageNo,
    bboxPolygon: segment.position.bboxPolygon,
    readingOrder: null,
    timeStartMs: segment.position.timeStartMs,
    timeEndMs: segment.position.timeEndMs,
    overlapsAdjacent: false,
    soundEventLabel: null,
    sourceReferences: null,
  };
}

interface PageViewerState {
  documentId: string;
  documentName: string;
  pageNo: number;
  bboxPolygon: number[][] | null;
}

interface SegmentPlayerState {
  documentId: string;
  documentName: string;
  segment: ReviewSegment;
}

/** search-fulltext-05-frontend.md §1/§4 (Task 4/6/7/8/9) - the main search page. */
export default function SearchPage() {
  const { data: session, status } = useSession();
  const canView = canSeeFeature(session, 'search');

  const [query, setQuery] = useState('');
  const [filters, setFilters] = useState<SearchFilterState>(EMPTY_FILTER_STATE);
  const [sort, setSort] = useState<'relevance' | 'recent'>('relevance');
  const [response, setResponse] = useState<SearchResponseBody | null>(null);
  // The query the shown results belong to (the input may have been edited since).
  const [resultQuery, setResultQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [docTypeOptions, setDocTypeOptions] = useState<TagRow[]>([]);
  const [topicOptions, setTopicOptions] = useState<TagRow[]>([]);
  const [groupOptions, setGroupOptions] = useState<GroupRow[]>([]);
  const [folderOptions, setFolderOptions] = useState<{ id: string; label: string }[]>([]);

  const [pageViewer, setPageViewer] = useState<PageViewerState | null>(null);
  const [segmentPlayer, setSegmentPlayer] = useState<SegmentPlayerState | null>(null);
  const [seekNonce, setSeekNonce] = useState(0);

  // §4 bước 1.1: đổ dữ liệu panel lọc song song, không chặn phần còn lại của trang.
  useEffect(() => {
    if (!canView) return;
    apiJson<TagRow[]>('/tags?kind=doc_type', session)
      .then(setDocTypeOptions)
      .catch(() => setDocTypeOptions([]));
    apiJson<TagRow[]>('/tags?kind=topic', session)
      .then(setTopicOptions)
      .catch(() => setTopicOptions([]));
    apiJson<GroupRow[]>('/groups', session)
      .then(setGroupOptions)
      .catch(() => setGroupOptions([]));
    apiJson<FolderNode[]>('/folders/tree', session)
      .then((tree) => setFolderOptions(flattenFolders(tree)))
      .catch(() => setFolderOptions([]));
  }, [session, canView]);

  const runSearch = useCallback(
    async (nextFilters: SearchFilterState) => {
      const validationError = validateSearchInput(query, nextFilters);
      if (validationError) {
        setError(validationError);
        setResponse(null);
        return;
      }
      setError(null);
      setLoading(true);
      try {
        const body = buildSearchRequest({ query, filters: nextFilters, sort, page: 0, size: 20 });
        const res = await apiJson<SearchResponseBody>('/search', session, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        });
        setResponse(res);
        setResultQuery(query);
      } catch (e) {
        setError((e as Error).message);
        setResponse(null);
      } finally {
        setLoading(false);
      }
    },
    [query, sort, session],
  );

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    void runSearch(filters);
  };

  const onRefine = (removedFilter: string) => {
    const nextFilters = clearRefinedFilterGroup(filters, removedFilter);
    setFilters(nextFilters);
    void runSearch(nextFilters);
  };

  if (status === 'loading') return null;
  if (!session || !canView) {
    return <AccessDenied featureKey="search" />;
  }

  return (
    <AppShell title="Tìm kiếm & tra cứu">
      <div className="max-w-7xl">
        <div className="mb-6">
          <h2 className="text-2xl font-bold text-[#0D2546]">Tìm kiếm toàn văn</h2>
          <p className="text-sm text-gray-500 mt-1">
            Tìm theo từ khoá và/hoặc bộ lọc nâng cao trong nội dung đã số hoá.
          </p>
        </div>

        <form onSubmit={onSubmit} className="flex flex-col sm:flex-row gap-2 mb-5">
          <input
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Nhập từ khoá, ví dụ: hợp đồng thuê nhà"
            title="Tối đa 256 ký tự / 32 từ. Nhập nhiều từ để tìm văn bản chứa tất cả các từ đó."
            className="flex-1 border border-gray-300 rounded-md px-3 py-2 text-sm text-gray-800"
          />
          <select
            value={sort}
            onChange={(e) => setSort(e.target.value as 'relevance' | 'recent')}
            className="border border-gray-300 rounded-md px-2 py-2 text-sm text-gray-700"
          >
            <option value="relevance">Liên quan nhất</option>
            <option value="recent">Mới nạp gần đây</option>
          </select>
          <button
            type="submit"
            disabled={loading}
            className="bg-[#0D2546] hover:bg-[#163a6b] disabled:opacity-50 text-white font-semibold px-4 py-2 rounded-md text-sm cursor-pointer disabled:cursor-not-allowed"
          >
            {loading ? 'Đang tìm…' : 'Tìm kiếm'}
          </button>
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
            {error && (
              <div className="bg-red-50 border border-red-200 rounded-md p-3 text-sm text-red-800">{error}</div>
            )}

            {response && response.degradedMode !== 'none' && (
              <div className="bg-yellow-50 border border-yellow-200 rounded-md p-3 text-sm text-yellow-800">
                Hệ thống tìm kiếm đang gặp sự cố tạm thời, kết quả bên dưới có thể chưa đầy đủ.
              </div>
            )}

            {/* results.length, not totalApprox: hits dropped by the Postgres double-check (ACL / non-clean status)
                still count in OpenSearch's estimate, which would otherwise leave an empty panel. */}
            {response && response.results.length === 0 && (
              <div className="bg-white rounded-lg border border-gray-200 p-4 text-sm text-gray-600">
                <p className="font-semibold text-[#0D2546]">Không tìm thấy kết quả phù hợp.</p>
                <ul className="mt-2 list-disc pl-5 text-gray-500 space-y-0.5">
                  <li>Kiểm tra chính tả hoặc thử từ khoá ngắn hơn, ít từ hơn.</li>
                  <li>Bỏ bớt bộ lọc nâng cao đang chọn.</li>
                  <li>Tài liệu mới nạp chỉ tìm được theo nội dung sau khi số hoá xong.</li>
                  <li>
                    Thử{' '}
                    <Link href="/search/semantic" className="font-semibold text-[#0D2546] hover:underline">
                      Tra cứu ngữ nghĩa
                    </Link>{' '}
                    nếu bạn nhớ ý chứ không nhớ chính xác từ ngữ.
                  </li>
                </ul>
                {response.refineSuggestions.length > 0 && (
                  <div className="mt-3 flex flex-wrap gap-2">
                    {response.refineSuggestions.map((s) => (
                      <button
                        key={s.removedFilter}
                        onClick={() => onRefine(s.removedFilter)}
                        className="text-xs border border-gray-300 rounded-full px-3 py-1 text-[#0D2546] hover:bg-gray-50 cursor-pointer"
                      >
                        Bỏ lọc &quot;{REFINE_FILTER_LABELS[s.removedFilter] ?? s.removedFilter}&quot; (≈
                        {s.estimatedCount} kết quả)
                      </button>
                    ))}
                  </div>
                )}
              </div>
            )}

            {response && response.results.length > 0 && (
              <>
                <p className="text-xs text-gray-400">Khoảng {response.totalApprox} kết quả</p>
                {response.results.map((result) => (
                  <SearchResultCard
                    key={result.documentId}
                    result={result}
                    query={resultQuery}
                    folderLabel={
                      folderOptions.find((f) => f.id === result.folderId)?.label.replace(/^(— )+/, '') ?? null
                    }
                    onViewPage={(r, segment) =>
                      setPageViewer({
                        documentId: r.documentId,
                        documentName: r.documentName,
                        pageNo: segment.position.pageNo ?? 1,
                        bboxPolygon: segment.position.bboxPolygon,
                      })
                    }
                    onPlaySegment={(r, segment) => {
                      setSegmentPlayer({
                        documentId: r.documentId,
                        documentName: r.documentName,
                        segment: toReviewSegmentStub(segment),
                      });
                      setSeekNonce((n) => n + 1);
                    }}
                  />
                ))}
              </>
            )}

            {response === null && !loading && !error && (
              <div className="bg-white rounded-lg border border-gray-200 p-8 text-center text-sm text-gray-400">
                Nhập từ khoá hoặc chọn bộ lọc rồi bấm Tìm kiếm.
              </div>
            )}
          </div>
        </div>
      </div>

      {pageViewer && (
        <SearchModal
          title={`${pageViewer.documentName} — Trang ${pageViewer.pageNo}`}
          documentHref={`/documents/${pageViewer.documentId}`}
          onClose={() => setPageViewer(null)}
        >
          <SearchPageViewer
            session={session}
            documentId={pageViewer.documentId}
            pageNo={pageViewer.pageNo}
            bboxPolygon={pageViewer.bboxPolygon}
          />
        </SearchModal>
      )}

      {segmentPlayer && (
        <SearchModal title={segmentPlayer.documentName} documentHref={`/documents/${segmentPlayer.documentId}`}
          onClose={() => setSegmentPlayer(null)}>
          <SegmentPlayer
            session={session}
            documentId={segmentPlayer.documentId}
            segments={[segmentPlayer.segment]}
            seekRequest={{ segmentId: segmentPlayer.segment.segmentId, nonce: seekNonce }}
            onActiveSegmentChange={() => {}}
          />
        </SearchModal>
      )}
    </AppShell>
  );
}
