import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useSession } from 'next-auth/react'
import PersonalPage from '@/app/personal/page'
import { ConfirmProvider } from '@/components/ConfirmDialog'
import { installFetchMock } from '@/test-utils/fetchMock'
import type { MockHandler } from '@/test-utils/fetchMock'
import { canBoSession } from '@/test-utils/session'
import type { FolderNode, PersonalFileRow, QuotaInfo } from '@/lib/api'

/**
 * Page-level test for `src/app/personal/page.tsx` - quota display, folder
 * tree/CRUD, queued-file list, delete confirmation - NOT `uploadOneChunked`
 * itself (see the sibling `uploadOneChunked.test.ts`, which owns that).
 */
vi.mock('next-auth/react', () => ({
  useSession: vi.fn(),
  signOut: vi.fn(),
}))
vi.mock('next/navigation', () => ({
  usePathname: () => '/personal',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
}))

const mockedUseSession = vi.mocked(useSession)

function useSessionReturn(data: ReturnType<typeof canBoSession> | null, status: string) {
  return { data, status, update: vi.fn() } as unknown as ReturnType<typeof useSession>
}

function renderPage() {
  return render(
    <ConfirmProvider>
      <PersonalPage />
    </ConfirmProvider>,
  )
}

const quota: QuotaInfo = {
  quotaBytes: 1000,
  usedBytes: 900,
  remainingBytes: 100,
  chunkThresholdBytes: 10_000_000,
}

const tree: FolderNode[] = [
  { id: 'folder-1', name: 'Ảnh cá nhân', depth: 1, children: [] },
]

const files: PersonalFileRow[] = [
  {
    id: 'file-1',
    folderId: null,
    originalName: 'root-file.pdf',
    displayName: 'root-file.pdf',
    relativePath: '',
    mimeType: 'application/pdf',
    size: '2048',
    sha256: 'abc',
    batchId: null,
    createdAt: '2026-01-01T00:00:00.000Z',
  },
  {
    id: 'file-2',
    folderId: 'folder-1',
    originalName: 'in-folder.pdf',
    displayName: 'in-folder.pdf',
    relativePath: '',
    mimeType: 'application/pdf',
    size: '4096',
    sha256: 'def',
    batchId: null,
    createdAt: '2026-01-02T00:00:00.000Z',
  },
]

function installAuthorizedFetch(overrides: Record<string, MockHandler> = {}) {
  return installFetchMock({
    '/personal/quota': { status: 200, body: quota },
    '/personal/folders/tree': { status: 200, body: tree },
    '/personal/files': { status: 200, body: files },
    ...overrides,
  })
}

function makeFile(sizeBytes: number, name = 'file.pdf'): File {
  return new File([new Uint8Array(sizeBytes)], name, { type: 'application/pdf' })
}

/** Stub XMLHttpRequest so `apiUploadJson` (used by the whole-file `uploadOne` path) resolves. */
function stubUploadXhr(status = 201) {
  class FakeXHR {
    upload = { onprogress: null as ((e: { loaded: number }) => void) | null }
    onload: (() => void) | null = null
    onerror: (() => void) | null = null
    status = status
    statusText = 'Created'
    responseText = JSON.stringify({ status: 'STORED', file: files[0] })
    open() {}
    setRequestHeader() {}
    send() {
      setTimeout(() => this.onload?.(), 0)
    }
  }
  vi.stubGlobal('XMLHttpRequest', FakeXHR as unknown as typeof XMLHttpRequest)
}

