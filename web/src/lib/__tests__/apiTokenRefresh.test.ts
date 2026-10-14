import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Session } from 'next-auth'

vi.mock('next-auth/react', () => ({ getSession: vi.fn(async () => null) }))

import { apiFetch, apiUploadJson, setSessionRefresher } from '@/lib/api'
import { sessionExpiredBus } from '@/lib/sessionExpiredBus'

/** 401 from Spring Security's bearer filter: no JSON body. */
function rejected(): Response {
  return {
    ok: false,
    status: 401,
    statusText: '',
    clone() {
      return this
    },
    json: async () => {
      throw new SyntaxError('empty')
    },
  } as unknown as Response
}

function ok(): Response {
  return { ok: true, status: 200, statusText: '', json: async () => ({ v: 1 }) } as unknown as Response
}

function businessUnauthorized(): Response {
  const body = { message: 'Sai mật khẩu' }
  return {
    ok: false,
    status: 401,
    statusText: '',
    clone() {
      return this
    },
    json: async () => body,
  } as unknown as Response
}

const session = (accessToken: string, error?: string) =>
  ({ accessToken, error, expires: '' }) as unknown as Session

function authHeader(call: unknown[]): string {
  return ((call[1] as RequestInit).headers as Record<string, string>).Authorization
}

describe('api.ts: refresh the access token on 401 and retry', () => {
  const expired = vi.fn()
  let unsub: () => void

  beforeEach(() => {
    expired.mockClear()
    unsub = sessionExpiredBus.subscribe(expired)
  })
  afterEach(() => {
    unsub()
    setSessionRefresher(null)
    vi.unstubAllGlobals()
  })

  it('refreshes once and retries with the new token', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(rejected()).mockResolvedValueOnce(ok())
    vi.stubGlobal('fetch', fetchMock)
    const refresher = vi.fn(async () => session('new-1'))
    setSessionRefresher(refresher)

    const res = await apiFetch('/x', session('old-1'))

    expect(res.status).toBe(200)
    expect(refresher).toHaveBeenCalledTimes(1)
    expect(authHeader(fetchMock.mock.calls[0])).toBe('Bearer old-1')
    expect(authHeader(fetchMock.mock.calls[1])).toBe('Bearer new-1')
    expect(expired).not.toHaveBeenCalled()
  })

  it('later calls holding the dead token use the refreshed one directly', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(rejected())
      .mockResolvedValue(ok())
    vi.stubGlobal('fetch', fetchMock)
    const refresher = vi.fn(async () => session('new-2'))
    setSessionRefresher(refresher)

    await apiFetch('/x', session('old-2'))
    await apiFetch('/y', session('old-2'))

    expect(refresher).toHaveBeenCalledTimes(1)
    expect(authHeader(fetchMock.mock.calls[2])).toBe('Bearer new-2')
  })

  it('concurrent 401s share a single refresh', async () => {
    const fetchMock = vi.fn(async (_url: string, init: RequestInit) =>
      (init.headers as Record<string, string>).Authorization === 'Bearer old-3' ? rejected() : ok(),
    )
    vi.stubGlobal('fetch', fetchMock)
    const refresher = vi.fn(async () => session('new-3'))
    setSessionRefresher(refresher)

    await Promise.all([apiFetch('/a', session('old-3')), apiFetch('/b', session('old-3'))])

    expect(refresher).toHaveBeenCalledTimes(1)
  })

  it('refresh failure raises the session-expired popup and throws 401', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => rejected()))
    setSessionRefresher(async () => session('', 'RefreshAccessTokenError'))

    await expect(apiFetch('/x', session('old-4'))).rejects.toMatchObject({ status: 401 })
    expect(expired).toHaveBeenCalledTimes(1)
  })

  it('retry still rejected raises the popup', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => rejected()))
    setSessionRefresher(async () => session('new-5'))

    await expect(apiFetch('/x', session('old-5'))).rejects.toMatchObject({ status: 401 })
    expect(expired).toHaveBeenCalledTimes(1)
  })

  it('a business 401 with a message body is not treated as an expired token', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => businessUnauthorized()))
    const refresher = vi.fn(async () => session('new-6'))
    setSessionRefresher(refresher)

    await expect(apiFetch('/x', session('old-6'))).rejects.toMatchObject({
      status: 401,
      message: 'Sai mật khẩu',
    })
    expect(refresher).not.toHaveBeenCalled()
    expect(expired).not.toHaveBeenCalled()
  })

  it('noRefresh (background poll) returns the 401 without refreshing', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => rejected()))
    const refresher = vi.fn(async () => session('new-8'))
    setSessionRefresher(refresher)

    await expect(apiFetch('/x', session('old-8'), { noRefresh: true })).rejects.toMatchObject({ status: 401 })
    expect(refresher).not.toHaveBeenCalled()
    expect(expired).not.toHaveBeenCalled()
  })

  it('apiUploadJson refreshes and re-sends the form', async () => {
    const tokens: string[] = []
    class FakeXhr {
      status = 0
      statusText = ''
      responseText = ''
      upload: { onprogress: unknown } = { onprogress: null }
      onload: (() => void) | null = null
      onerror: (() => void) | null = null
      private token = ''
      open() {}
      setRequestHeader(_k: string, v: string) {
        this.token = v
      }
      send() {
        tokens.push(this.token)
        this.status = this.token === 'Bearer old-7' ? 401 : 200
        this.responseText = this.status === 200 ? '{"ok":true}' : ''
        this.onload?.()
      }
    }
    vi.stubGlobal('XMLHttpRequest', FakeXhr)
    setSessionRefresher(async () => session('new-7'))

    await expect(apiUploadJson('/u', session('old-7'), new FormData())).resolves.toEqual({ ok: true })
    expect(tokens).toEqual(['Bearer old-7', 'Bearer new-7'])
  })
})
