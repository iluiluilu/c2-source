import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useSession } from 'next-auth/react'
import UploadPage from '@/app/upload/page'
import { ConfirmProvider } from '@/components/ConfirmDialog'
import { installFetchMock as baseInstallFetchMock } from '@/test-utils/fetchMock'
import type { MockHandler } from '@/test-utils/fetchMock'
import { permissionsHandler, resetPermissions, withPermissions } from '@/test-utils/permissions'
import { canBoSession, lanhDaoSession } from '@/test-utils/session'
import type { AllowedFormat, FolderNode, IngestLimits } from '@/lib/api'

/**
 * Page-level test for `src/app/upload/page.tsx` - the surrounding page
 * chrome (role gating, folder-tree/limits load, precheck-violation display,
 * queued-file list) - NOT `uploadOneChunked` itself (see the sibling
 * `uploadOneChunked.test.ts`, which owns that).
 */
vi.mock('next-auth/react', () => ({
  useSession: vi.fn(),
  signOut: vi.fn(),
  getSession: vi.fn(async () => ({ accessToken: 'test-token' })),
}))
vi.mock('next/navigation', () => ({
  usePathname: () => '/upload',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
}))

const mockedUseSession = vi.mocked(useSession)

const installFetchMock = (handlers: Record<string, MockHandler> = {}) =>
  baseInstallFetchMock(withPermissions(handlers))

function useSessionReturn(data: ReturnType<typeof canBoSession> | null, status: string) {
  return { data, status, update: vi.fn() } as unknown as ReturnType<typeof useSession>
}

const folderTree: FolderNode[] = [
  { id: 'folder-1', name: 'Kho A', depth: 1, children: [] },
  { id: 'folder-2', name: 'Kho B', depth: 1, children: [] },
]

const limits: IngestLimits = {
  maxFilesPerBatch: 2,
  maxBatchSizeBytes: 1024,
  maxFileSizeBytes: 512,
  chunkThresholdBytes: 10_000_000,
}

/** The submit button's label names the destination (or asks to pick one), e.g. "Tải lên 1 tệp vào Kho A". */
const SUBMIT_BUTTON = /^(Tải lên|Chọn thư mục đích)/

function renderPage() {
  return render(
    <ConfirmProvider>
      <UploadPage />
    </ConfirmProvider>,
  )
}

const allowedFormats: AllowedFormat[] = [{ mimeType: 'application/pdf', extensions: ['pdf'] }]

function makeFile(sizeBytes: number, name = 'file.pdf'): File {
  return new File([new Uint8Array(sizeBytes)], name, { type: 'application/pdf' })
}

function installAuthorizedFetch(overrides: Record<string, unknown> = {}) {
  return installFetchMock({
    '/folders/tree': { status: 200, body: folderTree },
    '/ingest/limits': { status: 200, body: limits },
    '/ingest/allowed-formats': { status: 200, body: allowedFormats },
    ...overrides,
  } as Record<string, import('@/test-utils/fetchMock').MockHandler>)
}

