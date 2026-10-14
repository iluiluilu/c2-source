'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useSession } from 'next-auth/react';
import { useCallback, useEffect, useState } from 'react';
import { ApiError, apiJson } from '@/lib/api';
import type { GroupLimitExceededError, GroupRow } from '@/lib/api';
import { AccessDenied } from '@/components/AccessDenied';
import { AppShell } from '@/components/AppShell';
import { canSeeFeature } from '@/lib/features';
import { toast } from '@/lib/toast';

const inputClass =
  'w-full border border-gray-300 rounded-md px-3 py-2 text-sm text-gray-800';

function friendlyError(e: unknown): string {
  if (e instanceof ApiError && e.status === 422) {
    const body = e.body as GroupLimitExceededError | undefined;
    if (body?.code === 'GROUP_LIMIT_EXCEEDED') return body.message;
  }
  if (e instanceof ApiError && e.status === 409) {
    return 'Đã tồn tại nhóm khác với tên này.';
  }
  return e instanceof Error ? e.message : String(e);
}

/**
 * group-management-sharing-01-group-lifecycle.md Story 1/6: list groups the
 * current user belongs to, and create a new one. R1/R2 only (NFR-04).
 */
export default function GroupsPage() {
  const { data: session, status } = useSession();
  const router = useRouter();
  const [rows, setRows] = useState<GroupRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [creating, setCreating] = useState(false);

  const canView = canSeeFeature(session, 'groups');

  const load = useCallback(() => {
    apiJson<GroupRow[]>('/groups', session)
      .then(setRows)
      .catch((e: Error) => setError(e.message));
  }, [session]);

  useEffect(() => {
    if (canView) load();
  }, [canView, load]);

  if (status === 'loading') return null;
  if (!session || !canView) {
    return <AccessDenied featureKey="groups" />;
  }

  const createGroup = async () => {
    setError(null);
    setCreating(true);
    try {
      const created = await apiJson<GroupRow>('/groups', session, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: name.trim(),
          ...(description.trim() ? { description: description.trim() } : {}),
        }),
      });
      setName('');
      setDescription('');
      setShowCreate(false);
      toast.success(`Đã tạo nhóm "${created.name}". Thêm thành viên để bắt đầu chia sẻ.`);
      // Next step is always adding members - go straight to the group.
      router.push(`/groups/${created.id}`);
    } catch (e) {
      setError(friendlyError(e));
    } finally {
      setCreating(false);
    }
  };

  return (
    <AppShell title="Nhóm chia sẻ">
      <div className="max-w-7xl">
        <div className="mb-5 flex items-center justify-between">
          <div>
            <h2 className="text-2xl font-bold text-[#0D2546]">Nhóm chia sẻ</h2>
            <p className="text-sm text-gray-500 mt-1">
              Tạo nhóm để chia sẻ tài liệu cho nhiều người cùng lúc - chỉ chủ
              nhóm (owner) mới quản lý được thành viên.
            </p>
          </div>
          <button
            onClick={() => setShowCreate((v) => !v)}
            className="bg-[#0D2546] hover:bg-[#163a6b] text-white font-semibold px-4 py-2 rounded-md text-sm transition-colors cursor-pointer"
          >
            {showCreate ? 'Đóng' : '+ Tạo nhóm'}
          </button>
        </div>

        {error && (
          <div className="mb-4 bg-red-50 border border-red-200 rounded-md p-3 text-sm text-red-800">
            {error}
          </div>
        )}

        {showCreate && (
          <div className="mb-5 bg-white rounded-lg border border-gray-200 p-5">
            <h4 className="font-semibold text-[#0D2546] mb-4">Tạo nhóm mới</h4>
            <div className="grid gap-4">
              <label className="block">
                <span className="text-xs font-semibold uppercase tracking-wide text-gray-500">
                  Tên nhóm
                </span>
                <input
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="VD: Phòng Kế hoạch"
                  className={`${inputClass} mt-1`}
                />
              </label>
              <label className="block">
                <span className="text-xs font-semibold uppercase tracking-wide text-gray-500">
                  Mô tả (tùy chọn)
                </span>
                <textarea
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  rows={2}
                  className={`${inputClass} mt-1`}
                />
              </label>
            </div>
            <button
              onClick={() => void createGroup()}
              disabled={name.trim().length === 0 || creating}
              className="mt-4 bg-[#0D2546] hover:bg-[#163a6b] disabled:bg-gray-300 text-white font-semibold px-5 py-2 rounded-md text-sm transition-colors cursor-pointer disabled:cursor-not-allowed"
            >
              Tạo nhóm
            </button>
          </div>
        )}

        <div className="bg-white rounded-lg border border-gray-200 overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-xs uppercase tracking-wide text-gray-500 border-b border-gray-200">
                <th className="px-4 py-3">Tên nhóm</th>
                <th className="px-4 py-3">Mô tả</th>
                <th className="px-4 py-3">Vai trò của bạn</th>
                <th className="px-4 py-3 text-right">Thành viên</th>
                <th className="px-4 py-3 text-right">Tài liệu</th>
                <th className="px-4 py-3">Ngày tạo</th>
                <th className="px-4 py-3 text-right">Chi tiết</th>
              </tr>
            </thead>
            <tbody>
              {rows === null ? (
                <tr>
                  <td colSpan={7} className="px-4 py-6 text-center text-gray-400">
                    Đang tải…
                  </td>
                </tr>
              ) : rows.length === 0 ? (
                <tr>
                  <td colSpan={7} className="px-4 py-6 text-center text-gray-400">
                    Bạn chưa thuộc nhóm nào.
                  </td>
                </tr>
              ) : (
                rows.map((row) => (
                  <tr key={row.id} className="border-b border-gray-100 last:border-0">
                    <td className="px-4 py-3 font-semibold">
                      <Link href={`/groups/${row.id}`} className="text-[#0D2546] hover:underline">
                        {row.name}
                      </Link>
                    </td>
                    <td className="px-4 py-3 text-gray-500 max-w-xs truncate" title={row.description ?? undefined}>
                      {row.description ?? '—'}
                    </td>
                    <td className="px-4 py-3 text-gray-600">
                      {row.myRole === 'owner' ? 'Chủ nhóm' : row.myRole === 'member' ? 'Thành viên' : '—'}
                    </td>
                    <td className="px-4 py-3 text-right text-gray-600 tabular-nums">{row.memberCount ?? '—'}</td>
                    <td className="px-4 py-3 text-right text-gray-600 tabular-nums">{row.documentCount ?? '—'}</td>
                    <td className="px-4 py-3 text-gray-500">
                      {new Date(row.createdAt).toLocaleDateString('vi-VN')}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <Link href={`/groups/${row.id}`} className="text-[#0D2546] hover:underline font-semibold">
                        Xem
                      </Link>
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
