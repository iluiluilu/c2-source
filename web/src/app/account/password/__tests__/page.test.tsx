import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useSession } from 'next-auth/react'
import ChangePasswordPage from '@/app/account/password/page'
import { installFetchMock } from '@/test-utils/fetchMock'
import { canBoSession } from '@/test-utils/session'

/**
 * account-password (FR-05) reference page test - establishes the
 * page-level test pattern (mock next-auth/react + next/navigation, stub
 * global fetch) reused across the other `src/app/**\/page.tsx` tests.
 */
vi.mock('next-auth/react', () => ({
  useSession: vi.fn(),
  signOut: vi.fn(),
}))
vi.mock('next/navigation', () => ({
  usePathname: () => '/account/password',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
}))

const mockedUseSession = vi.mocked(useSession)

function useSessionReturn(data: ReturnType<typeof canBoSession> | null, status: string) {
  return { data, status, update: vi.fn() } as unknown as ReturnType<typeof useSession>
}

describe('ChangePasswordPage', () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
  })

  it('renders nothing while the session is loading', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(null, 'loading'))
    installFetchMock()
    const { container } = render(<ChangePasswordPage />)
    expect(container).toBeEmptyDOMElement()
  })

  it('shows AccessDenied when there is no session', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(null, 'unauthenticated'))
    installFetchMock()
    render(<ChangePasswordPage />)
    expect(screen.getByText(/không có quyền/i)).toBeTruthy()
  })

  it('renders the form for a signed-in can-bo user', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock()
    render(<ChangePasswordPage />)
    expect(screen.getByRole('heading', { level: 2, name: 'Đổi mật khẩu' })).toBeTruthy()
    expect(screen.getByLabelText(/mật khẩu hiện tại/i)).toBeTruthy()
  })

  it('disables submit until current/new/confirm are all valid', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock()
    render(<ChangePasswordPage />)
    const submit = screen.getByRole('button', { name: /đổi mật khẩu/i })
    expect(submit).toBeDisabled()

    fireEvent.change(screen.getByLabelText(/mật khẩu hiện tại/i), { target: { value: 'oldpass1' } })
    fireEvent.change(screen.getByLabelText(/^mật khẩu mới/i), { target: { value: 'newpass123' } })
    expect(submit).toBeDisabled()

    fireEvent.change(screen.getByLabelText(/nhập lại mật khẩu mới/i), { target: { value: 'mismatch' } })
    expect(submit).toBeDisabled()
    expect(screen.getByText(/không khớp/i)).toBeTruthy()

    fireEvent.change(screen.getByLabelText(/nhập lại mật khẩu mới/i), { target: { value: 'newpass123' } })
    expect(submit).toBeEnabled()
  })

  it('submits the new password and shows a success message', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      '/accounts/me/password': { status: 204, body: null },
    })
    render(<ChangePasswordPage />)

    fireEvent.change(screen.getByLabelText(/mật khẩu hiện tại/i), { target: { value: 'oldpass1' } })
    fireEvent.change(screen.getByLabelText(/^mật khẩu mới/i), { target: { value: 'newpass123' } })
    fireEvent.change(screen.getByLabelText(/nhập lại mật khẩu mới/i), { target: { value: 'newpass123' } })
    fireEvent.click(screen.getByRole('button', { name: /đổi mật khẩu/i }))

    await waitFor(() => expect(screen.getByText(/thành công/i)).toBeTruthy())
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/accounts/me/password'),
      expect.objectContaining({ method: 'PUT' }),
    )
    expect(screen.getByLabelText(/mật khẩu hiện tại/i)).toHaveValue('')
  })

  it('shows the API error message when the change fails', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({
      '/accounts/me/password': { status: 400, body: { message: 'Mật khẩu hiện tại không đúng.' } },
    })
    render(<ChangePasswordPage />)

    fireEvent.change(screen.getByLabelText(/mật khẩu hiện tại/i), { target: { value: 'wrongpass' } })
    fireEvent.change(screen.getByLabelText(/^mật khẩu mới/i), { target: { value: 'newpass123' } })
    fireEvent.change(screen.getByLabelText(/nhập lại mật khẩu mới/i), { target: { value: 'newpass123' } })
    fireEvent.click(screen.getByRole('button', { name: /đổi mật khẩu/i }))

    await waitFor(() => expect(screen.getByText('Mật khẩu hiện tại không đúng.')).toBeTruthy())
  })
})
