import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useSession } from 'next-auth/react'
import DocumentsPage from '@/app/documents/page'
import { ConfirmProvider } from '@/components/ConfirmDialog'
import { installFetchMock as baseInstallFetchMock } from '@/test-utils/fetchMock'
import type { MockHandler } from '@/test-utils/fetchMock'
import { permissionsHandler, resetPermissions, withPermissions } from '@/test-utils/permissions'
import { canBoSession, lanhDaoSession, quanTriSession } from '@/test-utils/session'
import type { BulkShareResponse, DocumentRow, FolderNode, GroupRow, TagRow } from '@/lib/api'

/**
 * documents/page.tsx (FR-11/FR-12 warehouse listing, FR-15 tag filter,
 * delete-restore-trash-01 Story 2 xóa mềm): role gating (R1/R2 view, R3
 * denied per NFR-07), the populated table render, folder/tag filter re-fetch
 * wiring, API error handling, and the "Xóa"/"Xóa đã chọn" confirm+trash flow
 * (R1 only).
 */
vi.mock('next-auth/react', () => ({
  useSession: vi.fn(),
  signOut: vi.fn(),
}))
vi.mock('next/navigation', () => ({
  usePathname: () => '/documents',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
}))

const mockedUseSession = vi.mocked(useSession)

const installFetchMock = (handlers: Record<string, MockHandler> = {}) =>
  baseInstallFetchMock(withPermissions(handlers))

function useSessionReturn(data: ReturnType<typeof canBoSession> | null, status: string) {
  return { data, status, update: vi.fn() } as unknown as ReturnType<typeof useSession>
}

function renderPage() {
  return render(
    <ConfirmProvider>
      <DocumentsPage />
    </ConfirmProvider>,
  )
}

function doc(overrides: Partial<DocumentRow> = {}): DocumentRow {
  return {
    id: 'doc-1',
    originalName: 'bao-cao.pdf',
    displayName: 'bao-cao.pdf',
    mimeType: 'application/pdf',
    size: '2097152', // 2.0 MB
    sha256: 'abc123',
    relativePath: '/bao-cao.pdf',
    folderId: 'folder-1',
    uploaderUsername: 'canbo1',
    status: 'clean',
    createdAt: '2026-01-01T00:00:00.000Z',
    tags: [],
    ...overrides,
  }
}

const folderTree: FolderNode[] = [{ id: 'folder-1', name: 'Phòng ban A', depth: 1, children: [] }]

const tagRows: TagRow[] = [
  { id: 'tag-1', kind: 'topic', label: 'Chủ đề A', createdByUsername: 'canbo1', createdAt: '2026-01-01T00:00:00.000Z' },
]

const groupRows: GroupRow[] = [
  {
    id: 'group-1',
    name: 'Phòng Kế hoạch',
    description: null,
    ownerSub: 'canbo1',
    createdAt: '2026-01-01T00:00:00.000Z',
    updatedAt: '2026-01-01T00:00:00.000Z',
  },
]

/** Row checkboxes only - not the header's "chọn tất cả". */
const ROW_CHECKBOX = /^Chọn (?!tất cả)/

