import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useSession } from 'next-auth/react'
import TrashPage from '@/app/trash/page'
import { installFetchMock as baseInstallFetchMock } from '@/test-utils/fetchMock'
import type { MockHandler } from '@/test-utils/fetchMock'
import { permissionsHandler, resetPermissions, withPermissions } from '@/test-utils/permissions'
import { canBoSession, lanhDaoSession, quanTriSession } from '@/test-utils/session'
import type { RestoreBatchItem, TrashItem, TrashList } from '@/lib/api'

/**
 * app/trash/page.tsx (delete-restore-trash-01-xoa-mem-thung-rac.md Story 1 +
 * -03-restore Story 3 AC-9): role gating (R1 only), tab switching (own vs
 * uploaded, incl. the "Người xóa" column and each tab's empty-state copy),
 * the ≤30-day purgeAfter warning class, and bulk restore (restore-batch +
 * the CONFLICT/ERROR "Xử lý" follow-up via RestoreDialog's initialError,
 * with no second restore call).
 */
vi.mock('next-auth/react', () => ({
  useSession: vi.fn(),
  signOut: vi.fn(),
}))
vi.mock('next/navigation', () => ({
  usePathname: () => '/trash',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
}))

const mockedUseSession = vi.mocked(useSession)

const installFetchMock = (handlers: Record<string, MockHandler> = {}) =>
  baseInstallFetchMock(withPermissions(handlers))

function useSessionReturn(data: ReturnType<typeof canBoSession> | null, status: string) {
  return { data, status, update: vi.fn() } as unknown as ReturnType<typeof useSession>
}

function item(overrides: Partial<TrashItem> = {}): TrashItem {
  return {
    documentId: 'doc-1',
    originalName: 'bao-cao.pdf',
    deletedAt: '2026-01-01T00:00:00.000Z',
    purgeAfter: new Date(Date.now() + 10 * 86_400_000).toISOString(), // 10 days out -> warn
    size: 2_097_152,
    deletedBySub: 'sub-1',
    deletedByUsername: 'canbo1',
    uploaderSub: 'sub-2',
    uploaderUsername: 'canbo2',
    ...overrides,
  }
}

function list(items: TrashItem[]): TrashList {
  return { items, total: items.length }
}

