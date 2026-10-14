import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useSession } from 'next-auth/react'
import RbacAdminPage from '@/app/admin/rbac/page'
import { installFetchMock } from '@/test-utils/fetchMock'
import { canBoSession, quanTriSession } from '@/test-utils/session'
import type { RbacPermissionRow, RbacRoleRow } from '@/lib/api'

vi.mock('next-auth/react', () => ({
  useSession: vi.fn(),
  signOut: vi.fn(),
}))
vi.mock('next/navigation', () => ({
  usePathname: () => '/admin/rbac',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
}))

const mockedUseSession = vi.mocked(useSession)

function useSessionReturn(data: ReturnType<typeof canBoSession> | null, status: string) {
  return { data, status, update: vi.fn() } as unknown as ReturnType<typeof useSession>
}

const ROLES: RbacRoleRow[] = [
  { code: 'CAN_BO', displayName: 'Cán bộ', permissionCodes: ['document.read'] },
  { code: 'LANH_DAO', displayName: 'Lãnh đạo', permissionCodes: ['document.read'] },
  { code: 'QUAN_TRI', displayName: 'Quản trị', permissionCodes: ['admin.manage'] },
]

const PERMISSIONS: RbacPermissionRow[] = [
  { code: 'document.read', description: 'Xem tài liệu' },
  { code: 'document.write', description: 'Sửa tài liệu' },
  { code: 'admin.manage', description: 'Quản trị hệ thống' },
]

function installRbacFetch(overrides: Record<string, unknown> = {}) {
  return installFetchMock({
    // More specific patterns must be registered before the generic
    // '/admin/rbac/roles' one - installFetchMock matches by substring,
    // first-match-wins, and '/admin/rbac/roles' is itself a substring of
    // every roles/{code}/... URL.
    ...overrides,
    '/admin/rbac/roles': { status: 200, body: ROLES },
    '/admin/rbac/permissions': { status: 200, body: PERMISSIONS },
  })
}

describe('RbacAdminPage', () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
  })

  it('renders nothing while the session is loading', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(null, 'loading'))
    const { container } = render(<RbacAdminPage />)
    expect(container).toBeEmptyDOMElement()
  })

  it('shows AccessDenied for a non-admin user', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    render(<RbacAdminPage />)
    expect(screen.getByText(/không có quyền/i)).toBeTruthy()
  })

  it('loads and renders the permission matrix for a quan-tri user', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installRbacFetch()
    render(<RbacAdminPage />)

    expect(screen.getByText('Đang tải…')).toBeTruthy()
    await waitFor(() => expect(screen.getByText('Xem tài liệu')).toBeTruthy())
    expect(screen.getByText('Sửa tài liệu')).toBeTruthy()
    expect(screen.getByRole('columnheader', { name: /Cán bộ/ })).toBeTruthy()
  })

  it('hides the QUAN_TRI checkbox for a permission flagged adminAssignable=false (export-reporting AC-5)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock({
      '/admin/rbac/roles': { status: 200, body: ROLES },
      '/admin/rbac/permissions': {
        status: 200,
        body: [...PERMISSIONS, { code: 'report.read', description: 'Xem báo cáo', adminAssignable: false }],
      },
    })
    render(<RbacAdminPage />)
    await waitFor(() => expect(screen.getByText('Xem báo cáo')).toBeTruthy())

    const row = screen.getByText('Xem báo cáo').closest('tr') as HTMLElement
    // CAN_BO and LANH_DAO keep their checkboxes; the QUAN_TRI cell has none
    expect(within(row).getAllByRole('checkbox')).toHaveLength(2)
    expect(within(row).getByLabelText('Không thể gán cho Quản trị hệ thống')).toBeTruthy()
    // an assignable permission still has all 3
    const other = screen.getByText('Sửa tài liệu').closest('tr') as HTMLElement
    expect(within(other).getAllByRole('checkbox')).toHaveLength(3)
  })

  it('toggling a checkbox reveals Lưu/Huỷ for that role column only', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installRbacFetch()
    render(<RbacAdminPage />)
    await waitFor(() => expect(screen.getByText('Sửa tài liệu')).toBeTruthy())

    // "document.write" row, first column = CAN_BO
    const writeRow = screen.getByText('Sửa tài liệu').closest('tr') as HTMLElement
    const checkboxes = within(writeRow).getAllByRole('checkbox')
    fireEvent.click(checkboxes[0])

    expect(screen.getAllByRole('button', { name: 'Lưu' }).length).toBe(1)
    expect(screen.getByRole('button', { name: 'Huỷ' })).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Huỷ' }))
    expect(screen.queryByRole('button', { name: 'Lưu' })).toBeNull()
  })

  it('opens the preview-impact confirm dialog and saves', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    const fetchMock = installRbacFetch({
      '/admin/rbac/roles/CAN_BO/preview-impact': {
        status: 200,
        body: { affectedAccountCount: 3, permissionsAdded: ['document.write'], permissionsRemoved: [] },
      },
      '/admin/rbac/roles/CAN_BO/permissions': {
        status: 200,
        body: { roleCode: 'CAN_BO', permissionCodes: ['document.read', 'document.write'], affectedAccountCount: 3 },
      },
    })
    render(<RbacAdminPage />)
    await waitFor(() => expect(screen.getByText('Sửa tài liệu')).toBeTruthy())

    const writeRow = screen.getByText('Sửa tài liệu').closest('tr') as HTMLElement
    const checkboxes = within(writeRow).getAllByRole('checkbox')
    fireEvent.click(checkboxes[0])
    fireEvent.click(screen.getByRole('button', { name: 'Lưu' }))

    await waitFor(() => expect(screen.getByText(/Xác nhận thay đổi quyền/)).toBeTruthy())
    await waitFor(() => expect(screen.getByText('Thêm quyền:')).toBeTruthy())

    fireEvent.click(screen.getByRole('button', { name: 'Xác nhận lưu' }))

    await waitFor(() => expect(screen.getByText(/Đã cập nhật quyền cho Cán bộ/)).toBeTruthy())
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/admin/rbac/roles/CAN_BO/permissions'),
      expect.objectContaining({ method: 'PUT' }),
    )
  })

  it('shows the RBAC_LOCKOUT error message on a 409 save response', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installRbacFetch({
      '/admin/rbac/roles/QUAN_TRI/preview-impact': {
        status: 200,
        body: { affectedAccountCount: 1, permissionsAdded: [], permissionsRemoved: ['admin.manage'] },
      },
      '/admin/rbac/roles/QUAN_TRI/permissions': {
        status: 409,
        body: { code: 'RBAC_LOCKOUT', message: 'Không thể gỡ quyền quản trị cuối cùng.' },
      },
    })
    render(<RbacAdminPage />)
    await waitFor(() => expect(screen.getByTitle('admin.manage')).toBeTruthy())

    const adminRow = screen.getByTitle('admin.manage').closest('tr') as HTMLElement
    const checkboxes = within(adminRow).getAllByRole('checkbox')
    fireEvent.click(checkboxes[2]) // QUAN_TRI column
    fireEvent.click(screen.getByRole('button', { name: 'Lưu' }))

    await waitFor(() => expect(screen.getByText(/Xác nhận thay đổi quyền/)).toBeTruthy())
    fireEvent.click(screen.getByRole('button', { name: 'Xác nhận lưu' }))

    await waitFor(() => expect(screen.getByText('Không thể gỡ quyền quản trị cuối cùng.')).toBeTruthy())
  })
})
