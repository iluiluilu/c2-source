import type { SystemConfigRow } from './api'

/** Key prefix -> admin-facing section, in display order. */
export const SECTIONS: { label: string; prefixes: string[] }[] = [
  { label: 'Lưu trữ & hạn mức', prefixes: ['storage_quota.', 'warehouse.'] },
  { label: 'Nạp dữ liệu', prefixes: ['ingest.', 'upload.'] },
  { label: 'Số hoá (nhận dạng văn bản / giọng nói)', prefixes: ['ocr_stt.', 'ai_queue.'] },
  { label: 'Phân loại', prefixes: ['classification.'] },
  { label: 'Tìm kiếm', prefixes: ['search.', 'semantic.'] },
  { label: 'Báo cáo', prefixes: ['reporting.'] },
  { label: 'Thùng rác & sao lưu', prefixes: ['retention.', 'backup.'] },
];
export const OTHER_SECTION = 'Khác';

export function sectionOf(key: string): string {
  return SECTIONS.find((sec) => sec.prefixes.some((p) => key.startsWith(p)))?.label ?? OTHER_SECTION;
}

/** Descriptions come from the DB seed and may carry spec references - not for admins. */
export function cleanDescription(text: string): string {
  return text.replace(/\s*\((?:Quyết định|QĐ)[^)]*\)/g, '').trim();
}

/** Rows filtered by `search` (description or key), grouped by section in SECTIONS order. */
export function groupRows(rows: SystemConfigRow[], search: string): [string, SystemConfigRow[]][] {
  const needle = search.trim().toLocaleLowerCase('vi');
  const order = [...SECTIONS.map((sec) => sec.label), OTHER_SECTION];
  const bySection = new Map<string, SystemConfigRow[]>();
  for (const row of rows) {
    if (
      needle &&
      !cleanDescription(row.description).toLocaleLowerCase('vi').includes(needle) &&
      !row.key.toLowerCase().includes(needle)
    ) {
      continue;
    }
    const sec = sectionOf(row.key);
    bySection.set(sec, [...(bySection.get(sec) ?? []), row]);
  }
  return order.filter((sec) => bySection.has(sec)).map((sec) => [sec, bySection.get(sec)!]);
}
