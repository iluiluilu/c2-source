import type { ReportChart } from '@/lib/reports'
import { formatIsoDateLabel } from '@/lib/reports'

const COLORS = ['#0D2546', '#D11A2A', '#2E7D9A', '#E0A526', '#6B8E23', '#8E5BA8']

/**
 * Dependency-free bar chart for ReportChart (BAR / STACKED_BAR): one horizontal bar per category, series stacked.
 * Figures come straight from the backend (PostgreSQL) - this only draws them.
 */
export function BarChart({ chart }: { chart: ReportChart }) {
  const totals = chart.categories.map((_, i) => chart.series.reduce((sum, s) => sum + (s.values[i] ?? 0), 0))
  const max = Math.max(1, ...(chart.type === 'STACKED_BAR' ? totals : chart.series.flatMap((s) => s.values)))
  return (
    <figure className="bg-white border border-gray-200 rounded-lg p-4" data-testid={`chart-${chart.id}`}>
      <figcaption className="text-sm font-semibold text-[#0D2546] mb-3">{chart.title}</figcaption>
      <ul className="space-y-2">
        {chart.categories.map((category, i) => (
          <li key={category} className="flex items-center gap-3 text-xs">
            <span className="w-40 shrink-0 truncate text-gray-600" title={category}>
              {formatIsoDateLabel(category)}
            </span>
            <div className="flex-1 flex h-4 bg-gray-50 rounded overflow-hidden">
              {chart.series.map((s, si) => {
                const v = s.values[i] ?? 0
                return v > 0 ? (
                  <div
                    key={s.name}
                    title={`${s.name}: ${v}`}
                    style={{ width: `${(v / max) * 100}%`, backgroundColor: COLORS[si % COLORS.length] }}
                  />
                ) : null
              })}
            </div>
            <span className="w-12 text-right tabular-nums text-gray-700">
              {chart.type === 'STACKED_BAR' ? totals[i] : (chart.series[0]?.values[i] ?? 0)}
            </span>
          </li>
        ))}
      </ul>
      {chart.series.length > 1 && (
        <ul className="flex flex-wrap gap-3 mt-3 text-xs text-gray-600">
          {chart.series.map((s, si) => (
            <li key={s.name} className="flex items-center gap-1">
              <span className="inline-block w-3 h-3 rounded-sm" style={{ backgroundColor: COLORS[si % COLORS.length] }} />
              {s.name}
            </li>
          ))}
        </ul>
      )}
    </figure>
  )
}
