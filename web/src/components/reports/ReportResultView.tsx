import { useState } from 'react'
import { formatCell, formatIsoDateLabel } from '@/lib/reports'
import type { ReportResult, ReportTable } from '@/lib/reports'
import { BarChart } from './BarChart'

function TableView({
  table,
  page,
  pageSize,
  onPage,
}: {
  table: ReportTable
  page: number
  pageSize: number
  onPage: (page: number) => void
}) {
  const from = table.rows.length === 0 ? 0 : page * pageSize + 1
  const to = page * pageSize + table.rows.length
  // A daily breakdown over a month is mostly zero rows: offer to hide them.
  const numericKeys = table.columns.slice(1).filter((c) => c.type !== 'string').map((c) => c.key)
  const isZeroRow = (row: ReportTable['rows'][number]) =>
    numericKeys.length > 0 && numericKeys.every((k) => row[k] === 0 || row[k] === null || row[k] === undefined)
  const zeroRows = table.rows.filter(isZeroRow).length
  const canHideZero = table.rows.length > 10 && zeroRows > 0
  const [hideZero, setHideZero] = useState(true)
  const rows = canHideZero && hideZero ? table.rows.filter((r) => !isZeroRow(r)) : table.rows
  return (
    <section className="bg-white border border-gray-200 rounded-lg overflow-hidden" data-testid={`table-${table.id}`}>
      <div className="flex items-center justify-between gap-3 px-4 py-3 border-b border-gray-100">
        <h3 className="text-sm font-semibold text-[#0D2546]">{table.title}</h3>
        {canHideZero && (
          <label className="flex items-center gap-2 text-xs text-gray-500">
            <input type="checkbox" checked={hideZero} onChange={(e) => setHideZero(e.target.checked)} />
            Ẩn {zeroRows} dòng không có số liệu
          </label>
        )}
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-xs text-gray-500 border-b border-gray-200 bg-gray-50">
              {table.columns.map((c, i) => (
                <th key={c.key} className={`py-2 px-3 font-medium ${i === 0 ? '' : 'text-right'}`}>
                  {c.label}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row, ri) => (
              <tr key={ri} className="border-b border-gray-100 text-gray-800">
                {table.columns.map((c, i) => (
                  <td key={c.key} className={`py-2 px-3 ${i === 0 ? '' : 'text-right tabular-nums'}`}>
                    {formatCell(row[c.key], c.type)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {table.paged && table.totalRows > 0 && (
        <div className="flex items-center justify-between px-4 py-2 text-xs text-gray-500 border-t border-gray-100">
          <span>
            Hàng {from}–{to} / {table.totalRows}
          </span>
          <span className="flex gap-2">
            <button
              type="button"
              onClick={() => onPage(page - 1)}
              disabled={page === 0}
              className="px-3 py-1 border border-gray-200 rounded disabled:text-gray-300 cursor-pointer disabled:cursor-not-allowed"
            >
              Trước
            </button>
            <button
              type="button"
              onClick={() => onPage(page + 1)}
              disabled={to >= table.totalRows}
              className="px-3 py-1 border border-gray-200 rounded disabled:text-gray-300 cursor-pointer disabled:cursor-not-allowed"
            >
              Sau
            </button>
          </span>
        </div>
      )}
    </section>
  )
}

/**
 * Renders a ReportResult (AC-4 scope notice, AC-10 empty warning, AC-13/17 notes, AC-18 title/period/filters,
 * tables, charts). Purely presentational.
 */
export function ReportResultView({
  result,
  page,
  pageSize,
  onPage,
}: {
  result: ReportResult
  page: number
  pageSize: number
  onPage: (page: number) => void
}) {
  const filters = Object.entries(result.filters)
  return (
    <div className="space-y-4" data-testid="report-result">
      <div>
        <h2 className="text-xl font-bold text-[#0D2546]">{result.title}</h2>
        <p className="text-sm text-gray-600 mt-1">
          Kỳ báo cáo: {formatIsoDateLabel(result.period.from)} → {formatIsoDateLabel(result.period.to)}
        </p>
        {filters.length > 0 && (
          <p className="text-sm text-gray-600">
            Bộ lọc: {filters.map(([k, v]) => `${k}: ${v}`).join('; ')}
          </p>
        )}
        <p className="text-sm text-gray-500 mt-1" data-testid="scope-notice">
          {result.scopeNotice}
        </p>
      </div>

      {result.warnings.map((w) => (
        <div key={w} role="alert" className="bg-amber-50 border border-amber-200 rounded-md p-3 text-sm text-amber-800">
          {w}
        </div>
      ))}

      {/* Charts first: the overview, then the detailed tables. */}
      {result.charts.map((c) => (
        <BarChart key={c.id} chart={c} />
      ))}
      {/* AC-10: an empty report still shows its (all-zero) structure next to the warning. */}
      {result.tables.map((t) => (
        <TableView key={t.id} table={t} page={page} pageSize={pageSize} onPage={onPage} />
      ))}

      {result.notes.length > 0 && (
        <ul className="text-xs text-gray-500 list-disc pl-5 space-y-1" data-testid="report-notes">
          {result.notes.map((n) => (
            <li key={n}>{n}</li>
          ))}
        </ul>
      )}
    </div>
  )
}
