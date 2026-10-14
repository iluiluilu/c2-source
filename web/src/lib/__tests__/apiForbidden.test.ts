import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch, apiUploadJson } from '@/lib/api'
import { forbiddenBus } from '@/lib/forbiddenBus'

function res(status: number): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: '',
    json: async () => ({ message: 'x' }),
  } as unknown as Response
}

describe('api.ts -> forbiddenBus (AC-4)', () => {
  const listener = vi.fn()
  let unsub: () => void

  beforeEach(() => {
    listener.mockClear()
    unsub = forbiddenBus.subscribe(listener)
  })
  afterEach(() => {
    unsub()
    vi.unstubAllGlobals()
  })

  it('apiFetch emits on 403', async () => {
    global.fetch = vi.fn(async () => res(403)) as unknown as typeof fetch
    await expect(apiFetch('/x', null)).rejects.toMatchObject({ status: 403 })
    expect(listener).toHaveBeenCalledTimes(1)
  })

  it.each([200, 401, 500])('apiFetch does not emit on %i', async (status) => {
    global.fetch = vi.fn(async () => res(status)) as unknown as typeof fetch
    await apiFetch('/x', null).catch(() => undefined)
    expect(listener).not.toHaveBeenCalled()
  })

  function stubXhr(status: number) {
    class FakeXhr {
      status = status
      statusText = ''
      responseText = '{"message":"x"}'
      upload: { onprogress: unknown } = { onprogress: null }
      onload: (() => void) | null = null
      onerror: (() => void) | null = null
      open() {}
      setRequestHeader() {}
      send() {
        this.onload?.()
      }
    }
    vi.stubGlobal('XMLHttpRequest', FakeXhr)
  }

  it('apiUploadJson emits on 403', async () => {
    stubXhr(403)
    await expect(apiUploadJson('/u', null, new FormData())).rejects.toMatchObject({ status: 403 })
    expect(listener).toHaveBeenCalledTimes(1)
  })

  it.each([401, 500])('apiUploadJson does not emit on %i', async (status) => {
    stubXhr(status)
    await apiUploadJson('/u', null, new FormData()).catch(() => undefined)
    expect(listener).not.toHaveBeenCalled()
  })

  it('apiUploadJson does not emit on 200', async () => {
    stubXhr(200)
    await apiUploadJson('/u', null, new FormData())
    expect(listener).not.toHaveBeenCalled()
  })
})
