import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useSession } from 'next-auth/react'
import DocumentOwnershipAdminPage from '@/app/admin/document-ownership/page'
import { installFetchMock } from '@/test-utils/fetchMock'
import { canBoSession, quanTriSession } from '@/test-utils/session'
import type { AccountRow, DocumentOwnershipPageResponse } from '@/lib/api'

vi.mock('next-auth/react', () => ({
  useSession: vi.fn(),
  signOut: vi.fn(),
}))
vi.mock('next/navigation', () => ({
  usePathname: () => '/admin/document-ownership',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
}))

const mockedUseSession = vi.mocked(useSession)

function useSessionReturn(data: ReturnType<typeof canBoSession> | null, status: string) {
  return { data, status, update: vi.fn() } as unknown as ReturnType<typeof useSession>
}

const ACCOUNTS: AccountRow[] = [
  {
    id: 'uploader-1',
    username: 'canbo1',
    firstName: 'Van',
    lastName: 'A',
    email: 'canbo1@test.local',
    enabled: true,
    roles: ['can-bo'],
    status: 'active',
    statusReason: null,
    storageQuotaBytes: 1_000_000,
  },
  {
    id: 'canbo2-id',
    username: 'canbo2',
    firstName: 'Van',
    lastName: 'B',
    email: 'canbo2@test.local',
    enabled: true,
    roles: ['can-bo'],
    status: 'active',
    statusReason: null,
    storageQuotaBytes: 1_000_000,
  },
]

const OWNERSHIP_PAGE: DocumentOwnershipPageResponse = {
  content: [
    {
      documentId: 'doc-1',
      originalName: 'bao-cao.pdf',
      uploaderSub: 'uploader-1',
      digitizationStatus: {
        ocrStatus: 'confirmed',
        sttStatus: 'not_applicable',
        sedStatus: 'not_applicable',
        videoSummaryStatus: 'not_applicable',
      },
    },
  ],
  totalElements: 1,
  page: 0,
  size: 50,
  totalPages: 1,
}

