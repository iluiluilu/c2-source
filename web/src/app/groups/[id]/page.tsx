'use client';

import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import { useSession } from 'next-auth/react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { ApiError, apiFetch, apiJson } from '@/lib/api';
import type {
  DocumentRow,
  GroupDocumentsPageResponse,
  GroupLimitExceededError,
  GroupMemberRole,
  GroupMemberRow,
  GroupRow,
  UserDirectoryEntry,
} from '@/lib/api';
import { AccessDenied } from '@/components/AccessDenied';
import { AppShell } from '@/components/AppShell';
import { useConfirm } from '@/components/ConfirmDialog';
import { DocumentPicker } from '@/components/DocumentPicker';
import { UserPicker } from '@/components/UserPicker';
import { canSeeFeature } from '@/lib/features';
import { toast } from '@/lib/toast';

const inputClass =
  'w-full border border-gray-300 rounded-md px-3 py-2 text-sm text-gray-800';

const ROLE_LABELS: Record<GroupMemberRole, string> = {
  owner: 'Chủ nhóm',
  member: 'Thành viên',
};

const ROLE_BADGE_CLASS: Record<GroupMemberRole, string> = {
  owner: 'bg-amber-50 text-amber-800',
  member: 'bg-blue-50 text-blue-700',
};

function RoleBadge({ role }: { role: GroupMemberRole }) {
  return (
    <span
      className={`text-xs font-semibold px-2 py-0.5 rounded ${ROLE_BADGE_CLASS[role]}`}
    >
      {ROLE_LABELS[role]}
    </span>
  );
}

function friendlyError(e: unknown): string {
  if (e instanceof ApiError && e.status === 422) {
    const body = e.body as GroupLimitExceededError | undefined;
    if (body?.code === 'GROUP_LIMIT_EXCEEDED') return body.message;
  }
  if (e instanceof ApiError && e.status === 409) {
    if (e.message.toLowerCase().includes('already shared')) {
      return 'Tài liệu này đã được chia sẻ vào nhóm.';
    }
    return 'Không thể thực hiện: nhóm phải luôn còn ít nhất một chủ nhóm, hoặc tên nhóm đã tồn tại.';
  }
  if (e instanceof ApiError && e.status === 403) {
    return 'Chỉ chủ nhóm mới có thể thực hiện thao tác này.';
  }
  return e instanceof Error ? e.message : String(e);
}

/**
 * group-management-sharing-01-group-lifecycle.md Story 1-4: group detail -
 * rename/describe, member list with role changes, add/remove members, leave,
 * and (owner-only) delete. There is no single-group GET endpoint (§3.1), so
 * the header comes from filtering `GET /groups` (the same list the /groups
 * page already fetches).
 */
