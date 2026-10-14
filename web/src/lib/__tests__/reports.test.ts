import { describe, expect, it } from 'vitest'
import {
  formatIsoDateLabel,
  defaultParams,
  filenameFromDisposition,
  flattenFolders,
  formatBytes,
  formatCell,
  formatDuration,
  hasActiveExport,
  isActiveExport,
  periodDays,
  quickPeriod,
  suggestGranularity,
  toDateString,
  validatePeriod,
} from '@/lib/reports'
import type { ReportExportRow } from '@/lib/reports'

/** export-reporting AC-8 (quick periods + granularity suggestion), AC-9 client pre-check, display helpers. */
describe('quickPeriod (AC-8)', () => {
  const today = new Date(2026, 4, 17) // 17 May 2026 (local)

  it('month = the whole current calendar month', () => {
    expect(quickPeriod('month', today)).toEqual({ from: '2026-05-01', to: '2026-05-31' })
    expect(quickPeriod('month', new Date(2024, 1, 10))).toEqual({ from: '2024-02-01', to: '2024-02-29' }) // leap year
  })

  it('quarter = the current calendar quarter', () => {
    expect(quickPeriod('quarter', today)).toEqual({ from: '2026-04-01', to: '2026-06-30' })
    expect(quickPeriod('quarter', new Date(2026, 0, 3))).toEqual({ from: '2026-01-01', to: '2026-03-31' })
    expect(quickPeriod('quarter', new Date(2026, 11, 31))).toEqual({ from: '2026-10-01', to: '2026-12-31' })
  })

  it('halfYear = 6 calendar months ending today, crossing the year boundary', () => {
    expect(quickPeriod('halfYear', today)).toEqual({ from: '2025-12-01', to: '2026-05-17' })
    expect(quickPeriod('halfYear', new Date(2026, 1, 5))).toEqual({ from: '2025-09-01', to: '2026-02-05' })
  })

  it('year = 1 Jan .. 31 Dec of the current year', () => {
    expect(quickPeriod('year', today)).toEqual({ from: '2026-01-01', to: '2026-12-31' })
  })

  it('toDateString uses the LOCAL calendar date', () => {
    expect(toDateString(new Date(2026, 0, 1, 0, 30))).toBe('2026-01-01')
    expect(toDateString(new Date(2026, 11, 31, 23, 59))).toBe('2026-12-31')
  })
})

describe('suggestGranularity (AC-8)', () => {
  it('suggests a granularity that fits the length of the period', () => {
    expect(suggestGranularity('2026-05-01', '2026-05-31')).toBe('day')
    expect(suggestGranularity('2026-04-01', '2026-06-30')).toBe('month')
    expect(suggestGranularity('2025-12-01', '2026-05-17')).toBe('month')
    expect(suggestGranularity('2026-01-01', '2026-12-31')).toBe('month')
    expect(suggestGranularity('2023-01-01', '2026-12-31')).toBe('quarter')
    expect(suggestGranularity('2010-01-01', '2026-12-31')).toBe('year')
  })

  it('never suggests more than ~400 buckets for the quick periods', () => {
    const today = new Date(2026, 4, 17)
    for (const k of ['month', 'quarter', 'halfYear', 'year'] as const) {
      const { from, to } = quickPeriod(k, today)
      const g = suggestGranularity(from, to)
      const days = periodDays(from, to)
      const buckets = g === 'day' ? days : g === 'month' ? Math.ceil(days / 28) : g === 'quarter' ? Math.ceil(days / 90) : Math.ceil(days / 365)
      expect(buckets).toBeLessThanOrEqual(400)
    }
  })
})

describe('periodDays / validatePeriod', () => {
  it('counts inclusive days', () => {
    expect(periodDays('2026-03-01', '2026-03-01')).toBe(1)
    expect(periodDays('2026-03-01', '2026-03-31')).toBe(31)
    expect(periodDays('2026-03-31', '2026-03-01')).toBe(0)
    expect(periodDays('', '2026-03-01')).toBe(0)
  })

  it('mirrors the server rules (AC-9): both dates required, from <= to', () => {
    expect(validatePeriod('', '2026-03-01')).toMatch(/đủ ngày/)
    expect(validatePeriod('2026-03-02', '2026-03-01')).toMatch(/nhỏ hơn hoặc bằng/)
    expect(validatePeriod('2026-03-01', '2026-03-01')).toBeNull()
  })

  it('defaultParams starts on the current month with the suggested granularity', () => {
    const p = defaultParams(new Date(2026, 4, 17))
    expect(p).toMatchObject({ from: '2026-05-01', to: '2026-05-31', granularity: 'day', folderId: null })
    expect(p.groupIds).toEqual([])
  })
})

describe('display helpers', () => {
  it('formats numbers, bytes, durations and nulls', () => {
    expect(formatCell(1234567, 'number')).toBe('1.234.567')
    expect(formatCell(null, 'number')).toBe('')
    expect(formatCell('Văn bản/PDF', 'string')).toBe('Văn bản/PDF')
    expect(formatCell(3_725_000, 'duration')).toBe('1:02:05')
    expect(formatBytes(512)).toBe('512 B')
    expect(formatBytes(5_368_709_120)).toBe('5 GB')
    expect(formatDuration(59_000)).toBe('0:00:59')
  })

  it('reads the file name from Content-Disposition, with a safe fallback', () => {
    expect(filenameFromDisposition('attachment; filename="repo_summary_20260401.pdf"', 'x.pdf')).toBe('repo_summary_20260401.pdf')
    expect(filenameFromDisposition(null, 'fallback.pdf')).toBe('fallback.pdf')
    expect(filenameFromDisposition('attachment', 'fallback.pdf')).toBe('fallback.pdf')
  })

  it('flattens the folder tree with indentation', () => {
    const tree = [{ id: 'a', name: 'A', children: [{ id: 'b', name: 'B', children: [] }] }]
    expect(flattenFolders(tree)).toEqual([
      { id: 'a', label: 'A' },
      { id: 'b', label: '— B' },
    ])
  })

  it('knows which export statuses still change (AC-22 polling)', () => {
    expect(isActiveExport('PENDING')).toBe(true)
    expect(isActiveExport('RUNNING')).toBe(true)
    for (const s of ['DONE', 'FAILED', 'EXPIRED'] as const) expect(isActiveExport(s)).toBe(false)
    const row = (status: ReportExportRow['status']): ReportExportRow => ({
      id: status,
      templateCode: 'REPO_SUMMARY',
      format: 'PDF',
      status,
      createdAt: '2026-01-01T00:00:00Z',
      finishedAt: null,
      expiresAt: null,
    })
    expect(hasActiveExport([row('DONE'), row('RUNNING')])).toBe(true)
    expect(hasActiveExport([row('DONE'), row('FAILED')])).toBe(false)
    expect(hasActiveExport([])).toBe(false)
  })
})

describe('formatIsoDateLabel', () => {
  it('turns ISO day and month keys into dd/mm/yyyy and mm/yyyy', () => {
    expect(formatIsoDateLabel('2026-10-01')).toBe('01/10/2026')
    expect(formatIsoDateLabel('2026-10')).toBe('10/2026')
    expect(formatIsoDateLabel('Quý 4/2026')).toBe('Quý 4/2026')
  })
})
