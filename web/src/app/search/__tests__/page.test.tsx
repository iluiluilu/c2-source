import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useSession } from 'next-auth/react'
import SearchPage from '@/app/search/page'
import { installFetchMock } from '@/test-utils/fetchMock'
import type { MockHandler } from '@/test-utils/fetchMock'
import { canBoSession, quanTriSession } from '@/test-utils/session'
import type { FolderNode, GroupRow, SearchResponseBody, TagRow } from '@/lib/api'

/**
 * Page-level test for `src/app/search/page.tsx` - query/filter-panel wiring,
 * request building (`lib/search-request.ts`), and results/refine-suggestion
 * rendering. `SearchFilterPanel`/`SearchResultCard` already have solid unit
 * tests (src/components/search/__tests__), so this exercises the real,
 * integrated components rather than mocking them out.
 */
vi.mock('next-auth/react', () => ({
  useSession: vi.fn(),
  signOut: vi.fn(),
}))
vi.mock('next/navigation', () => ({
  usePathname: () => '/search',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
}))

const mockedUseSession = vi.mocked(useSession)

function useSessionReturn(data: ReturnType<typeof canBoSession> | null, status: string) {
  return { data, status, update: vi.fn() } as unknown as ReturnType<typeof useSession>
}

const docTypeOptions: TagRow[] = [
  { id: 'tag-1', kind: 'doc_type', label: 'Hợp đồng', createdByUsername: 'canbo1', createdAt: '2026-01-01T00:00:00.000Z' },
]
const topicOptions: TagRow[] = []
const groupOptions: GroupRow[] = []
const folderTree: FolderNode[] = []

function installAuthorizedFetch(overrides: Record<string, MockHandler> = {}) {
  return installFetchMock({
    '/tags?kind=doc_type': { status: 200, body: docTypeOptions },
    '/tags?kind=topic': { status: 200, body: topicOptions },
    '/groups': { status: 200, body: groupOptions },
    '/folders/tree': { status: 200, body: folderTree },
    ...overrides,
  })
}

function selectAfterLabel(labelText: string): HTMLSelectElement {
  return screen.getByLabelText(labelText) as HTMLSelectElement
}

function baseSearchResponse(overrides: Partial<SearchResponseBody> = {}): SearchResponseBody {
  return {
    results: [],
    totalApprox: 0,
    page: 0,
    size: 20,
    degradedMode: 'none',
    refineSuggestions: [],
    ...overrides,
  }
}