describe('DocumentsPage', () => {
  afterEach(() => {
    resetPermissions()
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

  it('shows AccessDenied for quan-tri (NFR-07: documents hidden from admins)', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock()
    renderPage()
    expect(screen.getByText(/không có quyền/i)).toBeTruthy()
  })

  it('loads and renders documents, folders and tags for a can-bo user', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({
      '/folders/tree': { status: 200, body: folderTree },
      '/tags': { status: 200, body: tagRows },
      '/documents': { status: 200, body: [doc()] },
    })
    renderPage()

    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())
    expect(screen.getByText('Phòng ban A')).toBeTruthy()
    expect(screen.getByText('Sạch')).toBeTruthy()
    expect(screen.getByText('2.0 MB')).toBeTruthy()
  })

  it('renders the same content for a lanh-dao user (R2 read access)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(lanhDaoSession(), 'authenticated'))
    installFetchMock({
      '/folders/tree': { status: 200, body: [] },
      '/tags': { status: 200, body: [] },
      '/documents': { status: 200, body: [doc()] },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())
  })

  it('re-fetches with folderId when a folder filter is clicked', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      '/folders/tree': { status: 200, body: folderTree },
      '/tags': { status: 200, body: tagRows },
      '/documents': { status: 200, body: [doc()] },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())

    fireEvent.click(screen.getByText('Phòng ban A'))

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining('/documents?folderId=folder-1'),
        expect.anything(),
      ),
    )
  })

  it('re-fetches with tagId when a tag is selected from the filter', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      '/folders/tree': { status: 200, body: folderTree },
      '/tags': { status: 200, body: tagRows },
      '/documents': { status: 200, body: [doc()] },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())

    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'tag-1' } })

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining('/documents?tagId=tag-1'),
        expect.anything(),
      ),
    )
  })

  it('attempts the content download when "Tải xuống" is clicked on a clean document', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      '/folders/tree': { status: 200, body: [] },
      '/tags': { status: 200, body: [] },
      '/documents': { status: 200, body: [doc()] },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())

    fireEvent.click(screen.getByRole('button', { name: 'Tải xuống' }))

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining('/documents/doc-1/content'),
        expect.anything(),
      ),
    )
  })

  it('shows an error banner when the documents call fails', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({
      '/folders/tree': { status: 200, body: [] },
      '/tags': { status: 200, body: [] },
      '/documents': { status: 500, body: { message: 'Lỗi máy chủ.' } },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('Lỗi máy chủ.')).toBeTruthy())
  })

  it('shows an empty state when there are no documents', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({
      '/folders/tree': { status: 200, body: [] },
      '/tags': { status: 200, body: [] },
      '/documents': { status: 200, body: [] },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('Không có tài liệu nào.')).toBeTruthy())
  })

  // ---- delete-restore-trash-01 Story 2: xác nhận + xóa mềm ----

  it('shows the "Xóa" button for a lanh-dao (R2) user holding trash.manage (AC-2)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(lanhDaoSession(), 'authenticated'))
    installFetchMock({
      '/folders/tree': { status: 200, body: [] },
      '/tags': { status: 200, body: [] },
      '/groups': { status: 200, body: [] },
      '/documents': { status: 200, body: [doc()] },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())
    await waitFor(() => expect(screen.getByRole('button', { name: 'Xóa' })).toBeTruthy())
  })

  it('shows which group a document was shared through and offers "Tải thêm" on a full page', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(lanhDaoSession(), 'authenticated'))
    const page = Array.from({ length: 50 }, (_, i) =>
      doc({ id: `d${i}`, originalName: `tai-lieu-${i}.pdf`, sharedViaGroups: i === 0 ? ['Phòng A'] : [] }),
    )
    installFetchMock({
      '/folders/tree': { status: 200, body: [] },
      '/tags': { status: 200, body: [] },
      '/groups': { status: 200, body: [] },
      '/documents': { status: 200, body: page },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('tai-lieu-0.pdf')).toBeTruthy())
    expect(screen.getByText('Chia sẻ qua nhóm: Phòng A')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Tải thêm tài liệu' })).toBeTruthy()
  })

  it('filters by name, sorts by size and selects all visible rows', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      '/folders/tree': { status: 200, body: [] },
      '/tags': { status: 200, body: [] },
      '/documents': {
        status: 200,
        body: [
          doc({ id: 'd1', originalName: 'hop-dong.pdf', size: '300' }),
          doc({ id: 'd2', originalName: 'bao-cao.pdf', size: '100' }),
          doc({ id: 'd3', originalName: 'hop-dong-2.pdf', size: '200' }),
        ],
      },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())

    // The name filter is sent to the API (debounced) - the mock ignores it, so only the request is checked.
    fireEvent.change(screen.getByLabelText('Lọc theo tên tài liệu'), { target: { value: 'hop' } })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('q=hop'), expect.anything()))

    fireEvent.click(screen.getByRole('button', { name: /Kích thước/ }))
    const names = screen.getAllByRole('link').map((a) => a.textContent).filter((t) => t?.endsWith('.pdf'))
    expect(names).toEqual(['bao-cao.pdf', 'hop-dong-2.pdf', 'hop-dong.pdf'])

    await waitFor(() => expect(screen.getByLabelText('Chọn tất cả tài liệu đang hiển thị')).toBeEnabled())
    fireEvent.click(screen.getByLabelText('Chọn tất cả tài liệu đang hiển thị'))
    expect(await screen.findByText('3 tài liệu đã chọn')).toBeTruthy()
  })

  it('hides the "Xóa" button but keeps the checkbox column (for bulk share) when trash.manage is missing (AC-3)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(lanhDaoSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      '/me/permissions': permissionsHandler(['document.share']),
      '/folders/tree': { status: 200, body: [] },
      '/tags': { status: 200, body: [] },
      '/groups': { status: 200, body: [] },
      '/documents': { status: 200, body: [doc()] },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/me/permissions'), expect.anything()),
    )

    await waitFor(() => expect(screen.queryByRole('checkbox', { name: ROW_CHECKBOX })).not.toBeNull())
    expect(screen.queryByRole('button', { name: 'Xóa' })).toBeNull()
    // group-management-sharing-02 Story 6 AC-2: LANH_DAO can select rows to
    // bulk-share (just not to delete), so the checkbox column stays visible.
    expect(screen.queryByRole('checkbox', { name: ROW_CHECKBOX })).not.toBeNull()
  })

  it('confirm dialog omits the group-warning line when totalGroups is 0 (Quyết định 1)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({
      // More specific than '/documents' below - must come first (fetchMock
      // matches by substring, first entry wins, and '/documents' is itself a
      // substring of these two URLs).
      '/documents/trash-preview': { status: 200, body: [{ documentId: 'doc-1', activeGroupShareCount: 0 }] },
      '/folders/tree': { status: 200, body: [] },
      '/tags': { status: 200, body: [] },
      '/documents': { status: 200, body: [doc()] },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())

    fireEvent.click(screen.getByRole('button', { name: 'Xóa' }))

    const dialog = await screen.findByRole('alertdialog')
    await waitFor(() => expect(within(dialog).getByText(/bao-cao\.pdf/)).toBeTruthy())
    expect(within(dialog).getByText(/chuyển vào Thùng rác và có thể phục hồi/)).toBeTruthy()
    expect(within(dialog).queryByText(/nhóm sẽ mất quyền xem/)).toBeNull()
    expect(within(dialog).queryByText(/Không tải được số nhóm bị ảnh hưởng/)).toBeNull()
  })

  it('confirm dialog shows the group-warning line with the right count when totalGroups > 0', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({
      '/documents/trash-preview': { status: 200, body: [{ documentId: 'doc-1', activeGroupShareCount: 3 }] },
      '/folders/tree': { status: 200, body: [] },
      '/tags': { status: 200, body: [] },
      '/documents': { status: 200, body: [doc()] },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())

    fireEvent.click(screen.getByRole('button', { name: 'Xóa' }))

    const dialog = await screen.findByRole('alertdialog')
    await waitFor(() => expect(within(dialog).getByText(/3 nhóm sẽ mất quyền xem/)).toBeTruthy())
  })

  it('lets the delete go through even when the preview call fails (AC-7), showing the fallback line', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      '/documents/trash-preview': { status: 500, body: { message: 'Lỗi.' } },
      '/documents/trash': { status: 200, body: { batchId: 'batch-1', itemCount: 1, purgeAfter: '2026-02-01T00:00:00.000Z' } },
      '/folders/tree': { status: 200, body: [] },
      '/tags': { status: 200, body: [] },
      '/documents': { status: 200, body: [doc()] },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())

    fireEvent.click(screen.getByRole('button', { name: 'Xóa' }))
    const dialog = await screen.findByRole('alertdialog')
    await waitFor(() => expect(within(dialog).getByText(/Không tải được số nhóm bị ảnh hưởng/)).toBeTruthy())

    fireEvent.click(within(dialog).getByRole('button', { name: 'Xóa' }))

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/documents/trash'), expect.anything()),
    )
  })

  it('collapses the message to "và N tài liệu khác" for more than 10 selected documents (AC-4)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const docs = Array.from({ length: 12 }, (_, i) => doc({ id: `doc-${i}`, originalName: `tai-lieu-${i}.pdf` }))
    installFetchMock({
      '/documents/trash-preview': { status: 200, body: docs.map((d) => ({ documentId: d.id, activeGroupShareCount: 0 })) },
      '/folders/tree': { status: 200, body: [] },
      '/tags': { status: 200, body: [] },
      '/documents': { status: 200, body: docs },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('tai-lieu-0.pdf')).toBeTruthy())

    // Select every row then trigger the bulk delete button.
    const checkboxes = screen.getAllByRole('checkbox', { name: ROW_CHECKBOX })
    checkboxes.forEach((cb) => fireEvent.click(cb))
    fireEvent.click(screen.getByRole('button', { name: 'Xóa đã chọn' }))

    await waitFor(() => expect(screen.getByText(/và 2 tài liệu khác/)).toBeTruthy())
  })

  it('removes deleted rows from state (no reload) and shows the success banner with a link to /trash (AC-5)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      '/documents/trash-preview': { status: 200, body: [{ documentId: 'doc-1', activeGroupShareCount: 0 }] },
      '/documents/trash': { status: 200, body: { batchId: 'batch-1', itemCount: 1, purgeAfter: '2026-02-01T00:00:00.000Z' } },
      '/folders/tree': { status: 200, body: [] },
      '/tags': { status: 200, body: [] },
      '/documents': { status: 200, body: [doc()] },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())

    fireEvent.click(screen.getByRole('button', { name: 'Xóa' }))
    const dialog = await screen.findByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Xóa' }))

    await waitFor(() => expect(screen.getByText(/Đã chuyển 1 tài liệu vào thùng rác\./)).toBeTruthy())
    expect(screen.getByText('Xem thùng rác').closest('a')).toHaveAttribute('href', '/trash')
    // The row for doc-1 must be gone from the table now.
    expect(screen.queryByText('bao-cao.pdf')).toBeNull()
    // Only 2 GET /documents calls: the initial load, NOT a reload after delete.
    const getDocumentsCalls = fetchMock.mock.calls.filter(
      ([url, init]) => /\/documents\?/.test(String(url)) && (init?.method ?? 'GET') === 'GET',
    )
    expect(getDocumentsCalls.length).toBe(1)
  })

  it('shows an error banner when the trash call fails after confirming (AC-6)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({
      '/documents/trash-preview': { status: 200, body: [{ documentId: 'doc-1', activeGroupShareCount: 0 }] },
      '/documents/trash': { status: 409, body: { message: 'Một hoặc nhiều tài liệu đã ở trong thùng rác' } },
      '/folders/tree': { status: 200, body: [] },
      '/tags': { status: 200, body: [] },
      '/documents': { status: 200, body: [doc()] },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())

    fireEvent.click(screen.getByRole('button', { name: 'Xóa' }))
    const dialog = await screen.findByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Xóa' }))

    await waitFor(() =>
      expect(screen.getByText('Một hoặc nhiều tài liệu đã ở trong thùng rác')).toBeTruthy(),
    )
    // Row stays in the table since the delete failed.
    expect(screen.getByText('bao-cao.pdf')).toBeTruthy()
  })

  // ---- group-management-sharing-02-document-sharing.md §4.7 (Story 6 BA): chia sẻ hàng loạt ----

  it('shows "Chia sẻ vào nhóm" for a lanh-dao user selecting rows, with the group dropdown populated', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(lanhDaoSession(), 'authenticated'))
    installFetchMock({
      '/folders/tree': { status: 200, body: [] },
      '/tags': { status: 200, body: [] },
      '/groups': { status: 200, body: groupRows },
      '/documents': { status: 200, body: [doc()] },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())

    fireEvent.click(screen.getByRole('checkbox', { name: ROW_CHECKBOX }))

    expect(screen.getByRole('button', { name: 'Chia sẻ vào nhóm' })).toBeTruthy()
    expect(screen.getByRole('option', { name: 'Phòng Kế hoạch' })).toBeTruthy()
    // R2 now holds trash.manage (r2-business-parity-03 AC-2): bulk-delete sits alongside it.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Xóa đã chọn' })).toBeTruthy())
  })

  it('hides bulk "Xóa đã chọn" for a lanh-dao without trash.manage but keeps bulk share (AC-3)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(lanhDaoSession(), 'authenticated'))
    installFetchMock({
      '/me/permissions': permissionsHandler(['document.share']),
      '/folders/tree': { status: 200, body: [] },
      '/tags': { status: 200, body: [] },
      '/groups': { status: 200, body: groupRows },
      '/documents': { status: 200, body: [doc()] },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())
    await waitFor(() => expect(screen.getByRole('checkbox', { name: ROW_CHECKBOX })).toBeTruthy())
    fireEvent.click(screen.getByRole('checkbox', { name: ROW_CHECKBOX }))
    expect(screen.getByRole('button', { name: 'Chia sẻ vào nhóm' })).toBeTruthy()
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0))
    })
    expect(screen.queryByRole('button', { name: 'Xóa đã chọn' })).toBeNull()
  })

  it('hides bulk "Chia sẻ vào nhóm" for a can-bo without document.share (ADR-3)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({
      '/me/permissions': permissionsHandler(['trash.manage']),
      '/folders/tree': { status: 200, body: [] },
      '/tags': { status: 200, body: [] },
      '/groups': { status: 200, body: groupRows },
      '/documents': { status: 200, body: [doc()] },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())
    await waitFor(() => expect(screen.getByRole('checkbox', { name: ROW_CHECKBOX })).toBeTruthy())
    fireEvent.click(screen.getByRole('checkbox', { name: ROW_CHECKBOX }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Xóa đã chọn' })).toBeTruthy())
    expect(screen.queryByRole('button', { name: 'Chia sẻ vào nhóm' })).toBeNull()
  })

  it('disables "Chia sẻ vào nhóm" when more than 100 documents are selected (AC-6)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const docs = Array.from({ length: 101 }, (_, i) => doc({ id: `doc-${i}`, originalName: `tai-lieu-${i}.pdf` }))
    installFetchMock({
      '/folders/tree': { status: 200, body: [] },
      '/tags': { status: 200, body: [] },
      '/groups': { status: 200, body: groupRows },
      '/documents': { status: 200, body: docs },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('tai-lieu-0.pdf')).toBeTruthy())

    screen.getAllByRole('checkbox', { name: ROW_CHECKBOX }).forEach((cb) => fireEvent.click(cb))
    fireEvent.change(screen.getByRole('combobox', { name: 'Chọn nhóm để chia sẻ' }), {
      target: { value: 'group-1' },
    })

    expect(screen.getByRole('button', { name: 'Chia sẻ vào nhóm' })).toBeDisabled()
  })

  it('confirms, calls POST /documents/shares, summarizes results and resets selection (AC-4/AC-7/AC-8)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const docs = [doc({ id: 'doc-1' }), doc({ id: 'doc-2', originalName: 'khac.pdf' })]
    const bulkShareResponse: BulkShareResponse = {
      results: [
        { documentId: 'doc-1', status: 'shared', reason: null },
        { documentId: 'doc-2', status: 'rejected', reason: 'group_limit_exceeded' },
      ],
    }
    const fetchMock = installFetchMock({
      '/documents/shares': { status: 200, body: bulkShareResponse },
      '/folders/tree': { status: 200, body: [] },
      '/tags': { status: 200, body: [] },
      '/groups': { status: 200, body: groupRows },
      '/documents': { status: 200, body: docs },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())

    screen.getAllByRole('checkbox', { name: ROW_CHECKBOX }).forEach((cb) => fireEvent.click(cb))
    fireEvent.change(screen.getByRole('combobox', { name: 'Chọn nhóm để chia sẻ' }), {
      target: { value: 'group-1' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Chia sẻ vào nhóm' }))

    const dialog = await screen.findByRole('alertdialog')
    expect(within(dialog).getByText('Xác nhận chia sẻ 2 tài liệu vào nhóm "Phòng Kế hoạch"?')).toBeTruthy()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Xác nhận' }))

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining('/documents/shares'),
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ groupId: 'group-1', documentIds: ['doc-1', 'doc-2'] }),
        }),
      ),
    )
    await waitFor(() =>
      expect(
        screen.getByText(/Đã chia sẻ 1 tài liệu vào nhóm "Phòng Kế hoạch"\..*1 tài liệu bị từ chối/),
      ).toBeTruthy(),
    )
    // AC-8: selection and dropdown reset after processing.
    expect(screen.queryByRole('button', { name: 'Chia sẻ vào nhóm' })).toBeNull()
  })
})
