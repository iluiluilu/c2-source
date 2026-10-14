import { describe, expect, it } from 'vitest'
import { boundingBoxPercent } from '@/components/digitization/OcrPageViewer'

/**
 * digitization-review-05-frontend.md §5 (Task 9): bbox overlay tính đúng vị
 * trí % từ bboxPolygon mẫu (ratio 0-1, V26) - bounding box của polygon, min/
 * max x,y, không cần vẽ polygon lồi.
 */
describe('boundingBoxPercent', () => {
  it('converts a rectangle-ish polygon (ratio 0-1) into left/top/width/height percentages', () => {
    const polygon = [
      [0.1, 0.2],
      [0.5, 0.2],
      [0.5, 0.4],
      [0.1, 0.4],
    ]

    expect(boundingBoxPercent(polygon)).toEqual({ left: 10, top: 20, width: 40, height: 20 })
  })

  it('takes the min/max across an irregular (non-axis-aligned) polygon', () => {
    const polygon = [
      [0.3, 0.05],
      [0.6, 0.1],
      [0.55, 0.5],
      [0.25, 0.45],
    ]

    const box = boundingBoxPercent(polygon)
    expect(box).not.toBeNull()
    expect(box!.left).toBeCloseTo(25)
    expect(box!.top).toBeCloseTo(5)
    expect(box!.width).toBeCloseTo(35)
    expect(box!.height).toBeCloseTo(45)
  })

  it('returns null for an empty polygon (e.g. a manually-added segment with no bbox)', () => {
    expect(boundingBoxPercent([])).toBeNull()
  })
})
