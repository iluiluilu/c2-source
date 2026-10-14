import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useSession } from 'next-auth/react'
import GroupDetailPage from '@/app/groups/[id]/page'
import { ConfirmProvider } from '@/components/ConfirmDialog'
import { installFetchMock } from '@/test-utils/fetchMock'
import { canBoSession, makeSession, quanTriSession } from '@/test-utils/session'
import type {
  DocumentRow,
  GroupDocumentsPageResponse,
  GroupMemberRow,
  GroupRow,
  UserDirectoryEntry,
} from '@/lib/api'
import { Role } from '@/lib/roles'

const pushMock = vi.hoisted(() => vi.fn())

vi.mock('next-auth/react', () => ({
  useSession: vi.fn(),
  signOut: vi.fn(),
}))
vi.mock('next/navigation', () => ({
  usePathname: () => '/groups/group-1',
  useRouter: () => ({ push: pushMock, replace: vi.fn(), back: vi.fn() }),
  useParams: () => ({ id: 'group-1' }),
}))

const mockedUseSession = vi.mocked(useSession)

function useSessionReturn(data: ReturnType<typeof canBoSession> | null, status: string) {
  return { data, status, update: vi.fn() } as unknown as ReturnType<typeof useSession>
}

function renderPage() {
  return render(
    <ConfirmProvider>
      <GroupDetailPage />
    </ConfirmProvider>,
  )
}

const ownerSession = () => makeSession({ username: 'canbo1', roles: [Role.CAN_BO], sub: 'user-owner' })
const memberSession = () => makeSession({ username: 'canbo2', roles: [Role.CAN_BO], sub: 'user-member' })

const GROUP: GroupRow = {
  id: 'group-1',
  name: 'Phòng Kế hoạch',
  description: 'Mô tả nhóm',
  ownerSub: 'user-owner',
  createdAt: '2026-01-01T00:00:00.000Z',
  updatedAt: '2026-01-01T00:00:00.000Z',
}

const MEMBERS_TWO_OWNERS: GroupMemberRow[] = [
  {
    userSub: 'user-owner',
    username: 'canbo1',
    displayName: 'Cán bộ Một',
    roleInGroup: 'owner',
    joinedAt: '2026-01-01T00:00:00.000Z',
  },
  {
    userSub: 'user-member',
    username: 'canbo2',
    displayName: 'Cán bộ Hai',
    roleInGroup: 'member',
    joinedAt: '2026-01-02T00:00:00.000Z',
  },
]

const MEMBERS_ONE_OWNER: GroupMemberRow[] = [
  {
    userSub: 'user-owner',
    username: 'canbo1',
    displayName: 'Cán bộ Một',
    roleInGroup: 'owner',
    joinedAt: '2026-01-01T00:00:00.000Z',
  },
]

const DOCS_EMPTY: GroupDocumentsPageResponse = {
  content: [],
  totalElements: 0,
  page: 0,
  size: 50,
  totalPages: 0,
}

const DOCS_ONE: GroupDocumentsPageResponse = {
  content: [
    {
      documentId: 'doc-1',
      title: 'Báo cáo quý 1',
      uploaderSub: 'user-owner',
      uploaderUsername: 'canbo1',
      uploaderDisplayName: 'Cán bộ Một',
      sharedBySub: 'user-owner',
      sharedByUsername: 'canbo1',
      sharedByDisplayName: 'Cán bộ Một',
      sharedAt: '2026-01-03T00:00:00.000Z',
    },
  ],
  totalElements: 1,
  page: 0,
  size: 50,
  totalPages: 1,
}

function groupHandlers(members: GroupMemberRow[], docs: GroupDocumentsPageResponse = DOCS_EMPTY) {
  return {
    '/groups/group-1/members': { status: 200, body: members },
    '/groups/group-1/documents': { status: 200, body: docs },
    '/groups': { status: 200, body: [GROUP] },
  }
}

