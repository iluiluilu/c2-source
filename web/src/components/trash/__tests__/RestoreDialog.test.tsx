import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useSession } from 'next-auth/react'
import { RestoreDialog } from '@/components/trash/RestoreDialog'
import { installFetchMock } from '@/test-utils/fetchMock'
import { canBoSession } from '@/test-utils/session'
import type { FolderNode, RestoreResult } from '@/lib/api'

/**
 * delete-restore-trash-02-xoa-cung-xung-dot.md §3: RestoreDialog xử lý đủ 8
 * case xung đột (mục 4.5 của tech spec) - mount gọi restore ngay (không
 * initialError) hoặc nhảy thẳng vào step tương ứng (có initialError, dùng
 * cho việc xử lý item lỗi sau restore-batch, không gọi lại restore lần 2).
 */
vi.mock('next-auth/react', () => ({
  useSession: vi.fn(),
}))

const mockedUseSession = vi.mocked(useSession)

const folderTree: FolderNode[] = [
  {
    id: 'folder-1',
    name: 'Phòng ban A',
    depth: 1,
    children: [{ id: 'folder-2', name: 'Phòng ban A / con', depth: 2, children: [] }],
  },
]

function restoreResult(overrides: Partial<RestoreResult> = {}): RestoreResult {
  return {
    documentId: 'doc-1',
    restoredFolderId: 'folder-1',
    restoredName: 'bao-cao.pdf',
    groupsLostEffect: [],
    ...overrides,
  }
}

