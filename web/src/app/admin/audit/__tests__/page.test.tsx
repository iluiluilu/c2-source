import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useSession } from 'next-auth/react'
import AuditAdminPage from '@/app/admin/audit/page'
import { installFetchMock } from '@/test-utils/fetchMock'
import { canBoSession, lanhDaoSession, quanTriSession } from '@/test-utils/session'
import type { AuditLogRow, AuditPageResponse } from '@/lib/api'

vi.mock('next-auth/react', () => ({
  useSession: vi.fn(),
  signOut: vi.fn(),
}))
vi.mock('next/navigation', () => ({
  usePathname: () => '/admin/audit',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
}))

const mockedUseSession = vi.mocked(useSession)

function useSessionReturn(data: ReturnType<typeof canBoSession> | null, status: string) {
  return { data, status, update: vi.fn() } as unknown as ReturnType<typeof useSession>
}

function row(overrides: Partial<AuditLogRow> = {}): AuditLogRow {
  return {
    id: 1,
    createdAt: '2026-01-01T08:00:00.000Z',
    actor: 'canbo1',
    actorSub: 'sub-1',
    action: 'document.create',
    resourceType: 'document',
    resourceId: '3fa85f64-5717-4562-b3fc-2c963f66afa6',
    outcome: 'success',
    method: 'POST',
    path: '/documents',
    statusCode: 201,
    ip: '10.0.0.1',
    metadata: { size: 123 },
    sealedAt: '2026-01-01T08:01:00.000Z',
    ...overrides,
  }
}

function pageOf(content: AuditLogRow[]): AuditPageResponse {
  return { content, totalElements: content.length, page: 0, size: 50, totalPages: 1 }
}

const AUDIT_PAGE = pageOf([row()])

/** The order the filter <select> elements are rendered in: Hành động, Loại tài nguyên. */
function getFilterSelects(): HTMLSelectElement[] {
  return Array.from(document.querySelectorAll('select'))
}

/**
 * A label like 'Tạo tài liệu' appears twice once the table has loaded: once
 * as a static <option> in the Hành động dropdown, once as the row's action
 * cell. Tests that need the cell (to click it, or to assert its presence)
 * must disambiguate; waiting for the row to load uses a different anchor
 * (the actor name) instead.
 */
function actionCell(label: string): HTMLElement {
  const match = screen.getAllByText(label).find((el) => el.tagName !== 'OPTION')
  if (!match) throw new Error(`No non-option element with text "${label}"`)
  return match
}

