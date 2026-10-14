import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useSession } from 'next-auth/react'
import SemanticSearchPage from '@/app/search/semantic/page'
import { installFetchMock } from '@/test-utils/fetchMock'
import type { MockHandler } from '@/test-utils/fetchMock'
import { canBoSession, quanTriSession } from '@/test-utils/session'
import type { SemanticResult, SemanticSearchResponseBody } from '@/lib/api'

/**
 * Page-level test for `/search/semantic` (search-semantic AC-1..AC-8). Copy is asserted VERBATIM
 * against the BA story; the filter panel and cards are the real components.
 */
vi.mock('next-auth/react', () => ({ useSession: vi.fn(), signOut: vi.fn() }))
vi.mock('next/navigation', () => ({
  usePathname: () => '/search/semantic',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
}))

const mockedUseSession = vi.mocked(useSession)

function sessionOf(data: ReturnType<typeof canBoSession> | null, status: string) {
  return { data, status, update: vi.fn() } as unknown as ReturnType<typeof useSession>
}

function installAuthorizedFetch(overrides: Record<string, MockHandler> = {}) {
  return installFetchMock({
    '/tags?kind=doc_type': { status: 200, body: [] },
    '/tags?kind=topic': { status: 200, body: [] },
    '/groups': { status: 200, body: [] },
    '/folders/tree': { status: 200, body: [] },
    ...overrides,
  })
}

function result(overrides: Partial<SemanticResult> = {}): SemanticResult {
  return {
    segmentId: 'seg-1',
    documentId: 'doc-1',
    documentName: 'Quyet_dinh_12.pdf',
    docType: 'Quyết định',
    excerpt: 'Thời hạn nộp báo cáo là 30 ngày kể từ ngày ký.',
    contentSource: 'ocr',
    confirmSource: 'manual',
    position: {
      type: 'page',
      pageNo: 3,
      lineFrom: 4,
      lineTo: 6,
      bboxPolygon: null,
      highlightOffsetStart: null,
      highlightOffsetEnd: null,
      timeStartMs: null,
      timeEndMs: null,
    },
    matchedLexical: false,
    cosineScore: 0.71,
    rrfScore: 0.03,
    documentDigitizationStatus: 'confirmed',
    sourceReferences: null,
    ...overrides,
  }
}

function response(overrides: Partial<SemanticSearchResponseBody> = {}): SemanticSearchResponseBody {
  return {
    results: [result()],
    degradedMode: 'none',
    insufficientEvidence: false,
    totalApprox: 1,
    hasMore: false,
    maxPages: 2,
    ...overrides,
  }
}

const QUESTION = 'thời hạn nộp báo cáo'

async function ask(question = QUESTION) {
  fireEvent.change(screen.getByLabelText('Câu hỏi hoặc mô tả ý'), { target: { value: question } })
  fireEvent.click(screen.getByRole('button', { name: 'Tìm kiếm' }))
}

function setup(handler: MockHandler) {
  mockedUseSession.mockReturnValue(sessionOf(canBoSession(), 'authenticated'))
  return installAuthorizedFetch({ '/search/semantic': handler })
}

