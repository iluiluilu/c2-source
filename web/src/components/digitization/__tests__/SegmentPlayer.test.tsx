import { act, cleanup, fireEvent, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReviewSegment } from '@/lib/api'
import { SegmentPlayer } from '@/components/digitization/SegmentPlayer'

vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>()
  return {
    ...actual,
    apiJson: vi.fn().mockResolvedValue({ mimeType: 'audio/mpeg' }),
    apiFetch: vi.fn().mockResolvedValue({ blob: async () => new Blob(['fake-audio']) }),
  }
})

function segment(id: string, index: number, startMs: number, endMs: number): ReviewSegment {
  return {
    segmentId: id,
    segmentIndex: index,
    origin: 'ai',
    aiText: `text ${index}`,
    currentText: `text ${index}`,
    currentRevisionNo: 0,
    isEdited: false,
    isDeleted: false,
    confidence: 0.9,
    pageNo: null,
    bboxPolygon: null,
    readingOrder: null,
    timeStartMs: startMs,
    timeEndMs: endMs,
    overlapsAdjacent: false,
    soundEventLabel: null,
    sourceReferences: null,
  }
}

/**
 * digitization-review-05-frontend.md §5 (Task 9): player tự dừng đúng
 * timeEndMs của đoạn đang chọn (AC-2 Story 3), không phát tràn sang đoạn kế
 * tiếp; và đồng bộ highlight khi phát tự do (AC-3).
 */
describe('SegmentPlayer', () => {
  beforeEach(() => {
    // jsdom does not implement real media playback.
    window.HTMLMediaElement.prototype.play = vi.fn().mockResolvedValue(undefined)
    window.HTMLMediaElement.prototype.pause = vi.fn()
    window.URL.createObjectURL = vi.fn(() => 'blob:mock-url')
    window.URL.revokeObjectURL = vi.fn()
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('auto-pauses once currentTime reaches the seeked-to segment timeEndMs', async () => {
    const segments = [segment('s1', 0, 1000, 3000), segment('s2', 1, 3000, 5000)]
    const onActiveSegmentChange = vi.fn()

    const { container, rerender } = render(
      <SegmentPlayer
        session={null}
        documentId="doc-1"
        segments={segments}
        seekRequest={null}
        onActiveSegmentChange={onActiveSegmentChange}
      />,
    )

    // Let the mocked apiJson/apiFetch promises resolve so <audio> mounts.
    await act(async () => {
      await Promise.resolve()
      await Promise.resolve()
    })

    const audio = container.querySelector('audio') as HTMLAudioElement
    expect(audio).toBeTruthy()

    // Click on s1 -> seeks to 1000ms and plays.
    rerender(
      <SegmentPlayer
        session={null}
        documentId="doc-1"
        segments={segments}
        seekRequest={{ segmentId: 's1', nonce: 1 }}
        onActiveSegmentChange={onActiveSegmentChange}
      />,
    )
    expect(audio.currentTime).toBeCloseTo(1)
    expect(window.HTMLMediaElement.prototype.play).toHaveBeenCalled()

    // Still inside [1000, 3000) -> no pause, highlights s1.
    Object.defineProperty(audio, 'currentTime', { value: 2, configurable: true })
    fireEvent.timeUpdate(audio)
    expect(window.HTMLMediaElement.prototype.pause).not.toHaveBeenCalled()
    expect(onActiveSegmentChange).toHaveBeenCalledWith('s1')

    // Reaches timeEndMs (3000ms = 3s) -> auto-pause (AC-2), does not bleed into s2.
    Object.defineProperty(audio, 'currentTime', { value: 3, configurable: true })
    fireEvent.timeUpdate(audio)
    expect(window.HTMLMediaElement.prototype.pause).toHaveBeenCalledTimes(1);
  })

  it('highlights the segment matching the free-playback position without an explicit click (AC-3)', async () => {
    const segments = [segment('s1', 0, 0, 1000), segment('s2', 1, 1000, 2000)]
    const onActiveSegmentChange = vi.fn()

    const { container } = render(
      <SegmentPlayer
        session={null}
        documentId="doc-1"
        segments={segments}
        seekRequest={null}
        onActiveSegmentChange={onActiveSegmentChange}
      />,
    )
    await act(async () => {
      await Promise.resolve()
      await Promise.resolve()
    })
    const audio = container.querySelector('audio') as HTMLAudioElement

    Object.defineProperty(audio, 'currentTime', { value: 1.5, configurable: true })
    fireEvent.timeUpdate(audio)
    expect(onActiveSegmentChange).toHaveBeenCalledWith('s2')
  })

  it('applies a seek requested before the media element mounts, without playing when play=false (deep-link)', async () => {
    const segments = [segment('s1', 0, 1000, 3000), segment('s2', 1, 4000, 6000)]

    // seekRequest present on first render - <audio> not mounted yet (blob still loading).
    const { container } = render(
      <SegmentPlayer
        session={null}
        documentId="doc-1"
        segments={segments}
        seekRequest={{ segmentId: 's2', nonce: 1, play: false }}
        onActiveSegmentChange={vi.fn()}
      />,
    )
    expect(container.querySelector('audio')).toBeNull()

    await act(async () => {
      await Promise.resolve()
      await Promise.resolve()
    })

    const audio = container.querySelector('audio') as HTMLAudioElement
    expect(audio).toBeTruthy()
    expect(audio.currentTime).toBeCloseTo(4)
    expect(window.HTMLMediaElement.prototype.play).not.toHaveBeenCalled()
  })
})
