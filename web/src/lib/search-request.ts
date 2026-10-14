import type { SearchFiltersBody, SearchMediaType, SearchRequestBody } from '@/lib/api'

/**
 * search-fulltext-05-frontend.md §3.2/§4 bước 1.2/§5 (Task 6) - client-side
 * mirror of `SearchServiceImpl`'s validation constants
 * (search-fulltext-02-tim-kiem-nang-cao.md §3.3 AC-13), so the user gets an
 * immediate message instead of round-tripping to the backend for a 400. The
 * backend remains the real authority - this is UX only, same convention as
 * `features.ts`'s own note on role gating.
 */
export const MAX_QUERY_LENGTH = 256
export const MAX_QUERY_TOKENS = 32
export const MAX_FILTER_VALUES = 20

/** UI-side filter state - one field per closed group (BA Story 2), independent of the wire shape. */
export interface SearchFilterState {
  docType: string
  topicTags: string[]
  topicTagsMode: 'AND' | 'OR'
  uploadedFrom: string
  uploadedTo: string
  groupIds: string[]
  folderId: string
  mediaType: SearchMediaType[]
  confirmSource: '' | 'manual' | 'auto'
}

export const EMPTY_FILTER_STATE: SearchFilterState = {
  docType: '',
  topicTags: [],
  topicTagsMode: 'AND',
  uploadedFrom: '',
  uploadedTo: '',
  groupIds: [],
  folderId: '',
  mediaType: [],
  confirmSource: '',
}

/** True when every filter group is unset/empty - mirrors `SearchFilters.isEmpty()`. */
export function isFilterStateEmpty(filters: SearchFilterState): boolean {
  return (
    !filters.docType &&
    filters.topicTags.length === 0 &&
    !filters.uploadedFrom &&
    !filters.uploadedTo &&
    filters.groupIds.length === 0 &&
    !filters.folderId &&
    filters.mediaType.length === 0 &&
    !filters.confirmSource
  )
}

/**
 * §3.2 - `filters` is omitted entirely (not sent as an empty object) when
 * every group is empty, matching `SearchFilters.isEmpty()` on the backend.
 */
export function buildFiltersBody(filters: SearchFilterState): SearchFiltersBody | null {
  if (isFilterStateEmpty(filters)) return null
  const body: SearchFiltersBody = {}
  if (filters.docType) body.docType = filters.docType
  if (filters.topicTags.length > 0) {
    body.topicTags = filters.topicTags
    body.topicTagsMode = filters.topicTagsMode
  }
  if (filters.uploadedFrom) body.uploadedFrom = filters.uploadedFrom
  if (filters.uploadedTo) body.uploadedTo = filters.uploadedTo
  if (filters.groupIds.length > 0) body.groupIds = filters.groupIds
  if (filters.folderId) body.folderId = filters.folderId
  if (filters.mediaType.length > 0) body.mediaType = filters.mediaType
  if (filters.confirmSource) body.confirmSource = filters.confirmSource
  return body
}

export interface BuildSearchRequestArgs {
  query: string
  filters: SearchFilterState
  sort: string
  page: number
  size: number
}

export function buildSearchRequest({ query, filters, sort, page, size }: BuildSearchRequestArgs): SearchRequestBody {
  const trimmedQuery = query.trim()
  return {
    query: trimmedQuery.length > 0 ? trimmedQuery : null,
    filters: buildFiltersBody(filters),
    sort: sort || null,
    page,
    size,
  }
}

/**
 * BA AC-8 Story 1 - both query and every filter group are empty -> block
 * before ever calling `POST /search`.
 */
export function validateQueryAndFiltersNotBothEmpty(query: string, filters: SearchFilterState): string | null {
  if (query.trim().length === 0 && isFilterStateEmpty(filters)) {
    return 'Vui lòng nhập từ khoá hoặc chọn ít nhất một bộ lọc trước khi tìm kiếm.'
  }
  return null
}

/**
 * BA AC-4 Story 2 / §3.3 AC-13 (-02) - query length/token-count and per-filter
 * value-count limits, checked client-side before any API call.
 */
export function validateSearchInput(query: string, filters: SearchFilterState): string | null {
  const trimmedQuery = query.trim()
  if (trimmedQuery.length > MAX_QUERY_LENGTH) {
    return `Từ khoá vượt quá ${MAX_QUERY_LENGTH} ký tự.`
  }
  const tokenCount = trimmedQuery.length === 0 ? 0 : trimmedQuery.split(/\s+/).length
  if (tokenCount > MAX_QUERY_TOKENS) {
    return `Từ khoá vượt quá ${MAX_QUERY_TOKENS} từ.`
  }
  if (filters.topicTags.length > MAX_FILTER_VALUES) {
    return `Nhãn chủ đề: tối đa ${MAX_FILTER_VALUES} giá trị.`
  }
  if (filters.groupIds.length > MAX_FILTER_VALUES) {
    return `Nhóm: tối đa ${MAX_FILTER_VALUES} giá trị.`
  }
  if (filters.mediaType.length > MAX_FILTER_VALUES) {
    return `Loại nội dung: tối đa ${MAX_FILTER_VALUES} giá trị.`
  }
  return validateQueryAndFiltersNotBothEmpty(query, filters)
}
