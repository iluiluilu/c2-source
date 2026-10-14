'use client';

import { useSession } from 'next-auth/react';
import { useCallback, useEffect, useState } from 'react';
import { FolderTree } from '@/components/FolderTree';
import { ApiError, apiFetch, apiJson } from '@/lib/api';
import type { FolderNode, WarehouseCapacity } from '@/lib/api';
import { AccessDenied } from '@/components/AccessDenied';
import { AdminTabs } from '@/components/AdminTabs';
import { AppShell } from '@/components/AppShell';
import { canSeeFeature } from '@/lib/features';
import { toast } from '@/lib/toast';

function formatSize(bytes: number): string {
  if (bytes >= 1_073_741_824) return `${(bytes / 1_073_741_824).toFixed(1)} GB`;
  if (bytes >= 1_048_576) return `${(bytes / 1_048_576).toFixed(1)} MB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${bytes} B`;
}

/** Flattened (id, indented label) list for the "move to" destination picker; excludes `excludeId`'s own subtree. */
function flattenFolders(
  nodes: FolderNode[],
  excludeId: string | null,
  depth = 0,
): { id: string; label: string }[] {
  return nodes.flatMap((n) => {
    if (n.id === excludeId) return [];
    return [
      { id: n.id, label: `${'— '.repeat(depth)}${n.name}` },
      ...flattenFolders(n.children, excludeId, depth + 1),
    ];
  });
}

/** The node and its parent id (null = root level) for `id`, or null. */
function locate(
  nodes: FolderNode[],
  id: string | null,
  parentId: string | null = null,
): { node: FolderNode; parentId: string | null } | null {
  if (!id) return null;
  for (const n of nodes) {
    if (n.id === id) return { node: n, parentId };
    const found = locate(n.children, id, n.id);
    if (found) return found;
  }
  return null;
}

const CAPACITY_STATUS_STYLE: Record<string, string> = {
  OK: 'bg-green-50 border-green-200 text-green-800',
  WARNING: 'bg-yellow-50 border-yellow-200 text-yellow-800',
  BLOCKED: 'bg-red-50 border-red-200 text-red-800',
};

/**
 * FR-13 (slice): R3 manages the warehouse folder STRUCTURE that FR-11
 * uploads target. R3 never sees document content (NFR-07) - the backend has
 * no quan-tri role on any /documents route.
 *
 * warehouse-structure.md Story 2/5: adds branch move (separate
 * PATCH /folders/{id}/move endpoint from rename) and the warehouse-wide
 * capacity display/edit (GET/PATCH /warehouse/capacity).
 */
