'use client';

import { useSession } from 'next-auth/react';
import { useCallback, useEffect, useState } from 'react';
import { ApiError, apiFetch, apiJson } from '@/lib/api';
import type {
  SystemConfigHistoryEntry,
  SystemConfigRow,
} from '@/lib/api';
import { AccessDenied } from '@/components/AccessDenied';
import { AdminTabs } from '@/components/AdminTabs';
import { AppShell } from '@/components/AppShell';
import { canSeeFeature } from '@/lib/features';
import { cleanDescription, groupRows } from '@/lib/systemConfigSections';
import { toast } from '@/lib/toast';

/** Render a config value for display, respecting its type. */
function displayValue(row: Pick<SystemConfigRow, 'value' | 'valueType'>): string {
  if (row.valueType === 'JSON') return JSON.stringify(row.value);
  if (row.valueType === 'BOOLEAN') return row.value ? 'Bật' : 'Tắt';
  return String(row.value);
}

/** Human-readable size for `unit: "byte"` rows (storage quotas, ingest limits). */
function formatBytesHuman(bytes: number): string {
  if (bytes >= 1_073_741_824) return `${(bytes / 1_073_741_824).toFixed(1)} GB`;
  if (bytes >= 1_048_576) return `${(bytes / 1_048_576).toFixed(1)} MB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${bytes} B`;
}

/** Parse the raw text input back into the JSON payload the API expects. */
function parseInput(raw: string, type: SystemConfigRow['valueType']): unknown {
  switch (type) {
    case 'NUMBER': {
      const n = Number(raw);
      if (!Number.isInteger(n)) throw new Error('Giá trị phải là số nguyên');
      return n;
    }
    case 'BOOLEAN': {
      if (raw === 'true' || raw === 'Bật') return true;
      if (raw === 'false' || raw === 'Tắt') return false;
      throw new Error('Giá trị phải là Bật hoặc Tắt');
    }
    case 'JSON':
      try {
        return JSON.parse(raw);
      } catch {
        throw new Error('Giá trị JSON không hợp lệ');
      }
    default:
      return raw;
  }
}

/**
 * FR-01 (Story system-config): R3 views and edits runtime business parameters.
 * Non-sensitive keys apply immediately; sensitive keys (marked) require a
 * two-step confirmation. A per-key history panel supports rollback.
 */
