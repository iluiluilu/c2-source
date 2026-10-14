import type { Session } from 'next-auth'
import { apiFetch } from './api'

/**
 * Save a warehouse document to disk. Programmatic: a plain <a href> cannot
 * carry the bearer token. Throws ApiError like apiFetch.
 */
export async function downloadDocument(
  session: Session | null,
  documentId: string,
  fileName: string,
): Promise<void> {
  const res = await apiFetch(`/documents/${documentId}/content`, session)
  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  a.click()
  URL.revokeObjectURL(url)
}
