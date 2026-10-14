import { describe, expect, it } from 'vitest'
import {
  EMPTY_FILTER_STATE,
  buildFiltersBody,
  buildSearchRequest,
  validateQueryAndFiltersNotBothEmpty,
  validateSearchInput,
  type SearchFilterState,
} from '@/lib/search-request'

/**
 * search-fulltext-05-frontend.md §3.2/§5 (Task 6) - request-building/
 * validation logic, independent of any React rendering.
 */
describe('buildFiltersBody', () => {
  it('returns null when every filter group is empty (matches SearchFilters.isEmpty())', () => {
    expect(buildFiltersBody(EMPTY_FILTER_STATE)).toBeNull()
  })

  it('only includes non-empty groups, and pairs topicTagsMode with topicTags', () => {
    const filters: SearchFilterState = {
      ...EMPTY_FILTER_STATE,
      topicTags: ['tài chính'],
      topicTagsMode: 'OR',
    }

    expect(buildFiltersBody(filters)).toEqual({ topicTags: ['tài chính'], topicTagsMode: 'OR' })
  })

  it('includes every populated group', () => {
    const filters: SearchFilterState = {
      docType: 'hop_dong',
      topicTags: ['tài chính'],
      topicTagsMode: 'AND',
      uploadedFrom: '2026-01-01',
      uploadedTo: '2026-09-01',
      groupIds: ['g1'],
      folderId: 'f1',
      mediaType: ['document'],
      confirmSource: 'manual',
    }

    expect(buildFiltersBody(filters)).toEqual({
      docType: 'hop_dong',
      topicTags: ['tài chính'],
      topicTagsMode: 'AND',
      uploadedFrom: '2026-01-01',
      uploadedTo: '2026-09-01',
      groupIds: ['g1'],
      folderId: 'f1',
      mediaType: ['document'],
      confirmSource: 'manual',
    })
  })
})

describe('buildSearchRequest', () => {
  it('omits filters entirely (null, not {}) when the filter state is empty', () => {
    const request = buildSearchRequest({
      query: 'hop dong thue nha',
      filters: EMPTY_FILTER_STATE,
      sort: 'relevance',
      page: 0,
      size: 20,
    })

    expect(request).toEqual({
      query: 'hop dong thue nha',
      filters: null,
      sort: 'relevance',
      page: 0,
      size: 20,
    })
  })

  it('sends query as null (not an empty string) when blank', () => {
    const request = buildSearchRequest({
      query: '   ',
      filters: { ...EMPTY_FILTER_STATE, docType: 'hop_dong' },
      sort: 'relevance',
      page: 0,
      size: 20,
    })

    expect(request.query).toBeNull()
    expect(request.filters).toEqual({ docType: 'hop_dong' })
  })
})

describe('validateQueryAndFiltersNotBothEmpty', () => {
  it('blocks when both query and every filter are empty (BA AC-8 Story 1)', () => {
    expect(validateQueryAndFiltersNotBothEmpty('   ', EMPTY_FILTER_STATE)).not.toBeNull()
  })

  it('allows a query-only search', () => {
    expect(validateQueryAndFiltersNotBothEmpty('hop dong', EMPTY_FILTER_STATE)).toBeNull()
  })

  it('allows a filters-only search', () => {
    const filters: SearchFilterState = { ...EMPTY_FILTER_STATE, docType: 'hop_dong' }
    expect(validateQueryAndFiltersNotBothEmpty('', filters)).toBeNull()
  })
})

describe('validateSearchInput', () => {
  it('rejects a query longer than 256 characters', () => {
    const longQuery = 'a'.repeat(257)
    expect(validateSearchInput(longQuery, EMPTY_FILTER_STATE)).not.toBeNull()
  })

  it('accepts a query of exactly 256 characters', () => {
    const query = 'a'.repeat(256)
    expect(validateSearchInput(query, EMPTY_FILTER_STATE)).toBeNull()
  })

  it('rejects a query with more than 32 tokens', () => {
    const query = Array.from({ length: 33 }, () => 'tu').join(' ')
    expect(validateSearchInput(query, EMPTY_FILTER_STATE)).not.toBeNull()
  })

  it('rejects more than 20 topicTags', () => {
    const filters: SearchFilterState = {
      ...EMPTY_FILTER_STATE,
      topicTags: Array.from({ length: 21 }, (_, i) => `tag-${i}`),
    }
    expect(validateSearchInput('', filters)).not.toBeNull()
  })

  it('rejects more than 20 groupIds', () => {
    const filters: SearchFilterState = {
      ...EMPTY_FILTER_STATE,
      groupIds: Array.from({ length: 21 }, (_, i) => `g-${i}`),
    }
    expect(validateSearchInput('', filters)).not.toBeNull()
  })

  it('falls through to the both-empty check when every limit is respected', () => {
    expect(validateSearchInput('', EMPTY_FILTER_STATE)).not.toBeNull()
    expect(validateSearchInput('hop dong', EMPTY_FILTER_STATE)).toBeNull()
  })
})
