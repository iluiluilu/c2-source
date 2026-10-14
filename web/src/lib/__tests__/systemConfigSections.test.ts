import { describe, expect, it } from 'vitest'
import { cleanDescription, groupRows } from '@/lib/systemConfigSections'
import type { SystemConfigRow } from '@/lib/api'

const row = (key: string, description: string): SystemConfigRow => ({
  key,
  description,
  valueType: 'NUMBER',
  unit: null,
  value: 1,
  defaultValue: 1,
  usingDefault: true,
  sensitive: false,
})

describe('system config sections', () => {
  it('strips spec references from descriptions', () => {
    expect(cleanDescription('Bật/tắt tự động xác nhận (Quyết định 1 SA)')).toBe('Bật/tắt tự động xác nhận')
  })

  it('groups by key prefix in section order and filters by search', () => {
    const rows = [row('search.max', 'Số kết quả'), row('ingest.max_files', 'Số tệp tối đa'), row('x.y', 'Khác lạ')]
    expect(groupRows(rows, '').map(([sec]) => sec)).toEqual(['Nạp dữ liệu', 'Tìm kiếm', 'Khác'])
    expect(groupRows(rows, 'tệp')).toEqual([['Nạp dữ liệu', [rows[1]]]])
  })
})
