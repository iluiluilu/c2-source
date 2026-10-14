import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useSession } from 'next-auth/react'
import PersonalPage from '@/app/personal/page'
import { PersonalToWarehouseDialog } from '@/components/PersonalToWarehouseDialog'
import { ConfirmProvider } from '@/components/ConfirmDialog'
import { installFetchMock } from '@/test-utils/fetchMock'
import { R2_BUSINESS_PERMISSIONS, permissionsHandler, resetPermissions } from '@/test-utils/permissions'
import { canBoSession, lanhDaoSession } from '@/test-utils/session'
import type { FolderNode, IngestLimits, PersonalFileRow } from '@/lib/api'
import { classifyFile, collectSelection, folderPathNames, limitWarning } from '@/lib/personalToWarehouse'

/** personal-to-warehouse.md §5 frontend tests (vitest; the spec's "jest" is this repo's vitest). */
vi.mock('next-auth/react', () => ({ useSession: vi.fn(), signOut: vi.fn() }))
vi.mock('next/navigation', () => ({
  usePathname: () => '/personal',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
}))

const mockedUseSession = vi.mocked(useSession)
const asSession = (data: unknown, status: string) =>
  ({ data, status, update: vi.fn() }) as unknown as ReturnType<typeof useSession>

const png = { mimeType: 'image/png', extensions: ['png'] }
const limits: IngestLimits = {
  maxFilesPerBatch: 2,
  maxBatchSizeBytes: 1000,
  maxFileSizeBytes: 500,
  chunkThresholdBytes: 100,
}

function row(id: string, name: string, extra: Partial<PersonalFileRow> = {}): PersonalFileRow {
  return {
    id,
    folderId: null,
    originalName: name,
    displayName: name,
    relativePath: '',
    mimeType: 'image/png',
    size: '100',
    sha256: 'x',
    batchId: null,
    createdAt: '2026-01-01T00:00:00.000Z',
    ...extra,
  }
}

const personalTree: FolderNode[] = [
  { id: 'A', name: 'A', depth: 1, children: [{ id: 'B', name: 'B', depth: 2, children: [] }] },
]
const warehouseTree: FolderNode[] = [
  { id: 'W1', name: 'Kho 1', depth: 1, children: [] },
  { id: 'W2', name: 'Kho 2', depth: 1, children: [{ id: 'W21', name: 'Hợp đồng', depth: 2, children: [] }] },
]

describe('personalToWarehouse helpers', () => {
  it('collectSelection expands folder subtrees and de-duplicates hand-picked files', () => {
    const files = [
      row('1', 'a.png', { folderId: 'A' }),
      row('2', 'b.png', { folderId: 'B' }),
      row('3', 'c.png'),
      row('4', 'd.png'),
    ]
    const picked = collectSelection(personalTree, files, ['1', '3'], ['A'])
    expect(picked.map((f) => f.id).sort()).toEqual(['1', '2', '3'])
  })

  it('classifyFile flags formats outside the allowlist and oversize files', () => {
    expect(classifyFile(row('1', 'a.png'), [png], limits)).toBe('OK')
    expect(classifyFile(row('1', 'a.exe', { mimeType: 'application/x-msdownload' }), [png], limits)).toBe(
      'INVALID_FORMAT',
    )
    expect(classifyFile(row('1', 'a.png', { size: '501' }), [png], limits)).toBe('FILE_TOO_LARGE')
  })

  it('folderPathNames resolves a nested destination and returns null when missing', () => {
    expect(folderPathNames(warehouseTree, 'W21')).toEqual(['Kho 2', 'Hợp đồng'])
    expect(folderPathNames(warehouseTree, 'W1')).toEqual(['Kho 1'])
    expect(folderPathNames(warehouseTree, 'nope')).toBeNull()
  })

  it('limitWarning only counts what is passed in (eligible files)', () => {
    expect(limitWarning([row('1', 'a'), row('2', 'b')], limits)).toEqual({
      overFileCount: false,
      overTotalBytes: false,
    })
    expect(limitWarning([row('1', 'a'), row('2', 'b'), row('3', 'c')], limits).overFileCount).toBe(true)
    expect(limitWarning([row('1', 'a', { size: '1001' })], limits).overTotalBytes).toBe(true)
  })
})