describe('SemanticSearchPage', () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
  })

  it('is denied to R3 (quan-tri) and to anonymous users', () => {
    mockedUseSession.mockReturnValue(sessionOf(quanTriSession(), 'authenticated'))
    installFetchMock()
    render(<SemanticSearchPage />)
    expect(screen.getByText(/không có quyền/i)).toBeTruthy()
  })

  it('has NO control to force semantic-only or keyword-only (AC-1.2) and shows the verbatim /search hint (AC-1.3)', () => {
    setup({ status: 200, body: response() })
    render(<SemanticSearchPage />)

    expect(screen.queryAllByRole('radio')).toHaveLength(0)
    expect(screen.queryByText(/chỉ tìm theo/i)).toBeNull()
    expect(screen.queryByText(/chế độ/i)).toBeNull()
    expect(screen.queryByRole('button', { name: /tìm rộng hơn|bỏ qua ngưỡng/i })).toBeNull()

    expect(screen.getByText(/Tìm theo số hiệu hoặc tên văn bản chính xác\?/)).toBeTruthy()
    const link = screen.getByRole('link', { name: 'Dùng trang Tìm kiếm toàn văn.' })
    expect(link.getAttribute('href')).toBe('/search')
  })

  it('posts the question + filters (no mode, no threshold) and renders passages under the verbatim heading', async () => {
    const fetchMock = setup({ status: 200, body: response() })
    render(<SemanticSearchPage />)
    await ask()

    await waitFor(() => expect(screen.getByText('Quyet_dinh_12.pdf')).toBeTruthy())
    expect(screen.getByRole('heading', { name: 'Các đoạn nội dung liên quan' })).toBeTruthy()
    expect(screen.getByText('Thời hạn nộp báo cáo là 30 ngày kể từ ngày ký.')).toBeTruthy()
    expect(screen.queryByText(/câu trả lời/i)).toBeNull()

    const call = fetchMock.mock.calls.find(([url]) => String(url).includes('/search/semantic'))!
    const body = JSON.parse((call[1] as RequestInit).body as string)
    expect(body).toEqual({ query: QUESTION, filters: null, page: 0, size: 10 })
    expect(Object.keys(body)).not.toContain('mode')
    expect(Object.keys(body)).not.toContain('minScore')
  })

  it('shows the verbatim yellow auto-confirm badge, "Khớp từ khoá" and the pending-review note (AC-2.1/2.3/8)', async () => {
    setup({
      status: 200,
      body: response({
        results: [
          result({ confirmSource: 'auto', matchedLexical: true, documentDigitizationStatus: 'pending_review' }),
        ],
      }),
    })
    render(<SemanticSearchPage />)
    await ask()

    await waitFor(() => expect(screen.getByText('Khớp từ khoá')).toBeTruthy())
    expect(screen.getByText('Nội dung tự động xác nhận — cân nhắc kỹ khi dùng làm chứng cứ')).toBeTruthy()
    expect(
      screen.getByText('Nội dung đang chờ kiểm duyệt, có thể mất tới 24 giờ để tìm được theo nội dung đầy đủ.'),
    ).toBeTruthy()
    expect(screen.getByRole('button', { name: /Xem trên ảnh trang 3/ })).toBeTruthy()
    expect(document.querySelector('em, mark')).toBeNull() // no highlight (AC-2.3)
  })

  it('labels a video summary and opens it from the start (AC-2.5)', async () => {
    setup({
      status: 200,
      body: response({
        results: [
          result({
            contentSource: 'summary',
            position: {
              type: 'none',
              pageNo: null,
              lineFrom: null,
              lineTo: null,
              bboxPolygon: null,
              highlightOffsetStart: null,
              highlightOffsetEnd: null,
              timeStartMs: null,
              timeEndMs: null,
            },
          }),
        ],
      }),
    })
    render(<SemanticSearchPage />)
    await ask()

    await waitFor(() => expect(screen.getByText('Tóm tắt nội dung video')).toBeTruthy())
    expect(screen.getByText('Không có vị trí cụ thể')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Mở tài liệu từ đầu' })).toBeTruthy()
  })

  it('shows the neutral verbatim empty state, not a banner, for "không đủ căn cứ" (AC-3)', async () => {
    setup({ status: 200, body: response({ results: [], insufficientEvidence: true, totalApprox: 0 }) })
    render(<SemanticSearchPage />)
    await ask()

    await waitFor(() =>
      expect(
        screen.getByText(
          'Không tìm thấy đoạn nội dung nào đủ căn cứ trong phạm vi dữ liệu của bạn. Hãy thử diễn đạt lại câu hỏi hoặc điều chỉnh bộ lọc.',
        ),
      ).toBeTruthy(),
    )
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('shows the red scope banner on 403 and no results (AC-4.2)', async () => {
    setup({ status: 403, body: { message: 'x' } })
    render(<SemanticSearchPage />)
    await ask()

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toBe(
      'Không xác định được phạm vi dữ liệu của bạn nên chưa thể tra cứu. Vui lòng thử lại sau hoặc liên hệ quản trị viên.',
    )
    expect(alert.className).toContain('red')
  })

  it('on 503 shows the red unavailable banner and CLEARS the previous results (AC-5.2)', async () => {
    let calls = 0
    setup(() => (++calls === 1 ? { status: 200, body: response() } : { status: 503, body: { message: 'x' } }))
    render(<SemanticSearchPage />)
    await ask()
    await waitFor(() => expect(screen.getByText('Quyet_dinh_12.pdf')).toBeTruthy())

    fireEvent.click(screen.getByRole('button', { name: 'Tìm kiếm' }))

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toBe('Dịch vụ tra cứu đang tạm thời không khả dụng, vui lòng thử lại sau.')
    expect(screen.queryByText('Quyet_dinh_12.pdf')).toBeNull()
  })

  it('on 429 shows the yellow busy banner with "Thử lại" that resends the same question (AC-5.3)', async () => {
    let calls = 0
    const fetchMock = setup(() => (++calls === 1 ? { status: 429, body: { message: 'x' } } : { status: 200, body: response() }))
    render(<SemanticSearchPage />)
    await ask()

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('Hệ thống tra cứu ngữ nghĩa đang bận, vui lòng thử lại sau ít giây.')
    expect(alert.className).toContain('yellow')

    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }))

    await waitFor(() => expect(screen.getByText('Quyet_dinh_12.pdf')).toBeTruthy())
    const semanticCalls = fetchMock.mock.calls.filter(([url]) => String(url).includes('/search/semantic'))
    expect(semanticCalls).toHaveLength(2)
    expect(JSON.parse((semanticCalls[1][1] as RequestInit).body as string).query).toBe(QUESTION)
  })

  it.each([
    [
      'lexical_only',
      'Tìm kiếm theo ngữ nghĩa đang tạm thời không khả dụng. Kết quả dưới đây chỉ khớp theo từ khoá và có thể bỏ sót nội dung liên quan.',
    ],
    [
      'semantic_only',
      'Tìm kiếm theo từ khoá đang tạm thời không khả dụng. Kết quả dưới đây chỉ dựa trên tương đồng ngữ nghĩa và có thể bỏ sót kết quả khớp chính xác mã số/tên riêng.',
    ],
  ] as const)('shows the yellow %s degradation banner above the results with a Thử lại button (AC-5.4)', async (mode, text) => {
    setup({ status: 200, body: response({ degradedMode: mode }) })
    render(<SemanticSearchPage />)
    await ask()

    const banner = await screen.findByRole('status')
    expect(banner.textContent).toContain(text)
    expect(banner.className).toContain('yellow')
    expect(screen.getByText('Quyet_dinh_12.pdf')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Thử lại' })).toBeTruthy()
  })

  it('disables "Thử lại" while a request is in flight (no duplicate send)', async () => {
    let resolveSecond: (() => void) | undefined
    let calls = 0
    const fetchMock = setup(async () => {
      if (++calls === 1) return { status: 200, body: response({ degradedMode: 'lexical_only' }) }
      await new Promise<void>((resolve) => {
        resolveSecond = resolve
      })
      return { status: 200, body: response() }
    })
    render(<SemanticSearchPage />)
    await ask()
    const retry = await screen.findByRole('button', { name: 'Thử lại' })

    fireEvent.click(retry)
    await waitFor(() => expect((screen.getByRole('button', { name: 'Thử lại' }) as HTMLButtonElement).disabled).toBe(true))
    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }))
    resolveSecond?.()
    await waitFor(() => expect(screen.queryByRole('status')).toBeNull())

    expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('/search/semantic'))).toHaveLength(2)
  })

  it('allows at most 2 pages: next only on page 1, never a third page, and hints to narrow when hasMore (AC-6)', async () => {
    const fetchMock = setup(() => ({
      status: 200,
      body: response({ totalApprox: 35, hasMore: true }),
    }))
    render(<SemanticSearchPage />)
    await ask()
    await waitFor(() => expect(screen.getByText('Trang 1/2')).toBeTruthy())
    expect(screen.getByText(/Khoảng 35 đoạn \(số liệu gần đúng\)/)).toBeTruthy()
    expect(screen.getByText(/Hãy thu hẹp bộ lọc/)).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Trang sau →' }))
    await waitFor(() => expect(screen.getByText('Trang 2/2')).toBeTruthy())
    expect((screen.getByRole('button', { name: 'Trang sau →' }) as HTMLButtonElement).disabled).toBe(true)

    const pages = fetchMock.mock.calls
      .filter(([url]) => String(url).includes('/search/semantic'))
      .map(([, init]) => JSON.parse((init as RequestInit).body as string).page)
    expect(pages).toEqual([0, 1])
  })

  it('validates an empty or over-long question client-side without calling the API', async () => {
    const fetchMock = setup({ status: 200, body: response() })
    render(<SemanticSearchPage />)

    fireEvent.click(screen.getByRole('button', { name: 'Tìm kiếm' }))
    expect(await screen.findByText('Vui lòng nhập câu hỏi hoặc mô tả ý cần tra cứu.')).toBeTruthy()
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/search/semantic'))).toBe(false)
  })
})