describe('UploadPage', () => {
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

  it('shows AccessDenied with the upload-specific hint when document.upload was revoked (AC-3)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(lanhDaoSession(), 'authenticated'))
    installFetchMock({ '/me/permissions': permissionsHandler([]) })
    renderPage()
    expect(await screen.findByText(/không có quyền/i)).toBeTruthy()
    expect(screen.getByText(/yêu cầu quyền nạp dữ liệu/i)).toBeTruthy()
  })

  it('renders the upload form for a lanh-dao holding document.upload (AC-2)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(lanhDaoSession(), 'authenticated'))
    installAuthorizedFetch()
    renderPage()
    expect(await screen.findByRole('heading', { level: 2, name: /Nạp & kiểm tra dữ liệu đầu vào/ })).toBeTruthy()
  })

  it('loads the folder tree/limits/allowed-formats and renders the form for a can-bo user', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const fetchMock = installAuthorizedFetch()
    renderPage()

    expect(await screen.findByRole('heading', { level: 2, name: /Nạp & kiểm tra dữ liệu đầu vào/ })).toBeTruthy()

    await waitFor(() => expect(screen.getByText('Kho A')).toBeTruthy())
    expect(screen.getByText('Kho B')).toBeTruthy()

    expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/folders/tree'), expect.anything())
    expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/ingest/limits'), expect.anything())
    expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/ingest/allowed-formats'), expect.anything())

    await waitFor(() =>
      expect(screen.getByText(/tối đa 2 tệp, 1.0 KB\/lượt, 512 B\/tệp/)).toBeTruthy(),
    )
  })

  it('shows the empty-tree message while the tree has not loaded yet, before switching to the loaded tree', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({
      '/folders/tree': { status: 200, body: [] },
      '/ingest/limits': { status: 200, body: limits },
      '/ingest/allowed-formats': { status: 200, body: allowedFormats },
    })
    renderPage()

    await waitFor(() => expect(screen.getByText('Chưa có thư mục nào.')).toBeTruthy())
  })

  it('lists queued files after picking them and enables submit once a folder is also chosen', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installAuthorizedFetch()
    renderPage()

    await waitFor(() => expect(screen.getByText('Kho A')).toBeTruthy())

    const fileInput = document.querySelector('input[type="file"]:not([multiple][webkitdirectory])') as HTMLInputElement
    // The first hidden file input is the "Chọn tệp" one (not the directory picker).
    const file = makeFile(100, 'a.pdf')
    fireEvent.change(fileInput, { target: { files: [file] } })

    expect(screen.getByText(/Đã chọn 1 tệp/)).toBeTruthy()
    expect(screen.getByText('a.pdf')).toBeTruthy()

    const submitButton = screen.getByRole('button', { name: SUBMIT_BUTTON })
    expect(submitButton).toBeDisabled()

    expect(submitButton.textContent).toBe('Chọn thư mục đích để tải lên')
    expect(screen.getByText(/Bấm chọn thư mục sẽ chứa các tệp tải lên/)).toBeTruthy()

    fireEvent.click(screen.getByText('Kho A'))
    expect(submitButton).toBeEnabled()
    // The destination is spelled out right on the button.
    expect(submitButton.textContent).toBe('Tải lên 1 tệp vào Kho A')
    expect(screen.getByText(/Đã chọn:/).textContent).toBe('Đã chọn: Kho A')
  })

  it('asks to confirm the destination once more above 20 files, and uploads nothing when cancelled', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const fetchMock = installAuthorizedFetch({
      '/ingest/limits': {
        status: 200,
        body: { ...limits, maxFilesPerBatch: 500, maxBatchSizeBytes: 10_000_000, maxFileSizeBytes: 1_000_000 },
      },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('Kho A')).toBeTruthy())
    await waitFor(() => expect(document.querySelector('input[type="file"]')?.getAttribute('accept')).toBe('.pdf'))

    const files = Array.from({ length: 21 }, (_, i) => makeFile(10, `f${i}.pdf`))
    fireEvent.change(document.querySelector('input[type="file"]') as HTMLInputElement, { target: { files } })
    fireEvent.click(screen.getByText('Kho A'))
    fireEvent.click(screen.getByRole('button', { name: 'Tải lên 21 tệp vào Kho A' }))

    expect(await screen.findByText('Tải 21 tệp vào thư mục "Kho A"?')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Hủy' }))

    await waitFor(() => expect(screen.queryByText('Tải 21 tệp vào thư mục "Kho A"?')).toBeNull())
    const calledPaths = fetchMock.mock.calls.map((c: unknown[]) => String(c[0]))
    expect(calledPaths.some((u: string) => u.includes('/ingest/name-check') || u.includes('/ingest/batches'))).toBe(false)
  })

  it('skips files outside the allowlist before queueing them (feedbacks D-03)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installAuthorizedFetch()
    renderPage()

    await waitFor(() => expect(screen.getByText('Kho A')).toBeTruthy())
    const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement
    await waitFor(() => expect(fileInput.getAttribute('accept')).toBe('.pdf'))

    const exe = new File([new Uint8Array(10)], 'setup.exe', { type: 'application/x-msdownload' })
    fireEvent.change(fileInput, { target: { files: [makeFile(10, 'a.pdf'), exe] } })

    expect(screen.getByText(/Đã chọn 1 tệp/)).toBeTruthy()
    expect(screen.getByText('a.pdf')).toBeTruthy()
    expect(screen.getByText(/Bỏ qua 1 tệp không thuộc định dạng được phép nạp: setup\.exe/)).toBeTruthy()
  })

  it('labels picked files "Chưa tải lên" and lets one be removed before uploading', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installAuthorizedFetch()
    renderPage()

    await waitFor(() => expect(screen.getByText('Kho A')).toBeTruthy())
    const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement
    await waitFor(() => expect(fileInput.getAttribute('accept')).toBe('.pdf'))
    fireEvent.change(fileInput, { target: { files: [makeFile(10, 'a.pdf'), makeFile(10, 'b.pdf')] } })

    expect(screen.getAllByText('Chưa tải lên')).toHaveLength(2)
    expect(screen.queryByText('Đang chờ…')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'Bỏ a.pdf' }))
    expect(screen.queryByText('a.pdf')).toBeNull()
    expect(screen.getByText(/Đã chọn 1 tệp/)).toBeTruthy()
  })

  it('shows a precheck error (client-side, before any API call) when the file count exceeds the batch limit', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installAuthorizedFetch()
    renderPage()

    await waitFor(() => expect(screen.getByText('Kho A')).toBeTruthy())

    const fileInput = document.querySelector('input[type="file"]:not([multiple][webkitdirectory])') as HTMLInputElement
    const files = [makeFile(10, 'a.pdf'), makeFile(10, 'b.pdf'), makeFile(10, 'c.pdf')]
    fireEvent.change(fileInput, { target: { files } })

    expect(screen.getByText(/Số tệp \(3\) vượt quá 2 tệp\/lượt\./)).toBeTruthy()

    fireEvent.click(screen.getByText('Kho A'))
    const submitButton = screen.getByRole('button', { name: SUBMIT_BUTTON })
    expect(submitButton).toBeDisabled()
  })

  it('shows a precheck error when a single file exceeds the per-file size limit', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installAuthorizedFetch()
    renderPage()

    await waitFor(() => expect(screen.getByText('Kho A')).toBeTruthy())

    const fileInput = document.querySelector('input[type="file"]:not([multiple][webkitdirectory])') as HTMLInputElement
    fireEvent.change(fileInput, { target: { files: [makeFile(1000, 'big.pdf')] } })

    expect(screen.getByText(/vượt quá dung lượng tối đa mỗi tệp/)).toBeTruthy()
  })

  it('opens a batch, uploads a queued file whole (non-chunked), and shows the ingested outcome', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const fetchMock = installAuthorizedFetch({
      '/ingest/batches/batch-1/close': { status: 200, body: {} },
      '/ingest/batches': { status: 201, body: { batchId: 'batch-1', status: 'OPEN', expiresAt: '2099-01-01T00:00:00Z' } },
    })
    renderPage()

    await waitFor(() => expect(screen.getByText('Kho A')).toBeTruthy())
    fireEvent.click(screen.getByText('Kho A'))

    const fileInput = document.querySelector('input[type="file"]:not([multiple][webkitdirectory])') as HTMLInputElement
    fireEvent.change(fileInput, { target: { files: [makeFile(10, 'ok.pdf')] } })

    // `apiUploadJson` uses XMLHttpRequest, not fetch - stub it minimally so
    // `uploadOne` resolves without exercising the real XHR/progress plumbing
    // (that belongs to `uploadOneChunked.test.ts`'s sibling coverage).
    class FakeXHR {
      static instances: FakeXHR[] = []
      upload = { onprogress: null as ((e: { loaded: number }) => void) | null }
      onload: (() => void) | null = null
      onerror: (() => void) | null = null
      status = 201
      statusText = 'Created'
      responseText = JSON.stringify({ status: 'INGESTED', document: { id: 'doc-1' }, duplicateOf: [] })
      open() {}
      setRequestHeader() {}
      send() {
        setTimeout(() => this.onload?.(), 0)
      }
    }
    vi.stubGlobal('XMLHttpRequest', FakeXHR as unknown as typeof XMLHttpRequest)

    fireEvent.click(screen.getByRole('button', { name: SUBMIT_BUTTON }))

    await waitFor(() => expect(screen.getByText('Vào kho')).toBeTruthy())
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/ingest/batches'),
      expect.objectContaining({ method: 'POST' }),
    )
    await waitFor(() => expect(screen.getByRole('button', { name: /Đã tải lên xong/ })).toBeTruthy())
  })

  describe('folder-upload-display-name', () => {
    class CapturingXHR {
      static forms: FormData[] = []
      upload = { onprogress: null as ((e: { loaded: number }) => void) | null }
      onload: (() => void) | null = null
      onerror: (() => void) | null = null
      status = 201
      statusText = 'Created'
      responseText = ''
      open() {}
      setRequestHeader() {}
      send(form: FormData) {
        CapturingXHR.forms.push(form)
        const name = String(form.get('displayName'))
        this.responseText = JSON.stringify({
          status: 'INGESTED',
          document: { id: 'doc-1', displayName: name },
          duplicateOf: [],
        })
        setTimeout(() => this.onload?.(), 0)
      }
    }

    beforeEach(() => {
      mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    })

    function arrange(nameCheckBody: unknown) {
      CapturingXHR.forms = []
      vi.stubGlobal('XMLHttpRequest', CapturingXHR as unknown as typeof XMLHttpRequest)
      return installAuthorizedFetch({
        '/ingest/name-check': { status: 200, body: nameCheckBody },
        '/ingest/batches/batch-1/close': { status: 200, body: {} },
        '/ingest/batches': { status: 201, body: { batchId: 'batch-1', status: 'OPEN', expiresAt: '2099-01-01T00:00:00Z' } },
      })
    }

    it('no longer offers the folder picker (webkitdirectory): only "Chọn tệp"', async () => {
      arrange([])
      renderPage()
      await waitFor(() => expect(screen.getByText('Kho A')).toBeTruthy())
      expect(screen.queryByRole('button', { name: 'Chọn thư mục' })).toBeNull()
      expect(document.querySelectorAll('input[type="file"]')).toHaveLength(1)
      expect(document.querySelector('input[webkitdirectory]')).toBeNull()
      expect(screen.getByRole('button', { name: 'Chọn tệp' })).toBeTruthy()
    })

    it('name-checks the folder first, keeps free names, and sends displayName + relativePath', async () => {
      const fetchMock = arrange([{ taken: false }])
      renderPage()
      await waitFor(() => expect(screen.getByText('Kho A')).toBeTruthy())
      fireEvent.click(screen.getByText('Kho A'))
      const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement
      fireEvent.change(fileInput, { target: { files: [makeFile(10, 'ok.pdf')] } })
      fireEvent.click(screen.getByRole('button', { name: SUBMIT_BUTTON }))

      await waitFor(() => expect(screen.getByText('Vào kho')).toBeTruthy())
      const nameCheck = fetchMock.mock.calls.find(([url]) => String(url).includes('/ingest/name-check'))!
      expect(JSON.parse((nameCheck[1] as RequestInit).body as string)).toEqual({
        folderId: 'folder-1',
        files: [{ relativePath: '', originalName: 'ok.pdf' }],
      })
      expect(CapturingXHR.forms).toHaveLength(1)
      expect(CapturingXHR.forms[0].get('displayName')).toBe('ok.pdf')
      expect(CapturingXHR.forms[0].get('relativePath')).toBe('')
      expect(screen.queryByText(/bị đổi tên hiển thị/)).toBeNull()
    })

    it('a taken name gets a suffix, and the finished batch says how many files were renamed', async () => {
      arrange([{ taken: true }])
      renderPage()
      await waitFor(() => expect(screen.getByText('Kho A')).toBeTruthy())
      fireEvent.click(screen.getByText('Kho A'))
      const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement
      fireEvent.change(fileInput, { target: { files: [makeFile(10, 'ok.pdf')] } })
      fireEvent.click(screen.getByRole('button', { name: SUBMIT_BUTTON }))

      await waitFor(() => expect(screen.getByText(/1 tệp bị đổi tên hiển thị/)).toBeTruthy())
      expect(String(CapturingXHR.forms[0].get('displayName'))).toMatch(/^ok_\d{17}-[a-z0-9]{6}\.pdf$/)
    })

    it('a name-check failure does not block the upload (every name is treated as taken)', async () => {
      const fetchMock = arrange([])
      installAuthorizedFetch({
        '/ingest/name-check': { status: 429, body: { message: 'too many' } },
        '/ingest/batches/batch-1/close': { status: 200, body: {} },
        '/ingest/batches': { status: 201, body: { batchId: 'batch-1', status: 'OPEN', expiresAt: '2099-01-01T00:00:00Z' } },
      })
      renderPage()
      await waitFor(() => expect(screen.getByText('Kho A')).toBeTruthy())
      fireEvent.click(screen.getByText('Kho A'))
      const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement
      fireEvent.change(fileInput, { target: { files: [makeFile(10, 'ok.pdf')] } })
      fireEvent.click(screen.getByRole('button', { name: SUBMIT_BUTTON }))

      await waitFor(() => expect(CapturingXHR.forms).toHaveLength(1))
      expect(String(CapturingXHR.forms[0].get('displayName'))).not.toBe('ok.pdf')
      expect(fetchMock).toBeDefined()
    })

    it('blocks a file whose name is longer than 512 code points before any API call', async () => {
      arrange([])
      renderPage()
      await waitFor(() => expect(screen.getByText('Kho A')).toBeTruthy())
      const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement
      fireEvent.change(fileInput, { target: { files: [makeFile(10, `${'a'.repeat(513)}.pdf`)] } })
      expect(screen.getByText(/1 tệp có tên dài quá 512 ký tự/)).toBeTruthy()
    })
  })

  it('shows a batch-level error message when opening the batch fails', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installAuthorizedFetch({
      '/ingest/batches': { status: 500, body: { message: 'Không thể mở lượt tải' } },
    })
    renderPage()

    await waitFor(() => expect(screen.getByText('Kho A')).toBeTruthy())
    fireEvent.click(screen.getByText('Kho A'))

    const fileInput = document.querySelector('input[type="file"]:not([multiple][webkitdirectory])') as HTMLInputElement
    fireEvent.change(fileInput, { target: { files: [makeFile(10, 'ok.pdf')] } })

    fireEvent.click(screen.getByRole('button', { name: SUBMIT_BUTTON }))

    await waitFor(() => expect(screen.getByText('Không thể mở lượt tải')).toBeTruthy())
  })
})
