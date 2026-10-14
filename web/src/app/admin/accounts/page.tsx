'use client';

import { useSession } from 'next-auth/react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, apiFetch, apiJson } from '@/lib/api';
import type { AccountRow, AccountStatus } from '@/lib/api';
import { AccessDenied } from '@/components/AccessDenied';
import { AdminTabs } from '@/components/AdminTabs';
import { AppShell } from '@/components/AppShell';
import { PasswordInput } from '@/components/PasswordInput';
import { canSeeFeature } from '@/lib/features';
import { ROLE_LABELS, roleLabel } from '@/lib/roles';
import { toast } from '@/lib/toast';

const ROLE_OPTIONS = ROLE_LABELS;

const inputClass =
  'w-full border border-gray-300 rounded-md px-3 py-2 text-sm text-gray-800';

const STATUS_LABELS: Record<AccountStatus, string> = {
  active: 'Hoạt động',
  locked: 'Đã khóa',
  disabled: 'Đã vô hiệu hóa',
};

const STATUS_BADGE_CLASS: Record<AccountStatus, string> = {
  active: 'bg-green-50 text-green-700',
  locked: 'bg-yellow-50 text-yellow-800',
  disabled: 'bg-red-50 text-red-700',
};

/** account-lifecycle.md §2: storageQuotaBytes is snapshotted/edited in bytes; show as GB for readability. */
function formatGB(bytes: number): string {
  return (bytes / (1024 * 1024 * 1024)).toFixed(1);
}

/**
 * The secondary row actions behind "⋯" - five inline links per row (with
 * the destructive ones side by side) were hard to scan. Closes on outside
 * click, Esc, or after an item is chosen.
 */
function RowMenu({ label, children }: { label: string; children: React.ReactNode }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);
  return (
    <div className="relative inline-block text-left" ref={ref}>
      <button
        type="button"
        aria-label={label}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        className="cursor-pointer rounded px-2 py-0.5 font-bold text-gray-500 hover:bg-gray-100 hover:text-[#0D2546]"
      >
        ⋯
      </button>
      {open && (
        <div
          role="menu"
          onClick={() => setOpen(false)}
          className="absolute right-0 z-20 mt-1 w-44 rounded-md border border-gray-200 bg-white py-1 text-sm shadow-lg"
        >
          {children}
        </div>
      )}
    </div>
  );
}

const menuItemClass =
  'block w-full px-3 py-1.5 text-left hover:bg-gray-50 cursor-pointer disabled:opacity-60 disabled:cursor-not-allowed';

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

function RoleCheckboxes({
  value,
  onChange,
}: {
  value: string[];
  onChange: (roles: string[]) => void;
}) {
  return (
    <div className="flex gap-4">
      {ROLE_OPTIONS.map((r) => (
        <label key={r.slug} className="flex items-center gap-1.5 text-sm text-gray-700">
          <input
            type="checkbox"
            checked={value.includes(r.slug)}
            onChange={(e) =>
              onChange(
                e.target.checked
                  ? [...value, r.slug]
                  : value.filter((s) => s !== r.slug),
              )
            }
          />
          {r.label}
        </label>
      ))}
    </div>
  );
}

/**
 * Every per-row action (Sửa/Đặt lại MK/Hạn mức/Khóa/Vô hiệu hóa) used to open
 * a panel pinned to the top of the page - invisible without scrolling when
 * the trigger button is in a row far down a long table. All of them are
 * centered modals instead, so the form is always in view the moment it
 * opens, regardless of scroll position.
 */
