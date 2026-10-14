import type { Session } from 'next-auth'
import { getSession } from 'next-auth/react'
import { ApiError, apiJson } from './api'
import type { NameCheckRequest, NameCheckResult } from './api'
import {
  MAX_NAME_CODE_POINTS,
  MAX_SUFFIX_RETRIES,
  assignDisplayNames,
  cpLength,
  generateSuffixName,
  nfc,
} from './displayName'

/**
 * SPEC-folder-upload-display-name FE-3: helpers shared by /upload and /personal.
 * Flow: name-check (batches of 1000) -> assign display names -> upload -> on 409
 * DISPLAY_NAME_CONFLICT regenerate the suffix (max 3 times per file) and resend.
 */

export const NAME_CHECK_BATCH_SIZE = 1000

/**
 * feedbacks P-06: only a scanner/allowlist outage (503) or a network-level failure is retried. A ClamAV restart
 * leaves the scanner down for ~20s, so the backoff spans that window: 4 attempts, waiting 2s, 6s, 15s in between.
 */
export const TRANSIENT_RETRY_DELAYS_MS = [2000, 6000, 15000]

export function isTransientUploadError(e: unknown): boolean {
  // A plain (non-ApiError) exception is a fetch()/XHR network failure, not a decoded HTTP response.
  return !(e instanceof ApiError) || e.status === 503
}

export { MAX_SUFFIX_RETRIES }

export function isDisplayNameConflict(e: unknown): boolean {
  return (
    e instanceof ApiError &&
    e.status === 409 &&
    (e.body as { code?: string } | undefined)?.code === 'DISPLAY_NAME_CONFLICT'
  )
}

/** AC-28: block in the browser before ever calling the API (the server enforces it too). */
export function originalNameTooLong(name: string): boolean {
  return cpLength(nfc(name)) > MAX_NAME_CODE_POINTS
}

/**
 * Asks the server which (relativePath, name) pairs are taken. A failure of any kind (network, 429,
 * 5xx) must NOT block the upload (AC-12/AC-29): that batch is treated as "all taken", so every file
 * gets a suffix name and the server's unique index stays the final arbiter.
 */
export async function nameCheckAll(
  endpoint: '/ingest/name-check' | '/personal/name-check',
  folderId: string | null,
  files: { relativePath: string; originalName: string }[],
): Promise<boolean[]> {
  const taken: boolean[] = []
  for (let i = 0; i < files.length; i += NAME_CHECK_BATCH_SIZE) {
    const chunk = files.slice(i, i + NAME_CHECK_BATCH_SIZE)
    try {
      const session: Session | null = await getSession()
      const body: NameCheckRequest = {
        folderId,
        files: chunk.map((f) => ({ relativePath: nfc(f.relativePath), originalName: nfc(f.originalName) })),
      }
      const result = await apiJson<NameCheckResult[]>(endpoint, session, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
      if (!Array.isArray(result) || result.length !== chunk.length) {
        throw new Error('name-check: unexpected response')
      }
      taken.push(...result.map((r) => r.taken))
    } catch {
      taken.push(...chunk.map(() => true))
    }
  }
  return taken
}

/** name-check + assignment in one step; returns the display name of every file, in order. */
export async function resolveDisplayNames(
  endpoint: '/ingest/name-check' | '/personal/name-check',
  folderId: string | null,
  files: { relativePath: string; originalName: string }[],
): Promise<string[]> {
  const taken = await nameCheckAll(endpoint, folderId, files)
  return assignDisplayNames(files, taken)
}

export { generateSuffixName }