export default function FolderAdminPage() {
  const { data: session, status } = useSession();
  const [tree, setTree] = useState<FolderNode[] | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [newName, setNewName] = useState('');
  // Separate from newName: renaming edits the selected folder's current name.
  const [renameValue, setRenameValue] = useState('');
  const [asChild, setAsChild] = useState(false);
  const [moveTargetId, setMoveTargetId] = useState<string>(''); // '' = move to root
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [capacity, setCapacity] = useState<WarehouseCapacity | null>(null);
  const [capacityGb, setCapacityGb] = useState('');
  const [capacityMessage, setCapacityMessage] = useState<string | null>(null);
  const [capacityError, setCapacityError] = useState<string | null>(null);
  const [savingCapacity, setSavingCapacity] = useState(false);

  const isAdmin = canSeeFeature(session, 'admin-folders');

  const load = useCallback(() => {
    apiJson<FolderNode[]>('/folders/tree', session)
      .then(setTree)
      .catch((e: Error) => setError(e.message));
  }, [session]);

  const loadCapacity = useCallback(() => {
    apiJson<WarehouseCapacity>('/warehouse/capacity', session)
      .then((c) => {
        setCapacity(c);
        setCapacityGb(String(Math.round(c.maxCapacityBytes / 1_073_741_824)));
      })
      .catch((e: Error) => setCapacityError(e.message));
  }, [session]);

  useEffect(() => {
    if (isAdmin) {
      load();
      loadCapacity();
    }
  }, [isAdmin, load, loadCapacity]);

  if (status === 'loading') return null;
  if (!session || !isAdmin) {
    return <AccessDenied featureKey="admin-folders" />;
  }

  const run = async (action: () => Promise<void>, successMessage: string) => {
    setError(null);
    setBusy(true);
    try {
      await action();
      toast.success(successMessage);
      load();
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        const code = (e.body as { code?: string } | undefined)?.code;
        const hint =
          code === 'MAX_DEPTH_EXCEEDED'
            ? 'vượt quá độ sâu tối đa 5 cấp'
            : code === 'CYCLE_DETECTED'
              ? 'không thể chuyển vào chính nó hoặc thư mục con của nó'
              : code === 'FOLDER_NOT_EMPTY'
                ? 'thư mục còn dữ liệu hoặc thư mục con'
                : 'trùng tên ở cùng cấp';
        setError(`Không thể thực hiện: ${e.message} (${hint})`);
      } else {
        setError((e as Error).message);
      }
    } finally {
      setBusy(false);
    }
  };

  const createFolder = () =>
    run(async () => {
      const res = await apiFetch('/folders', session, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: newName,
          ...(asChild && selectedId ? { parentId: selectedId } : {}),
        }),
      });
      // Select the new folder so the next action (rename, add a child) applies to it.
      const created = (await res.json().catch(() => null)) as { id?: string; name?: string } | null;
      if (created?.id) {
        setSelectedId(created.id);
        setRenameValue(created.name ?? newName);
      }
      setNewName('');
    }, 'Đã tạo thư mục.');

  const renameFolder = () => {
    if (!selectedId) return;
    void run(async () => {
      await apiFetch(`/folders/${selectedId}`, session, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: renameValue }),
      });
    }, 'Đã đổi tên thư mục.');
  };

  const moveFolder = () => {
    if (!selectedId) return;
    void run(async () => {
      await apiFetch(`/folders/${selectedId}/move`, session, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ newParentId: moveTargetId || null }),
      });
    }, 'Đã chuyển thư mục.');
  };

  const deleteFolder = () => {
    if (!selectedId) return;
    void run(async () => {
      await apiFetch(`/folders/${selectedId}`, session, { method: 'DELETE' });
      setSelectedId(null);
    }, 'Đã xóa thư mục.');
  };

  const saveCapacity = async () => {
    const gb = Number(capacityGb);
    setCapacityError(null);
    setCapacityMessage(null);
    if (!Number.isFinite(gb) || gb <= 0) {
      setCapacityError('Dung lượng phải là số lớn hơn 0 (GB).');
      return;
    }
    setSavingCapacity(true);
    try {
      const updated = await apiFetch('/warehouse/capacity', session, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ maxCapacityBytes: Math.round(gb * 1_073_741_824) }),
      }).then((res) => res.json() as Promise<WarehouseCapacity>);
      setCapacity(updated);
      setCapacityMessage('Đã cập nhật dung lượng tối đa của kho.');
      setCapacityGb(String(Math.round(updated.maxCapacityBytes / 1_073_741_824)));
    } catch (e) {
      setCapacityError((e as Error).message);
    } finally {
      setSavingCapacity(false);
    }
  };

  const folderOptions = flattenFolders(tree ?? [], selectedId);
  const selected = locate(tree ?? [], selectedId);
  const moveIsNoop = selected !== null && (moveTargetId || null) === selected.parentId;
  const currentMaxGb = capacity ? String(Math.round(capacity.maxCapacityBytes / 1_073_741_824)) : '';
  const selectFolder = (id: string | null) => {
    setSelectedId(id);
    setRenameValue(locate(tree ?? [], id)?.node.name ?? '');
    setMoveTargetId('');
  };

  return (
    <AppShell title="Quản trị hệ thống">
      <div className="max-w-7xl">
        <div className="mb-5">
          <h2 className="text-2xl font-bold text-[#0D2546]">Cấu trúc kho</h2>
          <p className="text-sm text-gray-500 mt-1">
            Quản lý thư mục và dung lượng kho — quản trị không xem nội dung tài liệu.
          </p>
        </div>

        <AdminTabs active="folders" />

        {/* Story 5: warehouse-wide storage capacity */}
        <div className="bg-white rounded-lg border border-gray-200 p-6 mb-5">
          <h3 className="font-semibold text-[#0D2546] mb-4">
            Dung lượng kho
          </h3>
          {capacity === null ? (
            <p className="text-sm text-gray-400">Đang tải…</p>
          ) : (
            <div className="grid md:grid-cols-2 gap-6">
              <div>
                <div className="flex justify-between text-sm text-gray-600 mb-1">
                  <span>
                    Đã dùng: {formatSize(capacity.usedBytes)} /{' '}
                    {formatSize(capacity.maxCapacityBytes)}
                  </span>
                  <span>{capacity.usedPercent.toFixed(1)}%</span>
                </div>
                <div className="w-full h-2 rounded-full bg-gray-100 overflow-hidden">
                  <div
                    className={`h-full ${
                      capacity.status === 'BLOCKED'
                        ? 'bg-red-500'
                        : capacity.status === 'WARNING'
                          ? 'bg-yellow-500'
                          : 'bg-green-500'
                    }`}
                    style={{ width: `${Math.min(100, capacity.usedPercent)}%` }}
                  />
                </div>
                <div
                  className={`mt-3 inline-block rounded-md border px-2 py-1 text-xs font-semibold ${CAPACITY_STATUS_STYLE[capacity.status]}`}
                >
                  {capacity.status === 'BLOCKED'
                    ? 'Đã chặn nạp dữ liệu'
                    : capacity.status === 'WARNING'
                      ? 'Cảnh báo gần đầy'
                      : 'Bình thường'}{' '}
                  (ngưỡng cảnh báo {capacity.warningThresholdPercent}% / chặn{' '}
                  {capacity.blockThresholdPercent}%)
                </div>

                {/* Story 5 AC-1/AC-2: tỷ trọng thùng rác trong dung lượng đã dùng. */}
                <p className="mt-3 text-sm text-gray-600">
                  Trong đó, thùng rác: {formatSize(capacity.trashBytes)}
                </p>
                {capacity.usedBytes > 0 && capacity.trashBytes / capacity.usedBytes >= 0.2 && (
                  <div className="mt-2 bg-yellow-50 border border-yellow-200 rounded-md px-2 py-1.5 text-xs text-yellow-800">
                    Phần lớn dung lượng đang dùng là thùng rác, sẽ tự động giải
                    phóng theo lịch xóa cứng.
                  </div>
                )}
              </div>

              <div className="space-y-2">
                <h4 className="text-xs font-semibold uppercase tracking-wide text-gray-500 mb-1">
                  Đặt dung lượng tối đa (GB)
                </h4>
                <div className="flex gap-2">
                  <input
                    value={capacityGb}
                    onChange={(e) => setCapacityGb(e.target.value)}
                    placeholder={`VD: ${(capacity.maxCapacityBytes / 1_073_741_824).toFixed(0)}`}
                    type="number"
                    min={1}
                    className="flex-1 border border-gray-300 rounded-md px-3 py-2 text-sm text-gray-800"
                  />
                  <button
                    onClick={() => void saveCapacity()}
                    disabled={!capacityGb.trim() || capacityGb.trim() === currentMaxGb || savingCapacity}
                    className="bg-[#0D2546] hover:bg-[#163a6b] disabled:bg-gray-300 text-white font-semibold py-2 px-4 rounded-md text-sm transition-colors cursor-pointer disabled:cursor-not-allowed"
                  >
                    {savingCapacity ? 'Đang lưu…' : 'Lưu'}
                  </button>
                </div>
                {capacityMessage && (
                  <div className="bg-green-50 border border-green-200 rounded-md p-2 text-xs text-green-800">
                    {capacityMessage}
                  </div>
                )}
                {capacityError && (
                  <div className="bg-red-50 border border-red-200 rounded-md p-2 text-xs text-red-800">
                    {capacityError}
                  </div>
                )}
              </div>
            </div>
          )}
        </div>

        <div className="bg-white rounded-lg border border-gray-200 p-6">
          <h3 className="font-semibold text-[#0D2546] mb-4">
            Cây thư mục
          </h3>
          <div className="grid md:grid-cols-2 gap-6 items-start">
            <section>
              <h4 className="text-xs font-semibold uppercase tracking-wide text-gray-500 mb-2">
                Cây thư mục
              </h4>
              <div className="border border-gray-200 rounded-md p-3 max-h-96 overflow-y-auto">
                {tree === null ? (
                  <p className="text-sm text-gray-400">Đang tải…</p>
                ) : (
                  <FolderTree
                    nodes={tree}
                    selectedId={selectedId}
                    onSelect={(id) => selectFolder(selectedId === id ? null : id)}
                  />
                )}
              </div>
              <p className="text-xs text-gray-400 mt-2">
                Nhấn để chọn / bỏ chọn một thư mục. Tối đa 5 cấp.
              </p>
            </section>

            <section className="space-y-5">
              <div className="rounded-md border border-gray-200 p-4">
                <h4 className="text-xs font-semibold uppercase tracking-wide text-gray-500 mb-2">
                  Thêm thư mục mới
                </h4>
                <input
                  value={newName}
                  onChange={(e) => setNewName(e.target.value)}
                  placeholder="VD: Chuyen an A"
                  aria-label="Tên thư mục mới"
                  className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm text-gray-800"
                />
                <label className="flex items-center gap-2 mt-2 text-sm text-gray-600">
                  <input
                    type="checkbox"
                    checked={asChild}
                    onChange={(e) => setAsChild(e.target.checked)}
                    disabled={!selectedId}
                  />
                  {selected ? `Tạo bên trong "${selected.node.name}"` : 'Tạo bên trong thư mục đang chọn'}
                </label>
                <button
                  onClick={() => void createFolder()}
                  disabled={!newName.trim() || busy}
                  className="mt-3 w-full bg-[#0D2546] hover:bg-[#163a6b] disabled:bg-gray-300 text-white font-semibold py-2 rounded-md text-sm transition-colors cursor-pointer disabled:cursor-not-allowed"
                >
                  {busy ? 'Đang xử lý…' : 'Tạo thư mục'}
                </button>
              </div>

              <div className="rounded-md border border-gray-200 p-4">
                <h4 className="text-xs font-semibold uppercase tracking-wide text-gray-500 mb-2">
                  Thư mục đang chọn
                </h4>
                {!selected ? (
                  <p className="text-sm text-gray-400">Chọn một thư mục trên cây để đổi tên, di chuyển hoặc xóa.</p>
                ) : (
                  <div className="space-y-3">
                    <div className="flex gap-2">
                      <input
                        value={renameValue}
                        onChange={(e) => setRenameValue(e.target.value)}
                        aria-label="Tên mới của thư mục đang chọn"
                        className="flex-1 border border-gray-300 rounded-md px-3 py-2 text-sm text-gray-800"
                      />
                      <button
                        onClick={renameFolder}
                        disabled={!renameValue.trim() || renameValue.trim() === selected.node.name || busy}
                        className="bg-gray-200 hover:bg-gray-300 disabled:bg-gray-100 disabled:text-gray-400 text-gray-800 font-semibold px-3 py-2 rounded-md text-sm transition-colors cursor-pointer disabled:cursor-not-allowed"
                      >
                        Đổi tên thư mục đang chọn
                      </button>
                    </div>
                    <div className="flex gap-2">
                      <select
                        value={moveTargetId}
                        onChange={(e) => setMoveTargetId(e.target.value)}
                        aria-label="Chuyển thư mục đang chọn đến"
                        className="flex-1 border border-gray-300 rounded-md px-3 py-2 text-sm text-gray-800"
                      >
                        <option value="">(Gốc kho)</option>
                        {folderOptions.map((f) => (
                          <option key={f.id} value={f.id}>
                            {f.label}
                          </option>
                        ))}
                      </select>
                      <button
                        onClick={moveFolder}
                        disabled={moveIsNoop || busy}
                        title={moveIsNoop ? 'Thư mục đã ở vị trí này' : undefined}
                        className="bg-gray-200 hover:bg-gray-300 disabled:bg-gray-100 disabled:text-gray-400 text-gray-800 font-semibold px-3 py-2 rounded-md text-sm transition-colors cursor-pointer disabled:cursor-not-allowed"
                      >
                        Di chuyển
                      </button>
                    </div>
                    <button
                      onClick={deleteFolder}
                      disabled={busy}
                      className="w-full border border-red-200 text-[#D11A2A] hover:bg-red-50 disabled:opacity-60 font-semibold py-2 rounded-md text-sm transition-colors cursor-pointer disabled:cursor-not-allowed"
                    >
                      Xóa thư mục đang chọn
                    </button>
                  </div>
                )}
              </div>

              {error && (
                <div className="bg-red-50 border border-red-200 rounded-md p-3 text-sm text-red-800">
                  {error}
                </div>
              )}
            </section>
          </div>
        </div>
      </div>
    </AppShell>
  );
}
