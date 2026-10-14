'use client';

import { useSession } from 'next-auth/react';
import { Fragment, useCallback, useEffect, useState } from 'react';
import { ApiError, apiJson } from '@/lib/api';
import type { RbacPermissionRow, RbacPreviewImpact, RbacRoleRow, RbacUpdateResult } from '@/lib/api';
import { AccessDenied } from '@/components/AccessDenied';
import { AdminTabs } from '@/components/AdminTabs';
import { AppShell } from '@/components/AppShell';
import { canSeeFeature } from '@/lib/features';
import { toast } from '@/lib/toast';

/** rbac-permission-editing.md §3: the only 3 roles this API knows about, in R1→R3 order. */
const ROLE_ORDER = ['CAN_BO', 'LANH_DAO', 'QUAN_TRI'];

/** Selected permission codes per role, keyed by role code - the editable "desired state" per column. */
type Selection = Record<string, Set<string>>;

function selectionFromRoles(roles: RbacRoleRow[]): Selection {
  const selection: Selection = {};
  for (const role of roles) {
    selection[role.code] = new Set(role.permissionCodes);
  }
  return selection;
}

function sortedRoles(roles: RbacRoleRow[]): RbacRoleRow[] {
  return [...roles].sort((a, b) => ROLE_ORDER.indexOf(a.code) - ROLE_ORDER.indexOf(b.code));
}

/**
 * rbac-permission-editing.md: Story 1 (xem ma trận vai trò x quyền), Story 2
 * (sửa + xem trước tác động trước khi lưu), Story 3 (chặn tự khoá QUAN_TRI).
 * A checkbox matrix (hàng = permission, cột = role); each role column has its
 * own "Lưu" that only appears once that column has unsaved changes, and
 * always goes through a preview-impact confirm step before the actual PUT.
 */
/** Permission code prefix -> section of the matrix, in display order. */
const PERMISSION_SECTIONS: { label: string; prefixes: string[] }[] = [
  { label: 'Tài liệu & kho', prefixes: ['document.', 'folder.', 'note.', 'tag.'] },
  { label: 'Số hoá', prefixes: ['digitization_review.'] },
  { label: 'Nhóm chia sẻ', prefixes: ['group.', 'user.'] },
  { label: 'Lưu trữ cá nhân & thùng rác', prefixes: ['personal.', 'trash.'] },
  { label: 'Báo cáo', prefixes: ['report.'] },
  { label: 'Tài khoản & nhật ký', prefixes: ['account.', 'audit.'] },
  { label: 'Hệ thống', prefixes: ['system_config.', 'scan_allowlist.'] },
];

function groupPermissions<T extends { code: string }>(perms: T[]): [string, T[]][] {
  const groups = PERMISSION_SECTIONS.map(
    (sec) => [sec.label, perms.filter((p) => sec.prefixes.some((pre) => p.code.startsWith(pre)))] as [string, T[]],
  );
  const rest = perms.filter((p) => !PERMISSION_SECTIONS.some((sec) => sec.prefixes.some((pre) => p.code.startsWith(pre))));
  return [...groups, ['Khác', rest] as [string, T[]]].filter(([, list]) => list.length > 0);
}

