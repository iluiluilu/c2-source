import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { DocumentPreview, PREVIEW_MAX_BYTES, previewKind } from '@/components/DocumentPreview'
import { canBoSession } from '@/test-utils/session'

describe('DocumentPreview', () => {
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  it('maps MIME types to preview kinds', () => {
    expect(previewKind('application/pdf')).toBe('pdf')
    expect(previewKind('image/png')).toBe('image')
    expect(previewKind('image/tiff')).toBeNull()
    expect(previewKind('text/plain')).toBe('text')
    expect(previewKind('application/zip')).toBeNull()
  })

  it('loads text content only after the button is clicked', async () => {
    const fetchMock = vi.fn(async () => ({ ok: true, status: 200, blob: async () => ({ text: async () => 'xin chào' }) }))
    vi.stubGlobal('fetch', fetchMock)
    render(<DocumentPreview session={canBoSession()} documentId="d1" mimeType="text/plain" size={8} />)
    expect(fetchMock).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'Xem trước nội dung' }))
    await waitFor(() => expect(screen.getByText('xin chào')).toBeTruthy())
  })

  it('asks to download files that are too big or unsupported', () => {
    render(<DocumentPreview session={null} documentId="d1" mimeType="application/pdf" size={PREVIEW_MAX_BYTES + 1} />)
    expect(screen.getByText(/lớn hơn 20 MB/)).toBeTruthy()
    cleanup()
    render(<DocumentPreview session={null} documentId="d1" mimeType="application/zip" size={10} />)
    expect(screen.getByText(/chưa hỗ trợ xem trước/)).toBeTruthy()
  })
})
