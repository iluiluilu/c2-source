import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getSession } from 'next-auth/react'
import { uploadOneChunked } from '@/app/personal/page'

/**
 * chunked-file-upload-02-personal-chunked-upload.md §5 (Frontend) / Task F5.
 * Reuses the Vitest + Testing Library runner added in
 * chunked-file-upload-01-ingest-chunked-upload.md's Task F5 - not
 * reinstalled here. Structured exactly like
 * `web/src/app/upload/__tests__/uploadOneChunked.test.ts`: `next-auth/react`
 * is mocked (only `getSession` matters), `@/lib/api` is used for real, and
 * the global `fetch` is stubbed with a small fake server.
 */
vi.mock('next-auth/react', () => ({
  getSession: vi.fn(),
  useSession: vi.fn(),
}))

interface RecordedCall {
  url: string
  method: string
  headers: Record<string, string>
  bodySize?: number
}

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: '',
    json: async () => body,
  } as unknown as Response
}

function offsetOf(url: string): number {
  return Number(/offset=(\d+)/.exec(url)?.[1] ?? '0')
}

/** A minimal fake `/personal/batches/{id}/upload-sessions -> chunks -> complete` server. */
function fakeUploadServer(options: {
  chunkSizeBytes: number
  declaredSizeBytes: number
  /** Called for every PUT chunk request; throwing simulates a transient network failure. */
  onChunk?: (call: RecordedCall) => void
  /** Called for the complete() request; throwing simulates e.g. QUOTA_EXCEEDED. */
  onComplete?: () => void
}) {
  const { chunkSizeBytes, declaredSizeBytes, onChunk, onComplete } = options
  const calls: RecordedCall[] = []
  let confirmedReceivedBytes = 0

  const fetchMock = vi.fn(async (url: string, init: RequestInit = {}) => {
    const method = init.method ?? 'GET'
    const headers = (init.headers ?? {}) as Record<string, string>
    const bodySize = init.body instanceof Blob ? init.body.size : undefined
    const call: RecordedCall = { url, method, headers, bodySize }
    calls.push(call)

    if (method === 'POST' && url.includes('/personal/batches/') && url.includes('/upload-sessions')) {
      return jsonResponse(201, {
        sessionId: 'session-1',
        chunkSizeBytes,
        receivedBytes: confirmedReceivedBytes,
        expiresAt: '2099-01-01T00:00:00Z',
      })
    }
    if (method === 'PUT' && url.includes('/chunks')) {
      onChunk?.(call)
      confirmedReceivedBytes = offsetOf(url) + (bodySize ?? 0)
      return jsonResponse(200, {
        sessionId: 'session-1',
        receivedBytes: confirmedReceivedBytes,
        declaredSizeBytes,
      })
    }
    if (method === 'GET' && url.includes('/upload-sessions/')) {
      return jsonResponse(200, {
        sessionId: 'session-1',
        status: 'OPEN',
        receivedBytes: confirmedReceivedBytes,
        declaredSizeBytes,
        chunkSizeBytes,
        expiresAt: '2099-01-01T00:00:00Z',
      })
    }
    if (method === 'POST' && url.includes('/complete')) {
      if (onComplete) {
        try {
          onComplete()
        } catch (e) {
          if (e instanceof QuotaExceededSignal) {
            return jsonResponse(409, {
              code: 'QUOTA_EXCEEDED',
              message: 'Vượt hạn mức lưu trữ cá nhân',
              usedBytes: 100,
              quotaBytes: 100,
              remainingBytes: 0,
            })
          }
          throw e
        }
      }
      return jsonResponse(201, { status: 'STORED', file: { id: 'file-1', originalName: 'p.bin' } })
    }
    throw new Error(`unexpected fetch in test: ${method} ${url}`)
  })

  return { fetchMock, calls }
}

class QuotaExceededSignal extends Error {}

function makeFile(sizeBytes: number, name = 'big.bin'): File {
  return new File([new Uint8Array(sizeBytes)], name, { type: 'application/octet-stream' })
}

