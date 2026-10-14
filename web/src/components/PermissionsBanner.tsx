'use client';

import { usePermissions } from '@/lib/permissions';

/** Shown when /me/permissions failed: gated features stay hidden (fail-closed) until retry succeeds. */
export function PermissionsBanner() {
  const { status, refetch } = usePermissions();
  if (status !== 'error') return null;
  return (
    <div
      role="alert"
      className="mx-6 mt-4 flex items-center justify-between gap-4 rounded-md border border-amber-300 bg-amber-50 px-4 py-2 text-sm text-amber-900"
    >
      <span>
        Không tải được quyền truy cập. Một số chức năng đang bị ẩn tạm thời.
      </span>
      <button
        onClick={() => refetch()}
        className="font-semibold text-[#0D2546] hover:text-[#D11A2A] cursor-pointer"
      >
        Thử lại
      </button>
    </div>
  );
}
