import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { SemanticResult } from '@/lib/api'
import { SemanticDocumentCard, SemanticResultCard } from '@/components/search/SemanticResultCard'

function baseResult(overrides: Partial<SemanticResult> = {}): SemanticResult {
  return {
    segmentId: 'doc-1:title',
    documentId: 'doc-1',
    documentName: 'Hop_dong_thue_nha_2024.pdf',
    docType: null,
    excerpt: 'Hop_dong_thue_nha_2024.pdf',
    contentSource: 'title',
    confirmSource: null,
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
    matchedLexical: false,
    cosineScore: 0.71,
    rrfScore: 0.016,
    documentDigitizationStatus: null,
    sourceReferences: null,
    ...overrides,
  }
}

/** QA report search-semantic N4: a title/note hit (no position) must still lead to its document. */
describe('SemanticResultCard', () => {
  afterEach(cleanup)

  it('links the document name to the document page for a title hit with no position', () => {
    render(<SemanticResultCard result={baseResult()} onViewPage={vi.fn()} onPlay={vi.fn()} />)

    const link = screen.getByRole('link', { name: 'Hop_dong_thue_nha_2024.pdf' })
    expect(link.getAttribute('href')).toBe('/documents/doc-1')
    expect(screen.getByText('Không có vị trí cụ thể')).toBeTruthy()
  })

  it('links the document name for a note hit too', () => {
    render(
      <SemanticResultCard
        result={baseResult({ segmentId: 'doc-1:note', contentSource: 'note', excerpt: 'Ghi chú hợp đồng' })}
        onViewPage={vi.fn()}
        onPlay={vi.fn()}
      />,
    )

    expect(screen.getByRole('link', { name: 'Hop_dong_thue_nha_2024.pdf' }).getAttribute('href')).toBe('/documents/doc-1')
  })

  it('shows several passages of one document in a single card', () => {
    render(
      <SemanticDocumentCard
        results={[
          baseResult({ segmentId: 's1', excerpt: 'Đoạn thứ nhất' }),
          baseResult({ segmentId: 's2', excerpt: 'Đoạn thứ hai' }),
        ]}
        onViewPage={vi.fn()}
        onPlay={vi.fn()}
      />,
    )
    expect(screen.getAllByRole('heading')).toHaveLength(1)
    expect(screen.getByText('2 đoạn liên quan')).toBeTruthy()
    expect(screen.getByText('Đoạn thứ nhất')).toBeTruthy()
    expect(screen.getByText('Đoạn thứ hai')).toBeTruthy()
  })
})
