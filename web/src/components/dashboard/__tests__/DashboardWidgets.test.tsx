import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { DashboardWidgets } from '@/components/dashboard/DashboardWidgets'
import { installFetchMock } from '@/test-utils/fetchMock'
import { canBoSession, quanTriSession } from '@/test-utils/session'

const page = (totalElements: number) => ({ content: [], totalElements, page: 0, size: 1, totalPages: 1 })

describe('DashboardWidgets', () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
  })

  it('shows review counts, quota and recent documents for a can-bo', async () => {
    installFetchMock({
      'status=stale,upcoming_deadline': { status: 200, body: page(1) },
      '/digitization-review/queue': { status: 200, body: page(4) },
      '/personal/quota': { status: 200, body: { quotaBytes: 1000, usedBytes: 900, remainingBytes: 100 } },
      '/documents?limit=5': {
        status: 200,
        body: [{ id: 'd1', originalName: 'moi.pdf', uploaderUsername: 'canbo1', createdAt: '2026-10-13T00:00:00Z' }],
      },
    })
    const granted = new Set(['digitization_review.manage', 'document.read', 'personal.manage'])
    render(<DashboardWidgets session={canBoSession()} can={(c) => granted.has(c)} isAdmin={false} />)

    await waitFor(() => expect(screen.getByText('4')).toBeTruthy())
    expect(screen.getByText('1')).toBeTruthy()
    expect(screen.getByText('90%')).toBeTruthy()
    expect(screen.getByRole('link', { name: 'moi.pdf' })).toHaveAttribute('href', '/documents/d1')
    // admin-only tiles stay hidden
    expect(screen.queryByText('Dung lượng kho')).toBeNull()
  })

  it('shows warehouse capacity and blocked accounts for an admin', async () => {
    installFetchMock({
      '/warehouse/capacity': {
        status: 200,
        body: { usedBytes: 10, maxCapacityBytes: 100, usedPercent: 10, status: 'OK', trashBytes: 0 },
      },
      '/accounts': { status: 200, body: [{ status: 'active' }, { status: 'locked' }] },
      '/personal/quota': { status: 200, body: { quotaBytes: 100, usedBytes: 0, remainingBytes: 100 } },
    })
    const granted = new Set(['folder.read', 'account.read', 'personal.manage'])
    render(<DashboardWidgets session={quanTriSession()} can={(c) => granted.has(c)} isAdmin />)

    await waitFor(() => expect(screen.getByText('10.0%')).toBeTruthy())
    expect(screen.getByText('Trên tổng 2 tài khoản')).toBeTruthy()
    expect(screen.queryByText('Chờ kiểm duyệt số hoá')).toBeNull()
  })
})
