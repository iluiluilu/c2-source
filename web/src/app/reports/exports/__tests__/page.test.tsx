import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useSession } from 'next-auth/react'
import ReportExportsPage from '@/app/reports/exports/page'
import { installFetchMock as baseInstallFetchMock } from '@/test-utils/fetchMock'
import { permissionsHandler, resetPermissions } from '@/test-utils/permissions'
import { canBoSession } from '@/test-utils/session'
import type { ReportExportPage, ReportExportRow } from '@/lib/reports'

vi.mock('next-auth/react', () => ({
  useSession: vi.fn(),
  signOut: vi.fn(),
}))
vi.mock('next/navigation', () => ({
  usePathname: () => '/reports/exports',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
}))

const mockedUseSession = vi.mocked(useSession)

function sessionReturn(data: ReturnType<typeof canBoSession> | null, status: string) {
  return { data, status, update: vi.fn() } as unknown as ReturnType<typeof useSession>
}

function row(overrides: Partial<ReportExportRow>): ReportExportRow {
  return {
    id: 'e1',
    templateCode: 'REPO_SUMMARY',
    format: 'PDF',
    status: 'DONE',
    createdAt: '2026-04-01T00:00:00Z',
    finishedAt: '2026-04-01T00:01:00Z',
    expiresAt: '2026-04-08T00:00:00Z',
    ...overrides,
  }
}

function page(...content: ReportExportRow[]): ReportExportPage {
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: 1 }
}

describe('ReportExportsPage', () => {
  afterEach(() => {
    resetPermissions()
    cleanup()
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('is denied without report.export', async () => {
    mockedUseSession.mockReturnValue(sessionReturn(canBoSession(), 'authenticated'))
    baseInstallFetchMock({ '/me/permissions': permissionsHandler(['report.read']) })
    render(<ReportExportsPage />)
    await waitFor(() => expect(screen.getByText(/không có quyền/i)).toBeTruthy())
  })

  it('lists my exports with a status label per job, download for DONE, retry for FAILED (AC-29, AC-32)', async () => {
    mockedUseSession.mockReturnValue(sessionReturn(canBoSession(), 'authenticated'))
    baseInstallFetchMock({
      '/me/permissions': permissionsHandler(['report.export']),
      '/reports/exports': {
        status: 200,
        body: page(
          row({ id: 'done', status: 'DONE' }),
          row({ id: 'failed', status: 'FAILED', errorMessage: 'Báo cáo quá lớn, hãy thu hẹp khoảng thời gian/bộ lọc' }),
          row({ id: 'expired', status: 'EXPIRED' }),
        ),
      },
    })
    render(<ReportExportsPage />)
    await waitFor(() => expect(screen.getByTestId('export-done')).toBeTruthy())
    expect(screen.getByTestId('export-done').textContent).toContain('Hoàn tất')
    expect(screen.getByTestId('export-done').textContent).toContain('Tổng hợp tình hình kho tài liệu theo kỳ')
    expect(screen.getByTestId('export-failed').textContent).toContain('Thất bại')
    expect(screen.getByTestId('export-failed').textContent).toContain('Báo cáo quá lớn')
    expect(screen.getByTestId('export-expired').textContent).toContain('Hết hạn')
    expect(screen.getAllByRole('button', { name: 'Tải về' })).toHaveLength(1)
    expect(screen.getAllByRole('button', { name: 'Chạy lại' })).toHaveLength(1)
  })

  it('polls while a job is PENDING/RUNNING and stops once everything is final (AC-22)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    mockedUseSession.mockReturnValue(sessionReturn(canBoSession(), 'authenticated'))
    let calls = 0
    const fetchMock = baseInstallFetchMock({
      '/me/permissions': permissionsHandler(['report.export']),
      '/reports/exports': () => {
        calls++
        const status = calls === 1 ? 'PENDING' : calls === 2 ? 'RUNNING' : 'DONE'
        return { status: 200, body: page(row({ id: 'e1', status })) }
      },
    })
    render(<ReportExportsPage />)
    await waitFor(() => expect(screen.getByTestId('export-e1').textContent).toContain('Đang chờ'))

    await act(async () => {
      await vi.advanceTimersByTimeAsync(3100)
    })
    await waitFor(() => expect(screen.getByTestId('export-e1').textContent).toContain('Đang tạo'))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3100)
    })
    await waitFor(() => expect(screen.getByTestId('export-e1').textContent).toContain('Hoàn tất'))

    const listCalls = () => fetchMock.mock.calls.filter(([u]) => String(u).includes('/reports/exports?')).length
    const settled = listCalls()
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000)
    })
    expect(listCalls()).toBe(settled) // no more polling after DONE
  })

  it('downloads with the bearer token via a blob, and retries a failed job', async () => {
    mockedUseSession.mockReturnValue(sessionReturn(canBoSession(), 'authenticated'))
    const createObjectURL = vi.fn(() => 'blob:x')
    const revokeObjectURL = vi.fn()
    Object.assign(URL, { createObjectURL, revokeObjectURL })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const fetchMock = baseInstallFetchMock({
      '/me/permissions': permissionsHandler(['report.export']),
      '/download': {} as never,
      '/retry': { status: 202, body: { exportId: 'new' } },
      '/reports/exports': { status: 200, body: page(row({ id: 'done', status: 'DONE' }), row({ id: 'bad', status: 'FAILED' })) },
    })
    // the shared mock has no blob()/headers: extend the download response by hand
    const original = global.fetch
    global.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).includes('/download')) {
        return {
          ok: true,
          status: 200,
          headers: { get: () => 'attachment; filename="repo_summary_20260401.pdf"' },
          blob: async () => new Blob(['%PDF']),
        } as unknown as Response
      }
      return original(input, init)
    }) as unknown as typeof fetch

    render(<ReportExportsPage />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Tải về' })).toBeTruthy())
    fireEvent.click(screen.getByRole('button', { name: 'Tải về' }))
    await waitFor(() => expect(click).toHaveBeenCalled())
    expect(createObjectURL).toHaveBeenCalled()
    const dl = vi.mocked(global.fetch).mock.calls.find(([u]) => String(u).includes('/reports/exports/done/download'))
    expect((dl![1] as RequestInit).headers).toMatchObject({ Authorization: 'Bearer test-access-token' })

    fireEvent.click(screen.getByRole('button', { name: 'Chạy lại' }))
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(([u, init]) => String(u).includes('/reports/exports/bad/retry') && (init as RequestInit)?.method === 'POST'),
      ).toBe(true),
    )
  })
})
