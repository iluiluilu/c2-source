import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useSession } from 'next-auth/react'
import { TrashDetailPanel } from '@/components/trash/TrashDetailPanel'
import { installFetchMock } from '@/test-utils/fetchMock'
import { canBoSession } from '@/test-utils/session'
import type { TrashDetail } from '@/lib/api'

/**
 * delete-restore-trash-01-xoa-mem-thung-rac.md Story 1 AC-6: fetches GET
 * /trash/{id} on mount and shows the folderMissing note; the "Phục hồi"
 * button opens RestoreDialog on top.
 */
vi.mock('next-auth/react', () => ({
  useSession: vi.fn(),
}))

const mockedUseSession = vi.mocked(useSession)

function detail(overrides: Partial<TrashDetail> = {}): TrashDetail {
  return {
    documentId: 'doc-1',
    originalName: 'bao-cao.pdf',
    size: 2_097_152,
    deletedAt: '2026-01-01T00:00:00.000Z',
    purgeAfter: '2026-02-01T00:00:00.000Z',
    deletedBySub: 'sub-1',
    uploaderSub: 'sub-1',
    folderId: 'folder-1',
    folderMissing: false,
    suggestedFolderId: null,
    activeGroupShareCount: 0,
    purgeState: 'active',
    ...overrides,
  }
}

describe('TrashDetailPanel', () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
  })

  beforeEach(() => {
    mockedUseSession.mockReturnValue({
      data: canBoSession(),
      status: 'authenticated',
      update: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)
  })

  it('fetches and renders the trash item detail', async () => {
    installFetchMock({ '/trash/doc-1': { status: 200, body: detail() } })
    render(<TrashDetailPanel documentId="doc-1" onClose={vi.fn()} />)

    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())
    expect(screen.getByText('2.0 MB')).toBeTruthy()
    expect(screen.queryByText(/Thư mục gốc đã bị xóa/)).toBeNull()
  })

  it('shows the folderMissing note with the suggested folder id', async () => {
    installFetchMock({
      '/trash/doc-1': {
        status: 200,
        body: detail({ folderMissing: true, suggestedFolderId: 'folder-abc12345-xyz' }),
      },
    })
    render(<TrashDetailPanel documentId="doc-1" onClose={vi.fn()} />)

    await waitFor(() => expect(screen.getByText(/Thư mục gốc đã bị xóa/)).toBeTruthy())
    expect(screen.getByText(/folder-a/)).toBeTruthy()
  })

  it('calls onClose when "Đóng" is clicked', async () => {
    const onClose = vi.fn()
    installFetchMock({ '/trash/doc-1': { status: 200, body: detail() } })
    render(<TrashDetailPanel documentId="doc-1" onClose={onClose} />)
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())

    fireEvent.click(screen.getByRole('button', { name: 'Đóng' }))
    expect(onClose).toHaveBeenCalled()
  })

  it('opens RestoreDialog when "Phục hồi" is clicked', async () => {
    installFetchMock({
      '/trash/doc-1/restore': { status: 410, body: { code: 'GONE', message: 'Đã quá hạn.' } },
      '/trash/doc-1': { status: 200, body: detail() },
    })
    render(<TrashDetailPanel documentId="doc-1" onClose={vi.fn()} />)
    await waitFor(() => expect(screen.getByText('bao-cao.pdf')).toBeTruthy())

    fireEvent.click(screen.getByRole('button', { name: 'Phục hồi' }))

    await waitFor(() => expect(screen.getByRole('dialog', { name: 'Phục hồi tài liệu' })).toBeTruthy())
  })

  it('shows an error message when the fetch fails', async () => {
    installFetchMock({ '/trash/doc-1': { status: 404, body: { message: 'Không tìm thấy.' } } })
    render(<TrashDetailPanel documentId="doc-1" onClose={vi.fn()} />)
    await waitFor(() => expect(screen.getByText('Không tìm thấy.')).toBeTruthy())
  })
})
