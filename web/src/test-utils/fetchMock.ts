import { vi } from 'vitest'

export interface MockResult {
  status?: number
  body?: unknown
}

export type MockHandler =
  | MockResult
  | ((url: string, init: RequestInit | undefined, method: string) => MockResult | Promise<MockResult>)

/**
 * Installs `global.fetch` for one test. Handlers are matched by substring
 * against the request URL, in insertion order, first match wins - so put
 * more specific patterns first. Unmatched `/notifications*` calls (fired by
 * every page via AppShell -> NotificationBell on mount) and `/me/permissions`
 * (AppShell -> usePermissions; default empty permissions) default to `[]` so
 * page tests that don't care about notifications don't need to stub them.
 *
 * Every call is recorded on the returned mock's `.mock.calls` for assertions
 * (e.g. `expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining(...), ...)`).
 */
export function installFetchMock(handlers: Record<string, MockHandler> = {}) {
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input.toString()
    const method = (init?.method ?? 'GET').toUpperCase()

    for (const [pattern, handler] of Object.entries(handlers)) {
      if (url.includes(pattern)) {
        const result = typeof handler === 'function' ? await handler(url, init, method) : handler
        return toResponse(result)
      }
    }

    if (url.includes('/notifications')) {
      return toResponse({ status: 200, body: [] })
    }

    if (url.includes('/me/permissions')) {
      return toResponse({ status: 200, body: { roles: [], permissions: [] } })
    }

    throw new Error(`installFetchMock: unhandled ${method} ${url}`)
  })

  global.fetch = fn as unknown as typeof fetch
  return fn
}

function toResponse({ status = 200, body = null }: MockResult): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: '',
    json: async () => body,
    text: async () => JSON.stringify(body),
  } as unknown as Response
}
