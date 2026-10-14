import { renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ReviewRunDetail, ReviewSegment, ReviewSourceReference } from '@/lib/api'
import { confirmedViewSourceHref, useHighlightSegmentFromUrl } from '@/components/digitization/highlightSegment'

/** digitization-review-04-sed-summary.md §4 bước 5 (chỉnh 2026-09-23): deep-link `?highlightSegmentIndex=`. */
function seg(segmentId: string, segmentIndex: number, isDeleted = false): ReviewSegment {
  return { segmentId, segmentIndex, isDeleted } as ReviewSegment
}

const runDetail = { task: 'stt' } as ReviewRunDetail

function ref(state: ReviewSourceReference['sourceRunReviewState']): ReviewSourceReference {
  return { sourceSegmentId: 'src-1', sourceRunId: 'run-src', segmentIndex: 7, timeStartMs: 1000, sourceRunReviewState: state }
}

describe('confirmedViewSourceHref', () => {
  it('links to the read-only view of a confirmed source run', () => {
    expect(confirmedViewSourceHref('doc-1', ref('confirmed'))).toBe(
      '/documents/doc-1/digitization/run-src?highlightSegmentIndex=7',
    )
  })

  it('returns null for a source run that is no longer confirmed', () => {
    expect(confirmedViewSourceHref('doc-1', ref('superseded'))).toBeNull()
  })
})

describe('useHighlightSegmentFromUrl', () => {
  afterEach(() => window.history.replaceState(null, '', '/'))

  it('highlights the segment named in the URL once segments are loaded, only once', () => {
    window.history.replaceState(null, '', '/digitization/runs/r1?highlightSegmentIndex=1')
    const onHighlight = vi.fn()
    const segments = [seg('a', 0), seg('b', 1)]
    const { rerender } = renderHook(
      ({ detail, all }) => useHighlightSegmentFromUrl(detail, all, onHighlight),
      { initialProps: { detail: null as ReviewRunDetail | null, all: [] as ReviewSegment[] } },
    )
    expect(onHighlight).not.toHaveBeenCalled()
    rerender({ detail: runDetail, all: segments })
    expect(onHighlight).toHaveBeenCalledTimes(1)
    expect(onHighlight).toHaveBeenCalledWith(segments[1])
    rerender({ detail: runDetail, all: [...segments] })
    expect(onHighlight).toHaveBeenCalledTimes(1)
  })

  it('ignores a missing/deleted segment or an invalid param', () => {
    const onHighlight = vi.fn()
    for (const search of ['?highlightSegmentIndex=1', '?highlightSegmentIndex=5', '?highlightSegmentIndex=abc', '']) {
      window.history.replaceState(null, '', `/x${search}`)
      renderHook(() => useHighlightSegmentFromUrl(runDetail, [seg('a', 0), seg('b', 1, true)], onHighlight))
    }
    expect(onHighlight).not.toHaveBeenCalled()
  })
})