describe('PersonalPage - Bổ sung vào kho button', () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
    resetPermissions()
  })

  const handlers = (files: PersonalFileRow[], permissions: readonly string[] = R2_BUSINESS_PERMISSIONS) => ({
    '/me/permissions': permissionsHandler(permissions),
    '/personal/quota': { status: 200, body: { quotaBytes: 1000, usedBytes: 0, remainingBytes: 1000, chunkThresholdBytes: 1 } },
    '/personal/folders/tree': { status: 200, body: [] },
    '/personal/files': { status: 200, body: files },
  })

  it('is shown for R1 (can upload) and disabled until a file is ticked', async () => {
    mockedUseSession.mockReturnValue(asSession(canBoSession(), 'authenticated'))
    installFetchMock(handlers([row('1', 'a.png')]))
    render(
      <ConfirmProvider>
        <PersonalPage />
      </ConfirmProvider>,
    )
    const button = await screen.findByRole('button', { name: 'Bổ sung vào kho' })
    expect((button as HTMLButtonElement).disabled).toBe(true)
    fireEvent.click(await screen.findByLabelText('Chọn a.png'))
    expect((button as HTMLButtonElement).disabled).toBe(false)
  })

  it('is shown for R2 holding document.upload (AC-2)', async () => {
    mockedUseSession.mockReturnValue(asSession(lanhDaoSession(), 'authenticated'))
    installFetchMock(handlers([row('1', 'a.png')]))
    render(
      <ConfirmProvider>
        <PersonalPage />
      </ConfirmProvider>,
    )
    expect(await screen.findByRole('button', { name: 'Bổ sung vào kho' })).toBeTruthy()
  })

  it('is hidden for R2 without document.upload (AC-3)', async () => {
    mockedUseSession.mockReturnValue(asSession(lanhDaoSession(), 'authenticated'))
    installFetchMock(handlers([row('1', 'a.png')], []))
    render(
      <ConfirmProvider>
        <PersonalPage />
      </ConfirmProvider>,
    )
    await screen.findByText('a.png')
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0))
    })
    expect(screen.queryByRole('button', { name: 'Bổ sung vào kho' })).toBeNull()
    expect(screen.queryByLabelText('Chọn a.png')).toBeNull()
  })

  it('shows the "đã bổ sung" badge from addedToWarehouse', async () => {
    mockedUseSession.mockReturnValue(asSession(canBoSession(), 'authenticated'))
    installFetchMock(handlers([row('1', 'a.png', { addedToWarehouse: true }), row('2', 'b.png')]))
    render(
      <ConfirmProvider>
        <PersonalPage />
      </ConfirmProvider>,
    )
    await screen.findByText('b.png')
    expect(screen.getAllByText('Đã bổ sung vào kho')).toHaveLength(1)
  })
})