describe('TrashPage', () => {
  afterEach(() => {
    resetPermissions()
    cleanup()
    vi.restoreAllMocks()
  })

  it('renders nothing while the session is loading', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(null, 'loading'))
    installFetchMock()
    const { container } = render(<TrashPage />)
    expect(container).toBeEmptyDOMElement()
  })

  it('shows AccessDenied for a lanh-dao (R2) user without trash.manage (AC-3)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(lanhDaoSession(), 'authenticated'))
    installFetchMock({ '/me/permissions': permissionsHandler([]) })
    render(<TrashPage />)
    expect(await screen.findByText(/không có quyền/i)).toBeTruthy()
  })

  it('renders the trash page for a lanh-dao (R2) user holding trash.manage (AC-2)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(lanhDaoSession(), 'authenticated'))
    installFetchMock({ '/trash?view=own': { status: 200, body: list([item()]) } })
    render(<TrashPage />)
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())
  })

  it('shows AccessDenied for a quan-tri (R3) user (no trash.manage)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock({ '/me/permissions': permissionsHandler([]) })
    render(<TrashPage />)
    expect(await screen.findByText(/không có quyền/i)).toBeTruthy()
  })

  it('loads the "own" view by default, hiding the "Người xóa" column', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({
      '/trash?view=own': { status: 200, body: list([item()]) },
    })
    render(<TrashPage />)

    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())
    expect(screen.queryByText('Người xóa')).toBeNull()
  })

  it('switching to "uploaded" re-fetches with view=uploaded and shows the "Người xóa" column', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      '/trash?view=own': { status: 200, body: list([item()]) },
      '/trash?view=uploaded': {
        status: 200,
        body: list([item({ originalName: 'khac-xoa.pdf', deletedByUsername: 'nguoi-khac' })]),
      },
    })
    render(<TrashPage />)
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())

    fireEvent.click(screen.getByText('Tài liệu tôi nạp bị người khác xóa'))

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/trash?view=uploaded'), expect.anything()),
    )
    await waitFor(() => expect(screen.getByText('khac-xoa.pdf')).toBeTruthy())
    expect(screen.getByText('Người xóa')).toBeTruthy()
    expect(screen.getByText('nguoi-khac')).toBeTruthy()
  })

  it('shows the "own" empty-state copy when there are no items (AC-4)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({ '/trash?view=own': { status: 200, body: list([]) } })
    render(<TrashPage />)
    await waitFor(() => expect(screen.getByText('Thùng rác của bạn đang trống.')).toBeTruthy())
  })

  it('shows the "uploaded" empty-state copy when there are no items (AC-4)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    installFetchMock({
      '/trash?view=own': { status: 200, body: list([item()]) },
      '/trash?view=uploaded': { status: 200, body: list([]) },
    })
    render(<TrashPage />)
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())
    fireEvent.click(screen.getByText('Tài liệu tôi nạp bị người khác xóa'))
    await waitFor(() =>
      expect(screen.getByText('Không có tài liệu nào bạn nạp bị người khác xóa.')).toBeTruthy(),
    )
  })

  it('renders the red/bold warning class when purgeAfter is within 30 days, and not otherwise', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const soon = item({
      documentId: 'doc-soon',
      originalName: 'sap-het-han.pdf',
      purgeAfter: new Date(Date.now() + 10 * 86_400_000).toISOString(),
    })
    const later = item({
      documentId: 'doc-later',
      originalName: 'con-lau.pdf',
      purgeAfter: new Date(Date.now() + 60 * 86_400_000).toISOString(),
    })
    installFetchMock({ '/trash?view=own': { status: 200, body: list([soon, later]) } })
    render(<TrashPage />)
    await waitFor(() => expect(screen.getByText('sap-het-han.pdf')).toBeTruthy())

    const soonRow = screen.getByText('sap-het-han.pdf').closest('tr')!
    const laterRow = screen.getByText('con-lau.pdf').closest('tr')!
    expect(within(soonRow).getByText(/Còn \d+ ngày/).className).toContain('text-red-600')
    expect(within(laterRow).getByText(/Còn \d+ ngày/).className).not.toContain('text-red-600')
  })

  it('bulk-restores selected rows, shows the report, and reloads the list (AC-9)', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const a = item({ documentId: 'doc-a', originalName: 'a.pdf' })
    const b = item({ documentId: 'doc-b', originalName: 'b.pdf' })
    const batchResults: RestoreBatchItem[] = [
      { documentId: 'doc-a', status: 'RESTORED', code: null, restoredFolderId: 'folder-1', groupsLostEffect: [] },
      { documentId: 'doc-b', status: 'CONFLICT', code: 'NAME_CONFLICT', restoredFolderId: null, groupsLostEffect: null },
    ]
    const fetchMock = installFetchMock({
      '/trash/restore-batch': { status: 200, body: { results: batchResults } },
      // After the batch call, the page reloads /trash - doc-a (RESTORED) is
      // now gone from the source of truth, only doc-b remains.
      '/trash?view=own': { status: 200, body: list([a, b]) },
    })
    render(<TrashPage />)
    await waitFor(() => expect(screen.getByText('a.pdf')).toBeTruthy())

    const checkboxes = screen.getAllByRole('checkbox')
    fireEvent.click(checkboxes[0])
    fireEvent.click(checkboxes[1])
    fireEvent.click(screen.getByRole('button', { name: 'Phục hồi đã chọn' }))

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining('/trash/restore-batch'),
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ documentIds: ['doc-a', 'doc-b'] }),
        }),
      ),
    )
    await waitFor(() => expect(screen.getByText('Đã phục hồi 1/2 tài liệu.')).toBeTruthy())
    expect(screen.getByText(/doc-b — Xung đột \(NAME_CONFLICT\)/)).toBeTruthy()

    // GET /trash was called again (reload) after the batch response.
    const getTrashCalls = fetchMock.mock.calls.filter(
      ([url, init]) => String(url).includes('/trash?view=own') && (init?.method ?? 'GET') === 'GET',
    )
    expect(getTrashCalls.length).toBe(2)
  })

  it('"Xử lý" on a CONFLICT batch item opens RestoreDialog with initialError, without a second restore call', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    const a = item({ documentId: 'doc-a', originalName: 'a.pdf' })
    const batchResults: RestoreBatchItem[] = [
      { documentId: 'doc-a', status: 'CONFLICT', code: 'NAME_CONFLICT', restoredFolderId: null, groupsLostEffect: null },
    ]
    const fetchMock = installFetchMock({
      '/trash/restore-batch': { status: 200, body: { results: batchResults } },
      '/trash?view=own': { status: 200, body: list([a]) },
    })
    render(<TrashPage />)
    await waitFor(() => expect(screen.getByText('a.pdf')).toBeTruthy())

    fireEvent.click(screen.getAllByRole('checkbox')[0])
    fireEvent.click(screen.getByRole('button', { name: 'Phục hồi đã chọn' }))
    await waitFor(() => expect(screen.getByText('Đã phục hồi 0/1 tài liệu.')).toBeTruthy())

    fireEvent.click(screen.getByRole('button', { name: 'Xử lý' }))

    // Jumps straight to the name-conflict step (no extra fetch for /trash/doc-a/restore).
    await waitFor(() => expect(screen.getByText('Đổi tên')).toBeTruthy())
    expect(fetchMock).not.toHaveBeenCalledWith(
      expect.stringContaining('/trash/doc-a/restore'),
      expect.anything(),
    )
  })

  describe('several files stopped on NAME_CONFLICT (AC-23)', () => {
    const conflicts: RestoreBatchItem[] = ['doc-a', 'doc-b'].map((documentId) => ({
      documentId,
      status: 'CONFLICT',
      code: 'NAME_CONFLICT',
      restoredFolderId: null,
      groupsLostEffect: null,
    }))

    beforeEach(() => {
      mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    })

    async function startBatch(extra: Record<string, unknown> = {}) {
      const a = item({ documentId: 'doc-a', originalName: 'a.pdf' })
      const b = item({ documentId: 'doc-b', originalName: 'b.pdf' })
      let batchCalls = 0
      const fetchMock = installFetchMock({
        '/trash/restore-batch': (_url, init) => {
          batchCalls += 1
          if (batchCalls === 1) return { status: 200, body: { results: conflicts } }
          const sent = JSON.parse(String(init?.body))
          return {
            status: 200,
            body: {
              results: sent.documentIds.map((documentId: string) => ({
                documentId,
                status: sent.onNameConflict === 'AUTO_RENAME' ? 'RESTORED' : 'CONFLICT',
                code: sent.onNameConflict === 'AUTO_RENAME' ? null : 'NAME_CONFLICT',
                restoredFolderId: null,
                groupsLostEffect: null,
              })),
            },
          }
        },
        ...extra,
        '/trash?view=own': { status: 200, body: list([a, b]) },
      })
      render(<TrashPage />)
      await waitFor(() => expect(screen.getByText('a.pdf')).toBeTruthy())
      const boxes = screen.getAllByRole('checkbox')
      fireEvent.click(boxes[0])
      fireEvent.click(boxes[1])
      fireEvent.click(screen.getByRole('button', { name: 'Phục hồi đã chọn' }))
      await waitFor(() => expect(screen.getByLabelText(/Áp dụng cho tất cả/)).toBeTruthy())
      return fetchMock
    }

    it('ticked + "Đổi tên": ONE restore-batch with AUTO_RENAME for every conflicting file', async () => {
      const fetchMock = await startBatch()
      fireEvent.click(screen.getByLabelText(/Áp dụng cho tất cả/))
      fireEvent.click(screen.getByText('Đổi tên'))

      await waitFor(() =>
        expect(fetchMock).toHaveBeenCalledWith(
          expect.stringContaining('/trash/restore-batch'),
          expect.objectContaining({
            body: JSON.stringify({ documentIds: ['doc-a', 'doc-b'], onNameConflict: 'AUTO_RENAME' }),
          }),
        ),
      )
      const batchCalls = fetchMock.mock.calls.filter(([url]) => String(url).includes('/trash/restore-batch'))
      expect(batchCalls).toHaveLength(2) // the initial one + exactly one for "all"
      await waitFor(() => expect(screen.queryByText('Dừng khôi phục')).toBeNull())
    })

    it('ticked + "Dừng khôi phục": ONE restore-batch with CANCEL', async () => {
      const fetchMock = await startBatch()
      fireEvent.click(screen.getByLabelText(/Áp dụng cho tất cả/))
      fireEvent.click(screen.getByText('Dừng khôi phục'))
      await waitFor(() =>
        expect(fetchMock).toHaveBeenCalledWith(
          expect.stringContaining('/trash/restore-batch'),
          expect.objectContaining({
            body: JSON.stringify({ documentIds: ['doc-a', 'doc-b'], onNameConflict: 'CANCEL' }),
          }),
        ),
      )
    })

    it('unticked: asks per file, each "Đổi tên" calls /trash/{id}/restore separately', async () => {
      const fetchMock = await startBatch({
        '/trash/doc-a/restore': { status: 200, body: { documentId: 'doc-a', restoredFolderId: 'f', restoredName: 'a2.pdf', groupsLostEffect: [] } },
        '/trash/doc-b/restore': { status: 200, body: { documentId: 'doc-b', restoredFolderId: 'f', restoredName: 'b2.pdf', groupsLostEffect: [] } },
      })
      fireEvent.click(screen.getByText('Đổi tên'))
      await waitFor(() =>
        expect(fetchMock).toHaveBeenCalledWith(
          expect.stringContaining('/trash/doc-a/restore'),
          expect.objectContaining({ body: JSON.stringify({ onNameConflict: 'AUTO_RENAME' }) }),
        ),
      )
      // the popup moves on to the next conflicting file (no more checkbox: nobody else is waiting)
      await waitFor(() => expect(screen.queryByLabelText(/Áp dụng cho tất cả/)).toBeNull())
      fireEvent.click(await screen.findByText('Đổi tên'))
      await waitFor(() =>
        expect(fetchMock).toHaveBeenCalledWith(
          expect.stringContaining('/trash/doc-b/restore'),
          expect.objectContaining({ body: JSON.stringify({ onNameConflict: 'AUTO_RENAME' }) }),
        ),
      )
    })
  })
})
