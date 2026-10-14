/**
 * export-reporting (FR-30/FR-31) client helpers + DTO types mirroring the backend
 * `com.aismartstore.backend.reporting` API. Pure functions only (unit-tested in __tests__/reports.test.ts).
 */

export type ReportTemplateCode = 'REPO_SUMMARY' | 'DIGITIZATION_PROGRESS' | 'DOC_BY_CLASSIFICATION'
export type Granularity = 'day' | 'month' | 'quarter' | 'year'
export type ExportFormat = 'PDF' | 'XLSX' | 'DOCX'
export type ExportStatus = 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED' | 'EXPIRED'

export const GRANULARITY_LABELS: Record<Granularity, string> = {
  day: 'Ngày',
  month: 'Tháng',
  quarter: 'Quý',
  year: 'Năm',
}

/** Closed template catalogue (AC-36): names match `report_template.name`, for screens that only get the code. */
export const TEMPLATE_NAMES: Record<ReportTemplateCode, string> = {
  REPO_SUMMARY: 'Tổng hợp tình hình kho tài liệu theo kỳ',
  DIGITIZATION_PROGRESS: 'Tiến độ và chất lượng số hoá',
  DOC_BY_CLASSIFICATION: 'Thống kê theo chủ đề và loại tài liệu',
}

export const EXPORT_FORMAT_LABELS: Record<ExportFormat, string> = {
  PDF: 'PDF',
  XLSX: 'Excel (XLSX)',
  DOCX: 'Word (DOCX)',
}

export const EXPORT_STATUS_LABELS: Record<ExportStatus, string> = {
  PENDING: 'Đang chờ',
  RUNNING: 'Đang tạo',
  DONE: 'Hoàn tất',
  FAILED: 'Thất bại',
  EXPIRED: 'Hết hạn',
}

/** Shared by the run request, saved reports and export requests (backend `ReportParams`). */
export interface ReportParams {
  from: string
  to: string
  groupIds: string[]
  topicTagIds: string[]
  docTypeTagIds: string[]
  folderId: string | null
  granularity: Granularity
}

export interface ReportTemplateParam {
  name: string
  type: string
  required: boolean
}

export interface ReportTemplate {
  code: ReportTemplateCode
  name: string
  description: string
  params: ReportTemplateParam[]
}

export interface ReportColumn {
  key: string
  label: string
  type: 'string' | 'number' | 'bytes' | 'duration' | 'percent'
}

export interface ReportTable {
  id: string
  title: string
  columns: ReportColumn[]
  rows: Record<string, string | number | null>[]
  totalRows: number
  paged: boolean
}

export interface ReportChart {
  id: string
  title: string
  type: 'BAR' | 'STACKED_BAR'
  categories: string[]
  series: { name: string; values: number[] }[]
}

export interface ReportResult {
  templateCode: ReportTemplateCode
  title: string
  period: { from: string; to: string }
  filters: Record<string, string>
  scopeNotice: string
  generatedAt: string
  generatedBy: string
  notes: string[]
  warnings: string[]
  empty: boolean
  tables: ReportTable[]
  charts: ReportChart[]
}

export interface SavedReportSummary {
  id: string
  name: string
  templateCode: ReportTemplateCode
  updatedAt: string
}

export interface SavedReportDetail {
  id: string
  name: string
  templateCode: ReportTemplateCode
  params: ReportParams
  warnings: string[]
}

export interface ReportExportRow {
  id: string
  templateCode: ReportTemplateCode
  format: ExportFormat
  status: ExportStatus
  createdAt: string
  finishedAt: string | null
  expiresAt: string | null
  errorMessage?: string | null
}

