'use client';

import type { GroupRow, SearchMediaType, TagRow } from '@/lib/api';
import type { SearchFilterState } from '@/lib/search-request';

const MEDIA_TYPE_OPTIONS: { value: SearchMediaType; label: string }[] = [
  { value: 'document', label: 'Văn bản/ảnh' },
  { value: 'audio', label: 'Âm thanh' },
  { value: 'video', label: 'Video' },
];

export interface SearchFilterPanelProps {
  filters: SearchFilterState;
  onChange: (next: SearchFilterState) => void;
  docTypeOptions: TagRow[];
  topicOptions: TagRow[];
  groupOptions: GroupRow[];
  folderOptions: { id: string; label: string }[];
}

function toggle<T>(list: T[], value: T): T[] {
  return list.includes(value) ? list.filter((v) => v !== value) : [...list, value];
}

/**
 * search-fulltext-05-frontend.md §4 bước 1.2 (Task 6, BA Story 2) - the 7
 * closed filter groups (AC-1-AC-6), each optional. `onChange` receives the
 * whole next state (uncontrolled internally) so the parent page owns
 * validation/request-building (`lib/search-request.ts`) - this component is
 * presentation only.
 */
export function SearchFilterPanel({
  filters,
  onChange,
  docTypeOptions,
  topicOptions,
  groupOptions,
  folderOptions,
}: SearchFilterPanelProps) {
  return (
    <aside className="bg-white rounded-lg border border-gray-200 p-4 h-fit space-y-5">
      <h3 className="font-semibold text-[#0D2546] text-sm">Bộ lọc nâng cao</h3>

      {/* AC-1: docType - đúng 1 giá trị */}
      <div>
        <label htmlFor="search-filter-doctype" className="block text-xs font-medium text-gray-500 mb-1">
          Loại tài liệu
        </label>
        <select
          id="search-filter-doctype"
          value={filters.docType}
          onChange={(e) => onChange({ ...filters, docType: e.target.value })}
          className="w-full border border-gray-300 rounded-md px-2 py-1.5 text-sm text-gray-800"
        >
          <option value="">Tất cả</option>
          {docTypeOptions.map((t) => (
            <option key={t.id} value={t.label}>
              {t.label}
            </option>
          ))}
        </select>
      </div>

      {/* AC-2: topicTags - tối đa 20, AND/OR */}
      <fieldset className="border-0 p-0 m-0">
        {/* <select> is valid legend content (phrasing content) - keeping it
            inside <legend> (rather than a sibling <div>) is what makes the
            legend a direct child of <fieldset>, which is required for it to
            actually label the checkbox group below for assistive tech. */}
        <legend className="flex items-center justify-between w-full p-0 mb-1">
          <span className="text-xs font-medium text-gray-500">Nhãn chủ đề</span>
          <select
            value={filters.topicTagsMode}
            onChange={(e) => onChange({ ...filters, topicTagsMode: e.target.value as 'AND' | 'OR' })}
            className="text-xs border border-gray-300 rounded px-1 py-0.5 text-gray-600"
            aria-label="Chế độ kết hợp nhãn chủ đề"
          >
            <option value="AND">Khớp tất cả (AND)</option>
            <option value="OR">Khớp bất kỳ (OR)</option>
          </select>
        </legend>
        <div className="max-h-32 overflow-y-auto border border-gray-200 rounded-md p-2 space-y-1">
          {topicOptions.length === 0 && <p className="text-xs text-gray-400">Chưa có nhãn chủ đề nào.</p>}
          {topicOptions.map((t) => (
            <label key={t.id} className="flex items-center gap-2 text-sm text-gray-700">
              <input
                type="checkbox"
                checked={filters.topicTags.includes(t.label)}
                onChange={() => onChange({ ...filters, topicTags: toggle(filters.topicTags, t.label) })}
              />
              {t.label}
            </label>
          ))}
        </div>
      </fieldset>

      {/* AC-3: khoảng ngày nạp kho */}
      <fieldset className="border-0 p-0 m-0">
        <legend className="block text-xs font-medium text-gray-500 mb-1 p-0">Ngày nạp kho</legend>
        <div className="space-y-2">
          <div>
            <label htmlFor="search-filter-uploaded-from" className="block text-[11px] text-gray-400 mb-0.5">
              Từ ngày
            </label>
            <input
              id="search-filter-uploaded-from"
              type="date"
              aria-label="Ngày nạp kho - từ ngày"
              value={filters.uploadedFrom}
              onChange={(e) => onChange({ ...filters, uploadedFrom: e.target.value })}
              className="w-full border border-gray-300 rounded-md px-2 py-1.5 text-sm text-gray-800"
            />
          </div>
          <div>
            <label htmlFor="search-filter-uploaded-to" className="block text-[11px] text-gray-400 mb-0.5">
              Đến ngày
            </label>
            <input
              id="search-filter-uploaded-to"
              type="date"
              aria-label="Ngày nạp kho - đến ngày"
              value={filters.uploadedTo}
              onChange={(e) => onChange({ ...filters, uploadedTo: e.target.value })}
              className="w-full border border-gray-300 rounded-md px-2 py-1.5 text-sm text-gray-800"
            />
          </div>
        </div>
      </fieldset>

      {/* AC-4: nhóm - tối đa 20 */}
      <fieldset className="border-0 p-0 m-0">
        <legend className="block text-xs font-medium text-gray-500 mb-1 p-0">Nhóm</legend>
        <div className="max-h-32 overflow-y-auto border border-gray-200 rounded-md p-2 space-y-1">
          {groupOptions.length === 0 && <p className="text-xs text-gray-400">Bạn chưa thuộc nhóm nào.</p>}
          {groupOptions.map((g) => (
            <label key={g.id} className="flex items-center gap-2 text-sm text-gray-700">
              <input
                type="checkbox"
                checked={filters.groupIds.includes(g.id)}
                onChange={() => onChange({ ...filters, groupIds: toggle(filters.groupIds, g.id) })}
              />
              {g.name}
            </label>
          ))}
        </div>
      </fieldset>

      {/* AC-5: thư mục + toàn bộ thư mục con */}
      <div>
        <label htmlFor="search-filter-folder" className="block text-xs font-medium text-gray-500 mb-1">
          Thư mục
        </label>
        <select
          id="search-filter-folder"
          value={filters.folderId}
          onChange={(e) => onChange({ ...filters, folderId: e.target.value })}
          className="w-full border border-gray-300 rounded-md px-2 py-1.5 text-sm text-gray-800"
        >
          <option value="">Toàn bộ kho</option>
          {folderOptions.map((f) => (
            <option key={f.id} value={f.id}>
              {f.label}
            </option>
          ))}
        </select>
      </div>

      {/* AC-6: loại nội dung + nguồn xác nhận */}
      <fieldset className="border-0 p-0 m-0">
        <legend className="block text-xs font-medium text-gray-500 mb-1 p-0">Loại nội dung</legend>
        <div className="flex flex-col gap-1">
          {MEDIA_TYPE_OPTIONS.map((opt) => (
            <label key={opt.value} className="flex items-center gap-2 text-sm text-gray-700">
              <input
                type="checkbox"
                checked={filters.mediaType.includes(opt.value)}
                onChange={() => onChange({ ...filters, mediaType: toggle(filters.mediaType, opt.value) })}
              />
              {opt.label}
            </label>
          ))}
        </div>
      </fieldset>

      <div>
        <label htmlFor="search-filter-confirm-source" className="block text-xs font-medium text-gray-500 mb-1">
          Nguồn xác nhận
        </label>
        <select
          id="search-filter-confirm-source"
          value={filters.confirmSource}
          onChange={(e) => onChange({ ...filters, confirmSource: e.target.value as SearchFilterState['confirmSource'] })}
          className="w-full border border-gray-300 rounded-md px-2 py-1.5 text-sm text-gray-800"
        >
          <option value="">Tất cả</option>
          <option value="manual">Xác nhận thủ công</option>
          <option value="auto">Tự động xác nhận</option>
        </select>
      </div>
    </aside>
  );
}
