import { afterEach, describe, expect, it, vi } from 'vitest'
import { TOAST_MS, TOAST_WITH_ACTION_MS, dismissToast, getToasts, toast } from '@/lib/toast'

describe('toast store', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('auto-dismisses after TOAST_MS, longer when it carries an action', () => {
    vi.useFakeTimers()
    toast.success('plain')
    toast.success('with link', { label: 'Xem', href: '/trash' })
    expect(getToasts().map((t) => t.message)).toEqual(['plain', 'with link'])

    vi.advanceTimersByTime(TOAST_MS)
    expect(getToasts().map((t) => t.message)).toEqual(['with link'])

    vi.advanceTimersByTime(TOAST_WITH_ACTION_MS - TOAST_MS)
    expect(getToasts()).toEqual([])
  })

  it('keeps only the newest 3 and can be dismissed by id', () => {
    const ids = [1, 2, 3, 4].map((n) => toast.info(`m${n}`))
    expect(getToasts().map((t) => t.message)).toEqual(['m2', 'm3', 'm4'])
    dismissToast(ids[3])
    expect(getToasts().map((t) => t.message)).toEqual(['m2', 'm3'])
  })
})
