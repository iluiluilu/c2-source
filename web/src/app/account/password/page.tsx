'use client';

import { useSession } from 'next-auth/react';
import { useState } from 'react';
import { ApiError, apiFetch } from '@/lib/api';
import { AccessDenied } from '@/components/AccessDenied';
import { AppShell } from '@/components/AppShell';
import { canSeeFeature } from '@/lib/features';
import { toast } from '@/lib/toast';

const inputClass =
  'w-full border border-gray-300 rounded-md px-3 py-2 text-sm text-gray-800';

/**
 * FR-05: any signed-in user changes their own password after re-entering the
 * current one. The confirmation match is checked client-side (UI concern per
 * the SRS); everything else — current-password verification, policy, "new
 * must differ from old" — is enforced by the backend.
 */
export default function ChangePasswordPage() {
  const { data: session, status } = useSession();
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const canView = canSeeFeature(session, 'account-password');

  if (status === 'loading') return null;
  if (!session || !canView) {
    return <AccessDenied featureKey="account-password" />;
  }

  const mismatch = confirm !== '' && confirm !== newPassword;
  const valid =
    currentPassword !== '' && newPassword.length >= 8 && confirm === newPassword;

  const submit = async () => {
    setError(null);
    setSubmitting(true);
    try {
      await apiFetch('/accounts/me/password', session, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ currentPassword, newPassword }),
      });
      toast.success(
        'Đổi mật khẩu thành công. Mật khẩu mới có hiệu lực từ lần đăng nhập sau.',
      );
      setCurrentPassword('');
      setNewPassword('');
      setConfirm('');
    } catch (e) {
      setError(
        e instanceof ApiError ? e.message : 'Không thể đổi mật khẩu. Thử lại sau.',
      );
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AppShell title="Đổi mật khẩu">
      <div className="max-w-md">
        <div className="mb-5">
          <h2 className="text-2xl font-bold text-[#0D2546]">Đổi mật khẩu</h2>
          <p className="text-sm text-gray-500 mt-1">
            Nhập mật khẩu hiện tại trước khi đặt mật khẩu mới.
          </p>
        </div>

        <div className="bg-white rounded-lg border border-gray-200 p-6 space-y-4">
          <label className="block">
            <span className="text-xs font-semibold uppercase tracking-wide text-gray-500">
              Mật khẩu hiện tại
            </span>
            <input
              type="password"
              value={currentPassword}
              onChange={(e) => setCurrentPassword(e.target.value)}
              autoComplete="current-password"
              className={`${inputClass} mt-1`}
            />
          </label>

          <label className="block">
            <span className="text-xs font-semibold uppercase tracking-wide text-gray-500">
              Mật khẩu mới (≥ 8 ký tự, khác tên đăng nhập)
            </span>
            <input
              type="password"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              autoComplete="new-password"
              className={`${inputClass} mt-1`}
            />
          </label>

          <label className="block">
            <span className="text-xs font-semibold uppercase tracking-wide text-gray-500">
              Nhập lại mật khẩu mới
            </span>
            <input
              type="password"
              value={confirm}
              onChange={(e) => setConfirm(e.target.value)}
              autoComplete="new-password"
              className={`${inputClass} mt-1 ${
                mismatch ? 'border-red-400' : ''
              }`}
            />
            {mismatch && (
              <span className="text-xs text-red-600 mt-1 block">
                Mật khẩu nhập lại không khớp.
              </span>
            )}
          </label>

          <button
            onClick={() => void submit()}
            disabled={!valid || submitting}
            className="w-full bg-[#0D2546] hover:bg-[#163a6b] disabled:bg-gray-300 text-white font-semibold py-2 rounded-md text-sm transition-colors cursor-pointer disabled:cursor-not-allowed"
          >
            {submitting ? 'Đang xử lý…' : 'Đổi mật khẩu'}
          </button>

          {error && (
            <div className="bg-red-50 border border-red-200 rounded-md p-3 text-sm text-red-800">
              {error}
            </div>
          )}
        </div>
      </div>
    </AppShell>
  );
}