function queuedItem(file: File) {
  return { file, relativePath: '', displayName: file.name, outcome: { phase: 'queued' as const } }
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('uploadOneChunked (personal)', () => {
  it('sends exactly 1 init to /personal/batches/{id}/upload-sessions, ceil(size/chunkSize) chunk PUTs, then 1 complete, and maps to phase "done"', async () => {
    const declaredSizeBytes = 20
    const chunkSizeBytes = 8 // chunks of 8, 8, 4 - N = 3
    const { fetchMock, calls } = fakeUploadServer({ chunkSizeBytes, declaredSizeBytes })
    vi.stubGlobal('fetch', fetchMock)
    vi.mocked(getSession).mockResolvedValue({ accessToken: 'token' } as never)

    const item = queuedItem(makeFile(declaredSizeBytes))
    const updateOutcome = vi.fn()

    await uploadOneChunked('batch-1', item, 0, null, updateOutcome)

    const initCalls = calls.filter(
      (c) => c.method === 'POST' && c.url.includes('/personal/batches/') && c.url.includes('/upload-sessions'),
    )
    const ingestInitCalls = calls.filter((c) => c.url.includes('/ingest/batches/'))
    const chunkCalls = calls.filter((c) => c.method === 'PUT' && c.url.includes('/chunks'))
    const completeCalls = calls.filter((c) => c.method === 'POST' && c.url.includes('/complete'))

    expect(initCalls).toHaveLength(1)
    expect(ingestInitCalls).toHaveLength(0) // must never call the ingest domain's init endpoint
    expect(chunkCalls).toHaveLength(3)
    expect(chunkCalls.reduce((sum, c) => sum + (c.bodySize ?? 0), 0)).toBe(declaredSizeBytes)
    expect(completeCalls).toHaveLength(1)

    expect(updateOutcome).toHaveBeenCalledWith(0, expect.objectContaining({ phase: 'uploading' }))
    // Personal's FileOutcome is 'done', not 'ingested'/'skipped' (2 independent FileOutcome types).
    expect(updateOutcome).toHaveBeenLastCalledWith(0, expect.objectContaining({ phase: 'done' }))
  })

  it('passes folderId through to the init request body', async () => {
    const declaredSizeBytes = 4
    const chunkSizeBytes = 4
    const { fetchMock } = fakeUploadServer({ chunkSizeBytes, declaredSizeBytes })
    vi.stubGlobal('fetch', fetchMock)
    vi.mocked(getSession).mockResolvedValue({ accessToken: 'token' } as never)

    const item = queuedItem(makeFile(declaredSizeBytes))
    const updateOutcome = vi.fn()

    await uploadOneChunked('batch-1', item, 0, 'folder-42', updateOutcome)

    const initCall = fetchMock.mock.calls.find(
      ([url, init]) => (init?.method ?? 'GET') === 'POST' && String(url).includes('/upload-sessions'),
    )
    expect(initCall).toBeDefined()
    const body = JSON.parse(String(initCall?.[1]?.body))
    expect(body.folderId).toBe('folder-42')
  })

  /**
   * Quyết định 5 (SA) - same requirement as spec 01's Task F5, re-verified
   * here because this is a separate implementation of the retry loop, not a
   * shared function.
   */
  it('uses a freshly-fetched token for every chunk request, reflecting a token refresh mid-upload', async () => {
    const declaredSizeBytes = 8
    const chunkSizeBytes = 1 // 8 chunks - more than 5, so the switch happens mid-upload
    const { fetchMock, calls } = fakeUploadServer({ chunkSizeBytes, declaredSizeBytes })
    vi.stubGlobal('fetch', fetchMock)

    let getSessionCalls = 0
    vi.mocked(getSession).mockImplementation(async () => {
      getSessionCalls += 1
      return { accessToken: getSessionCalls <= 5 ? 'old-token' : 'new-token' } as never
    })

    const item = queuedItem(makeFile(declaredSizeBytes))
    const updateOutcome = vi.fn()

    await uploadOneChunked('batch-1', item, 0, null, updateOutcome)

    const chunkCalls = calls
      .filter((c) => c.method === 'PUT' && c.url.includes('/chunks'))
      .map((c) => ({ offset: offsetOf(c.url), auth: c.headers.Authorization }))

    expect(chunkCalls).toHaveLength(8)
    const beforeSwitch = chunkCalls.filter((c) => c.offset < 4)
    const afterSwitch = chunkCalls.filter((c) => c.offset >= 4)

    expect(beforeSwitch.length).toBeGreaterThan(0)
    expect(afterSwitch.length).toBeGreaterThan(0)
    expect(beforeSwitch.every((c) => c.auth === 'Bearer old-token')).toBe(true)
    expect(afterSwitch.every((c) => c.auth === 'Bearer new-token')).toBe(true)
  })

  // ---- QUOTA_EXCEEDED at complete() -----------------------------------------

  it('maps a QUOTA_EXCEEDED failure at complete() to phase "error" with a message derived from remainingBytes', async () => {
    const declaredSizeBytes = 4
    const chunkSizeBytes = 4
    const { fetchMock } = fakeUploadServer({
      chunkSizeBytes,
      declaredSizeBytes,
      onComplete: () => {
        throw new QuotaExceededSignal()
      },
    })
    vi.stubGlobal('fetch', fetchMock)
    vi.mocked(getSession).mockResolvedValue({ accessToken: 'token' } as never)

    const item = queuedItem(makeFile(declaredSizeBytes))
    const updateOutcome = vi.fn()

    await uploadOneChunked('batch-1', item, 0, null, updateOutcome)

    expect(updateOutcome).toHaveBeenLastCalledWith(
      0,
      expect.objectContaining({ phase: 'error', message: expect.stringContaining('còn trống') }),
    )
  })

  // ---- retry/backoff (same shape as spec 01's uploadOneChunked) -------------

  it('retries silently through 2 transient chunk failures and succeeds on the 3rd attempt', async () => {
    vi.useFakeTimers()
    try {
      const declaredSizeBytes = 4
      const chunkSizeBytes = 4
      let chunkAttempts = 0
      const { fetchMock } = fakeUploadServer({
        chunkSizeBytes,
        declaredSizeBytes,
        onChunk: () => {
          chunkAttempts += 1
          if (chunkAttempts <= 2) {
            throw new Error('simulated network drop')
          }
        },
      })
      vi.stubGlobal('fetch', fetchMock)
      vi.mocked(getSession).mockResolvedValue({ accessToken: 'token' } as never)

      const item = queuedItem(makeFile(declaredSizeBytes))
      const updateOutcome = vi.fn()

      let settled = false
      const done = uploadOneChunked('batch-1', item, 0, null, updateOutcome).then(() => {
        settled = true
      })
      for (let i = 0; i < 40 && !settled; i++) {
        await vi.advanceTimersByTimeAsync(1000)
      }
      await done

      expect(chunkAttempts).toBe(3)

      // Same guarantee as upload/'s AC-9 test: a transient failure never
      // surfaces a distinct "retrying"/"error" phase - every call before the
      // final one stays 'uploading' (each is a legitimate loadedBytes
      // progress update from the eventual successful chunk PUT, not a phase
      // change), and the very last call is 'done'.
      const phases = updateOutcome.mock.calls.map((c) => c[1].phase)
      expect(phases.slice(0, -1).every((p) => p === 'uploading')).toBe(true)
      expect(phases.at(-1)).toBe('done')
    } finally {
      vi.useRealTimers()
    }
  })

  it('transitions straight from uploading to error (one transition, no flicker) once every retry attempt is exhausted', async () => {
    vi.useFakeTimers()
    try {
      const declaredSizeBytes = 4
      const chunkSizeBytes = 4
      const { fetchMock } = fakeUploadServer({
        chunkSizeBytes,
        declaredSizeBytes,
        onChunk: () => {
          throw new Error('simulated network drop - always fails')
        },
      })
      vi.stubGlobal('fetch', fetchMock)
      vi.mocked(getSession).mockResolvedValue({ accessToken: 'token' } as never)

      const item = queuedItem(makeFile(declaredSizeBytes))
      const updateOutcome = vi.fn()

      let settled = false
      const done = uploadOneChunked('batch-1', item, 0, null, updateOutcome).then(() => {
        settled = true
      })
      for (let i = 0; i < 40 && !settled; i++) {
        await vi.advanceTimersByTimeAsync(1000)
      }
      await done

      expect(updateOutcome.mock.calls.map((c) => c[1].phase)).toEqual(['uploading', 'error'])
    } finally {
      vi.useRealTimers()
    }
  })

  // ---- case biên: file == threshold vẫn đi whole-file (kiểm tra ở PersonalPage, không phải ở đây) ----
})
