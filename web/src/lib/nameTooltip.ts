/**
 * folder-upload-display-name FE-7 (AC-24/AC-25): lists show the file's `originalName`; the tooltip adds
 * where it lives (destination folder + relativePath) and the unique `displayName`. Never the storage key.
 */
export function nameTooltip(opts: {
  originalName: string
  displayName?: string | null
  relativePath?: string | null
  folderLabel?: string | null
}): string {
  const where = [opts.folderLabel, opts.relativePath].filter((p): p is string => !!p).join(' / ')
  const lines = [opts.originalName]
  if (where) lines.push(`Vị trí: ${where}`)
  if (opts.displayName) lines.push(`Tên hiển thị: ${opts.displayName}`)
  return lines.join('\n')
}
