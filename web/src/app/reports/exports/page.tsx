'use client';

import Link from 'next/link';
import { useSession } from 'next-auth/react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { AccessDenied } from '@/components/AccessDenied';
import { AppShell } from '@/components/AppShell';
import { apiFetch, apiJson } from '@/lib/api';
import { canSeeFeature } from '@/lib/features';
import { usePermissions } from '@/lib/permissions';
import {
  EXPORT_FORMAT_LABELS,
  EXPORT_STATUS_LABELS,
  TEMPLATE_NAMES,
  filenameFromDisposition,
  hasActiveExport,
} from '@/lib/reports';
import type { ExportStatus, ReportExportPage, ReportExportRow } from '@/lib/reports';

const PAGE_SIZE = 20;
/** AC-22: how often the list is refreshed while at least one job is still PENDING/RUNNING. */
const POLL_INTERVAL_MS = 3000;

const STATUS_CLASS: Record<ExportStatus, string> = {
  PENDING: 'bg-gray-100 text-gray-700',
  RUNNING: 'bg-blue-100 text-blue-800',
  DONE: 'bg-green-100 text-green-800',
  FAILED: 'bg-red-100 text-red-800',
  EXPIRED: 'bg-amber-100 text-amber-800',
};

function formatDateTime(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString('vi-VN') : '—';
}

