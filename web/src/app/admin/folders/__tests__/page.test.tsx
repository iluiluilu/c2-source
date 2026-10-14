import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useSession } from 'next-auth/react'
import FolderAdminPage from '@/app/admin/folders/page'
import { installFetchMock } from '@/test-utils/fetchMock'
import { canBoSession, quanTriSession } from '@/test-utils/session'
import type { FolderNode, WarehouseCapacity } from '@/lib/api'

vi.mock('next-auth/react', () => ({
  useSession: vi.fn(),
  signOut: vi.fn(),
}))
vi.mock('next/navigation', () => ({
  usePathname: () => '/admin/folders',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
}))

const mockedUseSession = vi.mocked(useSession)

function useSessionReturn(data: ReturnType<typeof canBoSession> | null, status: string) {
  return { data, status, update: vi.fn() } as unknown as ReturnType<typeof useSession>
}

const TREE: FolderNode[] = [
  { id: 'folder-1', name: 'Chuyen an A', depth: 1, children: [] },
]

const CAPACITY: WarehouseCapacity = {
  usedBytes: 1_073_741_824,
  maxCapacityBytes: 10_737_418_240,
  usedPercent: 10,
  warningThresholdPercent: 80,
  blockThresholdPercent: 95,
  status: 'OK',
  trashBytes: 104_857_600, // 100 MB - well under 20% of usedBytes (Story 5 Quyết định 4)
}

function installFoldersFetch(overrides: Record<string, unknown> = {}) {
  const base: Record<string, unknown> = {
    '/folders/tree': { status: 200, body: TREE },
    '/warehouse/capacity': { status: 200, body: CAPACITY },
  }
  // Overrides win both on value and on match order (installFetchMock matches
  // by substring, first-match-wins) - merge overrides first, then only the
  // base entries whose key wasn't already supplied by the caller.
  const merged: Record<string, unknown> = { ...overrides }
  for (const [key, value] of Object.entries(base)) {
    if (!(key in merged)) merged[key] = value
  }
  return installFetchMock(merged as Parameters<typeof installFetchMock>[0])
}