describe('PersonalToWarehouseDialog', () => {
  beforeEach(() => {
    mockedUseSession.mockReturnValue(asSession(canBoSession(), 'authenticated'))
  })
  afterEach(() => {
    cleanup()
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  const baseHandlers = {
    '/folders/tree': { status: 200, body: warehouseTree },
    '/ingest/limits': { status: 200, body: limits },
    '/ingest/allowed-formats': { status: 200, body: [png] },
  }

  function renderDialog(files: PersonalFileRow[], extra: Record<string, unknown> = {}) {
    installFetchMock({ ...baseHandlers, ...extra } as never)
    return render(
      <PersonalToWarehouseDialog
        session={canBoSession()}
        files={files}
        fileIds={files.map((f) => f.id)}
        folderIds={[]}
        onClose={vi.fn()}
        onFinished={vi.fn()}
      />,
    )
  }

  it('requires a destination folder before continuing (AC-6)', async () => {
    renderDialog([row('1', 'a.png')])
    const next = screen.getByRole('button', { name: 'Tiếp tục' }) as HTMLButtonElement
    expect(next.disabled).toBe(true)
    fireEvent.click(await screen.findByRole('button', { name: 'Kho 1' }))
    expect(next.disabled).toBe(false)
  })

  it('marks ineligible files and warns/blocks when eligible files exceed the batch limit (AC-7/AC-8)', async () => {
    const files = [
      row('1', 'a.png'),
      row('2', 'b.png'),
      row('3', 'c.png'),
      row('4', 'virus.exe', { mimeType: 'application/x-msdownload' }),
    ]
    renderDialog(files)
    fireEvent.click(await screen.findByRole('button', { name: 'Kho 1' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Tiếp tục' })).toBeTruthy())
    fireEvent.click(screen.getByRole('button', { name: 'Tiếp tục' }))

    expect(await screen.findByText(/Không đủ điều kiện: Định dạng không được phép/)).toBeTruthy()
    expect(screen.getByTestId('p2w-target-path').textContent).toBe('Kho 1')
    expect(screen.getByText(/Vượt giới hạn 2 tệp mỗi lượt/)).toBeTruthy()
    expect((screen.getByRole('button', { name: 'Bổ sung vào kho' }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('polls every 2s and stops once the batch is CLOSED (AC-11/AC-17)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let polls = 0
    const fetchMock = renderDialogWithRun(() => {
      polls += 1
      return {
        status: 200,
        body: {
          batchId: 'b1',
          status: polls >= 3 ? 'CLOSED' : 'OPEN',
          done: polls >= 3 ? 1 : 0,
          total: 1,
          items: [
            {
              personalFileId: '1',
              name: 'a.png',
              status: polls >= 3 ? 'INGESTED' : 'PENDING',
              code: null,
              documentId: null,
              duplicateOf: [],
            },
          ],
        },
      }
    })

    fireEvent.click(await screen.findByRole('button', { name: 'Kho 1' }))
    fireEvent.click(screen.getByRole('button', { name: 'Tiếp tục' }))
    await waitFor(() =>
      expect((screen.getByRole('button', { name: 'Bổ sung vào kho' }) as HTMLButtonElement).disabled).toBe(false),
    )
    fireEvent.click(screen.getByRole('button', { name: 'Bổ sung vào kho' }))

    await waitFor(() => expect(polls).toBe(1)) // immediate first poll
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000)
    })
    expect(polls).toBe(2)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000)
    })
    expect(polls).toBe(3)
    expect(await screen.findByText('Đã bổ sung')).toBeTruthy()

    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000)
    })
    expect(polls).toBe(3) // stopped
    expect(fetchMock).toHaveBeenCalled()
  })

  it('sends name-check results as displayNames: free names kept, taken names get a valid suffix (AC-18)', async () => {
    const fetchMock = renderDialogWithRun(
      () => ({ status: 200, body: { batchId: 'b1', status: 'OPEN', done: 0, total: 1, items: [] } }),
      [{ personalFileId: '1', relativePath: '', originalName: 'a.png', taken: true }],
    )
    fireEvent.click(await screen.findByRole('button', { name: 'Kho 1' }))
    fireEvent.click(screen.getByRole('button', { name: 'Tiếp tục' }))
    await waitFor(() =>
      expect((screen.getByRole('button', { name: 'Bổ sung vào kho' }) as HTMLButtonElement).disabled).toBe(false),
    )
    fireEvent.click(screen.getByRole('button', { name: 'Bổ sung vào kho' }))
    await screen.findByTestId('p2w-progress')

    const start = fetchMock.mock.calls.find(
      ([url, init]) => String(url).endsWith('/ingest/from-personal') && (init as RequestInit)?.method === 'POST',
    )
    const body = JSON.parse((start![1] as RequestInit).body as string)
    expect(body.displayNames).toHaveLength(1)
    expect(body.displayNames[0].personalFileId).toBe('1')
    expect(body.displayNames[0].displayName).toMatch(/^a_\d{17}-[a-z0-9]{6}\.png$/)
  })

  it('falls back to "all taken" (suffix names) when name-check fails (AC-12 spirit)', async () => {
    const fetchMock = renderDialogWithRun(
      () => ({ status: 200, body: { batchId: 'b1', status: 'OPEN', done: 0, total: 1, items: [] } }),
      undefined,
      { '/ingest/from-personal/name-check': { status: 429, body: { message: 'too many' } } },
    )
    fireEvent.click(await screen.findByRole('button', { name: 'Kho 1' }))
    fireEvent.click(screen.getByRole('button', { name: 'Tiếp tục' }))
    await waitFor(() =>
      expect((screen.getByRole('button', { name: 'Bổ sung vào kho' }) as HTMLButtonElement).disabled).toBe(false),
    )
    fireEvent.click(screen.getByRole('button', { name: 'Bổ sung vào kho' }))
    await screen.findByTestId('p2w-progress')
    const start = fetchMock.mock.calls.find(
      ([url, init]) => String(url).endsWith('/ingest/from-personal') && (init as RequestInit)?.method === 'POST',
    )
    const body = JSON.parse((start![1] as RequestInit).body as string)
    expect(body.displayNames[0].displayName).not.toBe('a.png')
  })

  it('offers "Đổi tên & thử lại" for a DISPLAY_NAME_CONFLICT item and PUTs a valid suffix (AC-20, max 3)', async () => {
    const fetchMock = renderDialogWithRun(
      () => ({
        status: 200,
        body: {
          batchId: 'b1',
          status: 'CLOSED',
          done: 2,
          total: 2,
          items: [
            { personalFileId: '1', itemId: 'i1', name: 'a.png', status: 'FAILED', code: 'DISPLAY_NAME_CONFLICT',
              documentId: null, duplicateOf: [], nameRetryCount: 0 },
            { personalFileId: '2', itemId: 'i2', name: 'b.png', status: 'FAILED', code: 'DISPLAY_NAME_CONFLICT',
              documentId: null, duplicateOf: [], nameRetryCount: 3 },
          ],
        },
      }),
      undefined,
      {
        '/ingest/from-personal/b1/items/i1/display-name': { status: 200, body: { itemId: 'i1', status: 'PENDING' } },
      },
    )
    fireEvent.click(await screen.findByRole('button', { name: 'Kho 1' }))
    fireEvent.click(screen.getByRole('button', { name: 'Tiếp tục' }))
    await waitFor(() =>
      expect((screen.getByRole('button', { name: 'Bổ sung vào kho' }) as HTMLButtonElement).disabled).toBe(false),
    )
    fireEvent.click(screen.getByRole('button', { name: 'Bổ sung vào kho' }))

    const renameButtons = await screen.findAllByRole('button', { name: /Đổi tên & thử lại/ })
    expect(renameButtons).toHaveLength(1) // the item that used all 3 renames has no button
    fireEvent.click(renameButtons[0])

    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(([url, init]) =>
          String(url).endsWith('/ingest/from-personal/b1/items/i1/display-name') && (init as RequestInit)?.method === 'PUT'),
      ).toBe(true),
    )
    const put = fetchMock.mock.calls.find(([, init]) => (init as RequestInit)?.method === 'PUT')!
    expect(JSON.parse((put[1] as RequestInit).body as string).displayName).toMatch(/^a_\d{17}-[a-z0-9]{6}\.png$/)
  })

  it('stops polling when the dialog unmounts', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let polls = 0
    const view = renderDialogWithRun(() => {
      polls += 1
      return { status: 200, body: { batchId: 'b1', status: 'OPEN', done: 0, total: 1, items: [] } }
    })
    fireEvent.click(await screen.findByRole('button', { name: 'Kho 1' }))
    fireEvent.click(screen.getByRole('button', { name: 'Tiếp tục' }))
    await waitFor(() =>
      expect((screen.getByRole('button', { name: 'Bổ sung vào kho' }) as HTMLButtonElement).disabled).toBe(false),
    )
    fireEvent.click(screen.getByRole('button', { name: 'Bổ sung vào kho' }))
    await waitFor(() => expect(polls).toBe(1))

    cleanup() // unmount
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000)
    })
    expect(polls).toBe(1)
    expect(view).toBeDefined()
  })

  function renderDialogWithRun(
    poll: () => { status: number; body: unknown },
    nameCheck: unknown = [{ personalFileId: '1', relativePath: '', originalName: 'a.png', taken: false }],
    extra: Record<string, unknown> = {},
  ) {
    const fetchMock = installFetchMock({
      '/ingest/from-personal/name-check': { status: 200, body: nameCheck },
      ...extra,
      '/ingest/from-personal/b1': poll,
      '/ingest/from-personal': {
        status: 202,
        body: { batchId: 'b1', total: 1, ineligible: [], expiresAt: '2999-01-01T00:00:00Z' },
      },
      ...baseHandlers,
    } as never)
    render(
      <PersonalToWarehouseDialog
        session={canBoSession()}
        files={[row('1', 'a.png')]}
        fileIds={['1']}
        folderIds={[]}
        onClose={vi.fn()}
        onFinished={vi.fn()}
      />,
    )
    return fetchMock
  }
})