export default function RbacAdminPage() {
  const { data: session, status } = useSession();
  const [roles, setRoles] = useState<RbacRoleRow[] | null>(null);
  const [permissions, setPermissions] = useState<RbacPermissionRow[] | null>(null);
  const [selection, setSelection] = useState<Selection>({});
  const [error, setError] = useState<string | null>(null);

  const [confirmRole, setConfirmRole] = useState<string | null>(null);
  const [preview, setPreview] = useState<RbacPreviewImpact | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  const isAdmin = canSeeFeature(session, 'admin-rbac');

  const load = useCallback(() => {
    Promise.all([
      apiJson<RbacRoleRow[]>('/admin/rbac/roles', session),
      apiJson<RbacPermissionRow[]>('/admin/rbac/permissions', session),
    ])
      .then(([roleRows, permissionRows]) => {
        setRoles(sortedRoles(roleRows));
        setPermissions(permissionRows);
        setSelection(selectionFromRoles(roleRows));
      })
      .catch((e: Error) => setError(e.message));
  }, [session]);

  useEffect(() => {
    if (isAdmin) load();
  }, [isAdmin, load]);

  if (status === 'loading') return null;
  if (!session || !isAdmin) {
    return <AccessDenied featureKey="admin-rbac" />;
  }

  const isDirty = (roleCode: string): boolean => {
    const original = roles?.find((r) => r.code === roleCode)?.permissionCodes ?? [];
    const current = selection[roleCode] ?? new Set<string>();
    if (original.length !== current.size) return true;
    return original.some((p) => !current.has(p));
  };

  const toggle = (roleCode: string, permissionCode: string, checked: boolean) => {
    setSelection((prev) => {
      const next = new Set(prev[roleCode] ?? []);
      if (checked) next.add(permissionCode);
      else next.delete(permissionCode);
      return { ...prev, [roleCode]: next };
    });
  };

  const resetRole = (roleCode: string) => {
    const original = roles?.find((r) => r.code === roleCode)?.permissionCodes ?? [];
    setSelection((prev) => ({ ...prev, [roleCode]: new Set(original) }));
  };

  /** Story 2 AC-5: always preview before the confirm/save click. */
  const openConfirm = async (roleCode: string) => {
    setError(null);
    setConfirmRole(roleCode);
    setPreview(null);
    setPreviewLoading(true);
    try {
      const codes = Array.from(selection[roleCode] ?? []);
      const query = new URLSearchParams({ permissionCodes: codes.join(',') });
      const result = await apiJson<RbacPreviewImpact>(
        `/admin/rbac/roles/${roleCode}/preview-impact?${query.toString()}`,
        session,
      );
      setPreview(result);
    } catch (e) {
      setError((e as Error).message);
      setConfirmRole(null);
    } finally {
      setPreviewLoading(false);
    }
  };

  const closeConfirm = () => {
    setConfirmRole(null);
    setPreview(null);
  };

  /** Story 2/3: PUT the full desired set; a 409 is the lockout guard (Story 3 AC-2). */
  const save = async () => {
    if (!confirmRole) return;
    setSaving(true);
    setError(null);
    try {
      const codes = Array.from(selection[confirmRole] ?? []);
      const result = await apiJson<RbacUpdateResult>(
        `/admin/rbac/roles/${confirmRole}/permissions`,
        session,
        {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ permissionCodes: codes }),
        },
      );
      toast.success(
        `Đã cập nhật quyền cho ${roleLabel(confirmRole)}. Ảnh hưởng ${result.affectedAccountCount} tài khoản.`,
      );
      closeConfirm();
      load();
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        const body = e.body as { code?: string; message?: string } | undefined;
        setError(body?.message ?? 'Không thể lưu: vi phạm ràng buộc chống tự khoá quyền quản trị.');
      } else {
        setError((e as Error).message);
      }
      closeConfirm();
    } finally {
      setSaving(false);
    }
  };

  const roleLabel = (roleCode: string): string =>
    roles?.find((r) => r.code === roleCode)?.displayName ?? roleCode;

  const permissionLabel = (code: string): string =>
    permissions?.find((p) => p.code === code)?.description ?? code;

  return (
    <AppShell title="Quản trị hệ thống">
      <div className="max-w-7xl">
        <div className="mb-5">
          <h2 className="text-2xl font-bold text-[#0D2546]">Phân quyền</h2>
          <p className="text-sm text-gray-500 mt-1">
            Ma trận vai trò x quyền. Bỏ tick để gỡ quyền, tick để cấp thêm - mỗi
            lần lưu thay thế toàn bộ tập quyền của một vai trò.
          </p>
        </div>

        <AdminTabs active="rbac" />

        {error && (
          <div className="bg-red-50 border border-red-200 rounded-md p-3 text-sm text-red-800 mb-4">
            {error}
          </div>
        )}

        <div className="bg-white rounded-lg border border-gray-200 overflow-hidden overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-gray-500 text-xs uppercase tracking-wide">
              <tr>
                <th className="text-left px-4 py-3">Quyền</th>
                {(roles ?? []).map((role) => (
                  <th key={role.code} className="text-center px-4 py-3 whitespace-nowrap">
                    {role.displayName}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {roles === null || permissions === null ? (
                <tr>
                  <td className="px-4 py-4 text-gray-400" colSpan={4}>
                    Đang tải…
                  </td>
                </tr>
              ) : (
                groupPermissions(permissions).map(([section, list]) => (
                  <Fragment key={section}>
                    <tr className="bg-gray-50/70">
                      <th
                        colSpan={roles.length + 1}
                        className="px-4 py-2 text-left text-xs font-semibold uppercase tracking-wide text-[#0D2546]"
                      >
                        {section}
                      </th>
                    </tr>
                    {list.map((permission) => (
                  <tr key={permission.code}>
                    <td className="px-4 py-3 text-gray-800" title={permission.code}>
                      {permission.description}
                    </td>
                    {roles.map((role) => (
                      <td key={role.code} className="px-4 py-3 text-center">
                        {role.code === 'QUAN_TRI' && permission.adminAssignable === false ? (
                          // AC-5 (export-reporting): R3 never holds this permission - no checkbox at all.
                          <span
                            className="text-gray-300"
                            title="Quyền này không thể gán cho Quản trị hệ thống"
                            aria-label="Không thể gán cho Quản trị hệ thống"
                          >
                            —
                          </span>
                        ) : (
                          <input
                            type="checkbox"
                            checked={selection[role.code]?.has(permission.code) ?? false}
                            onChange={(e) => toggle(role.code, permission.code, e.target.checked)}
                          />
                        )}
                      </td>
                    ))}
                  </tr>
                    ))}
                  </Fragment>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Unsaved changes stay visible wherever the admin has scrolled to. */}
      {roles && roles.some((r) => isDirty(r.code)) && (
        <div className="fixed bottom-5 left-4 right-4 lg:left-[calc(16rem+1.5rem)] lg:right-6 z-30 mx-auto max-w-3xl rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 shadow-xl">
          {roles
            .filter((r) => isDirty(r.code))
            .map((role) => (
              <div key={role.code} className="flex items-center justify-between gap-3 py-1 text-sm">
                <span className="text-amber-900">
                  Thay đổi chưa lưu cho vai trò <strong>{role.displayName}</strong>
                </span>
                <span className="flex gap-3">
                  <button
                    onClick={() => resetRole(role.code)}
                    className="rounded-md border border-gray-300 bg-white px-3 py-1.5 font-semibold text-gray-700 hover:bg-gray-50 cursor-pointer"
                  >
                    Huỷ
                  </button>
                  <button
                    onClick={() => void openConfirm(role.code)}
                    className="rounded-md bg-[#0D2546] px-3 py-1.5 font-semibold text-white hover:bg-[#163a6b] cursor-pointer"
                  >
                    Lưu
                  </button>
                </span>
              </div>
            ))}
        </div>
      )}

      {/* Story 2 AC-5 / Story 3 AC-2: preview impact + lockout-safe confirm modal */}
      {confirmRole && (
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-md w-full mx-4">
            <h3 className="font-bold text-lg text-[#0D2546] mb-2">
              Xác nhận thay đổi quyền — {roleLabel(confirmRole)}
            </h3>
            {previewLoading ? (
              <p className="text-sm text-gray-400 mb-4">Đang tính toán tác động…</p>
            ) : preview ? (
              <div className="text-sm text-gray-600 mb-4 space-y-2">
                <p>
                  Thay đổi này ảnh hưởng đến{' '}
                  <strong>{preview.affectedAccountCount}</strong> tài khoản đang giữ vai trò
                  này.
                </p>
                {preview.permissionsAdded.length > 0 && (
                  <p>
                    <span className="text-green-700 font-semibold">Thêm quyền: </span>
                    {preview.permissionsAdded.map(permissionLabel).join(', ')}
                  </p>
                )}
                {preview.permissionsRemoved.length > 0 && (
                  <p>
                    <span className="text-[#D11A2A] font-semibold">Gỡ quyền: </span>
                    {preview.permissionsRemoved.map(permissionLabel).join(', ')}
                  </p>
                )}
                {preview.permissionsAdded.length === 0 && preview.permissionsRemoved.length === 0 && (
                  <p className="text-gray-400">Không có thay đổi thực tế.</p>
                )}
              </div>
            ) : null}
            <div className="flex justify-end gap-3">
              <button
                onClick={closeConfirm}
                className="px-4 py-2 text-sm text-gray-600 hover:bg-gray-100 rounded-md cursor-pointer disabled:cursor-not-allowed"
                disabled={saving}
              >
                Huỷ
              </button>
              <button
                onClick={() => void save()}
                disabled={saving || previewLoading}
                className="px-4 py-2 text-sm bg-[#D11A2A] hover:bg-red-700 disabled:opacity-50 text-white font-semibold rounded-md cursor-pointer disabled:cursor-not-allowed"
              >
                {saving ? 'Đang lưu…' : 'Xác nhận lưu'}
              </button>
            </div>
          </div>
        </div>
      )}
    </AppShell>
  );
}
