import type {
  AllowedFormat,
  FolderNode,
  IngestLimits,
  PersonalFileRow,
} from '@/lib/api'

/** personal-to-warehouse.md §4 step 7: poll interval for GET /ingest/from-personal/{batchId}. */
export const FROM_PERSONAL_POLL_MS = 2000

/** All folder ids in the subtrees rooted at `rootIds` (roots included). */
export function subtreeFolderIds(tree: FolderNode[], rootIds: string[]): Set<string> {
  const result = new Set<string>()
  const walk = (nodes: FolderNode[], inside: boolean) => {
    for (const n of nodes) {
      const isIn = inside || rootIds.includes(n.id)
      if (isIn) result.add(n.id)
      walk(n.children, isIn)
    }
  }
  walk(tree, false)
  return result
}

/** Names from the root down to folder `id`, or null when it is not in the tree (AC-7: show the destination). */
export function folderPathNames(tree: FolderNode[], id: string): string[] | null {
  for (const n of tree) {
    if (n.id === id) return [n.name]
    const below = folderPathNames(n.children, id)
    if (below) return [n.name, ...below]
  }
  return null
}

/**
 * The files a selection stands for: hand-picked files plus every file inside the
 * selected folders' subtrees, de-duplicated by id (mirrors the server's collect()).
 */
export function collectSelection(
  tree: FolderNode[],
  files: PersonalFileRow[],
  fileIds: string[],
  folderIds: string[],
): PersonalFileRow[] {
  const inFolders = subtreeFolderIds(tree, folderIds)
  return files.filter(
    (f) => fileIds.includes(f.id) || (f.folderId !== null && inFolders.has(f.folderId)),
  )
}

export type Eligibility = 'OK' | 'INVALID_FORMAT' | 'FILE_TOO_LARGE'

function extensionOf(name: string): string {
  const i = name.lastIndexOf('.')
  return i < 0 ? name.toLowerCase() : name.slice(i + 1).toLowerCase()
}

/** Client-side mirror of the server's pre-filter (name/MIME allowlist + per-file size cap). */
export function classifyFile(
  file: Pick<PersonalFileRow, 'originalName' | 'mimeType' | 'size'>,
  formats: AllowedFormat[],
  limits: IngestLimits | null,
): Eligibility {
  const ext = extensionOf(file.originalName)
  const allowed = formats.some((f) => f.mimeType === file.mimeType && f.extensions.includes(ext))
  if (!allowed) return 'INVALID_FORMAT'
  if (limits && Number(file.size) > limits.maxFileSizeBytes) return 'FILE_TOO_LARGE'
  return 'OK'
}

export interface LimitWarning {
  overFileCount: boolean
  overTotalBytes: boolean
}

/** AC-7/AC-8: early warning when the *eligible* files alone would exceed the batch limits. */
export function limitWarning(
  eligible: Pick<PersonalFileRow, 'size'>[],
  limits: IngestLimits | null,
): LimitWarning {
  if (!limits) return { overFileCount: false, overTotalBytes: false }
  const total = eligible.reduce((s, f) => s + Number(f.size), 0)
  return {
    overFileCount: eligible.length > limits.maxFilesPerBatch,
    overTotalBytes: total > limits.maxBatchSizeBytes,
  }
}

export const RESULT_CODE_LABELS: Record<string, string> = {
  DISPLAY_NAME_CONFLICT: 'Trùng tên trong thư mục đích',
  DISPLAY_NAME_MISSING: 'Thiếu tên hiển thị',
  INVALID_DISPLAY_NAME: 'Tên hiển thị không hợp lệ',
  ORIGINAL_NAME_TOO_LONG: 'Tên tệp dài quá 512 ký tự',
  INVALID_FORMAT: 'Định dạng không được phép',
  FILE_TOO_LARGE: 'Tệp quá lớn',
  MALWARE_DETECTED: 'Phát hiện mã độc',
  WAREHOUSE_CAPACITY_EXCEEDED: 'Kho đã đầy',
  SOURCE_NOT_FOUND: 'Tệp nguồn không còn tồn tại',
  CHECKSUM_MISMATCH: 'Tệp nguồn không toàn vẹn',
  SCANNER_UNAVAILABLE: 'Trình quét mã độc không khả dụng',
  STORAGE_WRITE_FAILED: 'Không ghi được vào kho lưu trữ',
  BATCH_LIMIT_EXCEEDED: 'Vượt hạn mức lượt nạp',
  BATCH_EXPIRED: 'Lượt nạp đã hết hạn',
  INTERNAL_ERROR: 'Lỗi hệ thống',
  SERVER_BUSY: 'Máy chủ đang bận',
}

/** Max times the client may rename one item after DISPLAY_NAME_CONFLICT (the server enforces it too). */
export const MAX_ITEM_RENAMES = 3
