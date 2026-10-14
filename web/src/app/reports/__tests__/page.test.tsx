import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useSession } from 'next-auth/react'
import ReportsPage from '@/app/reports/page'
import { installFetchMock as baseInstallFetchMock } from '@/test-utils/fetchMock'
import type { MockHandler } from '@/test-utils/fetchMock'
import { permissionsHandler, resetPermissions } from '@/test-utils/permissions'
import { canBoSession, quanTriSession } from '@/test-utils/session'
import type { ReportResult, ReportTemplate } from '@/lib/reports'

vi.mock('next-auth/react', () => ({
  useSession: vi.fn(),
  signOut: vi.fn(),
}))
vi.mock('next/navigation', () => ({
  usePathname: () => '/reports',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
}))

const mockedUseSession = vi.mocked(useSession)

function sessionReturn(data: ReturnType<typeof canBoSession> | null, status: string) {
  return { data, status, update: vi.fn() } as unknown as ReturnType<typeof useSession>
}

const templates: ReportTemplate[] = [
  { code: 'REPO_SUMMARY', name: 'Tổng hợp kho', description: 'Mô tả kho', params: [] },
  { code: 'DIGITIZATION_PROGRESS', name: 'Tiến độ số hoá', description: 'Mô tả số hoá', params: [] },
  { code: 'DOC_BY_CLASSIFICATION', name: 'Theo phân loại', description: 'Mô tả phân loại', params: [] },
]

const result: ReportResult = {
  templateCode: 'REPO_SUMMARY',
  title: 'Tổng hợp tình hình kho tài liệu theo kỳ',
  period: { from: '2026-03-01', to: '2026-03-31' },
  filters: {},
  scopeNotice: 'Số liệu trong phạm vi truy cập của canbo1 tại 2026-04-01 09:00:00',
  generatedAt: '2026-04-01T02:00:00Z',
  generatedBy: 'canbo1',
  notes: ['Số liệu lấy trực tiếp từ cơ sở dữ liệu chính thức (PostgreSQL), khác với tổng số gần đúng (total) trên màn tìm kiếm.'],
  warnings: [],
  empty: false,
  tables: [
    {
      id: 'b1',
      title: 'Bảng 1',
      columns: [
        { key: 'metric', label: 'Chỉ tiêu', type: 'string' },
        { key: 'value', label: 'Số tài liệu', type: 'number' },
      ],
      rows: [{ metric: 'Tồn kho cuối kỳ', value: 1234 }],
      totalRows: 1,
      paged: false,
    },
  ],
  charts: [{ id: 'c1', title: 'Biểu đồ', type: 'BAR', categories: ['A'], series: [{ name: 'S', values: [3] }] }],
}

const baseHandlers: Record<string, MockHandler> = {
  '/reports/templates': { status: 200, body: templates },
  '/reports/saved': { status: 200, body: [] },
  '/groups': { status: 200, body: [{ id: 'g1', name: 'Nhóm 1', description: null, ownerSub: 'x', createdAt: '', updatedAt: '' }] },
  '/tags': { status: 200, body: [{ id: 't1', kind: 'topic', label: 'Chủ đề A', createdByUsername: 'x', createdAt: '' }] },
  '/folders/tree': { status: 200, body: [{ id: 'f1', name: 'Thư mục 1', depth: 1, children: [] }] },
}

function install(extra: Record<string, MockHandler> = {}, perms: string[] = ['report.read', 'report.export']) {
  // First match wins (substring): specific keys of the test first, then the defaults it did not override.
  const merged: Record<string, MockHandler> = { '/me/permissions': permissionsHandler(perms), ...extra }
  for (const [k, v] of Object.entries(baseHandlers)) if (!(k in merged)) merged[k] = v
  return baseInstallFetchMock(merged)
}

