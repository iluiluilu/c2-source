import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ReportResultView } from '@/components/reports/ReportResultView'
import type { ReportResult } from '@/lib/reports'

function result(): ReportResult {
  const rows = Array.from({ length: 12 }, (_, i) => ({
    period: `2026-10-${String(i + 1).padStart(2, '0')}`,
    count: i === 3 ? 5 : 0,
  }))
  return {
    templateCode: 'WAREHOUSE_SUMMARY',
    title: 'Tổng hợp',
    period: { from: '2026-10-01', to: '2026-10-31' },
    filters: {},
    scopeNotice: '',
    warnings: [],
    notes: [],
    tables: [
      {
        id: 't1',
        title: 'Theo ngày',
        columns: [
          { key: 'period', label: 'Kỳ', type: 'string' },
          { key: 'count', label: 'Số lượng', type: 'number' },
        ],
        rows,
        totalRows: rows.length,
        paged: false,
      },
    ],
    charts: [{ id: 'c1', title: 'Biểu đồ', type: 'BAR', categories: ['2026-10-04'], series: [{ name: 'n', values: [5] }] }],
  } as unknown as ReportResult
}

describe('ReportResultView', () => {
  afterEach(() => cleanup())

  it('hides all-zero rows by default and shows them on demand', () => {
    render(<ReportResultView result={result()} page={0} pageSize={50} onPage={vi.fn()} />)
    expect(screen.getAllByRole('row')).toHaveLength(2) // header + the one non-zero day
    expect(screen.getAllByText('04/10/2026').length).toBeGreaterThan(0)

    fireEvent.click(screen.getByLabelText(/Ẩn 11 dòng không có số liệu/))
    expect(screen.getAllByRole('row')).toHaveLength(13)
  })

  it('renders charts before tables', () => {
    render(<ReportResultView result={result()} page={0} pageSize={50} onPage={vi.fn()} />)
    const chart = screen.getByTestId('chart-c1')
    const table = screen.getByTestId('table-t1')
    expect(chart.compareDocumentPosition(table) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })
})
