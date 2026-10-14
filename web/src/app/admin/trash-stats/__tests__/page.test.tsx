import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useSession } from 'next-auth/react'
import TrashStatsPage from '@/app/admin/trash-stats/page'
import { installFetchMock } from '@/test-utils/fetchMock'
import { canBoSession, quanTriSession } from '@/test-utils/session'
import type { TrashStats } from '@/lib/api'

/**
 * app/admin/trash-stats/page.tsx (delete-restore-trash-01-xoa-mem-thung-rac.md
 * Story 4, R3 only): role gating, the 5 fixed-bucket table with fixed
 * Vietnamese labels (incl. a missing bucket defaulting to 0), the
 * unrecoverableAccounts block staying visible even at count=0, and AC-4/AC-5 -
 * no link/filename ever renders here.
 */
vi.mock('next-auth/react', () => ({
  useSession: vi.fn(),
  signOut: vi.fn(),
}))
vi.mock('next/navigation', () => ({
  usePathname: () => '/admin/trash-stats',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
}))

const mockedUseSession = vi.mocked(useSession)

function useSessionReturn(data: ReturnType<typeof quanTriSession> | null, status: string) {
  return { data, status, update: vi.fn() } as unknown as ReturnType<typeof useSession>
}

function stats(overrides: Partial<TrashStats> = {}): TrashStats {
  return {
    totalItems: 42,
    totalBytes: 5_368_709_120, // 5.0 GB
    byExpiryBucket: [
      { bucket: 'expired', count: 1, bytes: 1_048_576 },
      { bucket: 'within_7_days', count: 2, bytes: 2_097_152 },
      { bucket: 'within_30_days', count: 3, bytes: 3_145_728 },
      { bucket: 'within_90_days', count: 4, bytes: 4_194_304 },
      { bucket: 'later', count: 5, bytes: 5_242_880 },
    ],
    unrecoverableAccounts: { count: 0, nearestPurgeAfter: null },
    ...overrides,
  }
}

describe('TrashStatsPage', () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
  })

  it('renders nothing while the session is loading', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(null, 'loading'))
    installFetchMock()
    const { container } = render(<TrashStatsPage />)
    expect(container).toBeEmptyDOMElement()
  })

  it('shows AccessDenied for a can-bo (R1) user', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock()
    render(<TrashStatsPage />)
    expect(screen.getByText(/không có quyền/i)).toBeTruthy()
  })

  it('renders all 5 buckets with fixed Vietnamese labels, defaulting a missing bucket to 0', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    const withoutLater = stats({
      byExpiryBucket: [
        { bucket: 'expired', count: 1, bytes: 1_048_576 },
        { bucket: 'within_7_days', count: 2, bytes: 2_097_152 },
        { bucket: 'within_30_days', count: 3, bytes: 3_145_728 },
        { bucket: 'within_90_days', count: 4, bytes: 4_194_304 },
        // 'later' bucket missing entirely (no doc in that group).
      ],
    })
    installFetchMock({ '/trash/stats': { status: 200, body: withoutLater } })
    render(<TrashStatsPage />)

    await waitFor(() => expect(screen.getByText('Đã quá hạn')).toBeTruthy())
    expect(screen.getByText('Trong 7 ngày tới')).toBeTruthy()
    expect(screen.getByText('Trong 30 ngày tới')).toBeTruthy()
    expect(screen.getByText('Trong 90 ngày tới')).toBeTruthy()
    const laterRow = screen.getByText('Sau 90 ngày').closest('tr')!
    expect(laterRow.textContent).toContain('0')
    expect(laterRow.textContent).toContain('0 B')
  })

  it('renders totals and the unrecoverableAccounts block even when count is 0', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock({ '/trash/stats': { status: 200, body: stats() } })
    render(<TrashStatsPage />)

    await waitFor(() => expect(screen.getByText('42')).toBeTruthy())
    expect(screen.getByText('5.0 GB')).toBeTruthy()
    expect(screen.getByText('Tài khoản không thể khôi phục')).toBeTruthy()
    expect(screen.getByText('0')).toBeTruthy()
  })

  it('never renders a link/anchor pointing at a specific trash item (AC-4/AC-5)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock({ '/trash/stats': { status: 200, body: stats() } })
    render(<TrashStatsPage />)

    await waitFor(() => expect(screen.getByText('42')).toBeTruthy())
    // AdminTabs itself renders <a> for other admin sections - assert none
    // point at /trash or /trash/<id>.
    const anchors = screen.queryAllByRole('link')
    anchors.forEach((a) => expect(a.getAttribute('href')).not.toMatch(/^\/trash\b/))
  })

  it('shows an error banner when the stats call fails', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock({ '/trash/stats': { status: 500, body: { message: 'Lỗi máy chủ.' } } })
    render(<TrashStatsPage />)
    await waitFor(() => expect(screen.getByText('Lỗi máy chủ.')).toBeTruthy())
  })
})
