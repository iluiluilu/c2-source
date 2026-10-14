'use client';

import { useSession } from 'next-auth/react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { apiJson } from '@/lib/api';
import type { AccountRow, RbacRoleRow, DocumentOwnershipPageResponse, DocumentOwnershipRow, DocumentOwnershipTransferResult } from '@/lib/api';
import { AccessDenied } from '@/components/AccessDenied';
import { AdminTabs } from '@/components/AdminTabs';
import { AppShell } from '@/components/AppShell';
import { canSeeFeature } from '@/lib/features';
import { Role } from '@/lib/roles';
import { toast } from '@/lib/toast';

const STATUS_LABELS: Record<string, string> = {
  not_applicable: 'Không áp dụng',
  queued: 'Đang chờ xử lý',
  processing: 'Đang xử lý',
  failed: 'Lỗi',
  pending_review: 'Chờ duyệt',
  in_review: 'Đang duyệt',
  confirmed: 'Đã xác nhận',
};

const STATUS_BADGE_CLASS: Record<string, string> = {
  not_applicable: 'bg-gray-50 text-gray-400',
  queued: 'bg-gray-100 text-gray-600',
  processing: 'bg-blue-50 text-blue-700',
  failed: 'bg-red-50 text-red-700',
  pending_review: 'bg-yellow-50 text-yellow-800',
  in_review: 'bg-blue-50 text-blue-700',
  confirmed: 'bg-green-50 text-green-700',
};

function StatusBadge({ label, status }: { label: string; status: string }) {
  return (
    <span
      title={`${label}: ${STATUS_LABELS[status] ?? status}`}
      className={`text-[10px] font-semibold px-1.5 py-0.5 rounded ${STATUS_BADGE_CLASS[status] ?? 'bg-gray-50 text-gray-500'}`}
    >
      {label}: {STATUS_LABELS[status] ?? status}
    </span>
  );
}

const inputClass = 'w-full border border-gray-300 rounded-md px-3 py-2 text-sm text-gray-800';
const PAGE_SIZE = 50;

/**
 * digitization-review-05-frontend.md Story 7 (-03-ownership-transfer.md):
 * metadata-only search by uploaderSub + per-row transfer dialog. Pattern
 * follows admin/rbac + admin/accounts (form filter + table + dialog).
 */
