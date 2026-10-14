import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getSession } from 'next-auth/react'
import { ApiError, apiUploadJson } from '@/lib/api'
import { uploadOneChunked, uploadOneWhole } from '@/app/upload/page'

/**
 * folder-upload-display-name FE-3 (AC-11/AC-14/AC-15): the 409 DISPLAY_NAME_CONFLICT handling of the
 * ingest upload paths. Whole-file: resend with a fresh suffix (max 3). Chunked: only `complete` is
 * repeated, no chunk is sent again.
 */
vi.mock('next-auth/react', () => ({ getSession: vi.fn(), useSession: vi.fn() }))
vi.mock('@/lib/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api')>()),
  apiUploadJson: vi.fn(),
}))

const SUFFIXED = /^doc_\d{17}-[a-z0-9]{6}\.pdf$/

function item(name = 'doc.pdf', size = 8) {
  return {
    file: new File([new Uint8Array(size)], name, { type: 'application/pdf' }),
    relativePath: 'Hồ sơ/2026',
    displayName: name,
    outcome: { phase: 'queued' as const },
  }
}

const conflict = () => new ApiError(409, 'trùng', { code: 'DISPLAY_NAME_CONFLICT' })
const ingested = (displayName: string) => ({
  status: 'INGESTED',
  document: { id: 'd1', displayName },
  duplicateOf: [],
})

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(getSession).mockResolvedValue({ accessToken: 't' } as never)
})

describe('uploadOneWhole', () => {
  it('sends displayName + relativePath in the multipart form', async () => {
    vi.mocked(apiUploadJson).mockResolvedValue(ingested('doc.pdf'))
    const update = vi.fn()
    await uploadOneWhole('b1', item(), 0, '', null, update)

    const form = vi.mocked(apiUploadJson).mock.calls[0][2] as FormData
    expect(form.get('displayName')).toBe('doc.pdf')
    expect(form.get('relativePath')).toBe('Hồ sơ/2026')
    expect(update).toHaveBeenLastCalledWith(0, expect.objectContaining({ phase: 'ingested' }))
  })

  it('on 409 resends the whole file with a new suffix name, then succeeds', async () => {
    vi.mocked(apiUploadJson)
      .mockRejectedValueOnce(conflict())
      .mockRejectedValueOnce(conflict())
      .mockResolvedValueOnce(ingested('x'))
    const update = vi.fn()
    await uploadOneWhole('b1', item(), 0, '', null, update)

    const names = vi.mocked(apiUploadJson).mock.calls.map((c) => (c[2] as FormData).get('displayName'))
    expect(names).toHaveLength(3)
    expect(names[0]).toBe('doc.pdf')
    expect(names[1]).toMatch(SUFFIXED)
    expect(names[2]).toMatch(SUFFIXED)
    expect(names[1]).not.toBe(names[2])
    expect(update).toHaveBeenLastCalledWith(0, expect.objectContaining({ phase: 'ingested' }))
  })

  it('gives up with an error row after 3 renames (4 attempts in total)', async () => {
    vi.mocked(apiUploadJson).mockRejectedValue(conflict())
    const update = vi.fn()
    await uploadOneWhole('b1', item(), 0, '', null, update)

    expect(apiUploadJson).toHaveBeenCalledTimes(4)
    expect(update).toHaveBeenLastCalledWith(0, expect.objectContaining({ phase: 'error' }))
  })

  it('a 422 is not retried', async () => {
    vi.mocked(apiUploadJson).mockRejectedValue(new ApiError(422, 'bad', { code: 'INVALID_DISPLAY_NAME' }))
    const update = vi.fn()
    await uploadOneWhole('b1', item(), 0, '', null, update)
    expect(apiUploadJson).toHaveBeenCalledTimes(1)
    expect(update).toHaveBeenLastCalledWith(0, expect.objectContaining({ phase: 'error' }))
  })
})

describe('uploadOneChunked - 409 at complete', () => {
  function fakeServer(conflictsBeforeSuccess: number) {
    const calls: { url: string; method: string; body?: string }[] = []
    let conflicts = 0
    const fetchMock = vi.fn(async (url: string, init: RequestInit = {}) => {
      const method = init.method ?? 'GET'
      calls.push({ url, method, body: typeof init.body === 'string' ? init.body : undefined })
      const json = (status: number, body: unknown) =>
        ({ ok: status < 300, status, statusText: '', json: async () => body }) as unknown as Response
      if (method === 'POST' && url.includes('/upload-sessions') && url.includes('/ingest/batches/')) {
        return json(201, { sessionId: 's1', chunkSizeBytes: 8, receivedBytes: 0, expiresAt: 'x' })
      }
      if (method === 'PUT') return json(200, { sessionId: 's1', receivedBytes: 8, declaredSizeBytes: 8 })
      if (method === 'GET') {
        return json(200, { sessionId: 's1', status: 'OPEN', receivedBytes: 8, declaredSizeBytes: 8, chunkSizeBytes: 8, expiresAt: 'x' })
      }
      if (method === 'POST' && url.includes('/complete')) {
        if (conflicts < conflictsBeforeSuccess) {
          conflicts++
          return json(409, { code: 'DISPLAY_NAME_CONFLICT', message: 'trùng tên' })
        }
        return json(201, ingested('final'))
      }
      throw new Error(`unexpected ${method} ${url}`)
    })
    return { fetchMock, calls }
  }

  it('init carries displayName + relativePath', async () => {
    const { fetchMock, calls } = fakeServer(0)
    vi.stubGlobal('fetch', fetchMock)
    await uploadOneChunked('b1', item(), 0, '', vi.fn())
    const init = calls.find((c) => c.url.includes('/upload-sessions') && c.method === 'POST' && !c.url.includes('/complete'))
    const body = JSON.parse(init!.body!)
    expect(body.displayName).toBe('doc.pdf')
    expect(body.relativePath).toBe('Hồ sơ/2026')
  })

  it('repeats only complete (with a fresh suffix) and never re-sends a chunk', async () => {
    const { fetchMock, calls } = fakeServer(2)
    vi.stubGlobal('fetch', fetchMock)
    const update = vi.fn()
    await uploadOneChunked('b1', item(), 0, '', update)

    const completes = calls.filter((c) => c.url.includes('/complete'))
    expect(completes).toHaveLength(3)
    expect(completes[0].body).toBeUndefined()
    expect(JSON.parse(completes[1].body!).displayName).toMatch(SUFFIXED)
    expect(JSON.parse(completes[2].body!).displayName).toMatch(SUFFIXED)
    expect(calls.filter((c) => c.method === 'PUT')).toHaveLength(1) // chunk sent exactly once
    expect(calls.filter((c) => c.method === 'POST' && !c.url.includes('/complete'))).toHaveLength(1) // 1 init
    expect(update).toHaveBeenLastCalledWith(0, expect.objectContaining({ phase: 'ingested' }))
  })

  it('reports an error after 3 renames were all rejected', async () => {
    const { fetchMock, calls } = fakeServer(99)
    vi.stubGlobal('fetch', fetchMock)
    const update = vi.fn()
    await uploadOneChunked('b1', item(), 0, '', update)
    expect(calls.filter((c) => c.url.includes('/complete'))).toHaveLength(4)
    expect(update).toHaveBeenLastCalledWith(0, expect.objectContaining({ phase: 'error' }))
  })
})