describe('FolderAdminPage', () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
  })

  it('renders nothing while the session is loading', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(null, 'loading'))
    const { container } = render(<FolderAdminPage />)
    expect(container).toBeEmptyDOMElement()
  })

  it('shows AccessDenied for a non-admin user', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    render(<FolderAdminPage />)
    expect(screen.getByText(/không có quyền/i)).toBeTruthy()
  })

  it('loads and renders the folder tree and capacity for a quan-tri user', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFoldersFetch()
    render(<FolderAdminPage />)

    await waitFor(() => expect(screen.getByRole('button', { name: 'Chuyen an A' })).toBeTruthy())
    expect(screen.getByText(/Đã dùng: 1.0 GB \/ 10.0 GB/)).toBeTruthy()
    expect(screen.getByText('10.0%')).toBeTruthy()
    expect(screen.getByText(/Bình thường/)).toBeTruthy()
  })

  it('creates a folder at the root', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    const fetchMock = installFoldersFetch({
      '/folders': (url: string, init: RequestInit | undefined, method: string) => {
        if (method === 'POST') return { status: 201, body: { id: 'new-folder', name: 'Kho moi', depth: 1, children: [] } }
        return { status: 200, body: TREE }
      },
    })
    render(<FolderAdminPage />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Chuyen an A' })).toBeTruthy())

    fireEvent.change(screen.getByPlaceholderText('VD: Chuyen an A'), { target: { value: 'Kho moi' } })
    fireEvent.click(screen.getByRole('button', { name: 'Tạo thư mục' }))

    await waitFor(() => expect(screen.getByText('Đã tạo thư mục.')).toBeTruthy())
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/folders'),
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('selects a folder and renames it', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    const fetchMock = installFoldersFetch({
      '/folders/folder-1': { status: 200, body: null },
    })
    render(<FolderAdminPage />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Chuyen an A' })).toBeTruthy())

    fireEvent.click(screen.getByRole('button', { name: 'Chuyen an A' }))
    // The rename field starts with the current name; unchanged = nothing to save.
    const renameInput = screen.getByLabelText('Tên mới của thư mục đang chọn') as HTMLInputElement
    expect(renameInput.value).toBe('Chuyen an A')
    expect(screen.getByRole('button', { name: 'Đổi tên thư mục đang chọn' })).toBeDisabled()
    // A folder at the root cannot be "moved" to the root again.
    expect(screen.getByRole('button', { name: 'Di chuyển' })).toBeDisabled()
    fireEvent.change(renameInput, { target: { value: 'Chuyen an A2' } })
    fireEvent.click(screen.getByRole('button', { name: 'Đổi tên thư mục đang chọn' }))

    await waitFor(() => expect(screen.getByText('Đã đổi tên thư mục.')).toBeTruthy())
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/folders/folder-1'),
      expect.objectContaining({ method: 'PATCH' }),
    )
  })

  it('deletes the selected folder', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    const fetchMock = installFoldersFetch({
      '/folders/folder-1': { status: 204, body: null },
    })
    render(<FolderAdminPage />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Chuyen an A' })).toBeTruthy())

    fireEvent.click(screen.getByRole('button', { name: 'Chuyen an A' }))
    fireEvent.click(screen.getByRole('button', { name: 'Xóa thư mục đang chọn' }))

    await waitFor(() => expect(screen.getByText('Đã xóa thư mục.')).toBeTruthy())
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/folders/folder-1'),
      expect.objectContaining({ method: 'DELETE' }),
    )
  })

  it('shows a friendly hint for a 409 MAX_DEPTH_EXCEEDED conflict on create', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFoldersFetch({
      '/folders': (url: string, init: RequestInit | undefined, method: string) => {
        if (method === 'POST') return { status: 409, body: { code: 'MAX_DEPTH_EXCEEDED', message: 'Không thể tạo.' } }
        return { status: 200, body: TREE }
      },
    })
    render(<FolderAdminPage />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Chuyen an A' })).toBeTruthy())

    fireEvent.change(screen.getByPlaceholderText('VD: Chuyen an A'), { target: { value: 'Sau qua' } })
    fireEvent.click(screen.getByRole('button', { name: 'Tạo thư mục' }))

    await waitFor(() =>
      expect(screen.getByText(/vượt quá độ sâu tối đa 5 cấp/)).toBeTruthy(),
    )
  })

  it('updates the warehouse capacity', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    const updated: WarehouseCapacity = { ...CAPACITY, maxCapacityBytes: 21_474_836_480, usedPercent: 5 }
    installFoldersFetch({
      '/warehouse/capacity': (url: string, init: RequestInit | undefined, method: string) => {
        if (method === 'PATCH') return { status: 200, body: updated }
        return { status: 200, body: CAPACITY }
      },
    })
    render(<FolderAdminPage />)
    await waitFor(() => expect(screen.getByText(/Đã dùng: 1.0 GB \/ 10.0 GB/)).toBeTruthy())

    fireEvent.change(screen.getByPlaceholderText('VD: 10'), { target: { value: '20' } })
    fireEvent.click(screen.getByRole('button', { name: 'Lưu' }))

    await waitFor(() => expect(screen.getByText('Đã cập nhật dung lượng tối đa của kho.')).toBeTruthy())
    expect(screen.getByText(/Đã dùng: 1.0 GB \/ 20.0 GB/)).toBeTruthy()
  })

  it('rejects an invalid (non-positive) capacity value', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFoldersFetch()
    render(<FolderAdminPage />)
    await waitFor(() => expect(screen.getByText(/Đã dùng: 1.0 GB \/ 10.0 GB/)).toBeTruthy())

    fireEvent.change(screen.getByPlaceholderText('VD: 10'), { target: { value: '0' } })
    fireEvent.click(screen.getByRole('button', { name: 'Lưu' }))

    expect(screen.getByText('Dung lượng phải là số lớn hơn 0 (GB).')).toBeTruthy()
  })

  // ---- delete-restore-trash-01 Story 5 AC-1/AC-2: trashBytes ----

  it('shows "Trong đó, thùng rác" with the formatted trashBytes size', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFoldersFetch()
    render(<FolderAdminPage />)

    await waitFor(() => expect(screen.getByText(/Trong đó, thùng rác: 100.0 MB/)).toBeTruthy())
  })

  it('shows the >=20% trash-share hint when trashBytes/usedBytes crosses the threshold', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    const highTrash: WarehouseCapacity = { ...CAPACITY, trashBytes: 300_000_000 } // ~28% of usedBytes
    installFoldersFetch({ '/warehouse/capacity': { status: 200, body: highTrash } })
    render(<FolderAdminPage />)

    await waitFor(() =>
      expect(
        screen.getByText(/Phần lớn dung lượng đang dùng là thùng rác/),
      ).toBeTruthy(),
    )
  })

  it('hides the trash-share hint when below 20%, and does not divide by zero when usedBytes is 0', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    const zeroUsed: WarehouseCapacity = { ...CAPACITY, usedBytes: 0, trashBytes: 0, usedPercent: 0 }
    installFoldersFetch({ '/warehouse/capacity': { status: 200, body: zeroUsed } })
    render(<FolderAdminPage />)

    await waitFor(() => expect(screen.getByText(/Trong đó, thùng rác: 0 B/)).toBeTruthy())
    expect(screen.queryByText(/Phần lớn dung lượng đang dùng là thùng rác/)).toBeNull()
  })
})
