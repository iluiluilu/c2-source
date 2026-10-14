import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useSession } from 'next-auth/react'
import DocumentDetailPage from '@/app/documents/[id]/page'
import { ConfirmProvider } from '@/components/ConfirmDialog'
import { installFetchMock as baseInstallFetchMock } from '@/test-utils/fetchMock'
import type { MockHandler } from '@/test-utils/fetchMock'
import { permissionsHandler, resetPermissions, withPermissions } from '@/test-utils/permissions'
import { canBoSession, lanhDaoSession, quanTriSession } from '@/test-utils/session'
import type { DocumentNote, DocumentRow, DocumentShareRow, GroupRow, TagRow } from '@/lib/api'

/**
 * documents/[id]/page.tsx: metadata + FR-15 tags + group-sharing §4.6 +
 * FR-16 notes. R1 (can-bo) can write, R2 (lanh-dao) is read-only, R3 is
 * denied (NFR-07). Covers the 3 most important flows per the test brief:
 * loading the record, assigning a tag (R1 write), and adding a note (R1
 * write) — the rest (share/edit-note) share the same `run()` wiring.
 */
vi.mock('next-auth/react', () => ({
  useSession: vi.fn(),
  signOut: vi.fn(),
}))
const pushMock = vi.fn()
vi.mock('next/navigation', () => ({
  usePathname: () => '/documents/doc-1',
  useRouter: () => ({ push: pushMock, replace: vi.fn(), back: vi.fn() }),
  useParams: () => ({ id: 'doc-1' }),
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
      <DocumentDetailPage />
    </ConfirmProvider>,
  )
}

function doc(overrides: Partial<DocumentRow> = {}): DocumentRow {
  return {
    id: 'doc-1',
    originalName: 'bao-cao.pdf',
    displayName: 'bao-cao.pdf',
    mimeType: 'application/pdf',
    size: '2097152',
    sha256: 'abc123',
    relativePath: '/bao-cao.pdf',
    folderId: 'folder-1',
    uploaderUsername: 'canbo1',
    status: 'clean',
    createdAt: '2026-01-01T00:00:00.000Z',
    ...overrides,
  }
}

const docTag: TagRow = {
  id: 'tag-1',
  kind: 'topic',
  label: 'Chủ đề A',
  createdByUsername: 'canbo1',
  createdAt: '2026-01-01T00:00:00.000Z',
}
const assignableTag: TagRow = {
  id: 'tag-2',
  kind: 'topic',
  label: 'Đơn vị B',
  createdByUsername: 'canbo1',
  createdAt: '2026-01-01T00:00:00.000Z',
}

const note: DocumentNote = {
  id: 'note-1',
  documentId: 'doc-1',
  body: 'Ghi chú đầu tiên',
  createdByUsername: 'canbo1',
  updatedByUsername: null,
  createdAt: '2026-01-01T00:00:00.000Z',
  updatedAt: '2026-01-01T00:00:00.000Z',
}

const share: DocumentShareRow = {
  groupId: 'group-1',
  groupName: 'Nhóm phòng A',
  sharedBySub: 'sub-1',
  sharedAt: '2026-01-01T00:00:00.000Z',
}

const myGroup: GroupRow = {
  id: 'group-2',
  name: 'Nhóm phòng B',
  description: null,
  ownerSub: 'sub-1',
  createdAt: '2026-01-01T00:00:00.000Z',
  updatedAt: '2026-01-01T00:00:00.000Z',
}

// installFetchMock matches by substring, first entry wins - keep the more
// specific `/documents/doc-1/...` sub-resource routes ahead of the bare
// `/documents/doc-1` document-metadata route in every handler map below.
function baseHandlers() {
  return {
    '/documents/doc-1/tags': { status: 200, body: [docTag] },
    '/documents/doc-1/notes': { status: 200, body: [note] },
    '/documents/doc-1/shares': { status: 200, body: [share] },
    '/tags': { status: 200, body: [docTag, assignableTag] },
    '/groups': { status: 200, body: [myGroup] },
    '/documents/doc-1': { status: 200, body: doc() },
  }
}

describe('DocumentDetailPage', () => {
  afterEach(() => {
    resetPermissions()
    cleanup()
    vi.restoreAllMocks()
    pushMock.mockClear()
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

  it('shows AccessDenied for quan-tri (NFR-07)', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock()
    renderPage()
    expect(screen.getByText(/không có quyền/i)).toBeTruthy()
  })

  it('loads and displays the document metadata, tags, shares and notes for can-bo', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock(baseHandlers())
    renderPage()

    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())
    expect(screen.getByText('Chủ đề A')).toBeTruthy()
    expect(screen.getByText('Nhóm phòng A')).toBeTruthy()
    expect(screen.getByText('Ghi chú đầu tiên')).toBeTruthy()
    expect(screen.getByText('abc123')).toBeTruthy()
  })

  it('offers a download button and shows the folder the document lives in', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({
      '/folders/tree': {
        status: 200,
        body: [{ id: 'root-1', name: 'Kho A', depth: 1, children: [{ id: 'folder-1', name: 'Hợp đồng', depth: 2, children: [] }] }],
      },
      ...baseHandlers(),
    })
    renderPage()

    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())
    expect(screen.getByRole('button', { name: 'Tải xuống' })).toBeTruthy()
    await waitFor(() => expect(screen.getByText('Kho A / Hợp đồng')).toBeTruthy())
  })

  it('shows tag/note/share controls for a lanh-dao holding tag.manage + note.write + document.share (AC-2)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(lanhDaoSession(), 'authenticated'))
    installFetchMock(baseHandlers())
    renderPage()

    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())
    await waitFor(() => expect(screen.getByRole('button', { name: 'Gắn' })).toBeTruthy())
    expect(screen.getByRole('button', { name: 'Thêm ghi chú' })).toBeTruthy()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Chia sẻ' })).toBeTruthy())
  })

  it('hides the group-share control without document.share, keeping other controls (ADR-3)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(lanhDaoSession(), 'authenticated'))
    installFetchMock({
      '/me/permissions': permissionsHandler(['tag.manage', 'note.write', 'trash.manage']),
      ...baseHandlers(),
    })
    renderPage()

    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())
    await waitFor(() => expect(screen.getByRole('button', { name: 'Gắn' })).toBeTruthy())
    expect(screen.queryByRole('button', { name: 'Chia sẻ' })).toBeNull()
  })

  it.each([
    ['tag.manage', 'Gắn', ['note.write', 'trash.manage']],
    ['note.write', 'Thêm ghi chú', ['tag.manage', 'trash.manage']],
    ['trash.manage', 'Xóa', ['tag.manage', 'note.write']],
  ])('hides only the control gated by %s when it is missing (AC-3)', async (_missing, hiddenLabel, granted) => {
    mockedUseSession.mockReturnValue(useSessionReturn(lanhDaoSession(), 'authenticated'))
    installFetchMock({ '/me/permissions': permissionsHandler(granted), ...baseHandlers() })
    renderPage()

    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())
    const labels = ['Gắn', 'Thêm ghi chú', 'Xóa']
    for (const label of labels.filter((l) => l !== hiddenLabel)) {
      await waitFor(() => expect(screen.getByRole('button', { name: label })).toBeTruthy())
    }
    expect(screen.queryByRole('button', { name: hiddenLabel })).toBeNull()
  })

  it('assigns an existing tag (FR-15) for a can-bo user', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const fetchMock = installFetchMock(baseHandlers())
    renderPage()
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())

    const selects = screen.getAllByRole('combobox')
    const assignSelect = selects[0]
    fireEvent.change(assignSelect, { target: { value: 'tag-2' } })
    fireEvent.click(screen.getByRole('button', { name: 'Gắn' }))

    await waitFor(() => expect(screen.getByText('Đã gắn nhãn.')).toBeTruthy())
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/documents/doc-1/tags'),
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('adds a note (FR-16) for a can-bo user', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const fetchMock = installFetchMock(baseHandlers())
    renderPage()
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())

    fireEvent.change(screen.getByPlaceholderText('Thêm ghi chú cho tệp dữ liệu…'), {
      target: { value: 'Ghi chú mới' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Thêm ghi chú' }))

    await waitFor(() => expect(screen.getByText('Đã thêm ghi chú.')).toBeTruthy())
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/documents/doc-1/notes'),
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ body: 'Ghi chú mới' }) }),
    )
  })

  it('shows a friendly 403 message when unsharing fails for lack of permission', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({
      // More specific than '/documents/doc-1/shares' below - must come first.
      '/documents/doc-1/shares/group-1': { status: 403, body: { message: 'Forbidden' } },
      ...baseHandlers(),
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('Nhóm phòng A')).toBeTruthy())

    fireEvent.click(screen.getByTitle('Gỡ chia sẻ'))
    // ConfirmProvider dialog: confirm the removal.
    fireEvent.click(screen.getByRole('button', { name: 'Xác nhận' }))

    await waitFor(() =>
      expect(
        screen.getByText(/Chỉ chủ nhóm, người đã chia sẻ, hoặc người nạp tài liệu mới có thể gỡ chia sẻ này\./),
      ).toBeTruthy(),
    )
  })

  it('shows an error banner when the initial document fetch fails', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({
      '/documents/doc-1/tags': { status: 200, body: [] },
      '/documents/doc-1/notes': { status: 200, body: [] },
      '/documents/doc-1/shares': { status: 200, body: [] },
      '/tags': { status: 200, body: [] },
      '/groups': { status: 200, body: [] },
      // Least specific - must come last so it doesn't swallow the routes above.
      '/documents/doc-1': { status: 404, body: { message: 'Không tìm thấy tài liệu.' } },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('Không tìm thấy tài liệu.')).toBeTruthy())
  })

  // ---- delete-restore-trash-01 Story 2: nút "Xóa" + xóa mềm ----

  it('shows the "Xóa" button for a lanh-dao holding trash.manage (AC-2)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(lanhDaoSession(), 'authenticated'))
    installFetchMock(baseHandlers())
    renderPage()
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())
    await waitFor(() => expect(screen.getByRole('button', { name: 'Xóa' })).toBeTruthy())
  })

  it('deletes the document (Story 2): confirms, POSTs /documents/trash, shows a message and redirects to /documents', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      '/documents/trash-preview': { status: 200, body: [{ documentId: 'doc-1', activeGroupShareCount: 2 }] },
      '/documents/trash': { status: 200, body: { batchId: 'batch-1', itemCount: 1, purgeAfter: '2026-02-01T00:00:00.000Z' } },
      ...baseHandlers(),
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())

    fireEvent.click(screen.getByRole('button', { name: 'Xóa' }))
    const dialog = await screen.findByRole('alertdialog')
    expect(within(dialog).getByText(/2 nhóm sẽ mất quyền xem tài liệu này\./)).toBeTruthy()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Xóa' }))

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining('/documents/trash'),
        expect.objectContaining({ method: 'POST' }),
      ),
    )
    await waitFor(() =>
      expect(screen.getByText(/Đã chuyển tài liệu vào thùng rác\. Đang quay lại kho tài liệu…/)).toBeTruthy(),
    )

    await vi.advanceTimersByTimeAsync(1600)
    expect(pushMock).toHaveBeenCalledWith('/documents')
    vi.useRealTimers()
  })

  it('shows an error banner when the trash call fails after confirming (AC-6), without redirecting', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({
      '/documents/trash-preview': { status: 200, body: [{ documentId: 'doc-1', activeGroupShareCount: 0 }] },
      '/documents/trash': { status: 409, body: { message: 'Tài liệu đã ở trong thùng rác.' } },
      ...baseHandlers(),
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())

    fireEvent.click(screen.getByRole('button', { name: 'Xóa' }))
    const dialog = await screen.findByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Xóa' }))

    await waitFor(() => expect(screen.getByText('Tài liệu đã ở trong thùng rác.')).toBeTruthy())
    expect(pushMock).not.toHaveBeenCalled()
  })
})
