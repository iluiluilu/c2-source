import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useSession } from 'next-auth/react'
import DigitizationRunDetailPage from '@/app/digitization/runs/[runId]/page'
import { ConfirmProvider } from '@/components/ConfirmDialog'
import { installFetchMock as baseInstallFetchMock } from '@/test-utils/fetchMock'
import type { MockHandler } from '@/test-utils/fetchMock'
import { permissionsHandler, resetPermissions, withPermissions } from '@/test-utils/permissions'
import { canBoSession, lanhDaoSession } from '@/test-utils/session'
import type { ReviewRunDetail, ReviewSegment } from '@/lib/api'

/**
 * digitization/runs/[runId]/page.tsx (Stories 2-6): run detail + confirm
 * flow. Sub-components (OcrPageViewer/SegmentList/SegmentPlayer) already
 * have their own tests and `loadFullRunDetail`'s pagination-merge logic has
 * its own pure-function test, so this file exercises the page's own
 * responsibility: gating, wiring the loaded run into the right
 * task-specific body, and the confirm mutation. Uses task=video_summary as
 * the primary scenario since it needs no extra image/audio fetch mocking
 * (OcrPageViewer/SegmentPlayer fetch their own media on mount).
 */
const pushMock = vi.fn()

vi.mock('next-auth/react', () => ({
  useSession: vi.fn(),
  signOut: vi.fn(),
}))
vi.mock('next/navigation', () => ({
  usePathname: () => '/digitization/runs/run-1',
  useRouter: () => ({ push: pushMock, replace: vi.fn(), back: vi.fn() }),
  useParams: () => ({ runId: 'run-1' }),
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
      <DigitizationRunDetailPage />
    </ConfirmProvider>,
  )
}

function segment(overrides: Partial<ReviewSegment> = {}): ReviewSegment {
  return {
    segmentId: 'seg-1',
    segmentIndex: 0,
    origin: 'ai',
    aiText: 'Tóm tắt nội dung video',
    currentText: 'Tóm tắt nội dung video',
    currentRevisionNo: 0,
    isEdited: false,
    isDeleted: false,
    confidence: 0.9,
    pageNo: null,
    bboxPolygon: null,
    readingOrder: 0,
    timeStartMs: null,
    timeEndMs: null,
    overlapsAdjacent: false,
    soundEventLabel: null,
    sourceReferences: [],
    ...overrides,
  }
}

function detail(overrides: Partial<ReviewRunDetail> = {}): ReviewRunDetail {
  return {
    runId: 'run-1',
    documentId: 'doc-1',
    documentName: 'video-hop.mp4',
    task: 'video_summary',
    reviewState: 'pending',
    effectiveStatus: 'pending',
    outputQualityFlag: 'ok',
    lowConfidenceCount: 0,
    segmentCount: 1,
    reviewDeadlineAt: null,
    firstEditAt: null,
    isReplacingConfirmedRun: false,
    sourceDurationMs: null,
    previousConfirmedRunId: null,
    staleWarning: false,
    segments: { content: [segment()], totalElements: 1, page: 0, size: 100, totalPages: 1 },
    ...overrides,
  }
}

const runDetailUrl = '/digitization-review/runs/run-1?segmentPage=0&segmentSize=100'

describe('DigitizationRunDetailPage', () => {
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

  it('shows AccessDenied when digitization_review.manage is missing (AC-3)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(lanhDaoSession(), 'authenticated'))
    installFetchMock({ '/me/permissions': permissionsHandler([]) })
    renderPage()
    expect(await screen.findByText(/không có quyền/i)).toBeTruthy()
  })

  it('renders the run for a lanh-dao holding digitization_review.manage (AC-2)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(lanhDaoSession(), 'authenticated'))
    installFetchMock({ [runDetailUrl]: { status: 200, body: detail() } })
    renderPage()
    await waitFor(() => expect(screen.getByText('video-hop.mp4')).toBeTruthy())
  })

  it('shows the loading placeholder, then the loaded run + its segments', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({
      [runDetailUrl]: { status: 200, body: detail() },
    })
    renderPage()

    expect(await screen.findByText('Đang tải…')).toBeTruthy()
    await waitFor(() => expect(screen.getByText('video-hop.mp4')).toBeTruthy())
    expect(screen.getByText('Tóm tắt video · 1 đoạn')).toBeTruthy()
    expect(screen.getByText('Tóm tắt nội dung video')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Xác nhận' })).toBeTruthy()
  })

  it('shows the stale warning banner for a stale video_summary run', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({
      [runDetailUrl]: { status: 200, body: detail({ staleWarning: true }) },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('video-hop.mp4')).toBeTruthy())
    expect(screen.getByText(/có thể đã lỗi thời/)).toBeTruthy()
  })

  it('shows the confirmed badge (not the confirm button) once already confirmed', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({
      [runDetailUrl]: { status: 200, body: detail({ reviewState: 'confirmed', effectiveStatus: 'confirmed' }) },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('video-hop.mp4')).toBeTruthy())
    expect(screen.queryByRole('button', { name: 'Xác nhận' })).toBeNull()
    expect(screen.getByText('Đã xác nhận')).toBeTruthy()
  })

  it('confirms the run: asks via ConfirmProvider, POSTs /confirm, shows a message and redirects to the queue', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      '/digitization-review/runs/run-1/confirm': {
        status: 200,
        body: { ...detail(), reviewState: 'confirmed', effectiveStatus: 'confirmed' },
      },
      [runDetailUrl]: { status: 200, body: detail() },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('video-hop.mp4')).toBeTruthy())

    fireEvent.click(screen.getByRole('button', { name: 'Xác nhận' }))
    // ConfirmProvider dialog appears - confirm the action (scoped: the page
    // still has its own "Xác nhận" button underneath the dialog overlay).
    const dialog = await screen.findByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Xác nhận' }))

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining('/digitization-review/runs/run-1/confirm'),
        expect.objectContaining({ method: 'POST' }),
      ),
    )
    await waitFor(() => expect(screen.getByText(/Đã xác nhận\. Đang quay lại hàng chờ…/)).toBeTruthy())

    await vi.advanceTimersByTimeAsync(1600)
    expect(pushMock).toHaveBeenCalledWith('/digitization')
    vi.useRealTimers()
  })

  it('shows an error banner when the initial run-detail fetch fails', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({
      [runDetailUrl]: { status: 404, body: { message: 'Không tìm thấy run này.' } },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('Không tìm thấy run này.')).toBeTruthy())
  })
})