/** export-reporting AC-22/29/32: lịch sử xuất của chính mình, poll trạng thái, tải tệp, chạy lại. */
export default function ReportExportsPage() {
  const { data: session, status } = useSession();
  const perms = usePermissions();
  const canView = canSeeFeature(session, 'reports-exports', perms);

  const [page, setPage] = useState(0);
  const [data, setData] = useState<ReportExportPage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const rowsRef = useRef<ReportExportRow[]>([]);

  const load = useCallback(() => {
    apiJson<ReportExportPage>(`/reports/exports?page=${page}&size=${PAGE_SIZE}`, session)
      .then((d) => {
        rowsRef.current = d.content;
        setData(d);
        setError(null);
      })
      .catch((e: Error) => setError(e.message));
  }, [session, page]);

  useEffect(() => {
    if (!canView) return;
    load();
  }, [canView, load]);

  // Poll only while something can still change (PENDING/RUNNING), then stop - no idle traffic.
  const active = data ? hasActiveExport(data.content) : false;
  useEffect(() => {
    if (!canView || !active) return;
    const timer = setInterval(load, POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [canView, active, load]);

  if (status === 'loading' || (session && perms.status === 'loading')) return null;
  if (!session || !canView) {
    return <AccessDenied featureKey="reports-exports" />;
  }

  const download = async (row: ReportExportRow) => {
    setBusyId(row.id);
    setError(null);
    try {
      // Programmatic download: a plain <a href> cannot carry the bearer token.
      const res = await apiFetch(`/reports/exports/${row.id}/download`, session);
      const blob = await res.blob();
      const name = filenameFromDisposition(
        res.headers?.get?.('Content-Disposition') ?? null,
        `${row.templateCode.toLowerCase()}.${row.format.toLowerCase()}`,
      );
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = name;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      setError((e as Error).message);
      load(); // e.g. 410: the status changed to EXPIRED meanwhile
    } finally {
      setBusyId(null);
    }
  };

  const retry = async (row: ReportExportRow) => {
    setBusyId(row.id);
    setError(null);
    try {
      await apiJson(`/reports/exports/${row.id}/retry`, session, { method: 'POST' });
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusyId(null);
    }
  };

  const totalPages = data?.totalPages ?? 0;

  return (
    <AppShell title="Tệp báo cáo đã xuất">
      <div className="max-w-7xl space-y-4">
        <div className="flex items-start justify-between">
          <div>
            <h2 className="text-2xl font-bold text-[#0D2546]">Tệp báo cáo đã xuất</h2>
            <p className="text-sm text-gray-500 mt-1">
              Lịch sử xuất của riêng bạn. Tệp được giữ trong thời hạn nhất định rồi tự hết hạn.
            </p>
          </div>
          <Link href="/reports" className="text-sm font-semibold text-[#0D2546] hover:text-[#D11A2A]">
            ← Về báo cáo
          </Link>
        </div>

        {error && (
          <div role="alert" className="bg-red-50 border border-red-200 rounded-md p-3 text-sm text-red-800">
            {error}
          </div>
        )}

        <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-xs uppercase tracking-wide text-gray-400 border-b border-gray-200">
                <th className="py-3 px-4 font-medium">Mẫu</th>
                <th className="py-3 px-4 font-medium">Định dạng</th>
                <th className="py-3 px-4 font-medium">Trạng thái</th>
                <th className="py-3 px-4 font-medium">Tạo lúc</th>
                <th className="py-3 px-4 font-medium">Hạn tải</th>
                <th className="py-3 px-4 font-medium text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {data === null ? (
                <tr>
                  <td colSpan={6} className="py-8 text-center text-gray-400">
                    Đang tải…
                  </td>
                </tr>
              ) : data.content.length === 0 ? (
                <tr>
                  <td colSpan={6} className="py-8 text-center text-gray-400">
                    Bạn chưa xuất báo cáo nào.
                  </td>
                </tr>
              ) : (
                data.content.map((row) => (
                  <tr key={row.id} className="border-b border-gray-100 text-gray-800" data-testid={`export-${row.id}`}>
                    <td className="py-3 px-4">
                      <div>{TEMPLATE_NAMES[row.templateCode] ?? row.templateCode}</div>
                      <div className="text-xs text-gray-400">{row.templateCode}</div>
                    </td>
                    <td className="py-3 px-4">{EXPORT_FORMAT_LABELS[row.format]}</td>
                    <td className="py-3 px-4">
                      <span className={`inline-block px-2 py-0.5 rounded text-xs font-semibold ${STATUS_CLASS[row.status]}`}>
                        {EXPORT_STATUS_LABELS[row.status]}
                      </span>
                      {row.status === 'FAILED' && row.errorMessage && (
                        <div className="text-xs text-red-700 mt-1">{row.errorMessage}</div>
                      )}
                    </td>
                    <td className="py-3 px-4">{formatDateTime(row.createdAt)}</td>
                    <td className="py-3 px-4">{formatDateTime(row.expiresAt)}</td>
                    <td className="py-3 px-4 text-right">
                      {row.status === 'DONE' && (
                        <button
                          type="button"
                          onClick={() => void download(row)}
                          disabled={busyId === row.id}
                          className="text-[#0D2546] hover:text-[#D11A2A] font-semibold text-xs cursor-pointer disabled:text-gray-300"
                        >
                          Tải về
                        </button>
                      )}
                      {row.status === 'FAILED' && (
                        <button
                          type="button"
                          onClick={() => void retry(row)}
                          disabled={busyId === row.id}
                          className="text-[#0D2546] hover:text-[#D11A2A] font-semibold text-xs cursor-pointer disabled:text-gray-300"
                        >
                          Chạy lại
                        </button>
                      )}
                      {row.status === 'EXPIRED' && <span className="text-xs text-gray-400">Đã hết hạn</span>}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>

        {totalPages > 1 && (
          <div className="flex items-center justify-between text-sm text-gray-500">
            <span>
              Trang {page + 1} / {totalPages}
            </span>
            <span className="flex gap-2">
              <button
                type="button"
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                disabled={page === 0}
                className="px-3 py-1 border border-gray-200 rounded disabled:text-gray-300 cursor-pointer disabled:cursor-not-allowed"
              >
                Trước
              </button>
              <button
                type="button"
                onClick={() => setPage((p) => p + 1)}
                disabled={page + 1 >= totalPages}
                className="px-3 py-1 border border-gray-200 rounded disabled:text-gray-300 cursor-pointer disabled:cursor-not-allowed"
              >
                Sau
              </button>
            </span>
          </div>
        )}
      </div>
    </AppShell>
  );
}