export default function SystemConfigPage() {
  const { data: session, status } = useSession();
  const [rows, setRows] = useState<SystemConfigRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // inline edit state
  const [editKey, setEditKey] = useState<string | null>(null);
  const [editValue, setEditValue] = useState('');
  const [search, setSearch] = useState('');

  // two-step confirm state
  const [confirm, setConfirm] = useState<{
    key: string;
    token: string;
    value: unknown;
    label: string;
  } | null>(null);

  // history panel state
  const [historyKey, setHistoryKey] = useState<string | null>(null);
  const [history, setHistory] = useState<SystemConfigHistoryEntry[] | null>(null);

  const isAdmin = canSeeFeature(session, 'admin-system-config');

  const load = useCallback(() => {
    apiJson<SystemConfigRow[]>('/admin/system-config', session)
      .then(setRows)
      .catch((e: Error) => setError(e.message));
  }, [session]);

  useEffect(() => {
    if (isAdmin) load();
  }, [isAdmin, load]);

  if (status === 'loading') return null;
  if (!session || !isAdmin) {
    return <AccessDenied featureKey="admin-system-config" />;
  }

  const startEdit = (row: SystemConfigRow) => {
    setError(null);
    setEditKey(row.key);
    setEditValue(displayValue(row));
  };

  const cancelEdit = () => {
    setEditKey(null);
    setEditValue('');
  };

  /** PATCH a key. 409 -> open the two-step confirm modal; other errors surface. */
  const saveEdit = async (row: SystemConfigRow) => {
    setError(null);
    let payloadValue: unknown;
    try {
      payloadValue = parseInput(editValue, row.valueType);
    } catch (e) {
      setError((e as Error).message);
      return;
    }
    setBusy(true);
    try {
      await apiFetch(`/admin/system-config/${encodeURIComponent(row.key)}`, session, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ value: payloadValue }),
      });
      toast.success(`Đã cập nhật "${row.key}".`);
      cancelEdit();
      load();
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        // sensitive: backend returned a confirmation token in the body
        const token = tokenFrom(e);
        if (token) {
          setConfirm({ key: row.key, token, value: payloadValue, label: row.description });
          cancelEdit();
          return;
        }
      }
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const applyConfirm = async () => {
    if (!confirm) return;
    setError(null);
    setBusy(true);
    try {
      await apiFetch(`/admin/system-config/${encodeURIComponent(confirm.key)}/confirm`, session, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ confirmationToken: confirm.token, value: confirm.value }),
      });
      toast.success(`Đã xác nhận và cập nhật "${confirm.key}".`);
      setConfirm(null);
      load();
    } catch (e) {
      setError((e as Error).message);
      setConfirm(null);
    } finally {
      setBusy(false);
    }
  };

  const openHistory = async (key: string) => {
    setError(null);
    setHistoryKey(key);
    setHistory(null);
    try {
      const h = await apiJson<SystemConfigHistoryEntry[]>(
        `/admin/system-config/${encodeURIComponent(key)}/history`,
        session,
      );
      setHistory(h);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  /** Roll back to a previous value. Sensitive keys 409 -> confirm modal. */
  const rollback = async (key: string, toValue: unknown, sensitive: boolean) => {
    setError(null);
    setBusy(true);
    try {
      await apiFetch(`/admin/system-config/${encodeURIComponent(key)}/rollback`, session, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ toValue }),
      });
      toast.success(`Đã khôi phục "${key}".`);
      setHistoryKey(null);
      load();
    } catch (e) {
      if (sensitive && e instanceof ApiError && e.status === 409) {
        const token = tokenFrom(e);
        if (token) {
          setConfirm({ key, token, value: toValue, label: `Khôi phục ${key}` });
          setHistoryKey(null);
          return;
        }
      }
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <AppShell title="Quản trị hệ thống">
      <div className="max-w-7xl">
        <div className="mb-5">
          <h2 className="text-2xl font-bold text-[#0D2546]">Cấu hình hệ thống</h2>
          <p className="text-sm text-gray-500 mt-1">
            Tham số vận hành hệ thống. Tham số nhạy cảm yêu cầu xác nhận hai bước.
          </p>
        </div>

        <AdminTabs active="system-config" />

        {error && (
          <div className="bg-red-50 border border-red-200 rounded-md p-3 text-sm text-red-800 mb-4">
            {error}
          </div>
        )}

        <input
          type="search"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Tìm tham số…"
          aria-label="Tìm tham số"
          className="mb-4 w-80 rounded-md border border-gray-300 px-3 py-2 text-sm text-gray-800"
        />

        {rows === null ? (
          <p className="text-sm text-gray-400">Đang tải…</p>
        ) : (
          groupRows(rows, search).map(([section, sectionRows]) => (
            <section key={section} className="mb-5 bg-white rounded-lg border border-gray-200 overflow-hidden">
              <h3 className="bg-gray-50 px-4 py-2.5 text-sm font-semibold text-[#0D2546]">{section}</h3>
              <table className="w-full text-sm">
                <thead className="text-gray-500 text-xs uppercase tracking-wide">
                  <tr>
                    <th className="text-left px-4 py-2 w-1/2">Tham số</th>
                    <th className="text-left px-4 py-2">Giá trị</th>
                    <th className="text-left px-4 py-2">Mặc định</th>
                    <th className="px-4 py-2"></th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {sectionRows.map((row) => (
                    <tr key={row.key} className="align-top">
                      <td className="px-4 py-3">
                        <div className="font-medium text-[#0D2546]">
                          {cleanDescription(row.description)}
                          {!row.usingDefault && (
                            <span className="ml-2 text-[10px] font-semibold text-amber-700 bg-amber-50 rounded px-1.5 py-0.5">
                              Đã thay đổi
                            </span>
                          )}
                          {row.sensitive && (
                            <span className="ml-2 text-[10px] font-semibold uppercase text-[#D11A2A] border border-[#D11A2A] rounded px-1 py-0.5">
                              Nhạy cảm
                            </span>
                          )}
                        </div>
                        <div className="text-xs text-gray-400 font-mono">{row.key}</div>
                      </td>
                      <td className="px-4 py-3">
                        {editKey === row.key ? (
                          row.valueType === 'BOOLEAN' ? (
                            <select
                              value={editValue}
                              onChange={(e) => setEditValue(e.target.value)}
                              aria-label={`Giá trị mới: ${row.key}`}
                              className="border border-gray-300 rounded-md px-2 py-1 text-sm text-gray-800"
                            >
                              <option value="Bật">Bật</option>
                              <option value="Tắt">Tắt</option>
                            </select>
                          ) : (
                            <input
                              value={editValue}
                              onChange={(e) => setEditValue(e.target.value)}
                              aria-label={`Giá trị mới: ${row.key}`}
                              className="w-full border border-gray-300 rounded-md px-2 py-1 text-sm text-gray-800 font-mono"
                            />
                          )
                        ) : (
                          <span className="font-mono text-gray-800">
                            {row.unit === 'byte' && typeof row.value === 'number' ? (
                              <>
                                <span title={`${displayValue(row)} byte`}>{formatBytesHuman(row.value)}</span>
                              </>
                            ) : (
                              <>
                                {displayValue(row)}
                                {row.unit ? ` ${row.unit}` : ''}
                              </>
                            )}
                          </span>
                        )}
                      </td>
                      <td className="px-4 py-3 text-gray-400 font-mono text-xs">
                        {row.unit === 'byte' && typeof row.defaultValue === 'number'
                          ? formatBytesHuman(row.defaultValue)
                          : displayValue({ value: row.defaultValue, valueType: row.valueType })}
                      </td>
                      <td className="px-4 py-3 text-right whitespace-nowrap">
                        {editKey === row.key ? (
                          <>
                            <button
                              onClick={() => void saveEdit(row)}
                              disabled={busy}
                              className="text-[#0D2546] font-semibold hover:underline mr-3 cursor-pointer disabled:cursor-not-allowed disabled:opacity-60 disabled:no-underline"
                            >
                              {busy ? 'Đang lưu…' : 'Lưu'}
                            </button>
                            <button
                              onClick={cancelEdit}
                              disabled={busy}
                              className="text-gray-400 hover:underline cursor-pointer disabled:cursor-not-allowed disabled:opacity-60 disabled:no-underline"
                            >
                              Huỷ
                            </button>
                          </>
                        ) : (
                          <>
                            <button
                              onClick={() => startEdit(row)}
                              className="text-[#0D2546] hover:underline mr-3 cursor-pointer"
                            >
                              Sửa
                            </button>
                            <button
                              onClick={() => void openHistory(row.key)}
                              className="text-gray-500 hover:underline cursor-pointer"
                            >
                              Lịch sử
                            </button>
                          </>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>
          ))
        )}
        {rows !== null && groupRows(rows, search).length === 0 && (
          <p className="text-sm text-gray-400">Không có tham số nào khớp “{search.trim()}”.</p>
        )}
      </div>

      {/* Two-step confirmation modal (sensitive keys, Story 3) */}
      {confirm && (
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-md w-full mx-4">
            <h3 className="font-bold text-lg text-[#0D2546] mb-2">Xác nhận thay đổi</h3>
            <p className="text-sm text-gray-600 mb-4">
              Bạn đang thay đổi tham số nhạy cảm <strong>{confirm.label}</strong> thành{' '}
              <span className="font-mono">{JSON.stringify(confirm.value)}</span>. Hành động này
              có thể ảnh hưởng đến dữ liệu hoặc khả năng khôi phục hệ thống.
            </p>
            <div className="flex justify-end gap-3">
              <button
                onClick={() => setConfirm(null)}
                disabled={busy}
                className="px-4 py-2 text-sm text-gray-600 hover:bg-gray-100 rounded-md cursor-pointer disabled:cursor-not-allowed disabled:opacity-60"
              >
                Huỷ
              </button>
              <button
                onClick={() => void applyConfirm()}
                disabled={busy}
                className="px-4 py-2 text-sm bg-[#D11A2A] hover:bg-red-700 disabled:bg-red-300 text-white font-semibold rounded-md cursor-pointer disabled:cursor-not-allowed"
              >
                {busy ? 'Đang xử lý…' : 'Xác nhận áp dụng'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* History + rollback panel (Story 5) */}
      {historyKey && (
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-lg w-full mx-4 max-h-[80vh] overflow-y-auto">
            <div className="flex items-center justify-between mb-4">
              <h3 className="font-bold text-lg text-[#0D2546]">Lịch sử thay đổi</h3>
              <button
                onClick={() => setHistoryKey(null)}
                className="text-gray-400 hover:text-gray-600 cursor-pointer"
              >
                Đóng
              </button>
            </div>
            <p className="text-xs text-gray-400 font-mono mb-4">{historyKey}</p>
            {history === null ? (
              <p className="text-sm text-gray-400">Đang tải…</p>
            ) : history.length === 0 ? (
              <p className="text-sm text-gray-400">Chưa có thay đổi nào.</p>
            ) : (
              <ul className="space-y-3">
                {history.map((h, i) => {
                  const row = rows?.find((r) => r.key === historyKey);
                  return (
                    <li key={i} className="border border-gray-200 rounded-md p-3 text-sm">
                      <div className="text-gray-800">
                        <span className="font-mono">{JSON.stringify(h.oldValue)}</span>
                        {' → '}
                        <span className="font-mono font-semibold">
                          {JSON.stringify(h.newValue)}
                        </span>
                      </div>
                      <div className="text-xs text-gray-400 mt-1">
                        {h.actor} · {new Date(h.createdAt).toLocaleString('vi-VN')}
                      </div>
                      {h.oldValue !== null && h.oldValue !== undefined && (
                        <button
                          onClick={() =>
                            void rollback(historyKey, h.oldValue, row?.sensitive ?? false)
                          }
                          disabled={busy}
                          className="mt-2 text-xs text-[#0D2546] hover:underline cursor-pointer disabled:cursor-not-allowed disabled:opacity-60 disabled:no-underline"
                        >
                          {busy ? 'Đang khôi phục…' : 'Khôi phục về giá trị này'}
                        </button>
                      )}
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        </div>
      )}
    </AppShell>
  );
}

/** Pull the confirmationToken out of a 409 response body. */
function tokenFrom(error: ApiError): string | null {
  const body = error.body as { confirmationToken?: string } | undefined;
  return body?.confirmationToken ?? null;
}

