'use client';

import Link from 'next/link';
import { useSession } from 'next-auth/react';
import { Fragment, useCallback, useEffect, useState } from 'react';
import { apiJson } from '@/lib/api';
import type { AuditChainVerification, AuditLogRow, AuditPageResponse } from '@/lib/api';
import { AccessDenied } from '@/components/AccessDenied';
import { AppShell } from '@/components/AppShell';
import { canSeeFeature } from '@/lib/features';
import { hasRole, Role } from '@/lib/roles';
import {
  AUDIT_ACTIONS,
  AUDIT_ACTION_GROUPS,
  AUDIT_RESOURCE_TYPES,
  REASON_LABELS,
  actionLabel,
  metadataLabel,
  outcomeLabel,
  resourceTypeInfo,
  shortResourceId,
} from '@/lib/auditCatalog';

const inputClass =
  'w-full border border-gray-300 rounded-md px-3 py-2 text-sm text-gray-800';


const EMPTY_FILTERS = {
  from: '',
  to: '',
  actor: '',
  action: '',
  resourceType: '',
};

/** audit-ui-readability.md AC-3: dropdown options sorted by label (vi). */
const SORTED_RESOURCE_TYPES = [...AUDIT_RESOURCE_TYPES].sort((a, b) =>
  a.label.localeCompare(b.label, 'vi'),
);

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="text-xs font-semibold uppercase tracking-wide text-gray-500">
        {label}
      </span>
      <div className="mt-1">{children}</div>
    </label>
  );
}

function buildQuery(filters: typeof EMPTY_FILTERS, page: number, size: number): string {
  const params = new URLSearchParams();
  if (filters.from) params.set('from', new Date(filters.from).toISOString());
  if (filters.to) params.set('to', new Date(filters.to).toISOString());
  if (filters.actor.trim()) params.set('actor', filters.actor.trim());
  if (filters.action.trim()) params.set('action', filters.action.trim());
  if (filters.resourceType.trim()) params.set('resourceType', filters.resourceType.trim());
  params.set('page', String(page));
  params.set('size', String(size));
  return params.toString();
}

/**
 * audit-logging.md Task 9: shared by R2 (Story 3, backend narrows the result
 * set) and R3 (Story 2, unrestricted) - the UI itself does not branch on
 * role for the list, only for the verify action (R3-only, Story 4).
 */
