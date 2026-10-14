import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getSession } from 'next-auth/react'
import { uploadOneChunked } from '@/app/upload/page'

/**
 * chunked-file-upload-01-ingest-chunked-upload.md §5 (Frontend) / Task F5.
 *
 * `next-auth/react` is mocked (only `getSession` matters here - `uploadOneChunked`
 * never touches `useSession`). `@/lib/api` is used for real: these tests stub
 * the global `fetch` instead, so `apiJson`/`apiFetch`'s real header-building
 * logic (Quyết định 5's `Authorization: Bearer <token>`) is exactly what is
 * being exercised, not a mock standing in for it.
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

/** A minimal fake `/ingest/batches/{id}/upload-sessions -> chunks -> complete` server. */
function fakeUploadServer(options: {
  chunkSizeBytes: number
  declaredSizeBytes: number
  /** Called for every PUT chunk request; throwing simulates a transient network failure. */
  onChunk?: (call: RecordedCall) => void
}) {
  const { chunkSizeBytes, declaredSizeBytes, onChunk } = options
  const calls: RecordedCall[] = []
  let confirmedReceivedBytes = 0

  const fetchMock = vi.fn(async (url: string, init: RequestInit = {}) => {
    const method = init.method ?? 'GET'
    const headers = (init.headers ?? {}) as Record<string, string>
    const bodySize = init.body instanceof Blob ? init.body.size : undefined
    const call: RecordedCall = { url, method, headers, bodySize }
    calls.push(call)

    if (method === 'POST' && url.includes('/ingest/batches/') && url.includes('/upload-sessions')) {
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
      return jsonResponse(201, { status: 'INGESTED', document: { id: 'doc-1' }, duplicateOf: [] })
    }
    throw new Error(`unexpected fetch in test: ${method} ${url}`)
  })

  return { fetchMock, calls }
}

function makeFile(sizeBytes: number, name = 'big.bin'): File {
  return new File([new Uint8Array(sizeBytes)], name, { type: 'application/octet-stream' })
}