function ModalShell({
  ariaLabel,
  onClose,
  initialFocusRef,
  maxWidthClass = 'max-w-md',
  children,
}: {
  ariaLabel: string;
  onClose: () => void;
  initialFocusRef?: React.RefObject<HTMLElement | null>;
  maxWidthClass?: string;
  children: React.ReactNode;
}) {
  useEffect(() => {
    initialFocusRef?.current?.focus();
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        onClose();
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [onClose, initialFocusRef]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
      onClick={onClose}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label={ariaLabel}
        className={`w-full ${maxWidthClass} rounded-lg bg-white p-6 shadow-2xl`}
        onClick={(e) => e.stopPropagation()}
      >
        {children}
      </div>
    </div>
  );
}

function StatusChangeModal({
  actionLabel,
  tone,
  hint,
  username,
  reason,
  onReasonChange,
  onConfirm,
  onCancel,
  busy,
}: {
  actionLabel: string;
  tone: 'danger' | 'default';
  hint: string;
  username: string | undefined;
  reason: string;
  onReasonChange: (value: string) => void;
  onConfirm: () => void;
  onCancel: () => void;
  busy: boolean;
}) {
  const reasonRef = useRef<HTMLTextAreaElement>(null);
  const confirmClass =
    tone === 'danger'
      ? 'bg-[#D11A2A] hover:bg-[#b01523]'
      : 'bg-[#0D2546] hover:bg-[#163a6b]';

  return (
    <ModalShell
      ariaLabel={`${actionLabel} tài khoản: ${username ?? ''}`}
      onClose={onCancel}
      initialFocusRef={reasonRef}
    >
      <h2 className="text-lg font-bold text-[#0D2546]">
        {actionLabel} tài khoản: {username}
      </h2>
      <p className="text-xs text-gray-500 mt-2 mb-4">{hint}</p>
      <Field label="Lý do (bắt buộc)">
        <textarea
          ref={reasonRef}
          value={reason}
          onChange={(e) => onReasonChange(e.target.value)}
          rows={3}
          className={inputClass}
        />
      </Field>
      <div className="mt-5 flex justify-end gap-3">
        <button
          type="button"
          onClick={onCancel}
          disabled={busy}
          className="rounded-md border border-gray-300 px-4 py-2 text-sm font-semibold text-gray-700 transition-colors hover:bg-gray-50 disabled:opacity-60 cursor-pointer disabled:cursor-not-allowed"
        >
          Hủy
        </button>
        <button
          type="button"
          onClick={onConfirm}
          disabled={reason.trim() === '' || busy}
          className={`rounded-md ${confirmClass} disabled:bg-gray-300 px-4 py-2 text-sm font-semibold text-white transition-colors cursor-pointer disabled:cursor-not-allowed`}
        >
          {busy ? 'Đang xử lý…' : `${actionLabel} tài khoản`}
        </button>
      </div>
    </ModalShell>
  );
}

type EditForm = { firstName: string; lastName: string; email: string; roles: string[] };

function EditModal({
  username,
  form,
  onChange,
  onSave,
  onCancel,
  valid,
  busy,
}: {
  username: string | undefined;
  form: EditForm;
  onChange: (updater: (f: EditForm) => EditForm) => void;
  onSave: () => void;
  onCancel: () => void;
  valid: boolean;
  busy: boolean;
}) {
  const firstNameRef = useRef<HTMLInputElement>(null);

  return (
    <ModalShell
      ariaLabel={`Sửa tài khoản: ${username ?? ''}`}
      onClose={onCancel}
      initialFocusRef={firstNameRef}
      maxWidthClass="max-w-lg"
    >
      <h2 className="text-lg font-bold text-[#0D2546] mb-4">
        Sửa tài khoản: {username}
      </h2>
      <div className="grid md:grid-cols-2 gap-4">
        <Field label="Họ và tên đệm">
          <input
            ref={firstNameRef}
            value={form.firstName}
            onChange={(e) => onChange((f) => ({ ...f, firstName: e.target.value }))}
            className={inputClass}
          />
        </Field>
        <Field label="Tên">
          <input
            value={form.lastName}
            onChange={(e) => onChange((f) => ({ ...f, lastName: e.target.value }))}
            className={inputClass}
          />
        </Field>
        <Field label="Email (tùy chọn)">
          <input
            type="email"
            value={form.email}
            onChange={(e) => onChange((f) => ({ ...f, email: e.target.value }))}
            className={inputClass}
          />
        </Field>
        <Field label="Vai trò">
          <RoleCheckboxes
            value={form.roles}
            onChange={(roles) => onChange((f) => ({ ...f, roles }))}
          />
        </Field>
      </div>
      <div className="mt-5 flex justify-end gap-3">
        <button
          type="button"
          onClick={onCancel}
          disabled={busy}
          className="rounded-md border border-gray-300 px-4 py-2 text-sm font-semibold text-gray-700 transition-colors hover:bg-gray-50 disabled:opacity-60 cursor-pointer disabled:cursor-not-allowed"
        >
          Hủy
        </button>
        <button
          type="button"
          onClick={onSave}
          disabled={!valid || busy}
          className="rounded-md bg-[#0D2546] hover:bg-[#163a6b] disabled:bg-gray-300 px-4 py-2 text-sm font-semibold text-white transition-colors cursor-pointer disabled:cursor-not-allowed"
        >
          {busy ? 'Đang xử lý…' : 'Lưu thay đổi'}
        </button>
      </div>
    </ModalShell>
  );
}

function ResetPasswordModal({
  username,
  form,
  onChange,
  onSubmit,
  onCancel,
  busy,
}: {
  username: string | undefined;
  form: { password: string; temporary: boolean };
  onChange: (updater: (f: typeof form) => typeof form) => void;
  onSubmit: () => void;
  onCancel: () => void;
  busy: boolean;
}) {
  const passwordRef = useRef<HTMLInputElement>(null);

  return (
    <ModalShell
      ariaLabel={`Đặt lại mật khẩu: ${username ?? ''}`}
      onClose={onCancel}
      initialFocusRef={passwordRef}
    >
      <h2 className="text-lg font-bold text-[#0D2546] mb-1">
        Đặt lại mật khẩu: {username}
      </h2>
      <p className="text-xs text-gray-500 mb-4">
        Đặt mật khẩu mới cho tài khoản — quản trị không xem được mật khẩu cũ.
      </p>
      <Field label="Mật khẩu mới (≥ 8 ký tự)">
        <PasswordInput
          ref={passwordRef}
          value={form.password}
          onChange={(password) => onChange((f) => ({ ...f, password }))}
          className={inputClass}
        />
      </Field>
      <label className="flex items-center gap-2 mt-4 text-sm text-gray-600">
        <input
          type="checkbox"
          checked={form.temporary}
          onChange={(e) => onChange((f) => ({ ...f, temporary: e.target.checked }))}
        />
        Bắt buộc đổi mật khẩu ở lần đăng nhập kế tiếp
      </label>
      <div className="mt-5 flex justify-end gap-3">
        <button
          type="button"
          onClick={onCancel}
          disabled={busy}
          className="rounded-md border border-gray-300 px-4 py-2 text-sm font-semibold text-gray-700 transition-colors hover:bg-gray-50 disabled:opacity-60 cursor-pointer disabled:cursor-not-allowed"
        >
          Hủy
        </button>
        <button
          type="button"
          onClick={onSubmit}
          disabled={form.password.length < 8 || busy}
          className="rounded-md bg-[#0D2546] hover:bg-[#163a6b] disabled:bg-gray-300 px-4 py-2 text-sm font-semibold text-white transition-colors cursor-pointer disabled:cursor-not-allowed"
        >
          {busy ? 'Đang xử lý…' : 'Đặt lại mật khẩu'}
        </button>
      </div>
    </ModalShell>
  );
}

function QuotaModal({
  username,
  quotaGb,
  onChange,
  onSubmit,
  onCancel,
  busy,
}: {
  username: string | undefined;
  quotaGb: string;
  onChange: (value: string) => void;
  onSubmit: () => void;
  onCancel: () => void;
  busy: boolean;
}) {
  const quotaRef = useRef<HTMLInputElement>(null);

  return (
    <ModalShell
      ariaLabel={`Hạn mức lưu trữ: ${username ?? ''}`}
      onClose={onCancel}
      initialFocusRef={quotaRef}
    >
      <h2 className="text-lg font-bold text-[#0D2546] mb-1">
        Hạn mức lưu trữ: {username}
      </h2>
      <p className="text-xs text-gray-500 mb-4">
        Áp dụng ngay cho lượt tải lên tiếp theo; tệp đã lưu vượt hạn mức mới
        vẫn được giữ.
      </p>
      <Field label="Hạn mức (GB)">
        <input
          ref={quotaRef}
          type="number"
          min={0.1}
          step={0.1}
          value={quotaGb}
          onChange={(e) => onChange(e.target.value)}
          className={inputClass}
        />
      </Field>
      <div className="mt-5 flex justify-end gap-3">
        <button
          type="button"
          onClick={onCancel}
          disabled={busy}
          className="rounded-md border border-gray-300 px-4 py-2 text-sm font-semibold text-gray-700 transition-colors hover:bg-gray-50 disabled:opacity-60 cursor-pointer disabled:cursor-not-allowed"
        >
          Hủy
        </button>
        <button
          type="button"
          onClick={onSubmit}
          disabled={!Number.isFinite(Number(quotaGb)) || Number(quotaGb) <= 0 || busy}
          className="rounded-md bg-[#0D2546] hover:bg-[#163a6b] disabled:bg-gray-300 px-4 py-2 text-sm font-semibold text-white transition-colors cursor-pointer disabled:cursor-not-allowed"
        >
          {busy ? 'Đang xử lý…' : 'Lưu hạn mức'}
        </button>
      </div>
    </ModalShell>
  );
}

const EMPTY_CREATE = {
  username: '',
  firstName: '',
  lastName: '',
  email: '',
  roles: [] as string[],
  password: '',
  temporary: true,
};

/**
 * FR-04/05/06: R3 manages Keycloak accounts through the backend /accounts
 * API. Lock/unlock and password reset are the FR-04/FR-06 admin operations;
 * users change their own password at /account/password (FR-05).
 */
export default function AccountsAdminPage() {
  const { data: session, status } = useSession();
  const [rows, setRows] = useState<AccountRow[] | null>(null);
  const [search, setSearch] = useState('');
  const [roleFilter, setRoleFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState<'' | AccountStatus>('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [showCreate, setShowCreate] = useState(false);
  const [createForm, setCreateForm] = useState(EMPTY_CREATE);

  const [editingId, setEditingId] = useState<string | null>(null);
  const [editForm, setEditForm] = useState({
    firstName: '',
    lastName: '',
    email: '',
    roles: [] as string[],
  });

  const [resetId, setResetId] = useState<string | null>(null);
  const [resetForm, setResetForm] = useState({ password: '', temporary: true });

  const [statusChangeId, setStatusChangeId] = useState<string | null>(null);
  const [statusChangeForm, setStatusChangeForm] = useState<{
    status: AccountStatus;
    reason: string;
  }>({ status: 'locked', reason: '' });

  const [quotaId, setQuotaId] = useState<string | null>(null);
  const [quotaGbForm, setQuotaGbForm] = useState('');

  const canView = canSeeFeature(session, 'admin-accounts');

  const load = useCallback(() => {
    apiJson<AccountRow[]>('/accounts', session)
      .then(setRows)
      .catch((e: Error) => setError(e.message));
  }, [session]);

  useEffect(() => {
    if (canView) load();
  }, [canView, load]);

  if (status === 'loading') return null;
  if (!session || !canView) {
    return <AccessDenied featureKey="admin-accounts" />;
  }

  const run = async (
    action: () => Promise<void>,
    successMessage: string,
    lastAdminMessage?: string,
  ) => {
    setError(null);
    setBusy(true);
    try {
      await action();
      toast.success(successMessage);
      load();
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        const code = (e.body as { code?: string } | undefined)?.code;
        if (code === 'LAST_ADMIN') {
          setError(
            lastAdminMessage ??
              'Không thể khóa/vô hiệu hóa quản trị viên đang hoạt động cuối cùng.',
          );
        } else {
          setError(`Không thể thực hiện: ${e.message}`);
        }
      } else {
        setError((e as Error).message);
      }
    } finally {
      setBusy(false);
    }
  };

  const createAccount = () =>
    run(async () => {
      await apiFetch('/accounts', session, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          username: createForm.username.trim(),
          firstName: createForm.firstName.trim(),
          lastName: createForm.lastName.trim(),
          ...(createForm.email.trim() ? { email: createForm.email.trim() } : {}),
          roles: createForm.roles,
          password: createForm.password,
          temporary: createForm.temporary,
        }),
      });
      setCreateForm(EMPTY_CREATE);
      setShowCreate(false);
    }, 'Đã tạo tài khoản.');

  const startEdit = (row: AccountRow) => {
    setResetId(null);
    setEditingId(row.id);
    setEditForm({
      firstName: row.firstName ?? '',
      lastName: row.lastName ?? '',
      email: row.email ?? '',
      roles: row.roles,
    });
  };

  const saveEdit = () => {
    if (!editingId) return;
    void run(async () => {
      await apiFetch(`/accounts/${editingId}`, session, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          firstName: editForm.firstName.trim(),
          lastName: editForm.lastName.trim(),
          ...(editForm.email.trim() ? { email: editForm.email.trim() } : {}),
          roles: editForm.roles,
        }),
      });
      setEditingId(null);
    }, 'Đã cập nhật tài khoản.');
  };

  const startStatusChange = (row: AccountRow, status: AccountStatus) => {
    setEditingId(null);
    setResetId(null);
    setQuotaId(null);
    setStatusChangeId(row.id);
    setStatusChangeForm({ status, reason: '' });
  };

  const submitStatusChange = () => {
    if (!statusChangeId) return;
    const target = statusChangeForm.status;
    const priorStatus = rows?.find((r) => r.id === statusChangeId)?.status;
    const successMessage =
      target === 'locked'
        ? 'Đã khóa tài khoản.'
        : target === 'disabled'
          ? 'Đã vô hiệu hóa tài khoản.'
          : priorStatus === 'disabled'
            ? 'Đã kích hoạt lại tài khoản.'
            : 'Đã mở khóa tài khoản.';
    const lastAdminMessage =
      target === 'locked'
        ? 'Không thể khóa quản trị viên đang hoạt động cuối cùng.'
        : target === 'disabled'
          ? 'Không thể vô hiệu hóa quản trị viên đang hoạt động cuối cùng.'
          : undefined;
    void run(
      async () => {
        await apiFetch(`/accounts/${statusChangeId}/status`, session, {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            status: target,
            reason: statusChangeForm.reason.trim(),
          }),
        });
        setStatusChangeId(null);
      },
      successMessage,
      lastAdminMessage,
    );
  };

  const startQuota = (row: AccountRow) => {
    setEditingId(null);
    setResetId(null);
    setStatusChangeId(null);
    setQuotaId(row.id);
    setQuotaGbForm(formatGB(row.storageQuotaBytes));
  };

  const submitQuota = () => {
    if (!quotaId) return;
    const gb = Number(quotaGbForm);
    if (!Number.isFinite(gb) || gb <= 0) return;
    void run(async () => {
      await apiFetch(`/accounts/${quotaId}/quota`, session, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ storageQuotaBytes: Math.round(gb * 1024 * 1024 * 1024) }),
      });
      setQuotaId(null);
    }, 'Đã cập nhật hạn mức lưu trữ.');
  };

  const resetPassword = () => {
    if (!resetId) return;
    void run(async () => {
      await apiFetch(`/accounts/${resetId}/password`, session, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(resetForm),
      });
      setResetId(null);
      setResetForm({ password: '', temporary: true });
    }, 'Đã đặt lại mật khẩu.');
  };

  const createValid =
    createForm.username.trim().length >= 3 &&
    createForm.firstName.trim() !== '' &&
    createForm.lastName.trim() !== '' &&
    createForm.roles.length > 0 &&
    createForm.password.length >= 8;

  const editValid =
    editForm.firstName.trim() !== '' &&
    editForm.lastName.trim() !== '' &&
    editForm.roles.length > 0;

  const statusChangeRow = rows?.find((r) => r.id === statusChangeId);
  const statusChangeActionLabel =
    statusChangeForm.status === 'locked'
      ? 'Khóa'
      : statusChangeForm.status === 'disabled'
        ? 'Vô hiệu hóa'
        : statusChangeRow?.status === 'disabled'
          ? 'Kích hoạt lại'
          : 'Mở khóa';
  const statusChangeHint =
    statusChangeForm.status === 'active'
      ? 'Lý do sẽ được ghi vào nhật ký hệ thống; tài khoản có thể đăng nhập lại ngay sau khi xác nhận.'
      : 'Bắt buộc nêu lý do; tài khoản bị chặn đăng nhập ngay lập tức, kể cả với phiên đang hoạt động.';

  const needle = search.trim().toLocaleLowerCase('vi');
  const visibleRows =
    rows?.filter(
      (r) =>
        (!roleFilter || r.roles.includes(roleFilter)) &&
        (!statusFilter || r.status === statusFilter) &&
        (!needle ||
          [r.username, r.firstName, r.lastName, r.email]
            .filter(Boolean)
            .some((v) => (v as string).toLocaleLowerCase('vi').includes(needle))),
    ) ?? null;

  return (
    <AppShell title="Quản trị hệ thống">
      <div className="max-w-7xl">
        <div className="mb-5">
          <h2 className="text-2xl font-bold text-[#0D2546]">Tài khoản</h2>
          <p className="text-sm text-gray-500 mt-1">
            Quản lý tài khoản người dùng — mọi thao tác đều được ghi nhật ký.
          </p>
        </div>

        <AdminTabs active="accounts" />

        <div className="mb-4 flex items-center justify-between">
          <h3 className="font-semibold text-[#0D2546]">
            Tài khoản người dùng ({rows?.length ?? '…'})
          </h3>
          <button
            onClick={() => {
              setShowCreate((v) => !v);
              setEditingId(null);
              setResetId(null);
            }}
            className="bg-[#0D2546] hover:bg-[#163a6b] text-white font-semibold px-4 py-2 rounded-md text-sm transition-colors cursor-pointer"
          >
            {showCreate ? 'Đóng' : '+ Tạo tài khoản'}
          </button>
        </div>

        {error && (
          <div className="mb-4 bg-red-50 border border-red-200 rounded-md p-3 text-sm text-red-800">
            {error}
          </div>
        )}

        {showCreate && (
          <div className="mb-5 bg-white rounded-lg border border-gray-200 p-5">
            <h4 className="font-semibold text-[#0D2546] mb-4">Tạo tài khoản mới</h4>
            <div className="grid md:grid-cols-2 gap-4">
              <Field label="Tên đăng nhập">
                <input
                  value={createForm.username}
                  onChange={(e) =>
                    setCreateForm((f) => ({ ...f, username: e.target.value }))
                  }
                  placeholder="VD: canbo2"
                  className={inputClass}
                />
              </Field>
              <Field label="Email (tùy chọn)">
                <input
                  type="email"
                  value={createForm.email}
                  onChange={(e) =>
                    setCreateForm((f) => ({ ...f, email: e.target.value }))
                  }
                  placeholder="ten@donvi.local"
                  className={inputClass}
                />
              </Field>
              <Field label="Họ và tên đệm">
                <input
                  value={createForm.firstName}
                  onChange={(e) =>
                    setCreateForm((f) => ({ ...f, firstName: e.target.value }))
                  }
                  className={inputClass}
                />
              </Field>
              <Field label="Tên">
                <input
                  value={createForm.lastName}
                  onChange={(e) =>
                    setCreateForm((f) => ({ ...f, lastName: e.target.value }))
                  }
                  className={inputClass}
                />
              </Field>
              <Field label="Mật khẩu ban đầu (≥ 8 ký tự)">
                <PasswordInput
                  value={createForm.password}
                  onChange={(password) => setCreateForm((f) => ({ ...f, password }))}
                  className={inputClass}
                />
              </Field>
              <Field label="Vai trò">
                <RoleCheckboxes
                  value={createForm.roles}
                  onChange={(roles) => setCreateForm((f) => ({ ...f, roles }))}
                />
              </Field>
            </div>
            <label className="flex items-center gap-2 mt-4 text-sm text-gray-600">
              <input
                type="checkbox"
                checked={createForm.temporary}
                onChange={(e) =>
                  setCreateForm((f) => ({ ...f, temporary: e.target.checked }))
                }
              />
              Bắt buộc đổi mật khẩu ở lần đăng nhập đầu tiên
            </label>
            <button
              onClick={() => void createAccount()}
              disabled={!createValid || busy}
              className="mt-4 bg-[#0D2546] hover:bg-[#163a6b] disabled:bg-gray-300 text-white font-semibold px-5 py-2 rounded-md text-sm transition-colors cursor-pointer disabled:cursor-not-allowed"
            >
              {busy ? 'Đang xử lý…' : 'Tạo tài khoản'}
            </button>
          </div>
        )}

        {editingId && (
          <EditModal
            username={rows?.find((r) => r.id === editingId)?.username}
            form={editForm}
            onChange={setEditForm}
            onSave={saveEdit}
            onCancel={() => setEditingId(null)}
            valid={editValid}
            busy={busy}
          />
        )}

        {resetId && (
          <ResetPasswordModal
            username={rows?.find((r) => r.id === resetId)?.username}
            form={resetForm}
            onChange={setResetForm}
            onSubmit={resetPassword}
            onCancel={() => setResetId(null)}
            busy={busy}
          />
        )}

        {statusChangeId && (
          <StatusChangeModal
            actionLabel={statusChangeActionLabel}
            tone={statusChangeForm.status === 'active' ? 'default' : 'danger'}
            hint={statusChangeHint}
            username={statusChangeRow?.username}
            reason={statusChangeForm.reason}
            onReasonChange={(reason) =>
              setStatusChangeForm((f) => ({ ...f, reason }))
            }
            onConfirm={submitStatusChange}
            onCancel={() => setStatusChangeId(null)}
            busy={busy}
          />
        )}

        {quotaId && (
          <QuotaModal
            username={rows?.find((r) => r.id === quotaId)?.username}
            quotaGb={quotaGbForm}
            onChange={setQuotaGbForm}
            onSubmit={submitQuota}
            onCancel={() => setQuotaId(null)}
            busy={busy}
          />
        )}

        <div className="mb-3 flex flex-wrap items-center gap-3">
          <input
            type="search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Tìm theo tên đăng nhập, họ tên, email…"
            aria-label="Tìm tài khoản"
            className="w-80 rounded-md border border-gray-300 px-3 py-2 text-sm text-gray-800"
          />
          <select
            value={roleFilter}
            onChange={(e) => setRoleFilter(e.target.value)}
            aria-label="Lọc theo vai trò"
            className="rounded-md border border-gray-300 px-3 py-2 text-sm text-gray-800"
          >
            <option value="">Mọi vai trò</option>
            {ROLE_LABELS.map((r) => (
              <option key={r.slug} value={r.slug}>
                {r.label}
              </option>
            ))}
          </select>
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value as '' | AccountStatus)}
            aria-label="Lọc theo trạng thái"
            className="rounded-md border border-gray-300 px-3 py-2 text-sm text-gray-800"
          >
            <option value="">Mọi trạng thái</option>
            {(Object.keys(STATUS_LABELS) as AccountStatus[]).map((st) => (
              <option key={st} value={st}>
                {STATUS_LABELS[st]}
              </option>
            ))}
          </select>
          {visibleRows && rows && visibleRows.length !== rows.length && (
            <span className="text-sm text-gray-500">
              {visibleRows.length}/{rows.length} tài khoản
            </span>
          )}
        </div>

        <div className="bg-white rounded-lg border border-gray-200 overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-xs uppercase tracking-wide text-gray-500 border-b border-gray-200">
                <th className="px-4 py-3">Tên đăng nhập</th>
                <th className="px-4 py-3">Họ tên</th>
                <th className="px-4 py-3">Email</th>
                <th className="px-4 py-3">Vai trò</th>
                <th className="px-4 py-3">Trạng thái</th>
                <th className="px-4 py-3">Hạn mức</th>
                <th className="px-4 py-3 text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {visibleRows === null ? (
                <tr>
                  <td colSpan={7} className="px-4 py-6 text-center text-gray-400">
                    Đang tải…
                  </td>
                </tr>
              ) : visibleRows.length === 0 ? (
                <tr>
                  <td colSpan={7} className="px-4 py-6 text-center text-gray-400">
                    {rows && rows.length > 0 ? 'Không có tài khoản nào khớp bộ lọc.' : 'Chưa có tài khoản nào.'}
                  </td>
                </tr>
              ) : (
                visibleRows.map((row) => {
                  const isSelf = row.username === session.username;
                  return (
                    <tr
                      key={row.id}
                      className="border-b border-gray-100 last:border-0"
                    >
                      <td className="px-4 py-3 font-semibold text-[#0D2546]">
                        {row.username}
                        {isSelf && (
                          <span className="ml-2 text-[10px] uppercase tracking-wide bg-blue-50 text-blue-700 px-1.5 py-0.5 rounded">
                            Bạn
                          </span>
                        )}
                      </td>
                      <td
                        className="px-4 py-3 text-gray-700 max-w-[10rem] truncate"
                        title={
                          [row.firstName, row.lastName].filter(Boolean).join(' ') ||
                          undefined
                        }
                      >
                        {[row.firstName, row.lastName].filter(Boolean).join(' ') ||
                          '—'}
                      </td>
                      <td className="px-4 py-3 text-gray-500">
                        {row.email ?? '—'}
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex gap-1">
                          {row.roles.length === 0 ? (
                            <span className="text-gray-400">—</span>
                          ) : (
                            row.roles.map((r) => (
                              <span
                                key={r}
                                title={r}
                                className="text-xs bg-gray-100 text-gray-700 px-1.5 py-0.5 rounded"
                              >
                                {roleLabel(r)}
                              </span>
                            ))
                          )}
                        </div>
                      </td>
                      <td className="px-4 py-3">
                        <span
                          title={row.statusReason ?? undefined}
                          className={`text-xs font-semibold px-2 py-1 rounded-full ${STATUS_BADGE_CLASS[row.status]}`}
                        >
                          {STATUS_LABELS[row.status]}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-gray-600 whitespace-nowrap">
                        {formatGB(row.storageQuotaBytes)} GB
                      </td>
                      <td className="px-4 py-3 text-right whitespace-nowrap">
                        <button
                          onClick={() => startEdit(row)}
                          className="mr-2 text-[#0D2546] hover:underline font-semibold cursor-pointer"
                        >
                          Sửa
                        </button>
                        <RowMenu label={`Thao tác khác: ${row.username}`}>
                          <button
                            role="menuitem"
                            onClick={() => {
                              setEditingId(null);
                              setResetId(row.id);
                              setResetForm({ password: '', temporary: true });
                            }}
                            className={`${menuItemClass} text-[#0D2546]`}
                          >
                            Đặt lại MK
                          </button>
                          <button
                            role="menuitem"
                            onClick={() => startQuota(row)}
                            className={`${menuItemClass} text-[#0D2546]`}
                          >
                            Hạn mức
                          </button>
                          {!isSelf && <div className="my-1 border-t border-gray-100" />}
                          {!isSelf &&
                            (row.status === 'disabled' ? (
                              <button
                                role="menuitem"
                                onClick={() => startStatusChange(row, 'active')}
                                disabled={busy}
                                className={`${menuItemClass} text-green-700`}
                              >
                                Kích hoạt lại
                              </button>
                            ) : (
                              <>
                                {row.status === 'locked' ? (
                                  <button
                                    role="menuitem"
                                    onClick={() => startStatusChange(row, 'active')}
                                    disabled={busy}
                                    className={`${menuItemClass} text-green-700`}
                                  >
                                    Mở khóa
                                  </button>
                                ) : (
                                  <button
                                    role="menuitem"
                                    onClick={() => startStatusChange(row, 'locked')}
                                    disabled={busy}
                                    className={`${menuItemClass} text-[#B7791F]`}
                                  >
                                    Khóa
                                  </button>
                                )}
                                <button
                                  role="menuitem"
                                  onClick={() => startStatusChange(row, 'disabled')}
                                  disabled={busy}
                                  className={`${menuItemClass} text-[#D11A2A]`}
                                >
                                  Vô hiệu hóa
                                </button>
                              </>
                            ))}
                        </RowMenu>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>

        <p className="text-xs text-gray-400 mt-3">
          Khóa/vô hiệu hóa tài khoản chặn truy cập ngay lập tức, kể cả với
          phiên đang hoạt động (không cần chờ token hết hạn). Tài khoản không
          thể tự khóa/vô hiệu hóa chính mình, và không thể khóa/vô hiệu hóa
          quản trị viên đang hoạt động cuối cùng.
        </p>
      </div>
    </AppShell>
  );
}