describe('DocumentOwnershipAdminPage', () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
  })

  it('renders nothing while the session is loading', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(null, 'loading'))
    const { container } = render(<DocumentOwnershipAdminPage />)
    expect(container).toBeEmptyDOMElement()
  })

  it('shows AccessDenied for a non-admin user', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    render(<DocumentOwnershipAdminPage />)
    expect(screen.getByText(/không có quyền/i)).toBeTruthy()
  })

  it('shows the empty-search prompt before an uploader is chosen', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock({ '/accounts': { status: 200, body: ACCOUNTS } })
    render(<DocumentOwnershipAdminPage />)
    await waitFor(() =>
      expect(screen.getByText('Chọn một tài khoản người nạp để bắt đầu tìm kiếm.')).toBeTruthy(),
    )
  })

  it('searches documents by uploader and renders results', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock({
      '/accounts': { status: 200, body: ACCOUNTS },
      '/admin/document-ownership': { status: 200, body: OWNERSHIP_PAGE },
    })
    render(<DocumentOwnershipAdminPage />)
    await waitFor(() => expect(screen.getByRole('option', { name: /^canbo1/ })).toBeTruthy())

    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'uploader-1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Tìm tài liệu' }))

    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())
    expect(screen.getByText(/OCR: Đã xác nhận/)).toBeTruthy()
  })

  it('opens the transfer dialog and submits a transfer', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      '/accounts': { status: 200, body: ACCOUNTS },
      '/admin/document-ownership/doc-1/transfer': {
        status: 200,
        body: {
          documentId: 'doc-1',
          fromUploaderSub: 'uploader-1',
          toUploaderSub: 'canbo2-id',
          transferredAt: '2026-01-01T00:00:00.000Z',
        },
      },
      '/admin/document-ownership': { status: 200, body: OWNERSHIP_PAGE },
    })
    render(<DocumentOwnershipAdminPage />)
    await waitFor(() => expect(screen.getByRole('option', { name: /^canbo1/ })).toBeTruthy())

    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'uploader-1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Tìm tài liệu' }))
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())

    fireEvent.click(screen.getByRole('button', { name: 'Chuyển quyền sở hữu' }))
    expect(screen.getByRole('heading', { name: 'Chuyển quyền sở hữu' })).toBeTruthy()

    const recipientSelect = screen.getAllByRole('combobox')[1]
    fireEvent.change(recipientSelect, { target: { value: 'canbo2-id' } })
    fireEvent.click(screen.getByRole('button', { name: 'Xác nhận chuyển' }))

    await waitFor(() =>
      expect(screen.getByText('Đã chuyển quyền sở hữu tài liệu từ uploader-1 sang canbo2-id.')).toBeTruthy(),
    )
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/admin/document-ownership/doc-1/transfer'),
      expect.objectContaining({ method: 'POST' }),
    )
    // AC-4: the transferred row disappears from the currently-filtered list.
    expect(screen.queryByText('bao-cao.pdf')).toBeNull()
  })

  describe('LANH_DAO recipients (ADR-4)', () => {
    const LANH_DAO_ACCOUNT: AccountRow = {
      ...ACCOUNTS[1],
      id: 'lanhdao-id',
      username: 'lanhdao1',
      roles: ['lanh-dao'],
    }

    async function setupTransferDialog(rolePermissions: string[]) {
      installFetchMock({
        '/accounts': { status: 200, body: [...ACCOUNTS, LANH_DAO_ACCOUNT] },
        '/admin/rbac/roles': {
          status: 200,
          body: [{ code: 'LANH_DAO', displayName: 'Lãnh đạo', permissionCodes: rolePermissions }],
        },
        '/admin/document-ownership': { status: 200, body: OWNERSHIP_PAGE },
      })
      render(<DocumentOwnershipAdminPage />)
      await waitFor(() => expect(screen.getByRole('option', { name: /^canbo1/ })).toBeTruthy())
      fireEvent.change(screen.getByRole('combobox'), { target: { value: 'uploader-1' } })
      fireEvent.click(screen.getByRole('button', { name: 'Tìm tài liệu' }))
      await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())
      fireEvent.click(screen.getByRole('button', { name: 'Chuyển quyền sở hữu' }))
    }

    it('lists a lanh-dao account as a recipient and warns when the role lacks document.upload', async () => {
      mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
      await setupTransferDialog(['document.read'])
      const recipientSelect = screen.getAllByRole('combobox')[1]
      await waitFor(() => expect(within(recipientSelect).getByRole('option', { name: 'lanhdao1' })).toBeTruthy())
      fireEvent.change(recipientSelect, { target: { value: 'lanhdao-id' } })
      expect(await screen.findByRole('alert')).toHaveTextContent(/không có quyền nạp dữ liệu/)
    })

    it('does not warn when the lanh-dao role holds document.upload', async () => {
      mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
      await setupTransferDialog(['document.read', 'document.upload'])
      const recipientSelect = screen.getAllByRole('combobox')[1]
      await waitFor(() => expect(within(recipientSelect).getByRole('option', { name: 'lanhdao1' })).toBeTruthy())
      fireEvent.change(recipientSelect, { target: { value: 'lanhdao-id' } })
      expect(screen.queryByRole('alert')).toBeNull()
    })
  })

  it('narrows the uploader list with the filter box', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock({ '/accounts': { status: 200, body: ACCOUNTS } })
    render(<DocumentOwnershipAdminPage />)
    await waitFor(() => expect(screen.getByRole('option', { name: /^canbo1/ })).toBeTruthy())

    fireEvent.change(screen.getByLabelText('Lọc danh sách người nạp'), { target: { value: 'không-có' } })
    expect(screen.queryByRole('option', { name: /^canbo1/ })).toBeNull()
    expect(screen.getByRole('option', { name: '— Không có tài khoản khớp —' })).toBeTruthy()
  })

  it('shows the API error message when the search fails', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock({
      '/accounts': { status: 200, body: ACCOUNTS },
      '/admin/document-ownership': { status: 500, body: { message: 'Lỗi máy chủ.' } },
    })
    render(<DocumentOwnershipAdminPage />)
    await waitFor(() => expect(screen.getByRole('option', { name: /^canbo1/ })).toBeTruthy())

    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'uploader-1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Tìm tài liệu' }))

    await waitFor(() => expect(screen.getByText('Lỗi máy chủ.')).toBeTruthy())
  })
})
