'use client';

import { nameTooltip } from '@/lib/nameTooltip';
import Link from 'next/link';
import { useSession } from 'next-auth/react';
import { useCallback, useEffect, useState } from 'react';
import { TAG_KIND_LABELS, apiFetch, apiJson } from '@/lib/api';
import type {
  BulkShareResponse,
  DocumentRow,
  FolderNode,
  GroupRow,
  TagKind,
  TagRow,
  TrashPreviewItem,
} from '@/lib/api';
import { AccessDenied } from '@/components/AccessDenied';
import { AppShell } from '@/components/AppShell';
import { FolderTree } from '@/components/FolderTree';
import { useConfirm } from '@/components/ConfirmDialog';
import { canSeeFeature } from '@/lib/features';
import { usePermissions } from '@/lib/permissions';
import { downloadDocument } from '@/lib/download';
import { folderPathLabel } from '@/lib/folderPath';
import { TRASH_NOTE } from '@/lib/trashNote';
import { toast } from '@/lib/toast';

const MAX_BULK_SHARE = 100;

const STATUS_LABELS: Record<DocumentRow['status'], string> = {
  pending: 'Đang xử lý',
  clean: 'Sạch',
  infected: 'Nhiễm mã độc',
  rejected: 'Bị từ chối',
};

/** Rows per request to GET /documents (API max 200). */
const PAGE_SIZE = 50;

type SortKey = 'name' | 'size' | 'createdAt';

function ariaSort(sort: { key: SortKey; dir: 'asc' | 'desc' }, key: SortKey) {
  if (sort.key !== key) return 'none' as const;
  return sort.dir === 'asc' ? ('ascending' as const) : ('descending' as const);
}

function sortRows(rows: DocumentRow[], key: SortKey, dir: 'asc' | 'desc'): DocumentRow[] {
  const sign = dir === 'asc' ? 1 : -1;
  return [...rows].sort((a, b) => {
    let cmp: number;
    if (key === 'name') cmp = a.originalName.localeCompare(b.originalName, 'vi');
    else if (key === 'size') cmp = Number(a.size) - Number(b.size);
    else cmp = a.createdAt.localeCompare(b.createdAt);
    return cmp * sign;
  });
}

function flattenFolders(
  nodes: FolderNode[],
  depth = 0,
): { id: string; label: string }[] {
  return nodes.flatMap((n) => [
    { id: n.id, label: `${' '.repeat(depth * 3)}${n.name}` },
    ...flattenFolders(n.children, depth + 1),
  ]);
}

function formatSize(size: string): string {
  const bytes = Number(size);
  if (bytes >= 1_073_741_824) return `${(bytes / 1_073_741_824).toFixed(1)} GB`;
  if (bytes >= 1_048_576) return `${(bytes / 1_048_576).toFixed(1)} MB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${bytes} B`;
}