export default function DocumentOwnershipAdminPage() {
  const { data: session, status } = useSession();
  const [accounts, setAccounts] = useState<AccountRow[]>([]);
  // Quyền của vai trò LANH_DAO (null = chưa tải được) để cảnh báo khi thiếu document.upload.
  const [lanhDaoPermissions, setLanhDaoPermissions] = useState<string[] | null>(null);
  const [uploaderSub, setUploaderSub] = useState('');
  const [uploaderFilter, setUploaderFilter] = useState('');
  const [appliedUploaderSub, setAppliedUploaderSub] = useState('');
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<DocumentOwnershipPageResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [transferDocumentId, setTransferDocumentId] = useState<string | null>(null);
  const [transferToUserSub, setTransferToUserSub] = useState('');
  const [transferReason, setTransferReason] = useState('');
  const [transferring, setTransferring] = useState(false);

  const canView = canSeeFeature(session, 'admin-document-ownership');

  useEffect(() => {
    if (!canView) return;
    apiJson<AccountRow[]>('/accounts', session)
      .then(setAccounts)
      .catch(() => setAccounts([]));
    apiJson<RbacRoleRow[]>('/admin/rbac/roles', session)
      .then((rows) => setLanhDaoPermissions(rows.find((x) => x.code === 'LANH_DAO')?.permissionCodes ?? null))
      .catch(() => setLanhDaoPermissions(null));
  }, [session, canView]);

  // react-hooks/set-state-in-effect: only called from the effect below when
  // appliedUploaderSub is already non-empty, so it never needs an early
  // synchronous setResult(null) guard - every setState here runs inside the
  // async apiJson().then()/.catch() callback.
  const load = useCallback(() => {
    const params = new URLSearchParams({
      uploaderSub: appliedUploaderSub,
      page: String(page),
      size: String(PAGE_SIZE),
    });
    apiJson<DocumentOwnershipPageResponse>(`/admin/document-ownership?${params.toString()}`, session)
      .then((r) => {
        setResult(r);
        setError(null);
      })
      .catch((e: Error) => setError(e.message));
  }, [session, appliedUploaderSub, page]);

  useEffect(() => {
    if (canView && appliedUploaderSub) load();
  }, [canView, appliedUploaderSub, load]);

  // AC-1: chọn tài khoản có thể đứng tên nạp tài liệu (không giới hạn theo vai trò khi tìm - chỉ B mới cần R1).
  const uploaderOptions = useMemo(() => {
    const needle = uploaderFilter.trim().toLocaleLowerCase('vi');
    return [...accounts]
      .filter(
        (a) =>
          !needle ||
          a.id === uploaderSub ||
          [a.username, a.firstName, a.lastName]
            .filter(Boolean)
            .some((v) => (v as string).toLocaleLowerCase('vi').includes(needle)),
      )
      .sort((a, b) => a.username.localeCompare(b.username));
  }, [accounts, uploaderFilter, uploaderSub]);

  const transferDocumentRow = result?.content.find((r) => r.documentId === transferDocumentId) ?? null;

  // ADR-4: người nhận phải là R1 hoặc R2 đang hoạt động, khác chính người nạp hiện tại của dòng đang chuyển.
  const recipientOptions = useMemo(
    () =>
      accounts.filter(
        (a) => a.status === 'active' && (a.roles.includes(Role.CAN_BO) || a.roles.includes(Role.LANH_DAO)) && a.id !== transferDocumentRow?.uploaderSub,
      ),
    [accounts, transferDocumentRow],
  );

  const recipientIsLanhDaoWithoutUpload =
    lanhDaoPermissions !== null &&
    !lanhDaoPermissions.includes('document.upload') &&
    (accounts.find((a) => a.id === transferToUserSub)?.roles ?? []).includes(Role.LANH_DAO);

  if (status === 'loading') return null;
  if (!session || !canView) {
    return <AccessDenied featureKey="admin-document-ownership" />;
  }

  const search = () => {
    setPage(0);
    setAppliedUploaderSub(uploaderSub);
  };

  const openTransfer = (row: DocumentOwnershipRow) => {
    setError(null);
    setTransferDocumentId(row.documentId);
    setTransferToUserSub('');
    setTransferReason('');
  };

  const closeTransfer = () => setTransferDocumentId(null);

  const submitTransfer = async () => {
    if (!transferDocumentId || !transferToUserSub) return;
    setTransferring(true);
    setError(null);
    try {
      const res = await apiJson<DocumentOwnershipTransferResult>(
        `/admin/document-ownership/${transferDocumentId}/transfer`,
        session,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            toUserSub: transferToUserSub,
            ...(transferReason.trim() ? { reason: transferReason.trim() } : {}),
          }),
        },
      );
      // AC-4: dòng này không còn thuộc uploaderSub đang lọc -> loại khỏi danh sách đang xem.
      setResult((prev) =>
        prev
          ? { ...prev, content: prev.content.filter((r) => r.documentId !== res.documentId) }
          : prev,
      );
      toast.success(`Đã chuyển quyền sở hữu tài liệu từ ${res.fromUploaderSub} sang ${res.toUploaderSub}.`);
      closeTransfer();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setTransferring(false);
    }
  };

  return (
    <AppShell title="Quản trị hệ thống">
      <div className="max-w-7xl">
        <div className="mb-5">
          <h2 className="text-2xl font-bold text-[#0D2546]">Chuyển quyền sở hữu tài liệu</h2>
          <p className="text-sm text-gray-500 mt-1">
            Tìm tài liệu theo người nạp hiện tại và chuyển quyền sở hữu — chỉ xem metadata, không truy cập nội dung số hoá.
          </p>
        </div>

        <AdminTabs active="document-ownership" />

        <div className="mb-5 bg-white rounded-lg border border-gray-200 p-5">
          <div className="grid md:grid-cols-3 gap-4 items-end">
            <label className="block md:col-span-2">
              <span className="text-xs font-semibold uppercase tracking-wide text-gray-500">
                Người nạp hiện tại
              </span>
              <div className="mt-1 flex gap-2">
                <input
                  type="search"
                  value={uploaderFilter}
                  onChange={(e) => setUploaderFilter(e.target.value)}
                  placeholder="Lọc theo tên…"
                  aria-label="Lọc danh sách người nạp"
                  className={`${inputClass} w-48 shrink-0`}
                />
                <select
                  value={uploaderSub}
                  onChange={(e) => setUploaderSub(e.target.value)}
                  className={inputClass}
                >
                  <option value="">
                    {uploaderOptions.length === 0 ? '— Không có tài khoản khớp —' : '— Chọn tài khoản —'}
                  </option>
                  {uploaderOptions.map((a) => {
                    const fullName = [a.firstName, a.lastName].filter(Boolean).join(' ');
                    return (
                      <option key={a.id} value={a.id}>
                        {fullName ? `${a.username} — ${fullName}` : a.username}
                      </option>
                    );
                  })}
                </select>
              </div>
            </label>
            <button
              onClick={search}
              disabled={!uploaderSub}
              className="bg-[#0D2546] hover:bg-[#163a6b] disabled:bg-gray-300 text-white font-semibold px-5 py-2 rounded-md text-sm transition-colors cursor-pointer disabled:cursor-not-allowed"
            >
              Tìm tài liệu
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
                <th className="px-4 py-3">Tài liệu</th>
                <th className="px-4 py-3">Trạng thái số hoá</th>
                <th className="px-4 py-3 text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {!appliedUploaderSub ? (
                <tr>
                  <td colSpan={3} className="px-4 py-6 text-center text-gray-400">
                    Chọn một tài khoản người nạp để bắt đầu tìm kiếm.
                  </td>
                </tr>
              ) : result === null ? (
                <tr>
                  <td colSpan={3} className="px-4 py-6 text-center text-gray-400">
                    Đang tải…
                  </td>
                </tr>
              ) : result.content.length === 0 ? (
                <tr>
                  <td colSpan={3} className="px-4 py-6 text-center text-gray-400">
                    Không có tài liệu nào của tài khoản này.
                  </td>
                </tr>
              ) : (
                result.content.map((row) => (
                  <tr key={row.documentId} className="border-b border-gray-100 last:border-0">
                    <td className="px-4 py-3 font-semibold text-[#0D2546] max-w-sm truncate" title={row.originalName}>
                      {row.originalName}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex flex-wrap gap-1">
                        <StatusBadge label="OCR" status={row.digitizationStatus.ocrStatus} />
                        <StatusBadge label="STT" status={row.digitizationStatus.sttStatus} />
                        <StatusBadge label="SED" status={row.digitizationStatus.sedStatus} />
                        <StatusBadge label="Tóm tắt" status={row.digitizationStatus.videoSummaryStatus} />
                      </div>
                    </td>
                    <td className="px-4 py-3 text-right">
                      <button
                        onClick={() => openTransfer(row)}
                        className="text-[#0D2546] hover:underline font-semibold cursor-pointer"
                      >
                        Chuyển quyền sở hữu
                      </button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>

        {result && result.totalElements > 0 && (
          <div className="mt-4 flex items-center justify-between text-sm text-gray-500">
            <span>
              Trang {result.page + 1}/{Math.max(result.totalPages, 1)} — tổng {result.totalElements} tài liệu
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
      </div>

      {transferDocumentId && transferDocumentRow && (
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-md w-full mx-4">
            <h3 className="font-bold text-lg text-[#0D2546] mb-1">Chuyển quyền sở hữu</h3>
            <p className="text-sm text-gray-500 mb-4 break-all">{transferDocumentRow.originalName}</p>

            <label className="block mb-3">
              <span className="text-xs font-semibold uppercase tracking-wide text-gray-500">
                Người nhận (Cán bộ R1 / Lãnh đạo R2, đang hoạt động)
              </span>
              <select
                value={transferToUserSub}
                onChange={(e) => setTransferToUserSub(e.target.value)}
                className={`${inputClass} mt-1`}
              >
                <option value="">
                  {recipientOptions.length ? '— Chọn người nhận —' : 'Không có tài khoản R1/R2 phù hợp'}
                </option>
                {recipientOptions.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.username}
                  </option>
                ))}
              </select>
              {recipientIsLanhDaoWithoutUpload && (
                <p role="alert" className="mt-1 text-xs text-amber-700">
                  Vai trò Lãnh đạo hiện không có quyền nạp dữ liệu: người nhận sẽ sở hữu tài
                  liệu nhưng không thể nạp thêm.
                </p>
              )}
            </label>

            <label className="block mb-4">
              <span className="text-xs font-semibold uppercase tracking-wide text-gray-500">
                Lý do (tuỳ chọn, tối đa 500 ký tự)
              </span>
              <textarea
                value={transferReason}
                onChange={(e) => setTransferReason(e.target.value.slice(0, 500))}
                rows={3}
                className={`${inputClass} mt-1`}
              />
            </label>

            <div className="flex justify-end gap-3">
              <button
                onClick={closeTransfer}
                disabled={transferring}
                className="px-4 py-2 text-sm text-gray-600 hover:bg-gray-100 rounded-md cursor-pointer disabled:cursor-not-allowed"
              >
                Huỷ
              </button>
              <button
                onClick={() => void submitTransfer()}
                disabled={!transferToUserSub || transferring}
                className="px-4 py-2 text-sm bg-[#D11A2A] hover:bg-red-700 disabled:opacity-50 text-white font-semibold rounded-md cursor-pointer disabled:cursor-not-allowed"
              >
                {transferring ? 'Đang chuyển…' : 'Xác nhận chuyển'}
              </button>
            </div>
          </div>
        </div>
      )}
    </AppShell>
  );
}
