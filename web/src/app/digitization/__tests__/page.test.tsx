import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useSession } from 'next-auth/react'
import DigitizationQueuePage from '@/app/digitization/page'
import { installFetchMock as baseInstallFetchMock } from '@/test-utils/fetchMock'
import type { MockHandler } from '@/test-utils/fetchMock'
import { permissionsHandler, resetPermissions, withPermissions } from '@/test-utils/permissions'
import { canBoSession, lanhDaoSession } from '@/test-utils/session'
import type { ReviewRunSummary, ReviewRunSummaryPageResponse } from '@/lib/api'

/**
 * digitization/page.tsx (Story 1): review queue for can-bo only. Covers
 * gating, the populated-table render, task-tab / status-filter re-fetch
 * wiring (buildQueueQuery itself already has a dedicated pure-function test)
 * and clicking a row to navigate to the run detail page.
 */
const pushMock = vi.fn()

vi.mock('next-auth/react', () => ({
  useSession: vi.fn(),
  signOut: vi.fn(),
}))
vi.mock('next/navigation', () => ({
  usePathname: () => '/digitization',
  useRouter: () => ({ push: pushMock, replace: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
}))

const mockedUseSession = vi.mocked(useSession)

const installFetchMock = (handlers: Record<string, MockHandler> = {}) =>
  baseInstallFetchMock(withPermissions(handlers))

function useSessionReturn(data: ReturnType<typeof canBoSession> | null, status: string) {
  return { data, status, update: vi.fn() } as unknown as ReturnType<typeof useSession>
}

function run(overrides: Partial<ReviewRunSummary> = {}): ReviewRunSummary {
  return {
    runId: 'run-1',
    documentId: 'doc-1',
    documentName: 'bao-cao.pdf',
    task: 'ocr',
    reviewState: 'pending',
    effectiveStatus: 'pending',
    outputQualityFlag: 'ok',
    lowConfidenceCount: 0,
    segmentCount: 12,
    reviewDeadlineAt: null,
    firstEditAt: null,
    isReplacingConfirmedRun: false,
    // digitization-review-06-queue-pending-status.md AC-4/case biên: 'cancelled'
    // fallback về nhãn "Đang chờ" cũ - giữ hành vi mặc định của factory này
    // giống hệt trước khi processingStatus tồn tại, trừ khi 1 test override.
    processingStatus: 'cancelled',
    ...overrides,
  }
}

function page(content: ReviewRunSummary[]): ReviewRunSummaryPageResponse {
  return { content, totalElements: content.length, page: 0, size: 20, totalPages: 1 }
}

describe('DigitizationQueuePage', () => {
  afterEach(() => {
    resetPermissions()
    cleanup()
    vi.restoreAllMocks()
    pushMock.mockClear()
  })

  it('renders nothing while the session is loading', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(null, 'loading'))
    installFetchMock()
    const { container } = render(<DigitizationQueuePage />)
    expect(container).toBeEmptyDOMElement()
  })

  it('shows AccessDenied when there is no session', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(null, 'unauthenticated'))
    installFetchMock()
    render(<DigitizationQueuePage />)
    expect(screen.getByText(/không có quyền/i)).toBeTruthy()
  })

  it('shows AccessDenied when digitization_review.manage is missing (AC-3)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(lanhDaoSession(), 'authenticated'))
    installFetchMock({ '/me/permissions': permissionsHandler([]) })
    render(<DigitizationQueuePage />)
    expect(await screen.findByText(/không có quyền/i)).toBeTruthy()
  })

  it('renders the queue for a lanh-dao holding digitization_review.manage (AC-2)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(lanhDaoSession(), 'authenticated'))
    installFetchMock({ '/digitization-review/queue': { status: 200, body: page([run()]) } })
    render(<DigitizationQueuePage />)
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())
  })

  it('loads and renders the queue for a can-bo user', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({
      '/digitization-review/queue': { status: 200, body: page([run()]) },
    })
    render(<DigitizationQueuePage />)

    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())
    const row = screen.getByText('bao-cao.pdf').closest('tr') as HTMLElement
    expect(within(row).getByText('Văn bản & ảnh')).toBeTruthy()
    expect(within(row).getByText('Đang chờ')).toBeTruthy()
    expect(within(row).getByText('12')).toBeTruthy()
  })

  it.each([
    ['queued', 'Đang xử lý'],
    ['running', 'Đang xử lý'],
  ])(
    'AC-1: shows "Đang xử lý" for a pending row with processingStatus=%s',
    async (processingStatus, expectedLabel) => {
      mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
      installFetchMock({
        '/digitization-review/queue': {
          status: 200,
          body: page([run({ effectiveStatus: 'pending', processingStatus })]),
        },
      })
      render(<DigitizationQueuePage />)

      await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())
      const row = screen.getByText('bao-cao.pdf').closest('tr') as HTMLElement
      expect(within(row).getByText(expectedLabel)).toBeTruthy()
      expect(within(row).queryByText('Đang chờ')).toBeNull()
    },
  )

  it('AC-2: shows "Chờ duyệt" for a pending row with processingStatus=succeeded', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({
      '/digitization-review/queue': {
        status: 200,
        body: page([run({ effectiveStatus: 'pending', processingStatus: 'succeeded' })]),
      },
    })
    render(<DigitizationQueuePage />)

    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())
    const row = screen.getByText('bao-cao.pdf').closest('tr') as HTMLElement
    expect(within(row).getByText('Chờ duyệt')).toBeTruthy()
    expect(within(row).queryByText('Đang chờ')).toBeNull()
  })

  it.each(['failed_retryable', 'failed_permanent'])(
    'AC-6: shows "Lỗi xử lý" for a pending row with processingStatus=%s',
    async (processingStatus) => {
      mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
      installFetchMock({
        '/digitization-review/queue': {
          status: 200,
          body: page([run({ effectiveStatus: 'pending', processingStatus })]),
        },
      })
      render(<DigitizationQueuePage />)

      await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())
      const row = screen.getByText('bao-cao.pdf').closest('tr') as HTMLElement
      expect(within(row).getByText('Lỗi xử lý')).toBeTruthy()
      expect(within(row).queryByText('Đang chờ')).toBeNull()
    },
  )

  it('AC-4: effectiveStatus !== "pending" keeps the old label/badge regardless of processingStatus', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({
      '/digitization-review/queue': {
        status: 200,
        body: page([run({ effectiveStatus: 'in_review', processingStatus: 'succeeded' })]),
      },
    })
    render(<DigitizationQueuePage />)

    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())
    const row = screen.getByText('bao-cao.pdf').closest('tr') as HTMLElement
    expect(within(row).getByText('Đang sửa')).toBeTruthy()
  })

  it('case biên: an unknown processingStatus on a pending row falls back to "Đang chờ" without throwing', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({
      '/digitization-review/queue': {
        status: 200,
        body: page([run({ effectiveStatus: 'pending', processingStatus: 'some_future_status' })]),
      },
    })
    render(<DigitizationQueuePage />)

    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())
    const row = screen.getByText('bao-cao.pdf').closest('tr') as HTMLElement
    expect(within(row).getByText('Đang chờ')).toBeTruthy()
  })

  it('shows an empty state when the queue has no rows', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({
      '/digitization-review/queue': { status: 200, body: page([]) },
    })
    render(<DigitizationQueuePage />)
    await waitFor(() => expect(screen.getByText('Không có tài liệu nào cần duyệt.')).toBeTruthy())
  })

  it('re-fetches with task=stt and no "all" param when the STT tab is selected', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      '/digitization-review/queue': { status: 200, body: page([run()]) },
    })
    render(<DigitizationQueuePage />)
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())

    fireEvent.click(screen.getByRole('button', { name: 'Lời nói (ghi âm)' }))

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining('/digitization-review/queue?task=stt&page=0&size=20'),
        expect.anything(),
      ),
    )
  })

  it('re-fetches with the status filter when a status checkbox is toggled', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      '/digitization-review/queue': { status: 200, body: page([run()]) },
    })
    render(<DigitizationQueuePage />)
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())

    fireEvent.click(screen.getByLabelText('Quá hạn xử lý'))

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining('status=stale'),
        expect.anything(),
      ),
    )
  })

  it('navigates to the run detail page when a row is clicked', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({
      '/digitization-review/queue': { status: 200, body: page([run()]) },
    })
    render(<DigitizationQueuePage />)
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())

    fireEvent.click(screen.getByText('bao-cao.pdf'))
    expect(pushMock).toHaveBeenCalledWith('/digitization/runs/run-1')
  })

  it('shows an error banner when the queue call fails', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({
      '/digitization-review/queue': { status: 500, body: { message: 'Lỗi máy chủ.' } },
    })
    render(<DigitizationQueuePage />)
    await waitFor(() => expect(screen.getByText('Lỗi máy chủ.')).toBeTruthy())
  })
})
