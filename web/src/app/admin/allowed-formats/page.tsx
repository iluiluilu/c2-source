'use client';

import { useSession } from 'next-auth/react';
import { useCallback, useEffect, useState } from 'react';
import { ApiError, apiFetch, apiJson } from '@/lib/api';
import type { AllowedFormatDetail } from '@/lib/api';
import { AccessDenied } from '@/components/AccessDenied';
import { AdminTabs } from '@/components/AdminTabs';
import { AppShell } from '@/components/AppShell';
import { canSeeFeature } from '@/lib/features';
import { suggestFormat } from '@/lib/mimeSuggest';
import { toast } from '@/lib/toast';

const CATEGORIES = ['TEXT_DOC', 'IMAGE', 'AUDIO', 'VIDEO'] as const;

const CATEGORY_LABELS: Record<string, string> = {
  TEXT_DOC: 'Văn bản',
  IMAGE: 'Hình ảnh',
  AUDIO: 'Âm thanh',
  VIDEO: 'Video',
};

/**
 * data-ingestion.md §3, Story 5: R3 manages the format allowlist that backs
 * the ingest pipeline's format check (§4.2 step 5). Every write here takes
 * effect on the very next upload (Story 5 AC-3) - the backend refreshes the
 * Redis cache synchronously in the same request.
 */
