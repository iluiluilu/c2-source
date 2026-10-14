import type { AllowedFormat } from './api'

/** Lower-case extensions (no dot) accepted by the warehouse allowlist (GET /ingest/allowed-formats). */
export function allowedExtensionSet(formats: AllowedFormat[]): Set<string> {
  return new Set(formats.flatMap((f) => f.extensions.map((ext) => ext.toLowerCase())))
}

/** `a.tar.PDF` -> `pdf`; no dot (or a leading-dot-only name like `.env`) -> ''. */
export function extensionOf(name: string): string {
  const dot = name.lastIndexOf('.')
  return dot > 0 ? name.slice(dot + 1).toLowerCase() : ''
}

/**
 * feedbacks D-03: a dropped folder bypasses the file picker's `accept` filter, so every file is checked here
 * before it is queued - a file whose extension is not in the allowlist is never sent (the server still re-checks
 * extension + content). `allowed === null` (allowlist failed to load) keeps everything and leaves it to the server.
 */
export function partitionByExtension<T extends { file: File }>(
  items: T[],
  allowed: Set<string> | null,
): { accepted: T[]; skipped: T[] } {
  if (!allowed) return { accepted: items, skipped: [] }
  const accepted: T[] = []
  const skipped: T[] = []
  for (const item of items) {
    ;(allowed.has(extensionOf(item.file.name)) ? accepted : skipped).push(item)
  }
  return { accepted, skipped }
}
