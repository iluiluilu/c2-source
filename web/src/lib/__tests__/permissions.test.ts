import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { forbiddenBus } from '@/lib/forbiddenBus'
import {
  REFETCH_DEDUPE_MS,
  canWith,
  getPermissionsState,
  refetchPermissions,
  resetPermissionsStore,
  setPermissionsToken,
} from '@/lib/permissions'

function jsonResponse(body: unknown, ok = true): Response {
  return { ok, status: ok ? 200 : 500, json: async () => body } as unknown as Response
}

const flush = () => new Promise((r) => setTimeout(r, 0))

describe('permissions store', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    resetPermissionsStore()
    fetchMock = vi.fn(async () => jsonResponse({ roles: ['lanh-dao'], permissions: ['trash.manage'] }))
    global.fetch = fetchMock as unknown as typeof fetch
  })

  afterEach(() => {
    vi.useRealTimers()
    resetPermissionsStore()
  })

  it('is fail-closed while loading', () => {
    expect(getPermissionsState().status).toBe('loading')
    expect(canWith(getPermissionsState(), 'trash.manage')).toBe(false)
  })

  it('fetches once on login and then exposes the permissions (AC-1)', async () => {
    setPermissionsToken('tok')
    await flush()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(getPermissionsState().status).toBe('ready')
    expect(canWith(getPermissionsState(), 'trash.manage')).toBe(true)
    expect(canWith(getPermissionsState(), 'document.upload')).toBe(false)
  })

  it('first-fetch failure => status error and can() stays false (AC-4)', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({}, false))
    setPermissionsToken('tok')
    await flush()
    expect(getPermissionsState().status).toBe('error')
    expect(canWith(getPermissionsState(), 'trash.manage')).toBe(false)
  })

  it('keeps the previous permissions during a background refetch (AC-8)', async () => {
    setPermissionsToken('tok')
    await flush()
    let resolve!: (r: Response) => void
    fetchMock.mockImplementationOnce(() => new Promise<Response>((r) => (resolve = r)))
    refetchPermissions(true)
    expect(getPermissionsState().status).toBe('ready')
    expect(canWith(getPermissionsState(), 'trash.manage')).toBe(true)
    resolve(jsonResponse({ roles: [], permissions: [] }))
    await flush()
    expect(canWith(getPermissionsState(), 'trash.manage')).toBe(false)
  })

  it('two refetch triggers back to back call fetch only once (AC-7)', async () => {
    setPermissionsToken('tok')
    await flush()
    fetchMock.mockClear()
    vi.useFakeTimers()
    vi.setSystemTime(Date.now() + REFETCH_DEDUPE_MS * 2)
    refetchPermissions()
    refetchPermissions()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('does not refetch inside the dedupe window, but force bypasses it', async () => {
    setPermissionsToken('tok')
    await flush()
    fetchMock.mockClear()
    refetchPermissions()
    expect(fetchMock).not.toHaveBeenCalled()
    refetchPermissions(true)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('logout resets to loading/empty', async () => {
    setPermissionsToken('tok')
    await flush()
    setPermissionsToken(null)
    expect(getPermissionsState()).toEqual({ status: 'loading', roles: [], permissions: [] })
  })

  it('forbiddenBus.emit() is a no-op before a token exists', () => {
    forbiddenBus.emit()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('background poll on an expired token keeps the last permissions and refetches on the new token', async () => {
    setPermissionsToken('tok')
    await flush()
    fetchMock.mockResolvedValueOnce({ ok: false, status: 401, json: async () => ({}) } as unknown as Response)
    refetchPermissions(true, true)
    await flush()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(getPermissionsState().status).toBe('ready')

    setPermissionsToken('tok-refreshed')
    await flush()
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(fetchMock.mock.calls[2][1]).toMatchObject({ headers: { Authorization: 'Bearer tok-refreshed' } })
  })
})
