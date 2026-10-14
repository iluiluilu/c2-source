import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { SearchResult } from '@/lib/api'
import { SearchResultCard } from '@/components/search/SearchResultCard'

function baseResult(overrides: Partial<SearchResult> = {}): SearchResult {
  return {
    documentId: 'doc-1',
    documentName: 'Hop_dong_thue_nha_2024.pdf',
    docType: 'hop_dong',
    score: 12.34,
    documentDigitizationStatus: 'confirmed',
    segments: [],
    ...overrides,
  }
}

/**
 * search-fulltext-05-frontend.md §4 bước 1.6/1.7, §5 (Task 4/5/8) - the
 * "đang chờ kiểm duyệt" note is driven only by `documentDigitizationStatus`
 * (BA Story 4 AC-1), and the summary-segment link-list-vs-badge split (BA
 * Story 3 AC-3/AC-4).
 */
describe('SearchResultCard', () => {
  afterEach(cleanup)

  it('does not show the "đang chờ kiểm duyệt" note when documentDigitizationStatus is "confirmed"', () => {
    render(
      <SearchResultCard
        result={baseResult({ documentDigitizationStatus: 'confirmed' })}
        onViewPage={vi.fn()}
        onPlaySegment={vi.fn()}
      />,
    )

    expect(screen.queryByText(/chờ kiểm duyệt/i)).toBeNull()
  })

  it('does not show the "đang chờ kiểm duyệt" note when documentDigitizationStatus is null', () => {
    render(
      <SearchResultCard
        result={baseResult({ documentDigitizationStatus: null })}
        onViewPage={vi.fn()}
        onPlaySegment={vi.fn()}
      />,
    )

    expect(screen.queryByText(/chờ kiểm duyệt/i)).toBeNull()
  })

  it('shows the note for every other documentDigitizationStatus value', () => {
    for (const status of ['queued', 'processing', 'failed', 'pending_review', 'in_review']) {
      const { unmount } = render(
        <SearchResultCard
          result={baseResult({ documentDigitizationStatus: status })}
          onViewPage={vi.fn()}
          onPlaySegment={vi.fn()}
        />,
      )
      expect(screen.getByText(/đang chờ kiểm duyệt/i)).toBeTruthy()
      unmount()
    }
  })

  it('renders a link list for a summary segment with sourceReferences, not the "chỉ định vị" badge', () => {
    const result = baseResult({
      segments: [
        {
          segmentId: 'seg-1',
          contentSource: 'summary',
          confirmSource: 'manual',
          highlight: 'Chủ đề chính là dự án <em>số hoá</em>',
          position: {
            type: 'none',
            pageNo: null,
            lineFrom: null,
            lineTo: null,
            bboxPolygon: null,
            highlightOffsetStart: null,
            highlightOffsetEnd: null,
            timeStartMs: null,
            timeEndMs: null,
          },
          sourceReferences: [
            {
              sourceSegmentId: 'src-1',
              sourceRunId: '7e00f104-0000-0000-0000-000000000000',
              segmentIndex: 1,
              timeStartMs: 2000,
              sourceRunReviewState: 'confirmed',
            },
          ],
        },
      ],
    })

    render(<SearchResultCard result={result} onViewPage={vi.fn()} onPlaySegment={vi.fn()} />)

    expect(screen.getByText('Đoạn #1, 0:02')).toBeTruthy()
    expect(screen.queryByText('Chỉ định vị được ở cấp tài liệu')).toBeNull()
  })

  it('renders the "chỉ định vị" badge when sourceReferences is empty/null', () => {
    const result = baseResult({
      segments: [
        {
          segmentId: 'seg-1',
          contentSource: 'summary',
          confirmSource: 'manual',
          highlight: 'Chủ đề chính là dự án <em>số hoá</em>',
          position: {
            type: 'none',
            pageNo: null,
            lineFrom: null,
            lineTo: null,
            bboxPolygon: null,
            highlightOffsetStart: null,
            highlightOffsetEnd: null,
            timeStartMs: null,
            timeEndMs: null,
          },
          sourceReferences: null,
        },
      ],
    })

    render(<SearchResultCard result={result} onViewPage={vi.fn()} onPlaySegment={vi.fn()} />)

    expect(screen.getByText('Chỉ định vị được ở cấp tài liệu')).toBeTruthy()
  })

  it('renders a "Xem trên ảnh trang" button for a page-type position', () => {
    const result = baseResult({
      segments: [
        {
          segmentId: 'seg-1',
          contentSource: 'ocr',
          confirmSource: 'manual',
          highlight: '...<em>thuê nhà</em>...',
          position: {
            type: 'page',
            pageNo: 3,
            lineFrom: 10,
            lineTo: 12,
            bboxPolygon: [
              [0.12, 0.3],
              [0.62, 0.35],
            ],
            highlightOffsetStart: null,
            highlightOffsetEnd: null,
            timeStartMs: null,
            timeEndMs: null,
          },
          sourceReferences: null,
        },
      ],
    })

    render(<SearchResultCard result={result} onViewPage={vi.fn()} onPlaySegment={vi.fn()} />)

    expect(screen.getByText('Xem trên ảnh trang 3')).toBeTruthy()
  })

  it('links the document name to the document page so a result without a position can still be opened', () => {
    render(<SearchResultCard result={baseResult()} onViewPage={vi.fn()} onPlaySegment={vi.fn()} />)

    const link = screen.getByRole('link', { name: 'Hop_dong_thue_nha_2024.pdf' })
    expect(link.getAttribute('href')).toBe('/documents/doc-1')
  })

  it('shows type, folder, uploader and date under the title', () => {
    render(
      <SearchResultCard
        result={baseResult({ docType: 'văn bản', uploaderUsername: 'canbo1', createdAt: '2026-10-13T03:00:00Z' })}
        folderLabel="Kho A / Hợp đồng"
        onViewPage={vi.fn()}
        onPlaySegment={vi.fn()}
      />,
    )
    expect(screen.getByText(/văn bản · Kho A \/ Hợp đồng · Nạp bởi canbo1 · 13\/10\/2026/)).toBeTruthy()
  })
})
