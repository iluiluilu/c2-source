import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useSession } from 'next-auth/react'
import AccountsAdminPage from '@/app/admin/accounts/page'
import { ConfirmProvider } from '@/components/ConfirmDialog'
import { installFetchMock } from '@/test-utils/fetchMock'
import { canBoSession, quanTriSession } from '@/test-utils/session'
import type { AccountRow } from '@/lib/api'

vi.mock('next-auth/react', () => ({
  useSession: vi.fn(),
  signOut: vi.fn(),
}))
vi.mock('next/navigation', () => ({
  usePathname: () => '/admin/accounts',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
}))

const mockedUseSession = vi.mocked(useSession)

function useSessionReturn(data: ReturnType<typeof quanTriSession> | null, status: string) {
  return { data, status, update: vi.fn() } as unknown as ReturnType<typeof useSession>
}

function renderPage() {
  return render(
    <ConfirmProvider>
      <AccountsAdminPage />
    </ConfirmProvider>,
  )
}

function rowFor(username: string) {
  const cell = screen.getAllByText(username).find((el) => el.closest('td'))
  return (cell as HTMLElement).closest('tr') as HTMLElement
}

/** Secondary row actions live behind the row's "⋯" menu. */
function rowAction(username: string, label: string) {
  fireEvent.click(within(rowFor(username)).getByRole('button', { name: `Thao tác khác: ${username}` }))
  return within(rowFor(username)).getByRole('menuitem', { name: label })
}

const ROWS: AccountRow[] = [
  {
    id: 'acc-1',
    username: 'canbo1',
    firstName: 'Văn A',
    lastName: 'Nguyễn',
    email: 'canbo1@x.local',
    enabled: true,
    roles: ['can-bo'],
    status: 'active',
    statusReason: null,
    storageQuotaBytes: 5 * 1024 * 1024 * 1024,
  },
  {
    id: 'acc-2',
    username: 'admin1',
    firstName: 'Quản',
    lastName: 'Trị',
    email: null,
    enabled: true,
    roles: ['quan-tri'],
    status: 'active',
    statusReason: null,
    storageQuotaBytes: 10 * 1024 * 1024 * 1024,
  },
  {
    id: 'acc-3',
    username: 'locked1',
    firstName: 'Bị',
    lastName: 'Khoá',
    email: 'locked1@x.local',
    enabled: true,
    roles: ['can-bo'],
    status: 'locked',
    statusReason: 'vi phạm quy định',
    storageQuotaBytes: 5 * 1024 * 1024 * 1024,
  },
  {
    id: 'acc-4',
    username: 'disabled1',
    firstName: 'Vô',
    lastName: 'Hiệu',
    email: null,
    enabled: false,
    roles: ['lanh-dao'],
    status: 'disabled',
    statusReason: 'nghỉ việc',
    storageQuotaBytes: 5 * 1024 * 1024 * 1024,
  },
]

