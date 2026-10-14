import { describe, expect, it } from 'vitest'
import { buildQueueQuery } from '@/app/digitization/page'

/**
 * digitization-review-05-frontend.md Story 1 AC-2 (Task 9): tab "Tất cả"
 * không gửi `task` trong query string - API tự trả mọi loại khi bỏ trống.
 */
describe('buildQueueQuery', () => {
  it('omits task entirely for the "all" tab', () => {
    const params = buildQueueQuery('all', new Set(), false, 0, 20)
    expect(params.has('task')).toBe(false)
    expect(params.toString()).toBe('page=0&size=20')
  })

  it('sends exactly 1 task value for a specific tab', () => {
    const params = buildQueueQuery('ocr', new Set(), false, 0, 20)
    expect(params.get('task')).toBe('ocr')
  })

  it('joins multi-select status filters with a comma and adds qualityFlag=poor when checked', () => {
    const params = buildQueueQuery('stt', new Set(['stale', 'upcoming_deadline']), true, 2, 50)
    expect(params.get('status')).toBe('stale,upcoming_deadline')
    expect(params.get('qualityFlag')).toBe('poor')
    expect(params.get('page')).toBe('2')
    expect(params.get('size')).toBe('50')
  })
})