describe('PersonalPage', () => {
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

  it('shows the loading placeholders synchronously, then the loaded quota/tree/files', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installAuthorizedFetch()
    renderPage()

    expect(screen.getByText('Đang tải hạn mức…')).toBeTruthy()

    await waitFor(() => expect(screen.getByText('900 B / 1000 B (90%)')).toBeTruthy())
    expect(screen.getByRole('button', { name: 'Ảnh cá nhân' })).toBeTruthy()
    // Root is selected by default - only the root-level file is visible.
    expect(screen.getByText('root-file.pdf')).toBeTruthy()
    expect(screen.queryByText('in-folder.pdf')).toBeNull()
    expect(screen.getByText('Tệp trong thư mục gốc (1)')).toBeTruthy()
  })

  it('filters the visible files when a folder is selected in the tree', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installAuthorizedFetch()
    renderPage()

    await waitFor(() => expect(screen.getByRole('button', { name: 'Ảnh cá nhân' })).toBeTruthy())
    fireEvent.click(screen.getByRole('button', { name: 'Ảnh cá nhân' }))

    expect(screen.getByText('in-folder.pdf')).toBeTruthy()
    expect(screen.queryByText('root-file.pdf')).toBeNull()
    expect(screen.getByText('Tệp trong thư mục đang chọn (1)')).toBeTruthy()
  })

  it('creates a folder and shows a success message', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const fetchMock = installAuthorizedFetch({
      '/personal/folders': { status: 201, body: { id: 'folder-2', name: 'Mới' } },
    })
    renderPage()

    await waitFor(() => expect(screen.getByRole('button', { name: 'Ảnh cá nhân' })).toBeTruthy())

    fireEvent.change(screen.getByPlaceholderText('Tên thư mục'), { target: { value: 'Mới' } })
    fireEvent.click(screen.getByRole('button', { name: 'Tạo' }))

    await waitFor(() => expect(screen.getByText('Đã tạo thư mục.')).toBeTruthy())
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/personal/folders'),
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('deletes the selected folder after confirming the dialog', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const fetchMock = installAuthorizedFetch({
      '/personal/folders/folder-1': { status: 200, body: {} },
    })
    renderPage()

    await waitFor(() => expect(screen.getByRole('button', { name: 'Ảnh cá nhân' })).toBeTruthy())
    fireEvent.click(screen.getByRole('button', { name: 'Ảnh cá nhân' }))
    fireEvent.click(screen.getByRole('button', { name: 'Xóa thư mục đang chọn' }))

    const dialog = screen.getByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Xóa' }))

    await waitFor(() => expect(screen.getByText('Đã xóa thư mục.')).toBeTruthy())
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/personal/folders/folder-1'),
      expect.objectContaining({ method: 'DELETE' }),
    )
  })

  it('cancelling the delete-folder confirmation issues no DELETE call', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const fetchMock = installAuthorizedFetch()
    renderPage()

    await waitFor(() => expect(screen.getByRole('button', { name: 'Ảnh cá nhân' })).toBeTruthy())
    fireEvent.click(screen.getByRole('button', { name: 'Ảnh cá nhân' }))
    fireEvent.click(screen.getByRole('button', { name: 'Xóa thư mục đang chọn' }))
    fireEvent.click(screen.getByRole('button', { name: 'Hủy' }))

    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(fetchMock).not.toHaveBeenCalledWith(
      expect.stringContaining('/personal/folders/folder-1'),
      expect.objectContaining({ method: 'DELETE' }),
    )
  })

  it('selects and uploads a file (whole-file path), showing the "Đã lưu" outcome', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const fetchMock = installAuthorizedFetch({
      '/personal/batches/batch-1/close': { status: 200, body: {} },
      '/personal/batches': { status: 201, body: { batchId: 'batch-1', status: 'OPEN', expiresAt: '2099-01-01T00:00:00Z' } },
    })
    stubUploadXhr()
    renderPage()

    await waitFor(() => expect(screen.getByRole('button', { name: 'Ảnh cá nhân' })).toBeTruthy())

    const fileInput = document.querySelectorAll('input[type="file"]')[0] as HTMLInputElement
    fireEvent.change(fileInput, { target: { files: [makeFile(10, 'new.pdf')] } })
    expect(screen.getByText(/Đã chọn 1 tệp/)).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Tải lên' }))

    await waitFor(() => expect(screen.getByText('Đã lưu')).toBeTruthy())
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/personal/batches'),
      expect.objectContaining({ method: 'POST' }),
    )
    await waitFor(() => expect(screen.getByRole('button', { name: /Đã tải lên xong/ })).toBeTruthy())
  })

  it('shows the quota-exceeded error (with remaining bytes) when opening the batch is rejected', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installAuthorizedFetch({
      '/personal/batches': {
        status: 409,
        body: {
          code: 'QUOTA_EXCEEDED',
          message: 'Quá hạn mức lưu trữ.',
          usedBytes: 900,
          quotaBytes: 1000,
          remainingBytes: 100,
        },
      },
    })
    renderPage()

    await waitFor(() => expect(screen.getByRole('button', { name: 'Ảnh cá nhân' })).toBeTruthy())

    const fileInput = document.querySelectorAll('input[type="file"]')[0] as HTMLInputElement
    fireEvent.change(fileInput, { target: { files: [makeFile(10, 'new.pdf')] } })
    fireEvent.click(screen.getByRole('button', { name: 'Tải lên' }))

    await waitFor(() => expect(screen.getByText('Quá hạn mức lưu trữ. (còn trống 100 B)')).toBeTruthy())
  })

  it('deletes a file after confirming the dialog', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const fetchMock = installAuthorizedFetch({
      '/personal/files/file-1': { status: 200, body: {} },
    })
    renderPage()

    await waitFor(() => expect(screen.getByText('root-file.pdf')).toBeTruthy())
    fireEvent.click(screen.getByRole('button', { name: 'Xóa' }))

    const dialog = screen.getByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Xóa' }))

    await waitFor(() => expect(screen.getByText('Đã xóa tệp.')).toBeTruthy())
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/personal/files/file-1'),
      expect.objectContaining({ method: 'DELETE' }),
    )
  })

  it('shows an error message when loading the file list fails', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({
      '/personal/quota': { status: 200, body: quota },
      '/personal/folders/tree': { status: 200, body: tree },
      '/personal/files': { status: 500, body: { message: 'Không thể tải danh sách tệp.' } },
    })
    renderPage()

    await waitFor(() => expect(screen.getByText('Không thể tải danh sách tệp.')).toBeTruthy())
  })
})
