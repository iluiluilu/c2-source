import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { useLeaveGuard } from '../useLeaveGuard'

function Page({ active }: { active: boolean }) {
  useLeaveGuard(active)
  return (
    <>
      {/* eslint-disable-next-line @next/next/no-html-link-for-pages -- a plain anchor is what <Link> renders */}
      <a href="/documents">Kho tài liệu</a>
      <a href="/files/x" download>
        Tải xuống
      </a>
    </>
  )
}

/**
 * Returns whether the guard cancelled the click. A bubble-phase listener (after the guard's capture listener) records
 * that, then cancels the default itself so jsdom never attempts a real navigation.
 */
function clickLink(name: string): boolean {
  let cancelledByGuard = true
  const record = (e: Event) => {
    cancelledByGuard = e.defaultPrevented
    e.preventDefault()
  }
  document.addEventListener('click', record)
  screen.getByText(name).dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }))
  document.removeEventListener('click', record)
  return cancelledByGuard
}

describe('useLeaveGuard (feedbacks D-11)', () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
  })

  it('asks before following an in-app link while active and blocks it when the user cancels', () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    render(<Page active />)

    expect(clickLink('Kho tài liệu')).toBe(true)
    expect(confirmSpy).toHaveBeenCalledTimes(1)
  })

  it('lets the navigation through when the user confirms', () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(<Page active />)

    expect(clickLink('Kho tài liệu')).toBe(false)
  })

  it('never asks for download links or when no upload is running', () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    render(<Page active />)
    clickLink('Tải xuống')
    cleanup()

    render(<Page active={false} />)
    expect(clickLink('Kho tài liệu')).toBe(false)
    expect(confirmSpy).not.toHaveBeenCalled()
  })

  it('arms beforeunload only while active', () => {
    const { rerender } = render(<Page active />)
    const armed = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(armed)
    expect(armed.defaultPrevented).toBe(true)

    rerender(<Page active={false} />)
    const disarmed = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(disarmed)
    expect(disarmed.defaultPrevented).toBe(false)
  })
})
