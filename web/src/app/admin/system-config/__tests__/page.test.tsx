import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useSession } from 'next-auth/react'
import SystemConfigPage from '@/app/admin/system-config/page'
import { installFetchMock } from '@/test-utils/fetchMock'
import { canBoSession, quanTriSession } from '@/test-utils/session'
import type { SystemConfigRow } from '@/lib/api'

vi.mock('next-auth/react', () => ({
  useSession: vi.fn(),
  signOut: vi.fn(),
}))
vi.mock('next/navigation', () => ({
  usePathname: () => '/admin/system-config',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
}))

const mockedUseSession = vi.mocked(useSession)

function useSessionReturn(data: ReturnType<typeof quanTriSession> | null, status: string) {
  return { data, status, update: vi.fn() } as unknown as ReturnType<typeof useSession>
}

const ROWS: SystemConfigRow[] = [
  {
    key: 'max_upload_mb',
    description: 'Giới hạn dung lượng tải lên',
    valueType: 'NUMBER',
    unit: null,
    value: 100,
    defaultValue: 50,
    usingDefault: false,
    sensitive: false,
  },
  {
    key: 'secret_key',
    description: 'Khóa bí mật',
    valueType: 'STRING',
    unit: null,
    value: 'abc',
    defaultValue: 'default',
    usingDefault: false,
    sensitive: true,
  },
]

describe('SystemConfigPage', () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
  })

  it('renders nothing while the session is loading', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(null, 'loading'))
    installFetchMock()
    const { container } = render(<SystemConfigPage />)
    expect(container).toBeEmptyDOMElement()
  })

  it('shows AccessDenied when there is no session', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(null, 'unauthenticated'))
    installFetchMock()
    render(<SystemConfigPage />)
    expect(screen.getByText(/không có quyền/i)).toBeTruthy()
  })

  it('shows AccessDenied for a non-admin (can-bo) session', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock()
    render(<SystemConfigPage />)
    expect(screen.getByText(/không có quyền/i)).toBeTruthy()
  })

  it('shows a loading row before the config list arrives, then renders it', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock({
      '/admin/system-config': { status: 200, body: ROWS },
    })
    render(<SystemConfigPage />)
    expect(screen.getByText('Đang tải…')).toBeTruthy()

    await waitFor(() => expect(screen.getByText('Giới hạn dung lượng tải lên')).toBeTruthy())
    expect(screen.getByText('Khóa bí mật')).toBeTruthy()
    expect(screen.getByText('Nhạy cảm')).toBeTruthy()
  })

  it('edits and saves a non-sensitive value', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      '/admin/system-config/max_upload_mb': { status: 200, body: null },
      '/admin/system-config': { status: 200, body: ROWS },
    })
    render(<SystemConfigPage />)
    await waitFor(() => expect(screen.getByText('Giới hạn dung lượng tải lên')).toBeTruthy())

    fireEvent.click(screen.getAllByText('Sửa')[0])
    const input = screen.getByDisplayValue('100')
    fireEvent.change(input, { target: { value: '200' } })
    fireEvent.click(screen.getByText('Lưu'))

    await waitFor(() => expect(screen.getByText('Đã cập nhật "max_upload_mb".')).toBeTruthy())
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/admin/system-config/max_upload_mb'),
      expect.objectContaining({ method: 'PATCH' }),
    )
  })

  it('shows a client-side validation error without calling the API', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      '/admin/system-config': { status: 200, body: ROWS },
    })
    render(<SystemConfigPage />)
    await waitFor(() => expect(screen.getByText('Giới hạn dung lượng tải lên')).toBeTruthy())

    fireEvent.click(screen.getAllByText('Sửa')[0])
    fireEvent.change(screen.getByDisplayValue('100'), { target: { value: 'not-a-number' } })
    const callsBefore = fetchMock.mock.calls.length
    fireEvent.click(screen.getByText('Lưu'))

    await waitFor(() => expect(screen.getByText('Giá trị phải là số nguyên')).toBeTruthy())
    expect(fetchMock.mock.calls.length).toBe(callsBefore)
  })

  it('opens the two-step confirm modal on a 409 for a sensitive key, then applies it', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      '/admin/system-config/secret_key/confirm': { status: 200, body: null },
      '/admin/system-config/secret_key': {
        status: 409,
        body: { confirmationToken: 'tok-1' },
      },
      '/admin/system-config': { status: 200, body: ROWS },
    })
    render(<SystemConfigPage />)
    await waitFor(() => expect(screen.getByText('Khóa bí mật')).toBeTruthy())

    fireEvent.click(screen.getAllByText('Sửa')[1])
    fireEvent.change(screen.getByDisplayValue('abc'), { target: { value: 'new-secret' } })
    fireEvent.click(screen.getByText('Lưu'))

    await waitFor(() => expect(screen.getByText('Xác nhận thay đổi')).toBeTruthy())
    const confirmModal = screen.getByText('Xác nhận thay đổi').closest('div') as HTMLElement
    expect(within(confirmModal).getByText(/Khóa bí mật/)).toBeTruthy()
    expect(confirmModal.textContent).toContain('new-secret')

    fireEvent.click(screen.getByText('Xác nhận áp dụng'))
    await waitFor(() =>
      expect(screen.getByText('Đã xác nhận và cập nhật "secret_key".')).toBeTruthy(),
    )
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/admin/system-config/secret_key/confirm'),
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('shows the API error message when a save fails for a non-409 reason', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock({
      '/admin/system-config/max_upload_mb': {
        status: 500,
        body: { message: 'Lỗi máy chủ nội bộ.' },
      },
      '/admin/system-config': { status: 200, body: ROWS },
    })
    render(<SystemConfigPage />)
    await waitFor(() => expect(screen.getByText('Giới hạn dung lượng tải lên')).toBeTruthy())

    fireEvent.click(screen.getAllByText('Sửa')[0])
    fireEvent.change(screen.getByDisplayValue('100'), { target: { value: '200' } })
    fireEvent.click(screen.getByText('Lưu'))

    await waitFor(() => expect(screen.getByText('Lỗi máy chủ nội bộ.')).toBeTruthy())
  })

  it('opens history, shows entries, and rolls back to a previous value', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      '/admin/system-config/max_upload_mb/history': {
        status: 200,
        body: [
          { oldValue: 50, newValue: 100, actor: 'admin1', createdAt: '2026-01-01T00:00:00.000Z' },
        ],
      },
      '/admin/system-config/max_upload_mb/rollback': { status: 200, body: null },
      '/admin/system-config': { status: 200, body: ROWS },
    })
    render(<SystemConfigPage />)
    await waitFor(() => expect(screen.getByText('Giới hạn dung lượng tải lên')).toBeTruthy())

    fireEvent.click(screen.getAllByText('Lịch sử')[0])
    await waitFor(() => expect(screen.getByText('Lịch sử thay đổi')).toBeTruthy())
    const modal = screen.getByText('Lịch sử thay đổi').closest('div')!.parentElement as HTMLElement
    expect(within(modal).getByText('50')).toBeTruthy()
    expect(within(modal).getByText(/admin1/)).toBeTruthy()

    fireEvent.click(screen.getByText('Khôi phục về giá trị này'))
    await waitFor(() => expect(screen.getByText('Đã khôi phục "max_upload_mb".')).toBeTruthy())
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/admin/system-config/max_upload_mb/rollback'),
      expect.objectContaining({ method: 'POST' }),
    )
  })
})