function queuedItem(file: File) {
  return { file, relativePath: '', displayName: file.name, outcome: { phase: 'queued' as const } }
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('uploadOneChunked', () => {
  it('sends exactly 1 init, ceil(size/chunkSize) chunk PUTs whose bytes sum to the file size, then 1 complete', async () => {
    const declaredSizeBytes = 20
    const chunkSizeBytes = 8 // chunks of 8, 8, 4 - N = 3
    const { fetchMock, calls } = fakeUploadServer({ chunkSizeBytes, declaredSizeBytes })
    vi.stubGlobal('fetch', fetchMock)
    vi.mocked(getSession).mockResolvedValue({ accessToken: 'token' } as never)

    const item = queuedItem(makeFile(declaredSizeBytes))
    const updateOutcome = vi.fn()

    await uploadOneChunked('batch-1', item, 0, '', updateOutcome)

    const initCalls = calls.filter((c) => c.method === 'POST' && c.url.includes('/upload-sessions') && c.url.includes('/ingest/batches/'))
    const chunkCalls = calls.filter((c) => c.method === 'PUT' && c.url.includes('/chunks'))
    const completeCalls = calls.filter((c) => c.method === 'POST' && c.url.includes('/complete'))

    expect(initCalls).toHaveLength(1)
    expect(chunkCalls).toHaveLength(3)
    expect(chunkCalls.reduce((sum, c) => sum + (c.bodySize ?? 0), 0)).toBe(declaredSizeBytes)
    expect(completeCalls).toHaveLength(1)

    expect(updateOutcome).toHaveBeenCalledWith(0, expect.objectContaining({ phase: 'uploading' }))
    expect(updateOutcome).toHaveBeenLastCalledWith(0, expect.objectContaining({ phase: 'ingested' }))
  })

  /**
   * Quyết định 5 (SA, rủi ro Trung bình) / story Phụ thuộc: bắt buộc theo mục 5
   * của spec - verifies chunk requests sent AFTER NextAuth's background token
   * refresh carry the new token, never the one `getSession()` returned at the
   * very start of the upload.
   */
  it('uses a freshly-fetched token for every chunk request, reflecting a token refresh mid-upload', async () => {
    const declaredSizeBytes = 8
    const chunkSizeBytes = 1 // 8 chunks - more than 5, so the switch happens mid-upload
    const { fetchMock, calls } = fakeUploadServer({ chunkSizeBytes, declaredSizeBytes })
    vi.stubGlobal('fetch', fetchMock)

    let getSessionCalls = 0
    vi.mocked(getSession).mockImplementation(async () => {
      getSessionCalls += 1
      // 1st call = init, 2nd..9th = the 8 chunk PUTs, 10th = complete.
      // Switches after the 5th call (i.e. mid-way through the chunk PUTs).
      return { accessToken: getSessionCalls <= 5 ? 'old-token' : 'new-token' } as never
    })

    const item = queuedItem(makeFile(declaredSizeBytes))
    const updateOutcome = vi.fn()

    await uploadOneChunked('batch-1', item, 0, '', updateOutcome)

    const chunkCalls = calls
      .filter((c) => c.method === 'PUT' && c.url.includes('/chunks'))
      .map((c) => ({ offset: offsetOf(c.url), auth: c.headers.Authorization }))

    expect(chunkCalls).toHaveLength(8)
    const beforeSwitch = chunkCalls.filter((c) => c.offset < 4)
    const afterSwitch = chunkCalls.filter((c) => c.offset >= 4)

    expect(beforeSwitch.length).toBeGreaterThan(0)
    expect(afterSwitch.length).toBeGreaterThan(0)
    expect(beforeSwitch.every((c) => c.auth === 'Bearer old-token')).toBe(true)
    // The important assertion: requests sent after the refresh carry the new
    // token - not the one frozen at the start of the upload.
    expect(afterSwitch.every((c) => c.auth === 'Bearer new-token')).toBe(true)
  })

  // ---- AC-9: silent retry, no intermediate "retrying" outcome -----------------

  it('AC-9: retries silently through 2 transient chunk failures and succeeds on the 3rd attempt, with no intermediate outcome between uploading and ingested', async () => {
    vi.useFakeTimers()
    try {
      const declaredSizeBytes = 4
      const chunkSizeBytes = 4 // single chunk
      let chunkAttempts = 0
      const { fetchMock } = fakeUploadServer({
        chunkSizeBytes,
        declaredSizeBytes,
        onChunk: () => {
          chunkAttempts += 1
          if (chunkAttempts <= 2) {
            throw new Error('simulated network drop');
          }
        },
      })
      vi.stubGlobal('fetch', fetchMock)
      vi.mocked(getSession).mockResolvedValue({ accessToken: 'token' } as never)

      const item = queuedItem(makeFile(declaredSizeBytes))
      const updateOutcome = vi.fn()

      const done = uploadOneChunked('batch-1', item, 0, '', updateOutcome).then(() => {
        settled = true
      })
      let settled = false
      // Drain the TRANSIENT_RETRY_DELAYS_MS backoff sleeps (2s/6s/15s) between the outer attempts.
      for (let i = 0; i < 40 && !settled; i++) {
        await vi.advanceTimersByTimeAsync(1000)
      }
      await done;

      expect(chunkAttempts).toBe(3);

      // AC-9's real guarantee: a transient failure never surfaces a distinct
      // "retrying"/"error" phase to the UI - every call before the final one
      // stays 'uploading' (each is a legitimate loadedBytes progress update
      // from the eventual successful chunk PUT, not a phase change), and the
      // very last call is 'ingested'.
      const phases = updateOutcome.mock.calls.map((c) => c[1].phase);
      expect(phases.slice(0, -1).every((p) => p === 'uploading')).toBe(true);
      expect(phases.at(-1)).toBe('ingested');
    } finally {
      vi.useRealTimers()
    }
  })

  it('AC-9: transitions straight from uploading to error (one transition, no flicker) once every retry attempt is exhausted', async () => {
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
      const done = uploadOneChunked('batch-1', item, 0, '', updateOutcome).then(() => {
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
})