export default function GroupDetailPage() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const router = useRouter();
  const { data: session, status } = useSession();
  const confirm = useConfirm();

  const [group, setGroup] = useState<GroupRow | null>(null);
  const [members, setMembers] = useState<GroupMemberRow[] | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // group-management-sharing-02-document-sharing.md §4.6: tab "Tài liệu của nhóm"
  const [documentsPage, setDocumentsPage] = useState<GroupDocumentsPageResponse | null>(null);
  const [docPageIndex, setDocPageIndex] = useState(0);

  const [editingInfo, setEditingInfo] = useState(false);
  const [nameForm, setNameForm] = useState('');
  const [descriptionForm, setDescriptionForm] = useState('');

  const [addRole, setAddRole] = useState<GroupMemberRole>('member');

  const canView = canSeeFeature(session, 'groups');

  const load = useCallback(() => {
    apiJson<GroupRow[]>('/groups', session)
      .then((rows) => {
        const found = rows.find((g) => g.id === id);
        if (!found) {
          setNotFound(true);
          return;
        }
        setNotFound(false);
        setGroup(found);
        setNameForm(found.name);
        setDescriptionForm(found.description ?? '');
      })
      .catch((e: Error) => setError(e.message));
    apiJson<GroupMemberRow[]>(`/groups/${id}/members`, session)
      .then(setMembers)
      .catch(() => setMembers([]));
  }, [session, id]);

  useEffect(() => {
    if (canView) load();
  }, [canView, load]);

  const loadDocuments = useCallback(() => {
    apiJson<GroupDocumentsPageResponse>(
      `/groups/${id}/documents?page=${docPageIndex}&size=50`,
      session,
    )
      .then(setDocumentsPage)
      .catch(() => setDocumentsPage(null));
  }, [id, session, docPageIndex]);

  useEffect(() => {
    if (canView) loadDocuments();
  }, [canView, loadDocuments]);

  const myMembership = useMemo(
    () => members?.find((m) => m.userSub === session?.sub) ?? null,
    [members, session],
  );
  const isOwner = myMembership?.roleInGroup === 'owner';
  const ownerCount = useMemo(
    () => members?.filter((m) => m.roleInGroup === 'owner').length ?? 0,
    [members],
  );

  if (status === 'loading') return null;
  if (!session || !canView) {
    return <AccessDenied featureKey="groups" />;
  }

  const run = async (action: () => Promise<void>, ok: string) => {
    setError(null);
    setBusy(true);
    try {
      await action();
      toast.success(ok);
      load();
    } catch (e) {
      setError(friendlyError(e));
    } finally {
      setBusy(false);
    }
  };

  /** Group owner, the sharer or the uploader may unshare (backend rule, §4.3 Task 6). */
  const unshareDocument = async (documentId: string, title: string) => {
    const ok = await confirm({
      title: 'Gỡ chia sẻ',
      message: `Gỡ "${title}" khỏi nhóm? Các thành viên khác sẽ không còn thấy tài liệu này qua nhóm.`,
      tone: 'danger',
      confirmLabel: 'Gỡ',
    });
    if (!ok) return;
    await run(async () => {
      await apiFetch(`/documents/${documentId}/shares/${id}`, session, { method: 'DELETE' });
      loadDocuments();
    }, `Đã gỡ "${title}" khỏi nhóm.`);
  };

  const saveInfo = () =>
    run(async () => {
      await apiFetch(`/groups/${id}`, session, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: nameForm.trim(),
          description: descriptionForm.trim(),
        }),
      });
      setEditingInfo(false);
    }, 'Đã cập nhật thông tin nhóm.');

  const deleteGroup = async () => {
    const ok = await confirm({
      title: 'Xoá nhóm',
      message: `Xác nhận xoá nhóm "${group?.name}"? Hành động này không thể hoàn tác.`,
      confirmLabel: 'Xoá nhóm',
      tone: 'danger',
    });
    if (!ok) return;
    void run(async () => {
      await apiFetch(`/groups/${id}`, session, { method: 'DELETE' });
      router.push('/groups');
    }, 'Đã xoá nhóm.');
  };

  const leaveGroup = async () => {
    const ok = await confirm({
      title: 'Rời khỏi nhóm',
      message: 'Xác nhận rời khỏi nhóm này?',
    });
    if (!ok) return;
    void run(async () => {
      await apiFetch(`/groups/${id}/leave`, session, { method: 'POST' });
      router.push('/groups');
    }, 'Đã rời nhóm.');
  };

  const changeRole = (userSub: string, roleInGroup: GroupMemberRole) =>
    run(async () => {
      await apiFetch(`/groups/${id}/members/${encodeURIComponent(userSub)}`, session, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ roleInGroup }),
      });
    }, roleInGroup === 'owner' ? 'Đã đặt làm chủ nhóm.' : 'Đã chuyển thành thành viên.');

  const removeMember = async (userSub: string) => {
    const ok = await confirm({
      title: 'Gỡ thành viên',
      message: 'Xác nhận gỡ thành viên này khỏi nhóm?',
    });
    if (!ok) return;
    void run(async () => {
      await apiFetch(`/groups/${id}/members/${encodeURIComponent(userSub)}`, session, {
        method: 'DELETE',
      });
    }, 'Đã gỡ thành viên.');
  };

  const addMember = (entry: UserDirectoryEntry) =>
    run(async () => {
      await apiFetch(`/groups/${id}/members`, session, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userSub: entry.sub, roleInGroup: addRole }),
      });
    }, `Đã thêm ${entry.displayName} vào nhóm.`);

  // group-management-sharing-02-document-sharing.md §4.6: "Thêm tài liệu"
  // picker on the group detail page - reuses the per-document share endpoint
  // (same one the document detail page's "Chia sẻ với nhóm" card calls).
  const addDocument = (doc: DocumentRow) =>
    run(async () => {
      await apiFetch(`/documents/${doc.id}/shares`, session, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ groupId: id }),
      });
      loadDocuments();
    }, `Đã thêm "${doc.originalName}" vào nhóm.`);

  if (notFound) {
    return (
      <AppShell title="Nhóm chia sẻ">
        <div className="max-w-7xl">
          <p className="text-sm text-gray-500">
            Không tìm thấy nhóm này, hoặc bạn không phải là thành viên.{' '}
            <Link href="/groups" className="text-[#0D2546] font-semibold hover:underline">
              ← Về danh sách nhóm
            </Link>
          </p>
        </div>
      </AppShell>
    );
  }

  const memberSubs = members?.map((m) => m.userSub) ?? [];

  return (
    <AppShell title="Nhóm chia sẻ">
      <div className="max-w-7xl">
        <p className="mb-4 text-sm">
          <Link href="/groups" className="text-[#0D2546] font-semibold hover:underline">
            ← Về danh sách nhóm
          </Link>
        </p>

        {error && (
          <div className="mb-4 bg-red-50 border border-red-200 rounded-md p-3 text-sm text-red-800">
            {error}
          </div>
        )}

        <div className="mb-5 bg-white rounded-lg border border-gray-200 p-5">
          {editingInfo ? (
            <div className="grid gap-4">
              <label className="block">
                <span className="text-xs font-semibold uppercase tracking-wide text-gray-500">Tên nhóm</span>
                <input value={nameForm} onChange={(e) => setNameForm(e.target.value)} className={`${inputClass} mt-1`} />
              </label>
              <label className="block">
                <span className="text-xs font-semibold uppercase tracking-wide text-gray-500">Mô tả</span>
                <textarea
                  value={descriptionForm}
                  onChange={(e) => setDescriptionForm(e.target.value)}
                  rows={2}
                  className={`${inputClass} mt-1`}
                />
              </label>
              <div className="flex gap-2">
                <button
                  onClick={() => void saveInfo()}
                  disabled={nameForm.trim().length === 0 || busy}
                  className="bg-[#0D2546] hover:bg-[#163a6b] disabled:bg-gray-300 text-white font-semibold px-5 py-2 rounded-md text-sm transition-colors cursor-pointer disabled:cursor-not-allowed"
                >
                  {busy ? 'Đang xử lý…' : 'Lưu thay đổi'}
                </button>
                <button
                  onClick={() => {
                    setEditingInfo(false);
                    if (group) {
                      setNameForm(group.name);
                      setDescriptionForm(group.description ?? '');
                    }
                  }}
                  disabled={busy}
                  className="bg-gray-200 hover:bg-gray-300 disabled:opacity-60 text-gray-800 font-semibold px-5 py-2 rounded-md text-sm transition-colors cursor-pointer disabled:cursor-not-allowed"
                >
                  Hủy
                </button>
              </div>
            </div>
          ) : (
            <div className="flex items-start justify-between gap-4">
              <div>
                <h2 className="text-2xl font-bold text-[#0D2546]">{group?.name}</h2>
                <p className="text-sm text-gray-500 mt-1">{group?.description || 'Chưa có mô tả.'}</p>
              </div>
              <div className="flex gap-2 shrink-0">
                {isOwner && (
                  <button
                    onClick={() => setEditingInfo(true)}
                    className="text-[#0D2546] hover:underline font-semibold text-sm cursor-pointer"
                  >
                    Sửa
                  </button>
                )}
              </div>
            </div>
          )}
        </div>

        <div className="mb-5 bg-white rounded-lg border border-gray-200 p-5">
          <div className="flex items-center justify-between mb-4">
            <h3 className="font-semibold text-[#0D2546]">Thành viên ({members?.length ?? '…'})</h3>
          </div>
          <p className="mb-4 text-xs text-gray-500">
            {isOwner
              ? 'Nhập tên hoặc email vào ô bên dưới để tìm và thêm thành viên vào nhóm.'
              : 'Chỉ chủ nhóm mới có thể thêm hoặc gỡ thành viên.'}
          </p>

          {isOwner && (
            <div className="mb-4 flex items-end gap-2">
              <div className="flex-1">
                <UserPicker session={session} onSelect={(entry) => void addMember(entry)} excludeSubs={memberSubs} />
              </div>
              <select
                value={addRole}
                onChange={(e) => setAddRole(e.target.value as GroupMemberRole)}
                className="border border-gray-300 rounded-md px-2 py-2 text-sm text-gray-800"
              >
                <option value="member">Thành viên</option>
                <option value="owner">Chủ nhóm</option>
              </select>
            </div>
          )}

          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-xs uppercase tracking-wide text-gray-500 border-b border-gray-200">
                <th className="py-2 pr-4">Người dùng</th>
                <th className="py-2 pr-4">Vai trò</th>
                <th className="py-2 text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {members === null ? (
                <tr>
                  <td colSpan={3} className="py-4 text-center text-gray-400">Đang tải…</td>
                </tr>
              ) : (
                members.map((m) => {
                  const self = m.userSub === session.sub;
                  const canDemoteOrRemove = isOwner && !(m.roleInGroup === 'owner' && ownerCount <= 1);
                  const primaryName = m.displayName || m.username || m.userSub;
                  const secondaryUsername = m.displayName && m.username ? m.username : null;
                  return (
                    <tr key={m.userSub} className="border-b border-gray-100 last:border-0">
                      <td className="py-2 pr-4 text-gray-800">
                        <span className="font-semibold">{primaryName}</span>
                        {secondaryUsername && (
                          <span className="ml-1.5 text-xs text-gray-400">· {secondaryUsername}</span>
                        )}
                        {self && (
                          <span className="ml-2 text-[10px] uppercase tracking-wide bg-blue-50 text-blue-700 px-1.5 py-0.5 rounded">
                            Bạn
                          </span>
                        )}
                      </td>
                      <td className="py-2 pr-4">
                        <RoleBadge role={m.roleInGroup} />
                      </td>
                      <td className="py-2 text-right whitespace-nowrap">
                        <div className="inline-flex gap-3">
                          {isOwner && m.roleInGroup === 'member' && (
                            <button
                              onClick={() => void changeRole(m.userSub, 'owner')}
                              disabled={busy}
                              className="text-[#0D2546] hover:underline font-semibold disabled:opacity-60 disabled:no-underline cursor-pointer disabled:cursor-not-allowed"
                            >
                              Đặt làm chủ nhóm
                            </button>
                          )}
                          {isOwner && m.roleInGroup === 'owner' && ownerCount > 1 && (
                            <button
                              onClick={() => void changeRole(m.userSub, 'member')}
                              disabled={busy}
                              className="text-[#0D2546] hover:underline font-semibold disabled:opacity-60 disabled:no-underline cursor-pointer disabled:cursor-not-allowed"
                            >
                              Hạ xuống thành viên
                            </button>
                          )}
                          {canDemoteOrRemove && (
                            <button
                              onClick={() => void removeMember(m.userSub)}
                              disabled={busy}
                              className="text-[#D11A2A] hover:underline font-semibold disabled:opacity-60 disabled:no-underline cursor-pointer disabled:cursor-not-allowed"
                            >
                              Gỡ
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>

        {/* group-management-sharing-02-document-sharing.md §4.6: tab "Tài liệu của nhóm" */}
        <div className="mb-5 bg-white rounded-lg border border-gray-200 p-5">
          <div className="flex items-center justify-between mb-4">
            <h3 className="font-semibold text-[#0D2546]">
              Tài liệu của nhóm ({documentsPage?.totalElements ?? '…'})
            </h3>
          </div>
          <p className="mb-4 text-xs text-gray-500">
            Tìm và chọn tài liệu của bạn trong ô bên dưới để chia sẻ vào nhóm.
          </p>

          <div className="mb-4">
            <DocumentPicker
              session={session}
              onSelect={(doc) => void addDocument(doc)}
              excludeIds={documentsPage?.content.map((d) => d.documentId) ?? []}
            />
          </div>

          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-xs uppercase tracking-wide text-gray-500 border-b border-gray-200">
                <th className="py-2 pr-4">Tên tài liệu</th>
                <th className="py-2 pr-4">Người nạp</th>
                <th className="py-2 pr-4">Người chia sẻ</th>
                <th className="py-2 pr-4">Thời gian chia sẻ</th>
                <th className="py-2 text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {documentsPage === null ? (
                <tr>
                  <td colSpan={5} className="py-4 text-center text-gray-400">Đang tải…</td>
                </tr>
              ) : documentsPage.content.length === 0 ? (
                <tr>
                  <td colSpan={5} className="py-4 text-center text-gray-400">Chưa có tài liệu nào được chia sẻ vào nhóm.</td>
                </tr>
              ) : (
                documentsPage.content.map((d) => (
                  <tr key={d.documentId} className="border-b border-gray-100 last:border-0">
                    <td className="py-2 pr-4">
                      <Link href={`/documents/${d.documentId}`} className="text-[#0D2546] font-semibold hover:underline">
                        {d.title}
                      </Link>
                    </td>
                    <td className="py-2 pr-4 text-gray-700">
                      {d.uploaderDisplayName || d.uploaderUsername || d.uploaderSub}
                    </td>
                    <td className="py-2 pr-4 text-gray-700">
                      {d.sharedByDisplayName || d.sharedByUsername || d.sharedBySub}
                    </td>
                    <td className="py-2 pr-4 text-gray-500">{new Date(d.sharedAt).toLocaleString('vi-VN')}</td>
                    <td className="py-2 text-right">
                      {(isOwner || d.sharedBySub === session.sub || d.uploaderSub === session.sub) && (
                        <button
                          onClick={() => void unshareDocument(d.documentId, d.title)}
                          disabled={busy}
                          className="text-[#D11A2A] hover:underline font-semibold cursor-pointer disabled:opacity-60 disabled:cursor-not-allowed"
                        >
                          Gỡ
                        </button>
                      )}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
          {documentsPage && documentsPage.totalPages > 1 && (
            <div className="flex items-center justify-between mt-3 text-sm">
              <button
                onClick={() => setDocPageIndex((p) => Math.max(0, p - 1))}
                disabled={docPageIndex === 0}
                className="text-[#0D2546] disabled:text-gray-300 font-semibold hover:underline cursor-pointer disabled:cursor-not-allowed"
              >
                ← Trang trước
              </button>
              <span className="text-gray-400">
                Trang {documentsPage.page + 1}/{documentsPage.totalPages}
              </span>
              <button
                onClick={() => setDocPageIndex((p) => p + 1)}
                disabled={docPageIndex + 1 >= documentsPage.totalPages}
                className="text-[#0D2546] disabled:text-gray-300 font-semibold hover:underline cursor-pointer disabled:cursor-not-allowed"
              >
                Trang sau →
              </button>
            </div>
          )}
        </div>

        <div className="bg-white rounded-lg border border-gray-200 p-5 flex gap-3">
          {!(isOwner && ownerCount <= 1) && (
            <button
              onClick={() => void leaveGroup()}
              disabled={busy}
              className="bg-gray-200 hover:bg-gray-300 disabled:opacity-60 text-gray-800 font-semibold px-5 py-2 rounded-md text-sm transition-colors cursor-pointer disabled:cursor-not-allowed"
            >
              {busy ? 'Đang xử lý…' : 'Rời nhóm'}
            </button>
          )}
          {isOwner && (
            <button
              onClick={() => void deleteGroup()}
              disabled={busy}
              className="bg-[#D11A2A] hover:bg-red-700 disabled:opacity-60 text-white font-semibold px-5 py-2 rounded-md text-sm transition-colors cursor-pointer disabled:cursor-not-allowed"
            >
              {busy ? 'Đang xử lý…' : 'Xoá nhóm'}
            </button>
          )}
        </div>
        {isOwner && ownerCount <= 1 && (
          <p className="text-xs text-gray-400 mt-2">
            Bạn là chủ nhóm duy nhất - không thể rời nhóm cho đến khi đặt thêm
            một chủ nhóm khác, hoặc xoá hẳn nhóm.
          </p>
        )}
      </div>
    </AppShell>
  );
}
