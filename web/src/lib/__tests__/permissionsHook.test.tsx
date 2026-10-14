import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useSession } from 'next-auth/react'
import { forbiddenBus } from '@/lib/forbiddenBus'
import { REFETCH_DEDUPE_MS, REFETCH_INTERVAL_MS, resetPermissionsStore, usePermissions } from '@/lib/permissions'
import { canBoSession } from '@/test-utils/session'

vi.mock('next-auth/react', () => ({ useSession: vi.fn() }))

function Probe({ id }: { id: string }) {
  const { can, status } = usePermissions()
  return <span data-testid={id}>{`${status}:${can('trash.manage')}`}</span>
}

const flush = () => act(async () => { await new Promise((r) => setTimeout(r, 0)) })

describe('usePermissions', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    resetPermissionsStore()
    vi.mocked(useSession).mockReturnValue({
      data: canBoSession(),
      status: 'authenticated',
      update: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)
    fetchMock = vi.fn(async () => ({
      ok: true,
      status: 200,
      json: async () => ({ roles: ['lanh-dao'], permissions: ['trash.manage'] }),
    }))
    global.fetch = fetchMock as unknown as typeof fetch
  })

  afterEach(() => {
    cleanup()
    vi.useRealTimers()
    resetPermissionsStore()
  })

  it('many consumers share a single fetch (AC-7)', async () => {
    render(
      <>
        <Probe id="a" />
        <Probe id="b" />
        <Probe id="c" />
      </>,
    )
    await flush()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(screen.getByTestId('a').textContent).toBe('ready:true')
    expect(screen.getByTestId('c').textContent).toBe('ready:true')
  })

  it('refetches on a 403 signal from the bus, once even if emitted twice', async () => {
    render(<Probe id="a" />)
    await flush()
    fetchMock.mockClear()
    vi.useFakeTimers({ shouldAdvanceTime: true })
    vi.setSystemTime(Date.now() + REFETCH_DEDUPE_MS * 2)
    act(() => {
      forbiddenBus.emit()
      forbiddenBus.emit()
    })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  async function mountReady() {
    // fake timers first so the hook's setInterval is created under them
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const view = render(<Probe id="a" />)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    fetchMock.mockClear()
    return view
  }

  const pastDedupe = () => vi.setSystemTime(Date.now() + REFETCH_DEDUPE_MS * 2)

  it('refetches on window focus (AC-7 triggers)', async () => {
    await mountReady()
    pastDedupe()
    act(() => {
      window.dispatchEvent(new Event('focus'))
    })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('refetches on visibilitychange to visible, not when hidden', async () => {
    await mountReady()
    pastDedupe()
    const set = (v: string) =>
      Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => v })
    set('hidden')
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    expect(fetchMock).not.toHaveBeenCalled()
    set('visible')
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('refetches on the 4-minute interval', async () => {
    await mountReady()
    await act(async () => {
      await vi.advanceTimersByTimeAsync(REFETCH_INTERVAL_MS)
    })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('removes listeners and interval on unmount', async () => {
    const view = await mountReady()
    view.unmount()
    pastDedupe()
    window.dispatchEvent(new Event('focus'))
    forbiddenBus.emit()
    await vi.advanceTimersByTimeAsync(REFETCH_INTERVAL_MS * 2)
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