export default function AllowedFormatsPage() {
  const { data: session, status } = useSession();
  const [rows, setRows] = useState<AllowedFormatDetail[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [mimeType, setMimeType] = useState('');
  // True while the MIME field holds our suggestion (typing in it takes over).
  const [mimeSuggested, setMimeSuggested] = useState(true);
  const [extensions, setExtensions] = useState('');
  const [category, setCategory] = useState<(typeof CATEGORIES)[number]>('TEXT_DOC');
  const [saving, setSaving] = useState(false);

  const isAdmin = canSeeFeature(session, 'admin-allowed-formats');

  const load = useCallback(() => {
    apiJson<AllowedFormatDetail[]>('/admin/allowed-file-formats', session)
      .then(setRows)
      .catch((e: Error) => setError(e.message));
  }, [session]);

  useEffect(() => {
    if (isAdmin) load();
  }, [isAdmin, load]);

  if (status === 'loading') return null;
  if (!session || !isAdmin) {
    return <AccessDenied featureKey="admin-allowed-formats" />;
  }

  const resetForm = () => {
    setMimeType('');
    setExtensions('');
    setCategory('TEXT_DOC');
    setMimeSuggested(true);
  };

  const create = async () => {
    setError(null);
    const extList = extensions
      .split(',')
      .map((e) => e.trim().replace(/^\./, ''))
      .filter(Boolean);
    if (!mimeType.trim() || extList.length === 0) {
      setError('Cần nhập MIME type và ít nhất một phần mở rộng.');
      return;
    }
    setSaving(true);
    try {
      await apiFetch('/admin/allowed-file-formats', session, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mimeType: mimeType.trim(), extensions: extList, category }),
      });
      toast.success(`Đã thêm định dạng "${mimeType.trim()}".`);
      resetForm();
      load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Thêm định dạng thất bại');
    } finally {
      setSaving(false);
    }
  };

  const remove = async (row: AllowedFormatDetail) => {
    setError(null);
    try {
      await apiFetch(`/admin/allowed-file-formats/${row.mimeType}`, session, { method: 'DELETE' });
      toast.success(`Đã gỡ định dạng "${row.mimeType}".`);
      load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Gỡ định dạng thất bại');
    }
  };

  return (
    <AppShell title="Quản trị hệ thống">
      <div className="max-w-7xl">
        <div className="mb-5">
          <h2 className="text-2xl font-bold text-[#0D2546]">Định dạng cho phép</h2>
          <p className="text-sm text-gray-500 mt-1">
            Các loại tệp được chấp nhận khi nạp dữ liệu. Thay đổi có hiệu lực ngay từ lượt tải
            kế tiếp.
          </p>
        </div>

        <AdminTabs active="allowed-formats" />

        {error && (
          <div className="bg-red-50 border border-red-200 rounded-md p-3 text-sm text-red-800 mb-4">
            {error}
          </div>
        )}

        <div className="bg-white rounded-lg border border-gray-200 p-5 mb-5">
          <h3 className="font-semibold text-[#0D2546] mb-3 text-sm">Thêm định dạng mới</h3>
          <div className="grid grid-cols-1 md:grid-cols-4 gap-3">
            <input
              value={extensions}
              onChange={(e) => {
                setExtensions(e.target.value);
                const hint = suggestFormat(e.target.value);
                if (hint && mimeSuggested) {
                  setMimeType(hint.mime);
                  setCategory(hint.category);
                }
              }}
              placeholder="Đuôi tệp, phân tách bởi dấu phẩy (vd: docx, doc)"
              aria-label="Đuôi tệp"
              className="border border-gray-300 rounded-md px-3 py-2 text-sm text-gray-800 bg-white md:col-span-2"
            />
            <input
              value={mimeType}
              onChange={(e) => {
                setMimeType(e.target.value);
                setMimeSuggested(e.target.value === '');
              }}
              placeholder="MIME type (tự điền theo đuôi tệp)"
              aria-label="MIME type"
              className="border border-gray-300 rounded-md px-3 py-2 text-sm text-gray-800 bg-white"
            />
            <select
              value={category}
              onChange={(e) => setCategory(e.target.value as (typeof CATEGORIES)[number])}
              className="border border-gray-300 rounded-md px-3 py-2 text-sm text-gray-800 bg-white"
            >
              {CATEGORIES.map((c) => (
                <option key={c} value={c}>
                  {CATEGORY_LABELS[c]}
                </option>
              ))}
            </select>
          </div>
          <button
            onClick={() => void create()}
            disabled={saving}
            className="mt-3 bg-[#D11A2A] hover:bg-red-700 disabled:bg-gray-300 text-white text-sm font-semibold px-4 py-2 rounded-md cursor-pointer disabled:cursor-not-allowed"
          >
            {saving ? 'Đang lưu…' : 'Thêm định dạng'}
          </button>
        </div>

        <div className="bg-white rounded-lg border border-gray-200 overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-gray-500 text-xs uppercase tracking-wide">
              <tr>
                <th className="text-left px-4 py-3">MIME type</th>
                <th className="text-left px-4 py-3">Phần mở rộng</th>
                <th className="text-left px-4 py-3">Nhóm</th>
                <th className="text-left px-4 py-3">Cập nhật</th>
                <th className="px-4 py-3"></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {rows === null ? (
                <tr>
                  <td className="px-4 py-4 text-gray-400" colSpan={5}>
                    Đang tải…
                  </td>
                </tr>
              ) : rows.length === 0 ? (
                <tr>
                  <td className="px-4 py-4 text-gray-400" colSpan={5}>
                    Chưa có định dạng nào.
                  </td>
                </tr>
              ) : (
                rows.map((row) => (
                  <tr key={row.mimeType}>
                    <td className="px-4 py-3 font-mono text-gray-800 break-all">{row.mimeType}</td>
                    <td className="px-4 py-3 text-gray-600">{row.extensions.join(', ')}</td>
                    <td className="px-4 py-3 text-gray-600">{CATEGORY_LABELS[row.category] ?? row.category}</td>
                    <td className="px-4 py-3 text-gray-400 text-xs">
                      {new Date(row.updatedAt).toLocaleString('vi-VN')}
                    </td>
                    <td className="px-4 py-3 text-right whitespace-nowrap">
                      <button
                        onClick={() => void remove(row)}
                        className="text-[#D11A2A] hover:underline cursor-pointer"
                      >
                        Gỡ
                      </button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>
    </AppShell>
  );
}