describe('GroupDetailPage', () => {
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

  it('shows AccessDenied for a quan-tri session (groups is R1/R2 only)', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock()
    renderPage()
    expect(screen.getByText(/không có quyền/i)).toBeTruthy()
  })

  it('shows a not-found message when the group is absent from /groups', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(ownerSession(), 'authenticated'))
    installFetchMock({
      '/groups/group-1/members': { status: 200, body: [] },
      '/groups/group-1/documents': { status: 200, body: DOCS_EMPTY },
      '/groups': { status: 200, body: [] },
    })
    renderPage()
    await waitFor(() =>
      expect(screen.getByText(/Không tìm thấy nhóm này/)).toBeTruthy(),
    )
  })

  it('renders group info and members for the owner, with edit/add-member controls', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(ownerSession(), 'authenticated'))
    installFetchMock(groupHandlers(MEMBERS_TWO_OWNERS))
    renderPage()

    await waitFor(() => expect(screen.getByText('Phòng Kế hoạch')).toBeTruthy())
    expect(screen.getByText('Mô tả nhóm')).toBeTruthy()
    expect(screen.getByText('Cán bộ Một')).toBeTruthy()
    expect(screen.getByText('Cán bộ Hai')).toBeTruthy()
    expect(screen.getByText('· canbo1')).toBeTruthy()
    expect(screen.getByText('· canbo2')).toBeTruthy()
    expect(screen.getByText('Chủ nhóm', { selector: 'span' })).toBeTruthy()
    expect(screen.getByText('Thành viên', { selector: 'span' })).toBeTruthy()
    // owner-only controls
    expect(screen.getByText('Sửa')).toBeTruthy()
    expect(screen.getByPlaceholderText(/Tìm theo tên đăng nhập/)).toBeTruthy()
  })

  it('hides owner-only controls for a plain member', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(memberSession(), 'authenticated'))
    installFetchMock(groupHandlers(MEMBERS_TWO_OWNERS))
    renderPage()

    await waitFor(() => expect(screen.getByText('Phòng Kế hoạch')).toBeTruthy())
    expect(screen.queryByText('Sửa')).toBeNull()
    expect(screen.queryByPlaceholderText(/Tìm theo tên đăng nhập/)).toBeNull()
    expect(screen.queryByText('Xoá nhóm')).toBeNull()
    expect(screen.getByText('Rời nhóm')).toBeTruthy()
  })

  it('edits the group name/description', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(ownerSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      ...groupHandlers(MEMBERS_TWO_OWNERS),
      '/groups/group-1': { status: 200, body: null },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('Phòng Kế hoạch')).toBeTruthy())

    fireEvent.click(screen.getByText('Sửa'))
    const nameInput = screen.getByDisplayValue('Phòng Kế hoạch')
    fireEvent.change(nameInput, { target: { value: 'Phòng Kế hoạch 2' } })
    fireEvent.click(screen.getByText('Lưu thay đổi'))

    await waitFor(() => expect(screen.getByText('Đã cập nhật thông tin nhóm.')).toBeTruthy())
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/groups/group-1'),
      expect.objectContaining({ method: 'PATCH' }),
    )
  })

  it('adds a member found through the user picker', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(ownerSession(), 'authenticated'))
    const entry: UserDirectoryEntry = {
      sub: 'user-new',
      username: 'canbo3',
      displayName: 'Cán bộ Ba',
      role: 'can-bo',
    }
    const fetchMock = installFetchMock({
      '/users/directory': { status: 200, body: [entry] },
      '/groups/group-1/members': { status: 200, body: MEMBERS_TWO_OWNERS },
      '/groups/group-1/documents': { status: 200, body: DOCS_EMPTY },
      '/groups': { status: 200, body: [GROUP] },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('Phòng Kế hoạch')).toBeTruthy())

    fireEvent.change(screen.getByPlaceholderText(/Tìm theo tên đăng nhập/), {
      target: { value: 'canbo3' },
    })
    await waitFor(() => expect(screen.getByText('Cán bộ Ba')).toBeTruthy())
    fireEvent.click(screen.getByText('Cán bộ Ba'))

    await waitFor(() => expect(screen.getByText('Đã thêm Cán bộ Ba vào nhóm.')).toBeTruthy())
    const call = fetchMock.mock.calls.find(
      ([url, init]) =>
        String(url).includes('/groups/group-1/members') && (init as RequestInit)?.method === 'POST',
    )
    expect(call).toBeTruthy()
    expect(JSON.parse((call![1] as RequestInit).body as string)).toEqual({
      userSub: 'user-new',
      roleInGroup: 'member',
    })
  })

  it('adds a document found through the document picker', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(ownerSession(), 'authenticated'))
    const doc: DocumentRow = {
      id: 'doc-new',
      originalName: 'Kế hoạch quý 2.pdf',
      displayName: 'Kế hoạch quý 2.pdf',
      mimeType: 'application/pdf',
      size: '1024',
      sha256: 'abc',
      relativePath: 'kho_1/ke-hoach-quy-2.pdf',
      folderId: 'folder-1',
      uploaderUsername: 'canbo1',
      status: 'clean',
      createdAt: '2026-01-01T00:00:00.000Z',
    }
    const fetchMock = installFetchMock({
      '/documents?q=': { status: 200, body: [doc] },
      '/documents/doc-new/shares': { status: 200, body: null },
      ...groupHandlers(MEMBERS_TWO_OWNERS, DOCS_EMPTY),
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('Phòng Kế hoạch')).toBeTruthy())

    fireEvent.change(screen.getByPlaceholderText(/Tìm theo tên tài liệu/), {
      target: { value: 'ke hoach' },
    })
    await waitFor(() => expect(screen.getByText('Kế hoạch quý 2.pdf')).toBeTruthy())
    fireEvent.click(screen.getByText('Kế hoạch quý 2.pdf'))

    await waitFor(() =>
      expect(screen.getByText('Đã thêm "Kế hoạch quý 2.pdf" vào nhóm.')).toBeTruthy(),
    )
    const call = fetchMock.mock.calls.find(
      ([url, init]) =>
        String(url).includes('/documents/doc-new/shares') && (init as RequestInit)?.method === 'POST',
    )
    expect(call).toBeTruthy()
    expect(JSON.parse((call![1] as RequestInit).body as string)).toEqual({ groupId: 'group-1' })
  })

  it('removes a member after confirming', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(ownerSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      ...groupHandlers(MEMBERS_TWO_OWNERS),
      '/groups/group-1/members/user-member': { status: 200, body: null },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('Cán bộ Hai')).toBeTruthy())

    const memberRow = screen.getByText('Cán bộ Hai').closest('tr') as HTMLElement
    fireEvent.click(within(memberRow).getByText('Gỡ'))

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Gỡ thành viên' })).toBeTruthy())
    fireEvent.click(screen.getByRole('button', { name: 'Xác nhận' }))

    await waitFor(() => expect(screen.getByText('Đã gỡ thành viên.')).toBeTruthy())
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/groups/group-1/members/user-member'),
      expect.objectContaining({ method: 'DELETE' }),
    )
  })

  it('promotes a member to owner', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(ownerSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      ...groupHandlers(MEMBERS_TWO_OWNERS),
      '/groups/group-1/members/user-member': { status: 200, body: null },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('Cán bộ Hai')).toBeTruthy())

    const memberRow = screen.getByText('Cán bộ Hai').closest('tr') as HTMLElement
    fireEvent.click(within(memberRow).getByText('Đặt làm chủ nhóm'))

    await waitFor(() => expect(screen.getByText('Đã đặt làm chủ nhóm.')).toBeTruthy())
    const call = fetchMock.mock.calls.find(([url]) =>
      String(url).includes('/groups/group-1/members/user-member'),
    )
    expect(JSON.parse((call![1] as RequestInit).body as string)).toEqual({ roleInGroup: 'owner' })
  })

  it('deletes the group after confirming and navigates back to the list', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(ownerSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      ...groupHandlers(MEMBERS_TWO_OWNERS),
      '/groups/group-1': { status: 200, body: null },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('Phòng Kế hoạch')).toBeTruthy())

    fireEvent.click(screen.getByRole('button', { name: 'Xoá nhóm' }))
    await waitFor(() => expect(screen.getByRole('alertdialog')).toBeTruthy())
    const dialog = screen.getByRole('alertdialog')
    expect(within(dialog).getByRole('heading', { name: 'Xoá nhóm' })).toBeTruthy()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Xoá nhóm' }))

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining('/groups/group-1'),
        expect.objectContaining({ method: 'DELETE' }),
      ),
    )
    await waitFor(() => expect(pushMock).toHaveBeenCalledWith('/groups'))
  })

  it('lets a member leave the group and navigates back to the list', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(memberSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      ...groupHandlers(MEMBERS_TWO_OWNERS),
      '/groups/group-1/leave': { status: 200, body: null },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('Rời nhóm')).toBeTruthy())

    fireEvent.click(screen.getByText('Rời nhóm'))
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Rời khỏi nhóm' })).toBeTruthy())
    fireEvent.click(screen.getByRole('button', { name: 'Xác nhận' }))

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining('/groups/group-1/leave'),
        expect.objectContaining({ method: 'POST' }),
      ),
    )
    await waitFor(() => expect(pushMock).toHaveBeenCalledWith('/groups'))
  })

  it('hides the leave button for the sole remaining owner', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(ownerSession(), 'authenticated'))
    installFetchMock(groupHandlers(MEMBERS_ONE_OWNER))
    renderPage()
    await waitFor(() => expect(screen.getByText('Phòng Kế hoạch')).toBeTruthy())

    expect(screen.queryByText('Rời nhóm')).toBeNull()
    expect(screen.getByText(/Bạn là chủ nhóm duy nhất/)).toBeTruthy()
  })

  it('renders the shared documents tab', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(ownerSession(), 'authenticated'))
    installFetchMock(groupHandlers(MEMBERS_TWO_OWNERS, DOCS_ONE))
    renderPage()

    await waitFor(() => expect(screen.getByText('Báo cáo quý 1')).toBeTruthy())
    expect(screen.getByText('Tài liệu của nhóm (1)')).toBeTruthy()
  })

  it('lets the owner unshare a document from the group after confirming', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(ownerSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      '/documents/doc-1/shares/group-1': { status: 204, body: null },
      ...groupHandlers(MEMBERS_TWO_OWNERS, DOCS_ONE),
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('Báo cáo quý 1')).toBeTruthy())

    const docRow = screen.getByText('Báo cáo quý 1').closest('tr') as HTMLElement
    fireEvent.click(within(docRow).getByRole('button', { name: 'Gỡ' }))
    const dialog = await screen.findByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Gỡ' }))

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining('/documents/doc-1/shares/group-1'),
        expect.objectContaining({ method: 'DELETE' }),
      ),
    )
  })
})