export interface ReportExportPage {
  content: ReportExportRow[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

// ---- period helpers (AC-8) ------------------------------------------------------------------------------------------

export type QuickPeriod = 'month' | 'quarter' | 'halfYear' | 'year'

export const QUICK_PERIOD_LABELS: Record<QuickPeriod, string> = {
  month: 'Tháng này',
  quarter: 'Quý này',
  halfYear: '6 tháng gần nhất',
  year: 'Năm nay',
}

function pad(n: number): string {
  return String(n).padStart(2, '0')
}

/** yyyy-MM-dd of a LOCAL calendar date (never via toISOString, which would shift to UTC). */
export function toDateString(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

function parseDate(s: string): Date {
  const [y, m, d] = s.split('-').map(Number)
  return new Date(y, m - 1, d)
}

/**
 * AC-8: the from/to a quick-select button fills in (the user may still edit them freely).
 * month = current calendar month; quarter = current calendar quarter; halfYear = the last 6 months up to today
 * (start of the month 5 months ago); year = Jan 1 .. Dec 31 of the current year.
 */
export function quickPeriod(kind: QuickPeriod, today: Date): { from: string; to: string } {
  const y = today.getFullYear()
  const m = today.getMonth()
  switch (kind) {
    case 'month':
      return { from: toDateString(new Date(y, m, 1)), to: toDateString(new Date(y, m + 1, 0)) }
    case 'quarter': {
      const qStart = Math.floor(m / 3) * 3
      return { from: toDateString(new Date(y, qStart, 1)), to: toDateString(new Date(y, qStart + 3, 0)) }
    }
    case 'halfYear':
      return { from: toDateString(new Date(y, m - 5, 1)), to: toDateString(today) }
    case 'year':
      return { from: toDateString(new Date(y, 0, 1)), to: toDateString(new Date(y, 11, 31)) }
  }
}

/** Inclusive number of days between two yyyy-MM-dd strings (0 when invalid). */
export function periodDays(from: string, to: string): number {
  if (!from || !to) return 0
  const ms = parseDate(to).getTime() - parseDate(from).getTime()
  if (Number.isNaN(ms) || ms < 0) return 0
  return Math.round(ms / 86_400_000) + 1
}

/** AC-8: granularity suggested from the period length (keeps the bucket count far below the server limit). */
export function suggestGranularity(from: string, to: string): Granularity {
  const days = periodDays(from, to)
  if (days <= 62) return 'day'
  if (days <= 800) return 'month'
  if (days <= 1830) return 'quarter'
  return 'year'
}

/** Client-side pre-check mirroring the server's AC-9 rules, so obvious mistakes never cost a round trip. */
export function validatePeriod(from: string, to: string): string | null {
  if (!from || !to) return 'Hãy chọn đủ ngày bắt đầu và ngày kết thúc.'
  if (from > to) return 'Ngày bắt đầu phải nhỏ hơn hoặc bằng ngày kết thúc.'
  return null
}

// ---- display helpers ---------------------------------------------------------------------------------------------------

const NUMBER_FORMAT = new Intl.NumberFormat('vi-VN')

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let v = bytes
  let i = -1
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${NUMBER_FORMAT.format(Math.round(v * 10) / 10)} ${units[i]}`
}

export function formatDuration(ms: number): string {
  const s = Math.floor(ms / 1000)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  return `${h}:${pad(m)}:${pad(s % 60)}`
}

/**
 * ISO day/month keys from the API (`2026-10-01`, `2026-10`) in the app's
 * Vietnamese order (`01/10/2026`, `10/2026`); anything else is returned as is.
 */
export function formatIsoDateLabel(value: string): string {
  const day = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (day) return `${day[3]}/${day[2]}/${day[1]}`
  const month = /^(\d{4})-(\d{2})$/.exec(value)
  if (month) return `${month[2]}/${month[1]}`
  return value
}

export function formatCell(value: string | number | null | undefined, type: ReportColumn['type']): string {
  if (value === null || value === undefined) return ''
  if (typeof value === 'string') return formatIsoDateLabel(value)
  switch (type) {
    case 'bytes':
      return formatBytes(value)
    case 'duration':
      return formatDuration(value)
    case 'percent':
      return `${NUMBER_FORMAT.format(value)}%`
    default:
      return NUMBER_FORMAT.format(value)
  }
}

/** AC-22: UI keeps polling while a job can still change. */
export function isActiveExport(status: ExportStatus): boolean {
  return status === 'PENDING' || status === 'RUNNING'
}

export function hasActiveExport(rows: readonly ReportExportRow[]): boolean {
  return rows.some((r) => isActiveExport(r.status))
}

/** Default parameters for a fresh form: the current month, with the suggested granularity. */
export function defaultParams(today: Date): ReportParams {
  const { from, to } = quickPeriod('month', today)
  return {
    from,
    to,
    groupIds: [],
    topicTagIds: [],
    docTypeTagIds: [],
    folderId: null,
    granularity: suggestGranularity(from, to),
  }
}

/** Filename the browser should use: taken from Content-Disposition, falling back to a safe default. */
export function filenameFromDisposition(header: string | null, fallback: string): string {
  if (!header) return fallback
  const m = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(header)
  if (!m) return fallback
  try {
    return decodeURIComponent(m[1])
  } catch {
    return m[1]
  }
}

export interface FolderOption {
  id: string
  label: string
}

/** Flattens the folder tree into indented options for a <select>. */
export function flattenFolders(
  nodes: { id: string; name: string; children: unknown[] }[],
  depth = 0,
): FolderOption[] {
  const out: FolderOption[] = []
  for (const n of nodes) {
    out.push({ id: n.id, label: `${'— '.repeat(depth)}${n.name}` })
    out.push(...flattenFolders(n.children as { id: string; name: string; children: unknown[] }[], depth + 1))
  }
  return out
}
