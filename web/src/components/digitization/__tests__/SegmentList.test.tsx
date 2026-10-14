import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiJson } from '@/lib/api'
import type { ReviewSegment } from '@/lib/api'
import { ConfirmProvider } from '@/components/ConfirmDialog'
import { SegmentList } from '@/components/digitization/SegmentList'
import type { SegmentListVariant } from '@/components/digitization/SegmentList'

vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>()
  return { ...actual, apiJson: vi.fn(), apiFetch: vi.fn() }
})

const mockedApiJson = vi.mocked(apiJson)

function segment(overrides: Partial<ReviewSegment> = {}): ReviewSegment {
  return {
    segmentId: 'seg-1',
    segmentIndex: 0,
    origin: 'ai',
    aiText: 'ai text',
    currentText: 'current text',
    currentRevisionNo: 0,
    isEdited: false,
    isDeleted: false,
    confidence: 0.9,
    pageNo: 1,
    bboxPolygon: null,
    readingOrder: 0,
    timeStartMs: null,
    timeEndMs: null,
    overlapsAdjacent: false,
    soundEventLabel: null,
    sourceReferences: null,
    ...overrides,
  }
}

function renderList(variant: SegmentListVariant, segments: ReviewSegment[], readOnly = false) {
  return render(
    <ConfirmProvider>
      <SegmentList
        session={null}
        runId="run-1"
        variant={variant}
        segments={segments}
        selectedSegmentId={null}
        onSelectSegment={vi.fn()}
        sourceDurationMs={60000}
        onSegmentUpdated={vi.fn()}
        onSegmentDeleted={vi.fn()}
        onSegmentAdded={vi.fn()}
        readOnly={readOnly}
      />
    </ConfirmProvider>,
  )
}

/**
 * digitization-review-05-frontend.md §4.1/§4.3 + §5 (Task 9): SegmentList ẩn
 * đúng nút theo từng `variant` - nút thêm thủ công (chỉ ocr/stt), nút xoá
 * (mọi loại trừ video_summary - Story 5 AC-4), liên kết nguồn (chỉ
 * video_summary).
 */
