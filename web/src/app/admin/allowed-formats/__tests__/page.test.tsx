import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useSession } from 'next-auth/react'
import AllowedFormatsPage from '@/app/admin/allowed-formats/page'
import { installFetchMock } from '@/test-utils/fetchMock'
import { canBoSession, quanTriSession } from '@/test-utils/session'
import type { AllowedFormatDetail } from '@/lib/api'

vi.mock('next-auth/react', () => ({
  useSession: vi.fn(),
  signOut: vi.fn(),
}))
vi.mock('next/navigation', () => ({
  usePathname: () => '/admin/allowed-formats',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
}))

const mockedUseSession = vi.mocked(useSession)

function useSessionReturn(data: ReturnType<typeof canBoSession> | null, status: string) {
  return { data, status, update: vi.fn() } as unknown as ReturnType<typeof useSession>
}

const FORMAT_ROW: AllowedFormatDetail = {
  mimeType: 'image/png',
  extensions: ['png'],
  category: 'IMAGE',
  updatedAt: '2026-01-01T00:00:00.000Z',
}

describe('AllowedFormatsPage', () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
  })

  it('renders nothing while the session is loading', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(null, 'loading'))
    const { container } = render(<AllowedFormatsPage />)
    expect(container).toBeEmptyDOMElement()
  })

  it('shows AccessDenied for a non-admin user', () => {
    mockedUseSession.mockReturnValue(useSessionReturn(canBoSession(), 'authenticated'))
    render(<AllowedFormatsPage />)
    expect(screen.getByText(/không có quyền/i)).toBeTruthy()
  })

  it('loads and renders the allowed-format rows for a quan-tri user', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock({
      '/admin/allowed-file-formats': { status: 200, body: [FORMAT_ROW] },
    })
    render(<AllowedFormatsPage />)

    expect(screen.getByText('Đang tải…')).toBeTruthy()
    await waitFor(() => expect(screen.getByText('image/png')).toBeTruthy())
    expect(screen.getByText('png')).toBeTruthy()
    expect(screen.getByRole('cell', { name: 'Hình ảnh' })).toBeTruthy()
  })

  it('shows the empty state when there are no rows', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock({
      '/admin/allowed-file-formats': { status: 200, body: [] },
    })
    render(<AllowedFormatsPage />)
    await waitFor(() => expect(screen.getByText('Chưa có định dạng nào.')).toBeTruthy())
  })

  it('validates the create form before submitting', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock({
      '/admin/allowed-file-formats': { status: 200, body: [] },
    })
    render(<AllowedFormatsPage />)
    await waitFor(() => expect(screen.getByText('Chưa có định dạng nào.')).toBeTruthy())

    fireEvent.click(screen.getByRole('button', { name: 'Thêm định dạng' }))
    expect(screen.getByText('Cần nhập MIME type và ít nhất một phần mở rộng.')).toBeTruthy()
  })

  it('creates a new allowed format and shows a success message', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    const fetchMock = installFetchMock({
      '/admin/allowed-file-formats': (url, init, method) => {
        if (method === 'POST') return { status: 201, body: null }
        return { status: 200, body: [] }
      },
    })
    render(<AllowedFormatsPage />)
    await waitFor(() => expect(screen.getByText('Chưa có định dạng nào.')).toBeTruthy())

    fireEvent.change(screen.getByLabelText('MIME type'), {
      target: { value: 'image/webp' },
    })
    fireEvent.change(screen.getByLabelText('Đuôi tệp'), {
      target: { value: 'webp' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Thêm định dạng' }))

    await waitFor(() => expect(screen.getByText('Đã thêm định dạng "image/webp".')).toBeTruthy())
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/admin/allowed-file-formats'),
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('removes an allowed format', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    let deleted = false
    const fetchMock = installFetchMock({
      '/admin/allowed-file-formats/image/png': { status: 204, body: null },
      '/admin/allowed-file-formats': (url, init, method) => {
        if (method === 'DELETE') {
          deleted = true
          return { status: 204, body: null }
        }
        return { status: 200, body: deleted ? [] : [FORMAT_ROW] }
      },
    })
    render(<AllowedFormatsPage />)
    await waitFor(() => expect(screen.getByText('image/png')).toBeTruthy())

    fireEvent.click(screen.getByRole('button', { name: 'Gỡ' }))

    await waitFor(() => expect(screen.getByText('Đã gỡ định dạng "image/png".')).toBeTruthy())
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/admin/allowed-file-formats/image/png'),
      expect.objectContaining({ method: 'DELETE' }),
    )
  })

  it('shows an API error message when create fails', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock({
      '/admin/allowed-file-formats': (url, init, method) => {
        if (method === 'POST') return { status: 400, body: { message: 'Định dạng đã tồn tại.' } }
        return { status: 200, body: [] }
      },
    })
    render(<AllowedFormatsPage />)
    await waitFor(() => expect(screen.getByText('Chưa có định dạng nào.')).toBeTruthy())

    fireEvent.change(screen.getByLabelText('MIME type'), {
      target: { value: 'image/png' },
    })
    fireEvent.change(screen.getByLabelText('Đuôi tệp'), {
      target: { value: 'png' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Thêm định dạng' }))

    await waitFor(() => expect(screen.getByText('Định dạng đã tồn tại.')).toBeTruthy())
  })

  it('fills MIME type and group from a known extension', async () => {
    mockedUseSession.mockReturnValue(useSessionReturn(quanTriSession(), 'authenticated'))
    installFetchMock({ '/admin/allowed-file-formats': { status: 200, body: [] } })
    render(<AllowedFormatsPage />)
    await waitFor(() => expect(screen.getByText('Chưa có định dạng nào.')).toBeTruthy())

    fireEvent.change(screen.getByLabelText('Đuôi tệp'), { target: { value: '.mp3' } })
    expect((screen.getByLabelText('MIME type') as HTMLInputElement).value).toBe('audio/mpeg')
    expect((screen.getByRole('combobox') as HTMLSelectElement).value).toBe('AUDIO')
  })
})