describe('AuditAdminPage', () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
  })

  it('renders nothing while the session is loading', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(null, 'loading'))
    const { container } = render(<AuditAdminPage />)
    expect(container).toBeEmptyDOMElement()
  })

  it('shows AccessDenied for a can-bo user (only lanh-dao/quan-tri may view)', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    render(<AuditAdminPage />)
    expect(screen.getByText(/không có quyền/i)).toBeTruthy()
  })

  it('loads and renders audit rows with Vietnamese labels for a lanh-dao user, without the verify panel', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(lanhDaoSession(), 'authenticated'))
    installFetchMock({ '/audit/verify': { status: 200, body: null }, '/audit': { status: 200, body: AUDIT_PAGE } })
    render(<AuditAdminPage />)

    expect(screen.getByText('Đang tải…')).toBeTruthy()
    await waitFor(() => expect(actionCell('Tạo tài liệu')).toBeTruthy())
    expect(screen.getByText('canbo1')).toBeTruthy()
    expect(screen.queryByText('document.create')).toBeNull() // raw code now only in the title attribute
    expect(screen.queryByText('Xác minh toàn vẹn chuỗi')).toBeNull()
    expect(screen.getByText(/Chỉ Quản trị hệ thống mới xác minh/)).toBeTruthy()
  })

  it('has no "ID tài nguyên" free-text filter, and the action/resource dropdowns drive the query', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      '/audit/verify': { status: 200, body: null },
      '/audit': { status: 200, body: AUDIT_PAGE },
    })
    render(<AuditAdminPage />)
    await waitFor(() => expect(actionCell('Tạo tài liệu')).toBeTruthy())

    expect(screen.queryByLabelText('ID tài nguyên')).toBeNull()

    const [actionSelect, resourceTypeSelect] = getFilterSelects()
    fireEvent.change(actionSelect, { target: { value: 'document.create' } })
    fireEvent.change(resourceTypeSelect, { target: { value: 'document' } })
    fireEvent.click(screen.getByRole('button', { name: 'Tìm kiếm' }))

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringMatching(/action=document\.create/),
        expect.anything(),
      ),
    )
    const lastAuditCall = fetchMock.mock.calls
      .map((c) => String(c[0]))
      .filter((u) => u.includes('/audit?'))
      .pop()
    expect(lastAuditCall).toContain('resourceType=document')
    expect(lastAuditCall).not.toContain('resourceId')
  })

  it('clears both dropdowns back to "Tất cả" on Xóa bộ lọc', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock({ '/audit/verify': { status: 200, body: null }, '/audit': { status: 200, body: AUDIT_PAGE } })
    render(<AuditAdminPage />)
    await waitFor(() => expect(actionCell('Tạo tài liệu')).toBeTruthy())

    const [actionSelect, resourceTypeSelect] = getFilterSelects()
    fireEvent.change(actionSelect, { target: { value: 'document.create' } })
    fireEvent.change(resourceTypeSelect, { target: { value: 'document' } })
    expect(actionSelect.value).toBe('document.create')

    fireEvent.click(screen.getByRole('button', { name: 'Xóa bộ lọc' }))
    expect(actionSelect.value).toBe('')
    expect(resourceTypeSelect.value).toBe('')
  })

  it('shows a shortened UUID with a copy button that copies the full id without expanding the row', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    installFetchMock({ '/audit/verify': { status: 200, body: null }, '/audit': { status: 200, body: AUDIT_PAGE } })
    render(<AuditAdminPage />)
    await waitFor(() => expect(actionCell('Tạo tài liệu')).toBeTruthy())

    const shortId = screen.getByText('3fa85f64…')
    expect(shortId.getAttribute('title')).toBe('3fa85f64-5717-4562-b3fc-2c963f66afa6')

    fireEvent.click(screen.getByRole('button', { name: 'Sao chép ID tài nguyên' }))
    expect(writeText).toHaveBeenCalledWith('3fa85f64-5717-4562-b3fc-2c963f66afa6')
    expect(screen.queryByText(/Mã hành động/)).toBeNull() // row did not expand
  })

  it('renders raw codes when action/resourceType/outcome are unrecognized', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    const unknownRow = row({
      id: 2,
      action: 'some.unknown.action',
      resourceType: 'some_unknown_type',
      resourceId: null,
      outcome: 'weird_outcome',
      statusCode: null,
      metadata: null,
    })
    installFetchMock({
      '/audit/verify': { status: 200, body: null },
      '/audit': { status: 200, body: pageOf([unknownRow]) },
    })
    render(<AuditAdminPage />)

    await waitFor(() => expect(screen.getByText('some.unknown.action')).toBeTruthy())
    expect(screen.getByText(/some_unknown_type/)).toBeTruthy()
    expect(screen.getByText('weird_outcome')).toBeTruthy()
    // resourceId null -> em dash, not a copy button
    expect(screen.queryByRole('button', { name: 'Sao chép ID tài nguyên' })).toBeNull()
  })

  it('shows "Không có quyền" for a failure + 403 row', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    const deniedRow = row({ id: 3, outcome: 'failure', statusCode: 403 })
    installFetchMock({
      '/audit/verify': { status: 200, body: null },
      '/audit': { status: 200, body: pageOf([deniedRow]) },
    })
    render(<AuditAdminPage />)
    await waitFor(() => expect(screen.getByText('Không có quyền')).toBeTruthy())
  })

  it('expands a row to show mã hành động, labeled metadata, and toggles the raw JSON', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock({ '/audit/verify': { status: 200, body: null }, '/audit': { status: 200, body: AUDIT_PAGE } })
    render(<AuditAdminPage />)
    await waitFor(() => expect(actionCell('Tạo tài liệu')).toBeTruthy())

    expect(screen.queryByText(/Method\/Path/)).toBeNull()
    fireEvent.click(actionCell('Tạo tài liệu'))
    expect(screen.getByText(/Method\/Path/)).toBeTruthy()
    expect(screen.getByText(/POST \/documents/)).toBeTruthy()
    expect(screen.getByText('Mã hành động:')).toBeTruthy()
    expect(screen.getByText('document.create')).toBeTruthy() // now only inside the expanded row
    expect(screen.getByText('Kích thước (byte):')).toBeTruthy() // metadataLabel('size')

    expect(screen.queryByText('{"size":123}')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Xem JSON gốc' }))
    expect(screen.getByText('{"size":123}')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Ẩn JSON gốc' }))
    expect(screen.queryByText('{"size":123}')).toBeNull()
  })

  it('handles a row with no resourceId, no metadata, and a null statusCode', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    const bareRow = row({
      id: 4,
      resourceType: null,
      resourceId: null,
      outcome: 'failure',
      statusCode: null,
      metadata: null,
    })
    installFetchMock({
      '/audit/verify': { status: 200, body: null },
      '/audit': { status: 200, body: pageOf([bareRow]) },
    })
    render(<AuditAdminPage />)
    await waitFor(() => expect(screen.getByText('Thất bại')).toBeTruthy())
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(2) // resourceType + resourceId

    fireEvent.click(screen.getByText('Thất bại'))
    expect(screen.queryByText(/Metadata/)).toBeNull()
  })

  it('shows the verify panel for a quan-tri user and runs a verify', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock({
      '/audit/verify': { status: 200, body: { ok: true, count: 42, brokenAtId: null, reason: null } },
      '/audit': { status: 200, body: AUDIT_PAGE },
    })
    render(<AuditAdminPage />)
    await waitFor(() => expect(screen.getByText('Xác minh toàn vẹn chuỗi')).toBeTruthy())

    const verifyButton = screen.getByRole('button', { name: 'Xác minh' })
    expect(verifyButton).toBeDisabled()

    const dateInputs = document.querySelectorAll('input[type="datetime-local"]')
    // First two datetime inputs are the row filters (Từ/Đến); the verify
    // panel's own Từ/Đến are the 3rd and 4th datetime-local inputs.
    fireEvent.change(dateInputs[2], { target: { value: '2026-01-01T00:00' } })
    fireEvent.change(dateInputs[3], { target: { value: '2026-01-02T00:00' } })
    expect(verifyButton).toBeEnabled()

    fireEvent.click(verifyButton)
    await waitFor(() =>
      expect(screen.getByText(/Hợp lệ — đã kiểm tra 42 bản ghi/)).toBeTruthy(),
    )
  })

  it('filters by actor and clears filters', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      '/audit/verify': { status: 200, body: null },
      '/audit': { status: 200, body: AUDIT_PAGE },
    })
    render(<AuditAdminPage />)
    await waitFor(() => expect(actionCell('Tạo tài liệu')).toBeTruthy())

    fireEvent.change(screen.getByPlaceholderText('VD: canbo1'), { target: { value: 'canbo1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Tìm kiếm' }))

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining('actor=canbo1'),
        expect.anything(),
      ),
    )

    fireEvent.click(screen.getByRole('button', { name: 'Xóa bộ lọc' }))
    expect(screen.getByPlaceholderText('VD: canbo1')).toHaveValue('')
  })

  it('shows the API error message when loading fails', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock({
      '/audit/verify': { status: 200, body: null },
      '/audit': { status: 500, body: { message: 'Lỗi tải nhật ký.' } },
    })
    render(<AuditAdminPage />)
    await waitFor(() => expect(screen.getByText('Lỗi tải nhật ký.')).toBeTruthy())
  })
})