describe('SearchPage', () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
  })

  it('renders nothing while the session is loading', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(null, 'loading'))
    installFetchMock()
    const { container } = render(<SearchPage />)
    expect(container).toBeEmptyDOMElement()
  })

  it('shows AccessDenied when there is no session', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(null, 'unauthenticated'))
    installFetchMock()
    render(<SearchPage />)
    expect(screen.getByText(/không có quyền/i)).toBeTruthy()
  })

  it('shows AccessDenied for a role without search access (quan-tri)', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock()
    render(<SearchPage />)
    expect(screen.getByText(/không có quyền/i)).toBeTruthy()
  })

  it('loads the filter-panel options and shows the initial empty-state prompt for an authorized user', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const fetchMock = installAuthorizedFetch()
    render(<SearchPage />)

    expect(screen.getByRole('heading', { level: 2, name: 'Tìm kiếm toàn văn' })).toBeTruthy()
    expect(screen.getByText('Nhập từ khoá hoặc chọn bộ lọc rồi bấm Tìm kiếm.')).toBeTruthy()

    await waitFor(() => expect(screen.getByText('Hợp đồng')).toBeTruthy())
    expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/tags?kind=doc_type'), expect.anything())
    expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/tags?kind=topic'), expect.anything())
    expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/groups'), expect.anything())
    expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/folders/tree'), expect.anything())
  })

  it('blocks the search client-side (no /search call) when both the query and every filter are empty', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const fetchMock = installAuthorizedFetch()
    render(<SearchPage />)
    await waitFor(() => expect(screen.getByText('Hợp đồng')).toBeTruthy())

    fireEvent.click(screen.getByRole('button', { name: 'Tìm kiếm' }))

    expect(
      screen.getByText('Vui lòng nhập từ khoá hoặc chọn ít nhất một bộ lọc trước khi tìm kiếm.'),
    ).toBeTruthy()
    expect(fetchMock).not.toHaveBeenCalledWith(expect.stringContaining('/search'), expect.anything())
  })

  it('submits a query, builds the POST /search request, and renders the returned result', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const fetchMock = installAuthorizedFetch({
      '/search': {
        status: 200,
        body: baseSearchResponse({
          totalApprox: 1,
          results: [
            {
              documentId: 'doc-1',
              documentName: 'Hop_dong_thue_nha.pdf',
              docType: 'hop_dong',
              score: 9.5,
              documentDigitizationStatus: 'confirmed',
              segments: [],
            },
          ],
        }),
      },
    })
    render(<SearchPage />)
    await waitFor(() => expect(screen.getByText('Hợp đồng')).toBeTruthy())

    fireEvent.change(screen.getByPlaceholderText(/hợp đồng thuê nhà/i), { target: { value: 'hop dong' } })
    fireEvent.click(screen.getByRole('button', { name: 'Tìm kiếm' }))

    await waitFor(() => expect(screen.getByText('Hop_dong_thue_nha.pdf')).toBeTruthy())
    expect(screen.getByText('Khoảng 1 kết quả')).toBeTruthy()

    const call = fetchMock.mock.calls.find(([url]) => String(url).includes('/search'))
    expect(call).toBeTruthy()
    const body = JSON.parse((call![1] as RequestInit).body as string)
    expect(body).toEqual({ query: 'hop dong', filters: null, sort: 'relevance', page: 0, size: 20 })
    expect(call![1]).toEqual(expect.objectContaining({ method: 'POST' }))
  })

  it('includes the selected filter-panel value and the chosen sort in the request body', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const fetchMock = installAuthorizedFetch({ '/search': { status: 200, body: baseSearchResponse() } })
    render(<SearchPage />)
    await waitFor(() => expect(screen.getByText('Hợp đồng')).toBeTruthy())

    fireEvent.change(screen.getByPlaceholderText(/hợp đồng thuê nhà/i), { target: { value: 'abc' } })
    fireEvent.change(selectAfterLabel('Loại tài liệu'), { target: { value: 'Hợp đồng' } })
    fireEvent.change(screen.getByDisplayValue('Liên quan nhất'), { target: { value: 'recent' } })
    fireEvent.click(screen.getByRole('button', { name: 'Tìm kiếm' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/search'), expect.anything()))
    const call = fetchMock.mock.calls.find(([url]) => String(url).includes('/search'))!
    const body = JSON.parse((call[1] as RequestInit).body as string)
    expect(body.filters).toEqual({ docType: 'Hợp đồng' })
    expect(body.sort).toBe('recent')
  })

  it('shows the degraded-mode banner when the response signals opensearch_unavailable', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installAuthorizedFetch({
      '/search': { status: 200, body: baseSearchResponse({ degradedMode: 'opensearch_unavailable' }) },
    })
    render(<SearchPage />)
    await waitFor(() => expect(screen.getByText('Hợp đồng')).toBeTruthy())

    fireEvent.change(screen.getByPlaceholderText(/hợp đồng thuê nhà/i), { target: { value: 'abc' } })
    fireEvent.click(screen.getByRole('button', { name: 'Tìm kiếm' }))

    await waitFor(() =>
      expect(
        screen.getByText(/Hệ thống tìm kiếm đang gặp sự cố tạm thời/),
      ).toBeTruthy(),
    )
  })

  it('shows the API error message when the search request fails', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installAuthorizedFetch({
      '/search': { status: 500, body: { message: 'Lỗi máy chủ tìm kiếm.' } },
    })
    render(<SearchPage />)
    await waitFor(() => expect(screen.getByText('Hợp đồng')).toBeTruthy())

    fireEvent.change(screen.getByPlaceholderText(/hợp đồng thuê nhà/i), { target: { value: 'abc' } })
    fireEvent.click(screen.getByRole('button', { name: 'Tìm kiếm' }))

    await waitFor(() => expect(screen.getByText('Lỗi máy chủ tìm kiếm.')).toBeTruthy())
  })

  it('a zero-result refine suggestion click re-runs the search with that filter group cleared', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const fetchMock = installAuthorizedFetch({
      '/search': {
        status: 200,
        body: baseSearchResponse({
          totalApprox: 0,
          refineSuggestions: [{ removedFilter: 'docType', estimatedCount: 5 }],
        }),
      },
    })
    render(<SearchPage />)
    await waitFor(() => expect(screen.getByText('Hợp đồng')).toBeTruthy())

    fireEvent.change(screen.getByPlaceholderText(/hợp đồng thuê nhà/i), { target: { value: 'abc' } })
    fireEvent.change(selectAfterLabel('Loại tài liệu'), { target: { value: 'Hợp đồng' } })
    fireEvent.click(screen.getByRole('button', { name: 'Tìm kiếm' }))

    const refineButton = await screen.findByText(/Bỏ lọc "Loại tài liệu" \(≈5 kết quả\)/)
    fireEvent.click(refineButton)

    await waitFor(() => {
      const searchCalls = fetchMock.mock.calls.filter(([url]) => String(url).includes('/search'))
      expect(searchCalls).toHaveLength(2)
    })
    const searchCalls = fetchMock.mock.calls.filter(([url]) => String(url).includes('/search'))
    const secondBody = JSON.parse((searchCalls[1][1] as RequestInit).body as string)
    expect(secondBody.filters).toBeNull()
  })
})