describe('ReportsPage', () => {
  afterEach(() => {
    resetPermissions()
    cleanup()
    vi.restoreAllMocks()
  })

  it('renders nothing while the session loads, and AccessDenied for R3 / users without report.read', async () => {
    mockedUseSession.mockReturnValue(sessionReturn(null, 'loading'))
    install()
    const { container, unmount } = render(<ReportsPage />)
    expect(container).toBeEmptyDOMElement()
    unmount()

    mockedUseSession.mockReturnValue(sessionReturn(quanTriSession(), 'authenticated'))
    install({}, ['account.manage'])
    render(<ReportsPage />)
    await waitFor(() => expect(screen.getByText(/không có quyền/i)).toBeTruthy())
  })

  it('lists the 3 templates, and quick-period buttons fill from/to and suggest a granularity (AC-8)', async () => {
    mockedUseSession.mockReturnValue(sessionReturn(canBoSession(), 'authenticated'))
    install()
    render(<ReportsPage />)
    await waitFor(() => expect(screen.getByText('Tổng hợp kho')).toBeTruthy())
    expect(screen.getByText('Tiến độ số hoá')).toBeTruthy()
    expect(screen.getByText('Theo phân loại')).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Năm nay' }))
    const year = new Date().getFullYear()
    expect((screen.getByLabelText('Từ ngày') as HTMLInputElement).value).toBe(`${year}-01-01`)
    expect((screen.getByLabelText('Đến ngày') as HTMLInputElement).value).toBe(`${year}-12-31`)
    expect((screen.getByLabelText('Độ chi tiết') as HTMLSelectElement).value).toBe('month')

    // the user can still edit the dates freely
    fireEvent.change(screen.getByLabelText('Từ ngày'), { target: { value: `${year}-02-10` } })
    expect((screen.getByLabelText('Từ ngày') as HTMLInputElement).value).toBe(`${year}-02-10`)
  })

  it('runs a report and shows scope notice, tables, charts and the official-figures note (AC-4, AC-17, AC-18)', async () => {
    mockedUseSession.mockReturnValue(sessionReturn(canBoSession(), 'authenticated'))
    const fetchMock = install({ '/reports/REPO_SUMMARY/run': { status: 200, body: result } })
    render(<ReportsPage />)
    await waitFor(() => expect(screen.getByText('Tổng hợp kho')).toBeTruthy())

    fireEvent.click(screen.getByRole('button', { name: 'Chạy báo cáo' }))
    await waitFor(() => expect(screen.getByTestId('report-result')).toBeTruthy())
    expect(screen.getByTestId('scope-notice').textContent).toContain('Số liệu trong phạm vi truy cập của canbo1')
    expect(screen.getByText('1.234')).toBeTruthy()
    expect(screen.getByTestId('chart-c1')).toBeTruthy()
    expect(screen.getByTestId('report-notes').textContent).toContain('PostgreSQL')

    const call = fetchMock.mock.calls.find(([u]) => String(u).includes('/reports/REPO_SUMMARY/run'))
    expect(call).toBeTruthy()
    const body = JSON.parse(String((call![1] as RequestInit).body))
    expect(body).toMatchObject({ page: 0, size: 50, groupIds: [], topicTagIds: [], folderId: null })
  })

  it('shows the server message for a 400 and does not call the server for an inverted period', async () => {
    mockedUseSession.mockReturnValue(sessionReturn(canBoSession(), 'authenticated'))
    const fetchMock = install({
      '/reports/REPO_SUMMARY/run': { status: 400, body: { message: 'Một số bộ lọc không hợp lệ hoặc không khả dụng, hãy chọn lại' } },
    })
    render(<ReportsPage />)
    await waitFor(() => expect(screen.getByText('Tổng hợp kho')).toBeTruthy())

    fireEvent.click(screen.getByRole('button', { name: 'Chạy báo cáo' }))
    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('Một số bộ lọc không hợp lệ'))

    const runCalls = () => fetchMock.mock.calls.filter(([u]) => String(u).includes('/run')).length
    const before = runCalls()
    fireEvent.change(screen.getByLabelText('Từ ngày'), { target: { value: '2999-01-01' } })
    fireEvent.click(screen.getByRole('button', { name: 'Chạy báo cáo' }))
    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('nhỏ hơn hoặc bằng'))
    expect(runCalls()).toBe(before)
  })

  it('shows an empty report with its warning (AC-10)', async () => {
    mockedUseSession.mockReturnValue(sessionReturn(canBoSession(), 'authenticated'))
    install({
      '/reports/REPO_SUMMARY/run': { status: 200, body: { ...result, empty: true, warnings: ['Không có dữ liệu trong phạm vi và bộ lọc đã chọn.'] } },
    })
    render(<ReportsPage />)
    await waitFor(() => expect(screen.getByText('Tổng hợp kho')).toBeTruthy())
    fireEvent.click(screen.getByRole('button', { name: 'Chạy báo cáo' }))
    await waitFor(() => expect(screen.getByText(/Không có dữ liệu trong phạm vi/)).toBeTruthy())
  })

  it('hides the export controls without report.export, and exports with it (202 -> link to history)', async () => {
    mockedUseSession.mockReturnValue(sessionReturn(canBoSession(), 'authenticated'))
    install({}, ['report.read'])
    const { unmount } = render(<ReportsPage />)
    await waitFor(() => expect(screen.getByText('Tổng hợp kho')).toBeTruthy())
    expect(screen.queryByRole('button', { name: 'Xuất tệp' })).toBeNull()
    unmount()
    cleanup()
    resetPermissions()

    const fetchMock = install({ '/reports/exports': { status: 202, body: { exportId: 'e1', status: 'PENDING' } } })
    render(<ReportsPage />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Xuất tệp' })).toBeTruthy())
    fireEvent.change(screen.getByLabelText('Định dạng tệp'), { target: { value: 'XLSX' } })
    fireEvent.click(screen.getByRole('button', { name: 'Xuất tệp' }))
    await waitFor(() => expect(screen.getByText(/Đã tạo yêu cầu xuất tệp/)).toBeTruthy())
    const call = fetchMock.mock.calls.find(([u, init]) => String(u).endsWith('/reports/exports') && (init as RequestInit)?.method === 'POST')
    expect(JSON.parse(String((call![1] as RequestInit).body))).toMatchObject({ templateCode: 'REPO_SUMMARY', format: 'XLSX' })
  })

  it('shows the 429 message as is', async () => {
    mockedUseSession.mockReturnValue(sessionReturn(canBoSession(), 'authenticated'))
    install({ '/reports/exports': { status: 429, body: { message: 'Bạn đang có 3 tệp đang tạo, hãy chờ xong rồi thử lại' } } })
    render(<ReportsPage />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Xuất tệp' })).toBeTruthy())
    const original = Element.prototype.scrollIntoView
    const scrolled = vi.fn()
    Element.prototype.scrollIntoView = scrolled
    fireEvent.click(screen.getByRole('button', { name: 'Xuất tệp' }))
    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('3 tệp đang tạo'))
    // the banner sits above the parameter form, far from the button: it must be brought into view
    expect(scrolled).toHaveBeenCalled()
    Element.prototype.scrollIntoView = original
  })

  it('saves the current parameters under a name and reopens a saved report with its warning', async () => {
    mockedUseSession.mockReturnValue(sessionReturn(canBoSession(), 'authenticated'))
    const saved = [{ id: 's1', name: 'Quý 1', templateCode: 'DIGITIZATION_PROGRESS', updatedAt: '2026-01-01T00:00:00Z' }]
    const fetchMock = install({
      '/reports/saved/s1': {
        status: 200,
        body: {
          id: 's1', name: 'Quý 1', templateCode: 'DIGITIZATION_PROGRESS',
          params: { from: '2026-01-01', to: '2026-03-31', groupIds: [], topicTagIds: [], docTypeTagIds: [], folderId: null, granularity: 'month' },
          warnings: ['Một số tham số không còn khả dụng và đã được bỏ'],
        },
      },
      '/reports/saved': { status: 200, body: saved },
    })
    render(<ReportsPage />)
    await waitFor(() => expect(screen.getByText('Quý 1')).toBeTruthy())

    fireEvent.click(screen.getByRole('button', { name: 'Mở' }))
    await waitFor(() => expect(screen.getByText(/không còn khả dụng/)).toBeTruthy())
    expect((screen.getByLabelText('Từ ngày') as HTMLInputElement).value).toBe('2026-01-01')
    expect((screen.getByLabelText('Đến ngày') as HTMLInputElement).value).toBe('2026-03-31')
    expect((screen.getByRole('radio', { name: /Tiến độ số hoá/ }) as HTMLInputElement).checked).toBe(true)

    fireEvent.change(screen.getByLabelText('Tên báo cáo đã lưu'), { target: { value: 'Mới' } })
    fireEvent.click(screen.getByRole('button', { name: 'Lưu' }))
    await waitFor(() => expect(screen.getByText('Đã lưu báo cáo.')).toBeTruthy())
    const post = fetchMock.mock.calls.find(([u, init]) => String(u).endsWith('/reports/saved') && (init as RequestInit)?.method === 'POST')
    expect(JSON.parse(String((post![1] as RequestInit).body))).toMatchObject({ name: 'Mới', templateCode: 'DIGITIZATION_PROGRESS' })
  })
})
