import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReviewRunDetail, ReviewSegment } from '@/lib/api'
import { loadFullRunDetail } from '@/app/digitization/runs/[runId]/page'

vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>()
  return { ...actual, apiJson: vi.fn() }
})

beforeEach(async () => {
  const api = await import('@/lib/api')
  vi.mocked(api.apiJson).mockReset()
})

function segment(index: number): ReviewSegment {
  return {
    segmentId: `seg-${index}`,
    segmentIndex: index,
    origin: 'ai',
    aiText: `text ${index}`,
    currentText: `text ${index}`,
    currentRevisionNo: 0,
    isEdited: false,
    isDeleted: false,
    confidence: 0.9,
    pageNo: 1,
    bboxPolygon: null,
    readingOrder: index,
    timeStartMs: null,
    timeEndMs: null,
    overlapsAdjacent: false,
    soundEventLabel: null,
    sourceReferences: null,
  }
}

function runDetailPage(
  content: ReviewSegment[],
  page: number,
  size: number,
  totalElements: number,
  totalPages: number,
): ReviewRunDetail {
  return {
    runId: 'run-1',
    documentId: 'doc-1',
    documentName: 'doc.pdf',
    task: 'ocr',
    reviewState: 'pending',
    effectiveStatus: 'pending',
    outputQualityFlag: 'ok',
    lowConfidenceCount: 0,
    segmentCount: totalElements,
    reviewDeadlineAt: null,
    firstEditAt: null,
    isReplacingConfirmedRun: false,
    sourceDurationMs: null,
    previousConfirmedRunId: null,
    staleWarning: false,
    segments: { content, totalElements, page, size, totalPages },
  }
}

/**
 * digitization-review-05-frontend.md §4.2.1 (Task 9): vòng lặp tải
 * `segmentPage` dừng đúng khi hết dữ liệu và gộp đúng tổng số đoạn - một tài
 * liệu OCR nhiều trang hoàn toàn có thể vượt quá 100 segment/lần gọi.
 */
describe('loadFullRunDetail', () => {
  it('stops after 1 call when the first page already has fewer rows than its size (≤100 segments)', async () => {
    const api = await import('@/lib/api')
    const page0 = runDetailPage([segment(0), segment(1)], 0, 100, 2, 1)
    vi.mocked(api.apiJson).mockResolvedValueOnce(page0)

    const { detail, allSegments } = await loadFullRunDetail('run-1', null)

    expect(api.apiJson).toHaveBeenCalledTimes(1)
    expect(api.apiJson).toHaveBeenCalledWith(
      '/digitization-review/runs/run-1?segmentPage=0&segmentSize=100',
      null,
    )
    expect(allSegments).toHaveLength(2)
    expect(detail.runId).toBe('run-1')
  })

  it('keeps calling segmentPage=0,1,2… until content.length < size, merging every page (>100 segments)', async () => {
    const api = await import('@/lib/api')
    const page0 = runDetailPage(
      Array.from({ length: 100 }, (_, i) => segment(i)),
      0,
      100,
      230,
      3,
    )
    const page1 = runDetailPage(
      Array.from({ length: 100 }, (_, i) => segment(100 + i)),
      1,
      100,
      230,
      3,
    )
    const page2 = runDetailPage(
      Array.from({ length: 30 }, (_, i) => segment(200 + i)),
      2,
      100,
      230,
      3,
    )
    vi.mocked(api.apiJson).mockResolvedValueOnce(page0).mockResolvedValueOnce(page1).mockResolvedValueOnce(page2)

    const { allSegments } = await loadFullRunDetail('run-1', null)

    expect(api.apiJson).toHaveBeenCalledTimes(3)
    expect(allSegments).toHaveLength(230)
    expect(allSegments[0].segmentId).toBe('seg-0')
    expect(allSegments[229].segmentId).toBe('seg-229')
  })

  it('also stops via page >= totalPages - 1 even if a page happens to be exactly full', async () => {
    const api = await import('@/lib/api')
    const page0 = runDetailPage(
      Array.from({ length: 100 }, (_, i) => segment(i)),
      0,
      100,
      100,
      1,
    )
    vi.mocked(api.apiJson).mockResolvedValueOnce(page0)

    const { allSegments } = await loadFullRunDetail('run-1', null)

    expect(api.apiJson).toHaveBeenCalledTimes(1)
    expect(allSegments).toHaveLength(100)
  })
})
