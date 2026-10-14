import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { signIn, useSession } from 'next-auth/react'
import Home from '@/app/page'
import { installFetchMock as baseInstallFetchMock } from '@/test-utils/fetchMock'
import type { MockHandler } from '@/test-utils/fetchMock'
import { permissionsHandler, resetPermissions, withPermissions } from '@/test-utils/permissions'
import { canBoSession, lanhDaoSession, quanTriSession } from '@/test-utils/session'

vi.mock('next-auth/react', () => ({
  useSession: vi.fn(),
  signIn: vi.fn(),
  signOut: vi.fn(),
}))
vi.mock('next/navigation', () => ({
  usePathname: () => '/',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
}))

const mockedUseSession = vi.mocked(useSession)

const installFetchMock = (handlers: Record<string, MockHandler> = {}) =>
  baseInstallFetchMock(withPermissions(handlers))

function useSessionReturn(data: ReturnType<typeof canBoSession> | null, status: string) {
  return { data, status, update: vi.fn() } as unknown as ReturnType<typeof useSession>
}

describe('Home (dashboard)', () => {
  afterEach(() => {
    resetPermissions()
    cleanup()
    vi.restoreAllMocks()
  })

  it('shows a loading spinner while the session is loading', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(null, 'loading'))
    const { container } = render(<Home />)
    expect(container.querySelector('.animate-spin')).toBeTruthy()
  })

  it('redirects an anonymous visit straight to Keycloak, keeping the button as a fallback', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(null, 'unauthenticated'))
    render(<Home />)
    expect(signIn).toHaveBeenCalledWith('keycloak')
    vi.mocked(signIn).mockClear()
    expect(screen.getByText('Đăng nhập hệ thống')).toBeTruthy()
    const button = screen.getByRole('button', { name: 'Đăng nhập' })
    fireEvent.click(button)
    expect(signIn).toHaveBeenCalledWith('keycloak')
  })

  it('renders the dashboard with quick-access tiles for a can-bo user', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock()
    render(<Home />)

    expect(screen.getByRole('heading', { name: 'Bảng điều khiển', level: 1 })).toBeTruthy()
    expect(screen.getByRole('heading', { name: /^Xin chào/, level: 2 })).toBeTruthy()
    expect(screen.getAllByText(/Cán bộ/).length).toBeGreaterThan(0)

    const quickAccess = screen.getByText('Truy cập nhanh').nextElementSibling as HTMLElement
    // upload/digitization/trash are permission-gated: wait for /me/permissions.
    // can-bo sees personal, upload, documents, search, semantic-search, groups, digitization, trash.
    await waitFor(() => expect(within(quickAccess).getAllByRole('link')).toHaveLength(8))
    expect(within(quickAccess).getByRole('link', { name: /Nạp dữ liệu/ })).toHaveAttribute('href', '/upload')
    expect(within(quickAccess).getByRole('link', { name: /Kho tài liệu/ })).toHaveAttribute('href', '/documents')
  })

  it('shows Nạp dữ liệu in quick access for a lanh-dao user holding document.upload (AC-2)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(lanhDaoSession(), 'authenticated'))
    installFetchMock()
    render(<Home />)

    const quickAccess = screen.getByText('Truy cập nhanh').nextElementSibling as HTMLElement
    await waitFor(() =>
      expect(within(quickAccess).getByRole('link', { name: /Nạp dữ liệu/ })).toHaveAttribute('href', '/upload'),
    )
  })

  it('hides Nạp dữ liệu for a lanh-dao user whose document.upload was revoked (AC-3)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(lanhDaoSession(), 'authenticated'))
    const fetchMock = installFetchMock({ '/me/permissions': permissionsHandler(['document.read']) })
    render(<Home />)

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/me/permissions'), expect.anything()),
    )
    // let the resolved response flush into the store before asserting absence
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0))
    })
    const quickAccess = screen.getByText('Truy cập nhanh').nextElementSibling as HTMLElement
    expect(within(quickAccess).queryByRole('link', { name: /Nạp dữ liệu/ })).toBeNull()
  })

  it('renders a narrower quick-access list for a quan-tri user', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock()
    render(<Home />)

    expect(screen.getAllByText(/Quản trị/).length).toBeGreaterThan(0)

    const quickAccess = screen.getByText('Truy cập nhanh').nextElementSibling as HTMLElement
    // quan-tri sees personal (ALL_ROLES) + admin-folders + audit enabled (non-dashboard, non-hidden)
    expect(within(quickAccess).getAllByRole('link')).toHaveLength(3)
    expect(within(quickAccess).getByRole('link', { name: /Quản trị hệ thống/ })).toHaveAttribute(
      'href',
      '/admin/folders',
    )
    expect(within(quickAccess).getByRole('link', { name: /Nhật ký hoạt động/ })).toHaveAttribute(
      'href',
      '/admin/audit',
    )
    expect(within(quickAccess).queryByRole('link', { name: /Nạp dữ liệu/ })).toBeNull()
  })
})
