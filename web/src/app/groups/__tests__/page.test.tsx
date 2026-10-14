import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useSession } from 'next-auth/react'
import GroupsPage from '@/app/groups/page'
import { installFetchMock } from '@/test-utils/fetchMock'
import { canBoSession, quanTriSession } from '@/test-utils/session'
import type { GroupRow } from '@/lib/api'

vi.mock('next-auth/react', () => ({
  useSession: vi.fn(),
  signOut: vi.fn(),
}))
vi.mock('next/navigation', () => ({
  usePathname: () => '/groups',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
}))

const mockedUseSession = vi.mocked(useSession)

function useSessionReturn(data: ReturnType<typeof canBoSession> | null, status: string) {
  return { data, status, update: vi.fn() } as unknown as ReturnType<typeof useSession>
}

const ROWS: GroupRow[] = [
  {
    id: 'group-1',
    name: 'Phòng Kế hoạch',
    description: 'Nhóm chia sẻ tài liệu kế hoạch',
    ownerSub: 'user-canbo1',
    createdAt: '2026-01-01T00:00:00.000Z',
    updatedAt: '2026-01-01T00:00:00.000Z',
    memberCount: 4,
    documentCount: 12,
    myRole: 'owner',
  },
]

describe('GroupsPage', () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
  })

  it('renders nothing while the session is loading', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(null, 'loading'))
    installFetchMock()
    const { container } = render(<GroupsPage />)
    expect(container).toBeEmptyDOMElement()
  })

  it('shows AccessDenied when there is no session', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(null, 'unauthenticated'))
    installFetchMock()
    render(<GroupsPage />)
    expect(screen.getByText(/không có quyền/i)).toBeTruthy()
  })

  it('shows AccessDenied for a quan-tri session (groups is R1/R2 only)', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock()
    render(<GroupsPage />)
    expect(screen.getByText(/không có quyền/i)).toBeTruthy()
  })

  it('shows a loading row then renders the group list for an authorized (can-bo) user', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({ '/groups': { status: 200, body: ROWS } })
    render(<GroupsPage />)
    expect(screen.getByText('Đang tải…')).toBeTruthy()

    await waitFor(() => expect(screen.getByText('Phòng Kế hoạch')).toBeTruthy())
    const row = screen.getByText('Phòng Kế hoạch').closest('tr') as HTMLElement
    expect(row.textContent).toContain('Chủ nhóm')
    expect(row.textContent).toContain('4')
    expect(row.textContent).toContain('12')
    expect(screen.getByText('Nhóm chia sẻ tài liệu kế hoạch')).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Xem' })).toHaveAttribute('href', '/groups/group-1')
  })

  it('shows the empty state when the user belongs to no groups', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({ '/groups': { status: 200, body: [] } })
    render(<GroupsPage />)
    await waitFor(() => expect(screen.getByText('Bạn chưa thuộc nhóm nào.')).toBeTruthy())
  })

  it('creates a new group and reloads the list', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const fetchMock = installFetchMock({ '/groups': { status: 200, body: ROWS } })
    render(<GroupsPage />)
    await waitFor(() => expect(screen.getByText('Phòng Kế hoạch')).toBeTruthy())

    fireEvent.click(screen.getByText('+ Tạo nhóm'))
    const submit = screen.getByRole('button', { name: 'Tạo nhóm' })
    expect(submit).toBeDisabled()

    fireEvent.change(screen.getByPlaceholderText('VD: Phòng Kế hoạch'), {
      target: { value: 'Phòng Nhân sự' },
    })
    expect(submit).toBeEnabled()
    fireEvent.click(submit)

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining('/groups'),
        expect.objectContaining({ method: 'POST' }),
      ),
    )
    // form closes on success
    await waitFor(() => expect(screen.queryByText('Tạo nhóm mới')).toBeNull())
  })

  it('shows a friendly message for a 422 GROUP_LIMIT_EXCEEDED error', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({
      '/groups': (url, _init, method) =>
        method === 'POST'
          ? {
              status: 422,
              body: { code: 'GROUP_LIMIT_EXCEEDED', message: 'Đã đạt giới hạn số nhóm.' },
            }
          : { status: 200, body: ROWS },
    })
    render(<GroupsPage />)
    await waitFor(() => expect(screen.getByText('Phòng Kế hoạch')).toBeTruthy())

    fireEvent.click(screen.getByText('+ Tạo nhóm'))
    fireEvent.change(screen.getByPlaceholderText('VD: Phòng Kế hoạch'), {
      target: { value: 'Nhóm mới' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Tạo nhóm' }))

    await waitFor(() => expect(screen.getByText('Đã đạt giới hạn số nhóm.')).toBeTruthy())
  })

  it('shows a friendly message for a 409 duplicate-name error', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({
      '/groups': (url, _init, method) =>
        method === 'POST' ? { status: 409, body: null } : { status: 200, body: ROWS },
    })
    render(<GroupsPage />)
    await waitFor(() => expect(screen.getByText('Phòng Kế hoạch')).toBeTruthy())

    fireEvent.click(screen.getByText('+ Tạo nhóm'))
    fireEvent.change(screen.getByPlaceholderText('VD: Phòng Kế hoạch'), {
      target: { value: 'Phòng Kế hoạch' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Tạo nhóm' }))

    await waitFor(() => expect(screen.getByText('Đã tồn tại nhóm khác với tên này.')).toBeTruthy())
  })
})