describe('SegmentList variant gating', () => {
  afterEach(() => cleanup())

  it('ocr: shows "add manual" and delete, no source-reference links', () => {
    renderList('ocr', [segment()])
    expect(screen.getByText('+ Thêm đoạn thủ công')).toBeInTheDocument()
    expect(screen.getByText('Xoá')).toBeInTheDocument()
  })

  it('stt: shows "add manual" and delete', () => {
    renderList('stt', [segment({ timeStartMs: 1000, timeEndMs: 2000 })])
    expect(screen.getByText('+ Thêm đoạn thủ công')).toBeInTheDocument()
    expect(screen.getByText('Xoá')).toBeInTheDocument()
  })

  it('sed: hides "add manual" (Story 4 AC-3), still shows delete (Story 4 AC-4)', () => {
    renderList('sed', [segment({ soundEventLabel: 'Dog bark' })])
    expect(screen.queryByText('+ Thêm đoạn thủ công')).not.toBeInTheDocument()
    expect(screen.getByText('Xoá')).toBeInTheDocument()
  })

  it('video_summary: hides both "add manual" and delete (Story 5 AC-4), shows source-reference links', () => {
    renderList('video_summary', [
      segment({
        sourceReferences: [
          { sourceSegmentId: 'src-1', sourceRunId: 'run-src', segmentIndex: 3, timeStartMs: 12000, sourceRunReviewState: 'confirmed' },
        ],
      }),
    ])
    expect(screen.queryByText('+ Thêm đoạn thủ công')).not.toBeInTheDocument()
    expect(screen.queryByText('Xoá')).not.toBeInTheDocument()
    expect(screen.getByText(/Đoạn #3/)).toBeInTheDocument()
  })

  it('video_summary: source-reference link deep-links to the review screen with highlightSegmentIndex', () => {
    renderList('video_summary', [
      segment({
        sourceReferences: [
          { sourceSegmentId: 'src-1', sourceRunId: 'run-src', segmentIndex: 3, timeStartMs: 12000, sourceRunReviewState: 'superseded' },
        ],
      }),
    ])
    expect(screen.getByText(/Đoạn #3/).closest('a')).toHaveAttribute(
      'href',
      '/digitization/runs/run-src?highlightSegmentIndex=3',
    )
  })
})

/**
 * digitization-review-07-confirmed-content-view.md §5: trang xem nội dung đã
 * confirmed truyền `readOnly` - ẩn Sửa/Xoá/Thêm đoạn thủ công cho mọi
 * variant, giữ nguyên "Xem bản gốc AI" và link nguồn tham chiếu (chỉ đọc).
 */
describe('SegmentList readOnly', () => {
  afterEach(() => cleanup())

  it('ocr: hides Sửa/Xoá/"+ Thêm đoạn thủ công", keeps "Xem bản gốc AI"', () => {
    renderList('ocr', [segment()], true)
    expect(screen.queryByText('Sửa')).not.toBeInTheDocument()
    expect(screen.queryByText('Xoá')).not.toBeInTheDocument()
    expect(screen.queryByText('+ Thêm đoạn thủ công')).not.toBeInTheDocument()
    expect(screen.getByText('Xem bản gốc AI')).toBeInTheDocument()
  })

  it('stt: hides Sửa/Xoá/"+ Thêm đoạn thủ công"', () => {
    renderList('stt', [segment({ timeStartMs: 1000, timeEndMs: 2000 })], true)
    expect(screen.queryByText('Sửa')).not.toBeInTheDocument()
    expect(screen.queryByText('Xoá')).not.toBeInTheDocument()
    expect(screen.queryByText('+ Thêm đoạn thủ công')).not.toBeInTheDocument()
  })

  it('video_summary: still shows source-reference links (chỉ đọc, không phải hành động sửa)', () => {
    renderList(
      'video_summary',
      [
        segment({
          sourceReferences: [
            { sourceSegmentId: 'src-1', sourceRunId: 'run-src', segmentIndex: 3, timeStartMs: 12000, sourceRunReviewState: 'confirmed' },
          ],
        }),
      ],
      true,
    )
    expect(screen.queryByText('Sửa')).not.toBeInTheDocument()
    expect(screen.getByText(/Đoạn #3/)).toBeInTheDocument()
  })
})

/**
 * Bug fix: msToClock truncates to whole seconds, so comparing
 * clockToMs(editStart/End) against the raw (often sub-second-precise)
 * segment.timeStartMs/timeEndMs made a text-only edit look like it also
 * changed the time bounds whenever the stored value wasn't already a
 * multiple of 1000ms - sending timeStartMs/timeEndMs to the server and
 * tripping its "durationMs unknown" 422 even though the user never touched
 * the time fields.
 */
describe('SegmentList stt time-field change detection (AC-6)', () => {
  afterEach(() => {
    cleanup()
    mockedApiJson.mockReset()
  })

  function patchBody() {
    const call = mockedApiJson.mock.calls.at(-1)!
    return JSON.parse((call[2] as RequestInit).body as string)
  }

  it('omits timeStartMs/timeEndMs on a text-only edit when the stored value is not a whole second', async () => {
    mockedApiJson.mockResolvedValue(segment({ timeStartMs: 0, timeEndMs: 28170 }))
    renderList('stt', [segment({ timeStartMs: 0, timeEndMs: 28170 })])

    fireEvent.click(screen.getByText('Sửa'))
    fireEvent.change(screen.getByDisplayValue('current text'), { target: { value: 'sua loi chinh ta' } })
    fireEvent.click(screen.getByText('Lưu'))

    await waitFor(() => expect(mockedApiJson).toHaveBeenCalled())
    const body = patchBody()
    expect(body).not.toHaveProperty('timeStartMs')
    expect(body).not.toHaveProperty('timeEndMs')
    expect(body.text).toBe('sua loi chinh ta')
  })

  it('still includes timeEndMs when the user actually edits the end time', async () => {
    mockedApiJson.mockResolvedValue(segment({ timeStartMs: 0, timeEndMs: 30000 }))
    renderList('stt', [segment({ timeStartMs: 0, timeEndMs: 28170 })])

    fireEvent.click(screen.getByText('Sửa'))
    const [, endInput] = screen.getAllByPlaceholderText('mm:ss')
    fireEvent.change(endInput, { target: { value: '0:30' } })
    fireEvent.click(screen.getByText('Lưu'))

    await waitFor(() => expect(mockedApiJson).toHaveBeenCalled())
    const body = patchBody()
    expect(body.timeEndMs).toBe(30000)
  })
})

/**
 * digitization-review-05-frontend.md §4.3.1 (Task 10): đoạn được chọn (click
 * bbox, đoạn đang phát) tự cuộn vào vùng nhìn thấy; không cuộn khi đang sửa.
 */
describe('SegmentList auto-scroll to selected segment', () => {
  const scrollIntoView = vi.fn()
  const segments = [segment({ segmentId: 'seg-1' }), segment({ segmentId: 'seg-2', segmentIndex: 1 })]

  function listWith(selectedSegmentId: string | null) {
    return (
      <ConfirmProvider>
        <SegmentList
          session={null}
          runId="run-1"
          variant="ocr"
          segments={segments}
          selectedSegmentId={selectedSegmentId}
          onSelectSegment={vi.fn()}
          sourceDurationMs={60000}
          onSegmentUpdated={vi.fn()}
          onSegmentDeleted={vi.fn()}
          onSegmentAdded={vi.fn()}
        />
      </ConfirmProvider>
    )
  }

  beforeEach(() => {
    scrollIntoView.mockReset()
    // jsdom không cài scrollIntoView
    Element.prototype.scrollIntoView = scrollIntoView
  })
  afterEach(() => cleanup())

  it('scrolls the newly selected row into view', () => {
    const { rerender } = render(listWith(null))
    expect(scrollIntoView).not.toHaveBeenCalled()
    rerender(listWith('seg-2'))
    expect(scrollIntoView).toHaveBeenCalledTimes(1)
    expect(scrollIntoView.mock.contexts[0]).toBe(document.getElementById('segment-row-seg-2'))
    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest' })
  })

  it('does not scroll while a segment is being edited', () => {
    const { rerender } = render(listWith('seg-1'))
    scrollIntoView.mockReset()
    fireEvent.click(screen.getAllByText('Sửa')[0])
    rerender(listWith('seg-2'))
    expect(scrollIntoView).not.toHaveBeenCalled()
  })
})

/**
 * digitization-review-04-sed-summary.md §4 bước 5 (chỉnh 2026-09-23): màn xem
 * đã xác nhận truyền `sourceRunHref` - `null` → chữ không bấm được.
 */
describe('SegmentList sourceRunHref', () => {
  afterEach(() => cleanup())

  it('renders plain text (no link) when sourceRunHref returns null', () => {
    render(
      <ConfirmProvider>
        <SegmentList
          session={null}
          runId="run-1"
          variant="video_summary"
          segments={[
            segment({
              sourceReferences: [
                { sourceSegmentId: 'src-1', sourceRunId: 'run-src', segmentIndex: 3, timeStartMs: null, sourceRunReviewState: 'superseded' },
              ],
            }),
          ]}
          selectedSegmentId={null}
          onSelectSegment={vi.fn()}
          sourceDurationMs={null}
          onSegmentUpdated={vi.fn()}
          onSegmentDeleted={vi.fn()}
          onSegmentAdded={vi.fn()}
          readOnly
          sourceRunHref={() => null}
        />
      </ConfirmProvider>,
    )
    expect(screen.getByText(/Đoạn #3/).closest('a')).toBeNull()
  })
})