describe('RestoreDialog', () => {
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

  it('mounts without initialError: calls restore immediately with an empty body, success calls onSuccess', async () => {
    const fetchMock = installFetchMock({
      '/trash/doc-1/restore': { status: 200, body: restoreResult() },
    })
    const onSuccess = vi.fn()
    render(<RestoreDialog documentId="doc-1" onClose={vi.fn()} onSuccess={onSuccess} />)

    await waitFor(() => expect(screen.getByText('Phục hồi thành công.')).toBeTruthy())
    expect(onSuccess).toHaveBeenCalledWith(restoreResult())
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/trash/doc-1/restore'),
      expect.objectContaining({ method: 'POST', body: JSON.stringify({}) }),
    )
  })

  it('FOLDER_MISSING with a suggestedFolderId: pre-selects that folder, "Phục hồi" is enabled', async () => {
    installFetchMock({ '/folders/tree': { status: 200, body: folderTree } })
    render(
      <RestoreDialog
        documentId="doc-1"
        initialError={{ status: 409, body: { code: 'FOLDER_MISSING', suggestedFolderId: 'folder-1' } }}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
      />,
    )

    await waitFor(() => expect(screen.getByRole('button', { name: 'Phòng ban A' })).toBeTruthy())
    expect(screen.getByRole('button', { name: 'Phòng ban A' }).className).toContain('bg-[#0D2546]')
    expect(screen.getByRole('button', { name: 'Phục hồi' })).not.toBeDisabled()
  })

  it('FOLDER_MISSING with suggestedFolderId=null: nothing pre-selected, "Phục hồi" disabled until a node is picked', async () => {
    installFetchMock({ '/folders/tree': { status: 200, body: folderTree } })
    render(
      <RestoreDialog
        documentId="doc-1"
        initialError={{ status: 409, body: { code: 'FOLDER_MISSING', suggestedFolderId: null } }}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
      />,
    )

    await waitFor(() => expect(screen.getByRole('button', { name: 'Phòng ban A' })).toBeTruthy())
    expect(screen.getByRole('button', { name: 'Phục hồi' })).toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: 'Phòng ban A' }))
    expect(screen.getByRole('button', { name: 'Phục hồi' })).not.toBeDisabled()
  })

  it('NAME_CONFLICT: shows "Đổi tên" / "Dừng khôi phục"; "Đổi tên" retries with onNameConflict=AUTO_RENAME', async () => {
    const fetchMock = installFetchMock({
      '/trash/doc-1/restore': { status: 200, body: restoreResult({ restoredName: 'bao-cao (khôi phục).pdf' }) },
    })
    render(
      <RestoreDialog
        documentId="doc-1"
        initialError={{ status: 409, body: { code: 'NAME_CONFLICT', suggestedName: 'bao-cao (khôi phục).pdf' } }}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
      />,
    )

    expect(screen.getByText('Đổi tên')).toBeTruthy()
    expect(screen.getByText('Dừng khôi phục')).toBeTruthy()
    // folder switching / overwrite are gone when the name clashes
    expect(screen.queryByText('Chọn thư mục khác')).toBeNull()
    expect(screen.queryByText(/Ghi đè/)).toBeNull()
    expect(screen.getByText(/Tên mới: bao-cao \(khôi phục\)\.pdf/)).toBeTruthy()

    fireEvent.click(screen.getByText('Đổi tên'))

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining('/trash/doc-1/restore'),
        expect.objectContaining({ method: 'POST', body: JSON.stringify({ onNameConflict: 'AUTO_RENAME' }) }),
      ),
    )
    await waitFor(() => expect(screen.getByText('Phục hồi thành công.')).toBeTruthy())
  })

  it('NAME_CONFLICT: "Dừng khôi phục" calls onClose (no restore call)', () => {
    const onClose = vi.fn()
    const fetchMock = installFetchMock()
    render(
      <RestoreDialog
        documentId="doc-1"
        initialError={{ status: 409, body: { code: 'NAME_CONFLICT', suggestedName: 'bao-cao (khôi phục).pdf' } }}
        onClose={onClose}
        onSuccess={vi.fn()}
      />,
    )

    fireEvent.click(screen.getByText('Dừng khôi phục'))
    expect(onClose).toHaveBeenCalled()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('NAME_CONFLICT with other conflicting files: "Áp dụng cho tất cả" hands the choice to onApplyAll (AC-23)', () => {
    const onApplyAll = vi.fn()
    const onClose = vi.fn()
    const fetchMock = installFetchMock()
    render(
      <RestoreDialog
        documentId="doc-1"
        initialError={{ status: 409, body: { code: 'NAME_CONFLICT' } }}
        otherConflictCount={2}
        onApplyAll={onApplyAll}
        onClose={onClose}
        onSuccess={vi.fn()}
      />,
    )
    expect(screen.getByLabelText(/Áp dụng cho tất cả \(3 tệp trùng tên\)/)).toBeTruthy()

    fireEvent.click(screen.getByLabelText(/Áp dụng cho tất cả/))
    fireEvent.click(screen.getByText('Đổi tên'))
    expect(onApplyAll).toHaveBeenLastCalledWith('AUTO_RENAME')

    fireEvent.click(screen.getByText('Dừng khôi phục'))
    expect(onApplyAll).toHaveBeenLastCalledWith('CANCEL')
    expect(onClose).not.toHaveBeenCalled()
    expect(fetchMock).not.toHaveBeenCalled() // the page (not the dialog) sends the single restore-batch
  })

  it('NAME_CONFLICT without other conflicts: no "Áp dụng cho tất cả" checkbox', () => {
    installFetchMock()
    render(
      <RestoreDialog
        documentId="doc-1"
        initialError={{ status: 409, body: { code: 'NAME_CONFLICT' } }}
        onApplyAll={vi.fn()}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
      />,
    )
    expect(screen.queryByLabelText(/Áp dụng cho tất cả/)).toBeNull()
  })

  it('GONE: only a "Đóng" button, no other action', () => {
    installFetchMock()
    render(
      <RestoreDialog
        documentId="doc-1"
        initialError={{ status: 410, body: { code: 'GONE' } }}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
      />,
    )

    expect(screen.getAllByRole('button')).toHaveLength(1)
    expect(screen.getByRole('button', { name: 'Đóng' })).toBeTruthy()
  })

  it('PURGING: only a "Đóng" button, no other action', () => {
    installFetchMock()
    render(
      <RestoreDialog
        documentId="doc-1"
        initialError={{ status: 409, body: { code: 'PURGING' } }}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
      />,
    )

    expect(screen.getAllByRole('button')).toHaveLength(1)
    expect(screen.getByRole('button', { name: 'Đóng' })).toBeTruthy()
  })

  it('success with an empty groupsLostEffect shows the plain success message', async () => {
    installFetchMock({
      '/trash/doc-1/restore': { status: 200, body: restoreResult({ groupsLostEffect: [] }) },
    })
    render(<RestoreDialog documentId="doc-1" onClose={vi.fn()} onSuccess={vi.fn()} />)

    await waitFor(() => expect(screen.getByText('Phục hồi thành công.')).toBeTruthy())
    expect(screen.queryByText(/không còn hiệu lực chia sẻ/)).toBeNull()
  })

  it('success with a non-empty groupsLostEffect shows the AC-6 warning banner', async () => {
    installFetchMock({
      '/trash/doc-1/restore': { status: 200, body: restoreResult({ groupsLostEffect: ['g1', 'g2'] }) },
    })
    render(<RestoreDialog documentId="doc-1" onClose={vi.fn()} onSuccess={vi.fn()} />)

    await waitFor(() =>
      expect(
        screen.getByText(/Phục hồi thành công\. 2 nhóm từng chia sẻ tài liệu này đã bị xóa nên không còn hiệu lực chia sẻ\./),
      ).toBeTruthy(),
    )
  })

  it('does not call restore again for an initialError (reuses the batch result)', async () => {
    const fetchMock = installFetchMock()
    render(
      <RestoreDialog
        documentId="doc-1"
        initialError={{ status: 410, body: { code: 'GONE' } }}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
      />,
    )

    expect(fetchMock).not.toHaveBeenCalledWith(
      expect.stringContaining('/trash/doc-1/restore'),
      expect.anything(),
    )
  })
})
