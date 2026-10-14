import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getSession } from 'next-auth/react'
import { uploadOneChunked } from '@/app/personal/page'

/** folder-upload-display-name (AC-14/AC-15) for the personal chunked upload: only `complete` is repeated on a 409. */
vi.mock('next-auth/react', () => ({ getSession: vi.fn(), useSession: vi.fn() }))

const SUFFIXED = /^doc_\d{17}-[a-z0-9]{6}\.pdf$/

function fakeServer(conflictsBeforeSuccess: number) {
  const calls: { url: string; method: string; body?: string }[] = []
  let conflicts = 0
  const json = (status: number, body: unknown) =>
    ({ ok: status < 300, status, statusText: '', json: async () => body }) as unknown as Response
  const fetchMock = vi.fn(async (url: string, init: RequestInit = {}) => {
    const method = init.method ?? 'GET'
    calls.push({ url, method, body: typeof init.body === 'string' ? init.body : undefined })
    if (method === 'POST' && url.includes('/personal/batches/') && url.includes('/upload-sessions')) {
      return json(201, { sessionId: 's1', chunkSizeBytes: 8, receivedBytes: 0, expiresAt: 'x' })
    }
    if (method === 'PUT') return json(200, { sessionId: 's1', receivedBytes: 8, declaredSizeBytes: 8 })
    if (method === 'POST' && url.includes('/complete')) {
      if (conflicts < conflictsBeforeSuccess) {
        conflicts++
        return json(409, { code: 'DISPLAY_NAME_CONFLICT', message: 'trùng tên' })
      }
      return json(201, { status: 'STORED', file: { id: 'f1', displayName: 'final' } })
    }
    throw new Error(`unexpected ${method} ${url}`)
  })
  return { fetchMock, calls }
}

const item = () => ({
  file: new File([new Uint8Array(8)], 'doc.pdf', { type: 'application/pdf' }),
  relativePath: 'Ảnh/2026',
  displayName: 'doc.pdf',
  outcome: { phase: 'queued' as const },
})

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(getSession).mockResolvedValue({ accessToken: 't' } as never)
})

describe('uploadOneChunked (personal) - display name', () => {
  it('init carries displayName + relativePath', async () => {
    const { fetchMock, calls } = fakeServer(0)
    vi.stubGlobal('fetch', fetchMock)
    await uploadOneChunked('b1', item(), 0, null, vi.fn())
    const init = calls.find((c) => c.url.includes('/upload-sessions') && c.method === 'POST' && !c.url.includes('/complete'))
    expect(JSON.parse(init!.body!)).toMatchObject({ displayName: 'doc.pdf', relativePath: 'Ảnh/2026' })
  })

  it('409 at complete: repeats only complete with a fresh suffix, chunk sent once', async () => {
    const { fetchMock, calls } = fakeServer(1)
    vi.stubGlobal('fetch', fetchMock)
    const update = vi.fn()
    await uploadOneChunked('b1', item(), 0, null, update)

    const completes = calls.filter((c) => c.url.includes('/complete'))
    expect(completes).toHaveLength(2)
    expect(JSON.parse(completes[1].body!).displayName).toMatch(SUFFIXED)
    expect(calls.filter((c) => c.method === 'PUT')).toHaveLength(1)
    expect(update).toHaveBeenLastCalledWith(0, expect.objectContaining({ phase: 'done' }))
  })

  it('errors out after 3 rejected renames', async () => {
    const { fetchMock, calls } = fakeServer(99)
    vi.stubGlobal('fetch', fetchMock)
    const update = vi.fn()
    await uploadOneChunked('b1', item(), 0, null, update)
    expect(calls.filter((c) => c.url.includes('/complete'))).toHaveLength(4)
    expect(update).toHaveBeenLastCalledWith(0, expect.objectContaining({ phase: 'error' }))
  })
})