/** Document listing for R1/R2 (Cán bộ, Lãnh đạo). */
export default function DocumentsPage() {
  const { data: session, status } = useSession();
  const confirm = useConfirm();
  const [rows, setRows] = useState<DocumentRow[] | null>(null);
  const [folders, setFolders] = useState<{ id: string; label: string }[]>([]);
  const [folderTree, setFolderTree] = useState<FolderNode[]>([]);
  // Client-side refinements of the loaded rows (the API returns the whole folder).
  const [nameFilter, setNameFilter] = useState('');
  // Debounced copy of nameFilter that actually goes to the API (`q`).
  const [nameQuery, setNameQuery] = useState('');
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [sort, setSort] = useState<{ key: SortKey; dir: 'asc' | 'desc' }>({ key: 'createdAt', dir: 'desc' });
  // `?folder=<id>` (links from Nạp dữ liệu / breadcrumbs) preselects a folder.
  const [folderFilter, setFolderFilter] = useState(() =>
    typeof window === 'undefined' ? '' : (new URLSearchParams(window.location.search).get('folder') ?? ''),
  );
  const [tags, setTags] = useState<TagRow[]>([]);
  const [tagFilter, setTagFilter] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [myGroups, setMyGroups] = useState<GroupRow[]>([]);
  const [shareGroupId, setShareGroupId] = useState('');

  const canView = canSeeFeature(session, 'documents');
  // Các nút gate theo permission trùng @PreAuthorize của BE (r2-business-parity-03).
  const perms = usePermissions();
  const canDelete = perms.can('trash.manage');
  // group-management-sharing-02-document-sharing.md Story 6 AC-2: chia sẻ
  // hàng loạt khác quyền xóa (`canDelete`): gate bằng `document.share`.
  const canBulkShare = perms.can('document.share');
  const canSelect = canDelete || canBulkShare;
  const canUpload = perms.can('document.upload');

  // The API pages (default 50): fetch PAGE_SIZE at a time, "Tải thêm" appends.
  const fetchPage = useCallback(
    (offset: number) => {
      const params = new URLSearchParams();
      if (folderFilter) params.set('folderId', folderFilter);
      if (tagFilter) params.set('tagId', tagFilter);
      if (nameQuery) params.set('q', nameQuery);
      params.set('limit', String(PAGE_SIZE));
      if (offset > 0) params.set('offset', String(offset));
      return apiJson<DocumentRow[]>(`/documents?${params.toString()}`, session);
    },
    [session, folderFilter, tagFilter, nameQuery],
  );

  const load = useCallback(() => {
    fetchPage(0)
      .then((page) => {
        setRows(page);
        setHasMore(page.length === PAGE_SIZE);
      })
      .catch((e: Error) => setError(e.message));
  }, [fetchPage]);

  const loadMore = () => {
    setLoadingMore(true);
    fetchPage(rows?.length ?? 0)
      .then((page) => {
        setRows((prev) => [...(prev ?? []), ...page.filter((d) => !prev?.some((p) => p.id === d.id))]);
        setHasMore(page.length === PAGE_SIZE);
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoadingMore(false));
  };

  useEffect(() => {
    const t = setTimeout(() => setNameQuery(nameFilter.trim()), 300);
    return () => clearTimeout(t);
  }, [nameFilter]);

  useEffect(() => {
    if (!canView) return;
    load();
    apiJson<FolderNode[]>('/folders/tree', session)
      .then((tree) => {
        setFolderTree(tree);
        setFolders(flattenFolders(tree));
      })
      .catch(() => setFolders([]));
    apiJson<TagRow[]>('/tags', session)
      .then(setTags)
      .catch(() => setTags([]));
    apiJson<GroupRow[]>('/groups', session)
      .then(setMyGroups)
      .catch(() => setMyGroups([]));
  }, [session, canView, load]);

  if (status === 'loading') return null;
  if (!session || !canView) {
    return <AccessDenied featureKey="documents" />;
  }

  const download = async (doc: DocumentRow) => {
    try {
      await downloadDocument(session, doc.id, doc.originalName);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const toggleSelect = (id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  };

  // delete-restore-trash-01-xoa-mem-thung-rac.md Story 2: xác nhận + xóa mềm.
  // Định nghĩa cục bộ ở mỗi trang cần dùng (không tách hook dùng chung), theo
  // đúng mức độ hiện có của repo — xem tech spec §4.4.
  const confirmAndTrash = async (documentIds: string[], names: string[]) => {
    let totalGroups: number | null = null;
    try {
      const preview = await apiJson<TrashPreviewItem[]>('/documents/trash-preview', session, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ documentIds }),
      });
      totalGroups = preview.reduce((s, p) => s + p.activeGroupShareCount, 0);
    } catch {
      totalGroups = null; // AC-7: preview lỗi/chậm không chặn xóa
    }

    const nameLines =
      names.length > 10 ? [...names.slice(0, 10), `và ${names.length - 10} tài liệu khác`] : names;
    let message = nameLines.join('\n');
    if (totalGroups === null) {
      message += '\n\nKhông tải được số nhóm bị ảnh hưởng.';
    } else if (totalGroups > 0) {
      message += `\n\n${totalGroups} nhóm sẽ mất quyền xem (các) tài liệu này.`;
    }
    // totalGroups === 0: không thêm dòng nào (Quyết định 1)
    message += `\n\n${TRASH_NOTE}`;

    const ok = await confirm({
      title: 'Xóa tài liệu',
      message,
      tone: 'danger',
      confirmLabel: 'Xóa',
    });
    if (!ok) return;

    setError(null);
    try {
      await apiFetch('/documents/trash', session, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ documentIds }),
      });
      toast.success(`Đã chuyển ${documentIds.length} tài liệu vào thùng rác.`, {
        label: 'Xem thùng rác',
        href: '/trash',
      });
      // AC-5: loại các id đã xóa khỏi state rows ngay, không reload trang
      setRows((prev) => prev?.filter((r) => !documentIds.includes(r.id)) ?? prev);
      setSelectedIds((prev) => {
        const next = new Set(prev);
        documentIds.forEach((id) => next.delete(id));
        return next;
      });
    } catch (e) {
      setError((e as Error).message); // AC-6: dialog đã đóng nhưng thao tác không thành công
    }
  };

  // group-management-sharing-02-document-sharing.md §4.7 (Story 6 BA): chia
  // sẻ hàng loạt từ trang danh sách, tái dùng POST /documents/shares (§3.1).
  const shareSelectedToGroup = async (documentIds: string[], group: GroupRow) => {
    const ok = await confirm({
      title: 'Chia sẻ vào nhóm',
      message: `Xác nhận chia sẻ ${documentIds.length} tài liệu vào nhóm "${group.name}"?`,
    });
    if (!ok) return;

    setError(null);
    try {
      const { results } = await apiJson<BulkShareResponse>('/documents/shares', session, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ groupId: group.id, documentIds }),
      });
      const shared = results.filter((r) => r.status === 'shared').length;
      const alreadyShared = results.filter((r) => r.status === 'already_shared').length;
      const rejected = results.filter((r) => r.status === 'rejected');
      const notClean = rejected.filter((r) => r.reason === 'not_clean').length;
      const otherRejected = rejected.length - notClean;

      const lines = [`Đã chia sẻ ${shared} tài liệu vào nhóm "${group.name}".`];
      if (alreadyShared > 0) {
        lines.push(`${alreadyShared} tài liệu đã được chia sẻ từ trước.`);
      }
      if (notClean > 0) {
        lines.push(`${notClean} tài liệu bị từ chối/nhiễm mã độc nên không thể chia sẻ.`);
      }
      if (otherRejected > 0) {
        lines.push(`${otherRejected} tài liệu bị từ chối (không nhìn thấy được hoặc vượt giới hạn 50 nhóm/tài liệu).`);
      }
      // Partial rejections are worth reading - shown as info, which the user dismisses.
      if (lines.length > 1) toast.info(lines.join(' '));
      else toast.success(lines[0]);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      // AC-8: reset lựa chọn sau khi xử lý xong, kể cả khi có item rejected.
      setSelectedIds(new Set());
      setShareGroupId('');
    }
  };

  const needle = nameFilter.trim().toLocaleLowerCase('vi');
  // Sorting is over the rows loaded so far ("Tải thêm" for the rest).
  const visibleRows = rows === null ? null : sortRows(rows, sort.key, sort.dir);
  const allVisibleSelected =
    !!visibleRows && visibleRows.length > 0 && visibleRows.every((r) => selectedIds.has(r.id));
  const toggleSelectAll = () =>
    setSelectedIds((prev) => {
      const next = new Set(prev);
      for (const r of visibleRows ?? []) {
        if (allVisibleSelected) next.delete(r.id);
        else next.add(r.id);
      }
      return next;
    });
  const sortBy = (key: SortKey) =>
    setSort((prev) => ({ key, dir: prev.key === key && prev.dir === 'asc' ? 'desc' : 'asc' }));
  const sortMark = (key: SortKey) => (sort.key === key ? (sort.dir === 'asc' ? ' ▲' : ' ▼') : '');
  const folderPath = folderFilter ? folderPathLabel(folderTree, folderFilter) : null;

  return (
    <AppShell title="Kho tài liệu">
      <div className="max-w-7xl">
        <div className="mb-6">
          <h2 className="text-2xl font-bold text-[#0D2546]">Kho tài liệu</h2>
          <p className="text-sm text-gray-500 mt-1">
            Tài liệu trong kho theo cấu trúc thư mục
          </p>
        </div>


        {/* Floating, out of the page flow: appearing on the first tick must not push the table down. */}
        {canSelect && selectedIds.size > 0 && (
          <div
            role="toolbar"
            aria-label="Thao tác với tài liệu đã chọn"
            className="fixed bottom-5 left-4 right-4 lg:left-[calc(16rem+1.5rem)] lg:right-6 z-30 mx-auto flex max-w-4xl flex-wrap items-center justify-between gap-3 rounded-lg border border-gray-200 bg-white px-4 py-3 shadow-xl"
          >
            <span className="flex items-center gap-3 text-sm text-gray-600">
              {selectedIds.size} tài liệu đã chọn
              <button
                type="button"
                onClick={() => setSelectedIds(new Set())}
                className="cursor-pointer font-semibold text-[#0D2546] hover:underline"
              >
                Bỏ chọn
              </button>
            </span>
            <div className="flex items-center gap-3">
              {canBulkShare && (
                <>
                  <select
                    aria-label="Chọn nhóm để chia sẻ"
                    value={shareGroupId}
                    onChange={(e) => setShareGroupId(e.target.value)}
                    className="border border-gray-300 rounded-md px-2 py-1.5 text-sm text-gray-800"
                  >
                    <option value="">Chọn nhóm…</option>
                    {myGroups.map((g) => (
                      <option key={g.id} value={g.id}>
                        {g.name}
                      </option>
                    ))}
                  </select>
                  <button
                    onClick={() => {
                      const group = myGroups.find((g) => g.id === shareGroupId);
                      if (!group) return;
                      void shareSelectedToGroup([...selectedIds], group);
                    }}
                    disabled={!shareGroupId || selectedIds.size > MAX_BULK_SHARE}
                    title={
                      selectedIds.size > MAX_BULK_SHARE
                        ? `Tối đa ${MAX_BULK_SHARE} tài liệu/lượt`
                        : undefined
                    }
                    className="bg-[#0D2546] hover:bg-[#163b6d] disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer text-white font-semibold px-4 py-1.5 rounded-md text-sm transition-colors"
                  >
                    Chia sẻ vào nhóm
                  </button>
                </>
              )}
              {canDelete && (
                <button
                  onClick={() =>
                    void confirmAndTrash(
                      [...selectedIds],
                      (rows ?? []).filter((r) => selectedIds.has(r.id)).map((r) => r.originalName),
                    )
                  }
                  className="bg-[#D11A2A] hover:bg-red-700 text-white font-semibold px-4 py-1.5 rounded-md text-sm transition-colors cursor-pointer"
                >
                  Xóa đã chọn
                </button>
              )}
            </div>
          </div>
        )}

        {/* Room for the floating bulk bar so it never covers the last rows. */}
        <div className={`grid grid-cols-1 lg:grid-cols-4 gap-5 ${selectedIds.size > 0 ? 'pb-24' : ''}`}>
          {/* Folder structure filter */}
          <aside className="bg-white rounded-lg border border-gray-200 p-4 h-fit">
            <h3 className="font-semibold text-[#0D2546] mb-3 text-sm">
              Cấu trúc kho
            </h3>
            <ul className="space-y-1 text-sm">
              <li>
                <button
                  onClick={() => setFolderFilter('')}
                  className={`w-full text-left px-2 py-1.5 rounded cursor-pointer ${
                    folderFilter === ''
                      ? 'bg-[#0D2546]/5 text-[#0D2546] font-semibold'
                      : 'text-gray-600 hover:bg-gray-50'
                  }`}
                >
                  Tất cả thư mục
                </button>
              </li>
            </ul>
            {/* Same tree component as Nạp dữ liệu / Quản trị. */}
            <div className="mt-1">
              <FolderTree nodes={folderTree} selectedId={folderFilter || null} onSelect={setFolderFilter} />
            </div>

            {/* FR-15: filter by tag */}
            <h3 className="font-semibold text-[#0D2546] mt-5 mb-2 text-sm">
              Lọc theo nhãn
            </h3>
            <select
              value={tagFilter}
              onChange={(e) => setTagFilter(e.target.value)}
              className="w-full border border-gray-300 rounded-md px-2 py-1.5 text-sm text-gray-800"
            >
              <option value="">Tất cả nhãn</option>
              {(Object.keys(TAG_KIND_LABELS) as TagKind[]).map((kind) => {
                const group = tags.filter((t) => t.kind === kind);
                if (group.length === 0) return null;
                return (
                  <optgroup key={kind} label={TAG_KIND_LABELS[kind]}>
                    {group.map((t) => (
                      <option key={t.id} value={t.id}>
                        {t.label}
                      </option>
                    ))}
                  </optgroup>
                );
              })}
            </select>
          </aside>

          {/* Document table */}
          <div className="lg:col-span-3 bg-white rounded-lg border border-gray-200 overflow-hidden">
            {error && (
              <div className="bg-red-50 border-b border-red-200 p-3 text-sm text-red-800">
                {error}
              </div>
            )}
            <div className="flex flex-wrap items-center gap-3 border-b border-gray-200 px-4 py-3">
              <p className="min-w-0 flex-1 truncate text-sm text-gray-600">
                <span className="text-gray-400">Kho</span>
                {folderPath && <span className="font-semibold text-[#0D2546]"> / {folderPath}</span>}
                {visibleRows && (
                  <span className="text-gray-400">
                    {' '}
                    · {visibleRows.length}
                    {hasMore ? '+' : ''} tài liệu
                  </span>
                )}
              </p>
              <input
                type="search"
                value={nameFilter}
                onChange={(e) => setNameFilter(e.target.value)}
                placeholder="Lọc theo tên…"
                aria-label="Lọc theo tên tài liệu"
                className="w-56 rounded-md border border-gray-300 px-3 py-1.5 text-sm text-gray-800"
              />
              {canUpload && folderFilter && (
                <Link
                  href={`/upload?folder=${folderFilter}`}
                  className="rounded-md bg-[#0D2546] px-3 py-1.5 text-sm font-semibold text-white hover:bg-[#163a6b]"
                >
                  Nạp vào thư mục này
                </Link>
              )}
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-xs uppercase tracking-wide text-gray-400 border-b border-gray-200">
                    {canSelect && (
                      <th className="py-3 px-4 font-medium">
                        <input
                          type="checkbox"
                          checked={allVisibleSelected}
                          onChange={toggleSelectAll}
                          disabled={!visibleRows || visibleRows.length === 0}
                          aria-label="Chọn tất cả tài liệu đang hiển thị"
                        />
                      </th>
                    )}
                    <th className="py-3 px-4 font-medium" aria-sort={ariaSort(sort, 'name')}>
                      <button type="button" onClick={() => sortBy('name')} className="cursor-pointer uppercase">
                        Tên tài liệu{sortMark('name')}
                      </button>
                    </th>
                    <th className="py-3 px-4 font-medium">Nhãn</th>
                    <th className="py-3 px-4 font-medium whitespace-nowrap" aria-sort={ariaSort(sort, 'size')}>
                      <button type="button" onClick={() => sortBy('size')} className="cursor-pointer uppercase">
                        Kích thước{sortMark('size')}
                      </button>
                    </th>
                    <th className="py-3 px-4 font-medium whitespace-nowrap">Trạng thái</th>
                    <th className="py-3 px-4 font-medium whitespace-nowrap">Người tải</th>
                    <th className="py-3 px-4 font-medium whitespace-nowrap" aria-sort={ariaSort(sort, 'createdAt')}>
                      <button type="button" onClick={() => sortBy('createdAt')} className="cursor-pointer uppercase">
                        Thời gian{sortMark('createdAt')}
                      </button>
                    </th>
                    <th className="py-3 px-4 font-medium text-right">Thao tác</th>
                  </tr>
                </thead>
                <tbody>
                  {visibleRows === null ? (
                    <tr>
                      <td colSpan={canSelect ? 8 : 7} className="py-8 text-center text-gray-400">
                        Đang tải…
                      </td>
                    </tr>
                  ) : visibleRows.length === 0 ? (
                    <tr>
                      <td colSpan={canSelect ? 8 : 7} className="py-8 text-center text-gray-400">
                        {needle ? `Không có tài liệu nào có tên chứa "${nameFilter.trim()}".` : 'Không có tài liệu nào.'}
                      </td>
                    </tr>
                  ) : (
                    visibleRows.map((doc) => (
                      <tr
                        key={doc.id}
                        className="border-b border-gray-100 text-gray-800 hover:bg-gray-50"
                      >
                        {canSelect && (
                          <td className="py-3 px-4">
                            <input
                              type="checkbox"
                              checked={selectedIds.has(doc.id)}
                              onChange={() => toggleSelect(doc.id)}
                              aria-label={`Chọn ${doc.originalName}`}
                            />
                          </td>
                        )}
                        <td
                          className="py-3 px-4 max-w-xs truncate font-medium"
                          title={nameTooltip({
                            originalName: doc.originalName,
                            displayName: doc.displayName,
                            relativePath: doc.relativePath,
                            folderLabel: folders.find((f) => f.id === doc.folderId)?.label.replace(/^(— )+/, ''),
                          })}
                        >
                          <Link
                            href={`/documents/${doc.id}`}
                            className="text-[#0D2546] hover:text-[#D11A2A]"
                          >
                            {doc.originalName}
                          </Link>
                          {doc.sharedViaGroups && doc.sharedViaGroups.length > 0 && (
                            <span className="block text-xs font-normal text-gray-400 truncate">
                              Chia sẻ qua nhóm: {doc.sharedViaGroups.join(', ')}
                            </span>
                          )}
                        </td>
                        <td className="py-3 px-4">
                          <div className="flex flex-wrap gap-1 max-w-[12rem]">
                            {(doc.tags ?? []).map((t) => (
                              <span
                                key={t.id}
                                title={TAG_KIND_LABELS[t.kind]}
                                className="inline-block text-xs px-1.5 py-0.5 rounded bg-[#0D2546]/5 text-[#0D2546]"
                              >
                                {t.label}
                              </span>
                            ))}
                          </div>
                        </td>
                        <td className="py-3 px-4 text-gray-600 whitespace-nowrap">
                          {formatSize(doc.size)}
                        </td>
                        <td className="py-3 px-4">
                          <span
                            className={`inline-block whitespace-nowrap text-xs font-semibold px-2 py-0.5 rounded ${
                              doc.status === 'clean'
                                ? 'text-green-700 bg-green-50'
                                : 'text-red-700 bg-red-50'
                            }`}
                          >
                            {STATUS_LABELS[doc.status]}
                          </span>
                        </td>
                        <td className="py-3 px-4 text-gray-600">
                          {doc.uploaderUsername}
                        </td>
                        <td className="py-3 px-4 text-gray-600 whitespace-nowrap">
                          {new Date(doc.createdAt).toLocaleString('vi-VN')}
                        </td>
                        <td className="py-3 px-4 text-right whitespace-nowrap">
                          <div className="flex items-center justify-end gap-3">
                            {doc.status === 'clean' && (
                              <button
                                onClick={() => download(doc)}
                                className="text-[#0D2546] hover:text-[#D11A2A] font-semibold cursor-pointer"
                              >
                                Tải xuống
                              </button>
                            )}
                            {canDelete && (
                              <button
                                onClick={() => void confirmAndTrash([doc.id], [doc.originalName])}
                                className="text-[#D11A2A] hover:text-red-800 font-semibold cursor-pointer"
                              >
                                Xóa
                              </button>
                            )}
                          </div>
                        </td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
            {hasMore && (
              <div className="border-t border-gray-100 p-3 text-center">
                <button
                  type="button"
                  onClick={loadMore}
                  disabled={loadingMore}
                  className="cursor-pointer text-sm font-semibold text-[#0D2546] hover:underline disabled:cursor-not-allowed disabled:opacity-60"
                >
                  {loadingMore ? 'Đang tải…' : 'Tải thêm tài liệu'}
                </button>
              </div>
            )}
          </div>
        </div>
      </div>
    </AppShell>
  );
}