export default function AuditAdminPage() {
  const { data: session, status } = useSession();
  const [filters, setFilters] = useState(EMPTY_FILTERS);
  const [page, setPage] = useState(0);
  const [size] = useState(50);
  const [result, setResult] = useState<AuditPageResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [expandedId, setExpandedId] = useState<number | null>(null);
  const [jsonOpenId, setJsonOpenId] = useState<number | null>(null);
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const [verifyForm, setVerifyForm] = useState({ from: '', to: '' });
  const [verifyResult, setVerifyResult] = useState<AuditChainVerification | null>(null);
  const [verifyError, setVerifyError] = useState<string | null>(null);
  const [verifying, setVerifying] = useState(false);

  const canView = canSeeFeature(session, 'audit');
  const canVerify = hasRole(session, Role.QUAN_TRI);
  // Admins must not browse business documents (separation of duties); others can follow the link.
  const canOpenDocuments = canSeeFeature(session, 'documents');

  const load = useCallback(() => {
    apiJson<AuditPageResponse>(`/audit?${buildQuery(filters, page, size)}`, session)
      .then((r) => {
        setResult(r);
        setError(null);
      })
      .catch((e: Error) => setError(e.message));
  }, [session, filters, page, size]);

  useEffect(() => {
    if (canView) load();
  }, [canView, load]);

  if (status === 'loading') return null;
  if (!session || !canView) {
    return <AccessDenied featureKey="audit" />;
  }

  const search = () => {
    setPage(0);
    load();
  };

  const clearFilters = () => {
    setFilters(EMPTY_FILTERS);
    setPage(0);
  };

  const copyResourceId = (id: string) => {
    setCopiedId(id);
    setTimeout(() => setCopiedId((current) => (current === id ? null : current)), 1500);
    try {
      void navigator.clipboard?.writeText(id);
    } catch {
      // Không hỗ trợ (HTTP thường/trình duyệt cũ) hoặc bị từ chối quyền - bỏ qua, không chặn UI.
    }
  };

  const runVerify = async () => {
    if (!verifyForm.from || !verifyForm.to) return;
    setVerifying(true);
    setVerifyError(null);
    setVerifyResult(null);
    try {
      const params = new URLSearchParams({
        from: new Date(verifyForm.from).toISOString(),
        to: new Date(verifyForm.to).toISOString(),
      });
      const r = await apiJson<AuditChainVerification>(`/audit/verify?${params.toString()}`, session);
      setVerifyResult(r);
    } catch (e) {
      setVerifyError((e as Error).message);
    } finally {
      setVerifying(false);
    }
  };

  return (
    <AppShell title="Nhật ký hoạt động">
      <div className="max-w-7xl">
        <div className="mb-5">
          <h2 className="text-2xl font-bold text-[#0D2546]">Nhật ký hoạt động</h2>
          <p className="text-sm text-gray-500 mt-1">
            Tra cứu và lọc nhật ký hoạt động — mỗi lượt xem cũng được ghi lại.{' '}
            {!canVerify && 'Chỉ Quản trị hệ thống mới xác minh được toàn vẹn chuỗi.'}
          </p>
        </div>

        <div className="mb-5 bg-white rounded-lg border border-gray-200 p-4">
          <div className="grid md:grid-cols-3 lg:grid-cols-5 gap-3">
            <Field label="Từ thời điểm">
              <input
                type="datetime-local"
                value={filters.from}
                onChange={(e) => setFilters((f) => ({ ...f, from: e.target.value }))}
                className={inputClass}
              />
            </Field>
            <Field label="Đến thời điểm">
              <input
                type="datetime-local"
                value={filters.to}
                onChange={(e) => setFilters((f) => ({ ...f, to: e.target.value }))}
                className={inputClass}
              />
            </Field>
            <Field label="Người thực hiện (actor)">
              <input
                value={filters.actor}
                onChange={(e) => setFilters((f) => ({ ...f, actor: e.target.value }))}
                placeholder="VD: canbo1"
                className={inputClass}
              />
            </Field>
            <Field label="Hành động (action)">
              <select
                value={filters.action}
                onChange={(e) => setFilters((f) => ({ ...f, action: e.target.value }))}
                className={inputClass}
              >
                <option value="">Tất cả hành động</option>
                {AUDIT_ACTION_GROUPS.map((group) => (
                  <optgroup key={group} label={group}>
                    {AUDIT_ACTIONS.filter((a) => a.group === group).map((a) => (
                      <option key={a.code} value={a.code}>
                        {a.label}
                      </option>
                    ))}
                  </optgroup>
                ))}
              </select>
            </Field>
            <Field label="Loại tài nguyên">
              <select
                value={filters.resourceType}
                onChange={(e) => setFilters((f) => ({ ...f, resourceType: e.target.value }))}
                className={inputClass}
              >
                <option value="">Tất cả loại</option>
                {SORTED_RESOURCE_TYPES.map((r) => (
                  <option key={r.code} value={r.code}>
                    {r.icon} {r.label}
                  </option>
                ))}
              </select>
            </Field>
          </div>
          <div className="mt-3 flex gap-2">
            <button
              onClick={search}
              className="bg-[#0D2546] hover:bg-[#163a6b] text-white font-semibold px-5 py-2 rounded-md text-sm transition-colors cursor-pointer"
            >
              Tìm kiếm
            </button>
            <button
              onClick={clearFilters}
              className="bg-gray-200 hover:bg-gray-300 text-gray-800 font-semibold px-5 py-2 rounded-md text-sm transition-colors cursor-pointer"
            >
              Xóa bộ lọc
            </button>
          </div>
        </div>

        {error && (
          <div className="mb-4 bg-red-50 border border-red-200 rounded-md p-3 text-sm text-red-800">
            {error}
          </div>
        )}

        <div className="bg-white rounded-lg border border-gray-200 overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-xs uppercase tracking-wide text-gray-500 border-b border-gray-200">
                <th className="px-4 py-3">Thời điểm</th>
                <th className="px-4 py-3">Người thực hiện</th>
                <th className="px-4 py-3">Hành động</th>
                <th className="px-4 py-3">Loại tài nguyên</th>
                <th className="px-4 py-3">ID tài nguyên</th>
                <th className="px-4 py-3">Kết quả</th>
                <th className="px-4 py-3">IP</th>
              </tr>
            </thead>
            <tbody>
              {result === null ? (
                <tr>
                  <td colSpan={7} className="px-4 py-6 text-center text-gray-400">
                    Đang tải…
                  </td>
                </tr>
              ) : result.content.length === 0 ? (
                <tr>
                  <td colSpan={7} className="px-4 py-6 text-center text-gray-400">
                    Không có bản ghi nào khớp bộ lọc.
                  </td>
                </tr>
              ) : (
                result.content.map((row: AuditLogRow) => {
                  const action = actionLabel(row.action);
                  const resource = resourceTypeInfo(row.resourceType);
                  const outcome = outcomeLabel(row.outcome, row.statusCode);
                  const isJsonOpen = jsonOpenId === row.id;

                  return (
                    <Fragment key={row.id}>
                      <tr
                        className="border-b border-gray-100 last:border-0 cursor-pointer hover:bg-gray-50"
                        onClick={() => setExpandedId(expandedId === row.id ? null : row.id)}
                      >
                        <td className="px-4 py-3 text-gray-500 whitespace-nowrap">
                          {new Date(row.createdAt).toLocaleString('vi-VN')}
                        </td>
                        <td className="px-4 py-3 font-semibold text-[#0D2546]">{row.actor}</td>
                        <td className="px-4 py-3 text-gray-700" title={row.action}>
                          {action}
                        </td>
                        <td className="px-4 py-3 text-gray-600" title={row.resourceType ?? undefined}>
                          {resource ? `${resource.icon} ${resource.label}` : '—'}
                        </td>
                        <td className="px-4 py-3 text-gray-500">
                          {row.resourceId ? (
                            <span className="inline-flex items-center gap-1.5">
                              {canOpenDocuments && row.resourceType === 'document' ? (
                                <Link
                                  href={`/documents/${row.resourceId}`}
                                  title={`Mở tài liệu ${row.resourceId}`}
                                  className="font-mono text-[#0D2546] hover:underline"
                                >
                                  {shortResourceId(row.resourceId)}
                                </Link>
                              ) : (
                                <code title={row.resourceId} className="font-mono">
                                  {shortResourceId(row.resourceId)}
                                </code>
                              )}
                              <button
                                type="button"
                                aria-label="Sao chép ID tài nguyên"
                                onClick={(e) => {
                                  e.stopPropagation();
                                  copyResourceId(row.resourceId as string);
                                }}
                                className="text-gray-400 hover:text-gray-700 cursor-pointer"
                              >
                                📋
                              </button>
                              {copiedId === row.resourceId && (
                                <span className="text-xs text-green-700">Đã sao chép</span>
                              )}
                            </span>
                          ) : (
                            '—'
                          )}
                        </td>
                        <td
                          className="px-4 py-3 text-gray-700"
                          title={row.statusCode ? `HTTP ${row.statusCode}` : undefined}
                        >
                          {outcome}
                        </td>
                        <td className="px-4 py-3 text-gray-500">{row.ip ?? '—'}</td>
                      </tr>
                      {expandedId === row.id && (
                        <tr className="bg-gray-50 border-b border-gray-100">
                          <td colSpan={7} className="px-4 py-3 text-xs text-gray-600">
                            <div className="grid md:grid-cols-2 gap-2">
                              <div>
                                <span className="font-semibold">Method/Path:</span>{' '}
                                {row.method ?? '—'} {row.path ?? ''}
                              </div>
                              <div>
                                <span className="font-semibold">Đã niêm phong (sealed):</span>{' '}
                                {row.sealedAt ? new Date(row.sealedAt).toLocaleString('vi-VN') : 'Chưa (đang chờ)'}
                              </div>
                              <div>
                                <span className="font-semibold">Mã hành động:</span>{' '}
                                <code>{row.action}</code>
                              </div>
                              {row.metadata && Object.keys(row.metadata).length > 0 && (
                                <div className="md:col-span-2">
                                  <span className="font-semibold">Metadata:</span>
                                  <dl className="mt-1 grid grid-cols-[max-content_1fr] gap-x-2 gap-y-1">
                                    {Object.entries(row.metadata).map(([key, value]) => (
                                      <Fragment key={key}>
                                        <dt className="text-gray-500">{metadataLabel(key)}:</dt>
                                        <dd className="break-all">
                                          {key === 'reason' && typeof value === 'string' && REASON_LABELS[value]
                                            ? REASON_LABELS[value]
                                            : typeof value === 'object' && value !== null
                                              ? JSON.stringify(value)
                                              : String(value)}
                                        </dd>
                                      </Fragment>
                                    ))}
                                  </dl>
                                  <button
                                    type="button"
                                    onClick={() => setJsonOpenId(isJsonOpen ? null : row.id)}
                                    className="mt-2 text-xs font-semibold text-[#0D2546] underline cursor-pointer"
                                  >
                                    {isJsonOpen ? 'Ẩn JSON gốc' : 'Xem JSON gốc'}
                                  </button>
                                  {isJsonOpen && (
                                    <code className="mt-1 block break-all">{JSON.stringify(row.metadata)}</code>
                                  )}
                                </div>
                              )}
                            </div>
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  );
                })
              )}
            </tbody>
          </table>
        </div>

        {result && result.totalElements > 0 && (
          <div className="mt-4 flex items-center justify-between text-sm text-gray-500">
            <span>
              Trang {result.page + 1}/{Math.max(result.totalPages, 1)} — tổng {result.totalElements} bản ghi
            </span>
            <div className="flex gap-2">
              <button
                onClick={() => setPage((p) => Math.max(p - 1, 0))}
                disabled={result.page <= 0}
                className="bg-gray-200 hover:bg-gray-300 disabled:opacity-50 text-gray-800 font-semibold px-3 py-1.5 rounded-md text-sm transition-colors cursor-pointer disabled:cursor-not-allowed"
              >
                ← Trước
              </button>
              <button
                onClick={() => setPage((p) => p + 1)}
                disabled={result.page + 1 >= result.totalPages}
                className="bg-gray-200 hover:bg-gray-300 disabled:opacity-50 text-gray-800 font-semibold px-3 py-1.5 rounded-md text-sm transition-colors cursor-pointer disabled:cursor-not-allowed"
              >
                Sau →
              </button>
            </div>
          </div>
        )}
        {canVerify && (
          <details className="mt-5 bg-white rounded-lg border border-gray-200 p-5">
            <summary className="cursor-pointer font-semibold text-[#0D2546]">Xác minh toàn vẹn chuỗi</summary>
            <p className="text-xs text-gray-500 mb-4">
              Kiểm tra nhật ký trong khoảng thời gian đã chọn có bị sửa đổi hay không.
            </p>
            <div className="grid md:grid-cols-2 gap-4">
              <Field label="Từ thời điểm">
                <input
                  type="datetime-local"
                  value={verifyForm.from}
                  onChange={(e) => setVerifyForm((f) => ({ ...f, from: e.target.value }))}
                  className={inputClass}
                />
              </Field>
              <Field label="Đến thời điểm">
                <input
                  type="datetime-local"
                  value={verifyForm.to}
                  onChange={(e) => setVerifyForm((f) => ({ ...f, to: e.target.value }))}
                  className={inputClass}
                />
              </Field>
            </div>
            <button
              onClick={() => void runVerify()}
              disabled={!verifyForm.from || !verifyForm.to || verifying}
              className="mt-4 bg-[#0D2546] hover:bg-[#163a6b] disabled:bg-gray-300 text-white font-semibold px-5 py-2 rounded-md text-sm transition-colors cursor-pointer disabled:cursor-not-allowed"
            >
              {verifying ? 'Đang xác minh…' : 'Xác minh'}
            </button>
            {verifyError && <p className="mt-3 text-sm text-red-700">{verifyError}</p>}
            {verifyResult && (
              <div
                className={`mt-3 text-sm rounded-md p-3 ${
                  verifyResult.ok
                    ? 'bg-green-50 text-green-800 border border-green-200'
                    : 'bg-red-50 text-red-800 border border-red-200'
                }`}
              >
                {verifyResult.ok
                  ? `Hợp lệ — đã kiểm tra ${verifyResult.count} bản ghi, không phát hiện sai lệch.`
                  : `Phát hiện sai lệch tại bản ghi #${verifyResult.brokenAtId}: ${verifyResult.reason}`}
              </div>
            )}
          </details>
        )}

      </div>
    </AppShell>
  );
}