describe('AccountsAdminPage', () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
  })

  it('renders nothing while the session is loading', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(null, 'loading'))
    installFetchMock()
    const { container } = renderPage()
    expect(container).toBeEmptyDOMElement()
  })

  it('shows AccessDenied when there is no session', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(null, 'unauthenticated'))
    installFetchMock()
    renderPage()
    expect(screen.getByText(/không có quyền/i)).toBeTruthy()
  })

  it('shows AccessDenied for a non-admin (can-bo) session', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock()
    renderPage()
    expect(screen.getByText(/không có quyền/i)).toBeTruthy()
  })

  it('shows a loading state then renders the account list with role/status badges', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock({ '/accounts': { status: 200, body: ROWS } })
    renderPage()
    expect(screen.getByText('Đang tải…')).toBeTruthy()

    await waitFor(() => expect(screen.getByText('canbo1')).toBeTruthy())
    expect(screen.getByText('locked1')).toBeTruthy()
    expect(screen.getByText('disabled1')).toBeTruthy()
    expect(within(rowFor('canbo1')).getByText('Hoạt động')).toBeTruthy()
    expect(within(rowFor('locked1')).getByText('Đã khóa')).toBeTruthy()
    expect(within(rowFor('disabled1')).getByText('Đã vô hiệu hóa')).toBeTruthy()
  })

  it('marks the signed-in user\'s own row as "Bạn" and hides its lock/disable actions', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock({ '/accounts': { status: 200, body: ROWS } })
    renderPage()
    await waitFor(() => expect(screen.getByText('canbo1')).toBeTruthy())

    const selfRow = rowFor('admin1')
    expect(within(selfRow).getByText('Bạn')).toBeTruthy()
    fireEvent.click(within(selfRow).getByRole('button', { name: 'Thao tác khác: admin1' }))
    expect(within(selfRow).getByRole('menuitem', { name: 'Đặt lại MK' })).toBeTruthy()
    expect(within(selfRow).queryByRole('menuitem', { name: 'Khóa' })).toBeNull()
    expect(within(selfRow).queryByRole('menuitem', { name: 'Vô hiệu hóa' })).toBeNull()
    expect(within(selfRow).getByText('Sửa')).toBeTruthy()
  })

  it('filters the list by text, role and status', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock({ '/accounts': { status: 200, body: ROWS } })
    renderPage()
    await waitFor(() => expect(screen.getByText('canbo1')).toBeTruthy())

    fireEvent.change(screen.getByLabelText('Lọc theo trạng thái'), { target: { value: 'locked' } })
    expect(screen.getByText('locked1')).toBeTruthy()
    expect(screen.queryByText('canbo1')).toBeNull()

    fireEvent.change(screen.getByLabelText('Lọc theo trạng thái'), { target: { value: '' } })
    fireEvent.change(screen.getByLabelText('Tìm tài khoản'), { target: { value: 'không-có-ai' } })
    expect(screen.getByText('Không có tài khoản nào khớp bộ lọc.')).toBeTruthy()
  })

  it('fills and reveals a generated password in the create form', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock({ '/accounts': { status: 200, body: ROWS } })
    renderPage()
    await waitFor(() => expect(screen.getByText('canbo1')).toBeTruthy())

    fireEvent.click(screen.getByText('+ Tạo tài khoản'))
    fireEvent.click(screen.getByRole('button', { name: 'Sinh mật khẩu' }))
    const visible = document.querySelectorAll('input[autocomplete="new-password"]')[0] as HTMLInputElement
    expect(visible.type).toBe('text')
    expect(visible.value.length).toBe(14)
  })

  it('creates a new account, gating the submit button until the form is valid', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    const fetchMock = installFetchMock({ '/accounts': { status: 200, body: ROWS } })
    renderPage()
    await waitFor(() => expect(screen.getByText('canbo1')).toBeTruthy())

    fireEvent.click(screen.getByText('+ Tạo tài khoản'))
    const createSubmit = screen.getByRole('button', { name: 'Tạo tài khoản' })
    expect(createSubmit).toBeDisabled()

    fireEvent.change(screen.getByLabelText('Tên đăng nhập'), { target: { value: 'canbo2' } })
    fireEvent.change(screen.getByLabelText('Họ và tên đệm'), { target: { value: 'Thị B' } })
    fireEvent.change(screen.getByLabelText('Tên'), { target: { value: 'Trần' } })
    fireEvent.change(screen.getByLabelText('Mật khẩu ban đầu (≥ 8 ký tự)'), {
      target: { value: 'password1' },
    })
    fireEvent.click(screen.getByLabelText('Cán bộ'))

    expect(createSubmit).toBeEnabled()
    fireEvent.click(createSubmit)

    await waitFor(() => expect(screen.getByText('Đã tạo tài khoản.')).toBeTruthy())
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/accounts'),
      expect.objectContaining({ method: 'POST' }),
    )
    const call = fetchMock.mock.calls.find(
      ([url, init]) => String(url).endsWith('/accounts') && (init as RequestInit)?.method === 'POST',
    )
    expect(JSON.parse((call![1] as RequestInit).body as string)).toMatchObject({
      username: 'canbo2',
      firstName: 'Thị B',
      lastName: 'Trần',
      roles: ['can-bo'],
    })
  })

  it('edits an existing account', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      '/accounts/acc-1': { status: 200, body: null },
      '/accounts': { status: 200, body: ROWS },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('canbo1')).toBeTruthy())

    fireEvent.click(within(rowFor('canbo1')).getByText('Sửa'))
    await waitFor(() => expect(screen.getByText(/Sửa tài khoản: canbo1/)).toBeTruthy())

    const emailInput = screen.getByDisplayValue('canbo1@x.local')
    fireEvent.change(emailInput, { target: { value: 'new-email@x.local' } })
    fireEvent.click(screen.getByText('Lưu thay đổi'))

    await waitFor(() => expect(screen.getByText('Đã cập nhật tài khoản.')).toBeTruthy())
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/accounts/acc-1'),
      expect.objectContaining({ method: 'PATCH' }),
    )
  })

  it('locks an active account with a required reason', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      '/accounts/acc-1/status': { status: 200, body: null },
      '/accounts': { status: 200, body: ROWS },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('canbo1')).toBeTruthy())

    fireEvent.click(rowAction('canbo1', 'Khóa'))
    await waitFor(() => expect(screen.getByText(/^Khóa tài khoản: canbo1/)).toBeTruthy())

    const submit = screen.getByRole('button', { name: 'Khóa tài khoản' })
    expect(submit).toBeDisabled()
    fireEvent.change(screen.getByLabelText('Lý do (bắt buộc)'), { target: { value: 'vi phạm' } })
    expect(submit).toBeEnabled()
    fireEvent.click(submit)

    await waitFor(() => expect(screen.getByText('Đã khóa tài khoản.')).toBeTruthy())
    const call = fetchMock.mock.calls.find(([url]) => String(url).includes('/accounts/acc-1/status'))
    expect(call).toBeTruthy()
    expect(JSON.parse((call![1] as RequestInit).body as string)).toEqual({
      status: 'locked',
      reason: 'vi phạm',
    })
  })

  it('disables an active account', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      '/accounts/acc-1/status': { status: 200, body: null },
      '/accounts': { status: 200, body: ROWS },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('canbo1')).toBeTruthy())

    fireEvent.click(rowAction('canbo1', 'Vô hiệu hóa'))
    await waitFor(() => expect(screen.getByText(/Vô hiệu hóa tài khoản: canbo1/)).toBeTruthy())
    fireEvent.change(screen.getByLabelText('Lý do (bắt buộc)'), { target: { value: 'nghỉ việc' } })
    fireEvent.click(screen.getByRole('button', { name: 'Vô hiệu hóa tài khoản' }))

    await waitFor(() => expect(screen.getByText('Đã vô hiệu hóa tài khoản.')).toBeTruthy())
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/accounts/acc-1/status'),
      expect.objectContaining({ method: 'PATCH' }),
    )
  })

  it('unlocks a locked account after entering a reason', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      '/accounts/acc-3/status': { status: 200, body: null },
      '/accounts': { status: 200, body: ROWS },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('locked1')).toBeTruthy())

    fireEvent.click(rowAction('locked1', 'Mở khóa'))
    await waitFor(() => expect(screen.getByText(/^Mở khóa tài khoản: locked1/)).toBeTruthy())
    fireEvent.change(screen.getByLabelText('Lý do (bắt buộc)'), { target: { value: 'hết thời hạn khóa' } })
    fireEvent.click(screen.getByRole('button', { name: 'Mở khóa tài khoản' }))

    await waitFor(() => expect(screen.getByText('Đã mở khóa tài khoản.')).toBeTruthy())
    const call = fetchMock.mock.calls.find(([url]) => String(url).includes('/accounts/acc-3/status'))
    expect(call).toBeTruthy()
    expect(JSON.parse((call![1] as RequestInit).body as string)).toEqual({
      status: 'active',
      reason: 'hết thời hạn khóa',
    })
  })

  it('reactivates a disabled account after entering a reason', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock({
      '/accounts/acc-4/status': { status: 200, body: null },
      '/accounts': { status: 200, body: ROWS },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('disabled1')).toBeTruthy())

    fireEvent.click(rowAction('disabled1', 'Kích hoạt lại'))
    await waitFor(() => expect(screen.getByText(/^Kích hoạt lại tài khoản: disabled1/)).toBeTruthy())
    fireEvent.change(screen.getByLabelText('Lý do (bắt buộc)'), { target: { value: 'hết hạn kỷ luật' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kích hoạt lại tài khoản' }))

    await waitFor(() => expect(screen.getByText('Đã kích hoạt lại tài khoản.')).toBeTruthy())
  })

  it('cancels unlocking when the dialog is dismissed', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      '/accounts/acc-3/status': { status: 200, body: null },
      '/accounts': { status: 200, body: ROWS },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('locked1')).toBeTruthy())

    fireEvent.click(rowAction('locked1', 'Mở khóa'))
    await waitFor(() => expect(screen.getByText(/^Mở khóa tài khoản: locked1/)).toBeTruthy())
    fireEvent.click(screen.getByRole('button', { name: 'Hủy' }))

    expect(screen.queryByText(/^Mở khóa tài khoản: locked1/)).toBeNull()
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/status'))).toBe(false)
  })

  it('updates the storage quota', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      '/accounts/acc-1/quota': { status: 200, body: null },
      '/accounts': { status: 200, body: ROWS },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('canbo1')).toBeTruthy())

    fireEvent.click(rowAction('canbo1', 'Hạn mức'))
    await waitFor(() => expect(screen.getByText(/Hạn mức lưu trữ: canbo1/)).toBeTruthy())

    const quotaInput = screen.getByDisplayValue('5.0')
    fireEvent.change(quotaInput, { target: { value: '8' } })
    fireEvent.click(screen.getByText('Lưu hạn mức'))

    await waitFor(() => expect(screen.getByText('Đã cập nhật hạn mức lưu trữ.')).toBeTruthy())
    const call = fetchMock.mock.calls.find(([url]) => String(url).includes('/accounts/acc-1/quota'))
    expect(JSON.parse((call![1] as RequestInit).body as string)).toEqual({
      storageQuotaBytes: 8 * 1024 * 1024 * 1024,
    })
  })

  it('resets a password', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      '/accounts/acc-1/password': { status: 200, body: null },
      '/accounts': { status: 200, body: ROWS },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('canbo1')).toBeTruthy())

    fireEvent.click(rowAction('canbo1', 'Đặt lại MK'))
    await waitFor(() => expect(screen.getByText(/Đặt lại mật khẩu: canbo1/)).toBeTruthy())

    const submit = screen.getByRole('button', { name: 'Đặt lại mật khẩu' })
    expect(submit).toBeDisabled()
    fireEvent.change(screen.getByLabelText(/Mật khẩu mới/), { target: { value: 'newpass123' } })
    expect(submit).toBeEnabled()
    fireEvent.click(submit)

    await waitFor(() => expect(screen.getByText('Đã đặt lại mật khẩu.')).toBeTruthy())
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/accounts/acc-1/password'),
      expect.objectContaining({ method: 'PUT' }),
    )
  })

  it('shows a friendly message for a 409 LAST_ADMIN error instead of the raw API message', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock({
      '/accounts/acc-1/status': {
        status: 409,
        body: { code: 'LAST_ADMIN', message: 'raw backend message' },
      },
      '/accounts': { status: 200, body: ROWS },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('canbo1')).toBeTruthy())

    fireEvent.click(rowAction('canbo1', 'Khóa'))
    await waitFor(() => expect(screen.getByText(/^Khóa tài khoản: canbo1/)).toBeTruthy())
    fireEvent.change(screen.getByLabelText('Lý do (bắt buộc)'), { target: { value: 'vi phạm' } })
    fireEvent.click(screen.getByRole('button', { name: 'Khóa tài khoản' }))

    await waitFor(() =>
      expect(
        screen.getByText(
          'Không thể khóa quản trị viên đang hoạt động cuối cùng.',
        ),
      ).toBeTruthy(),
    )
    expect(screen.queryByText('raw backend message')).toBeNull()
  })
})
