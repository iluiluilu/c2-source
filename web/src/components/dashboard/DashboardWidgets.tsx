'use client';

import Link from 'next/link';
import type { Session } from 'next-auth';
import { useEffect, useState } from 'react';
import { apiJson } from '@/lib/api';
import type {
  AccountRow,
  DocumentRow,
  QuotaInfo,
  ReviewRunSummaryPageResponse,
  WarehouseCapacity,
} from '@/lib/api';
import { formatBytes } from '@/lib/reports';

/** null = loading, undefined = failed / not allowed (the tile is then hidden). */
type Loaded<T> = T | null | undefined;

function useLoad<T>(enabled: boolean, load: () => Promise<T>, deps: unknown[]): Loaded<T> {
  const [value, setValue] = useState<Loaded<T>>(null);
  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    load()
      .then((v) => !cancelled && setValue(v))
      .catch(() => !cancelled && setValue(undefined));
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- deps are passed explicitly by the caller
  }, [enabled, ...deps]);
  return enabled ? value : undefined;
}

function Tile({
  label,
  value,
  hint,
  href,
  tone = 'default',
}: {
  label: string;
  value: Loaded<string | number>;
  hint?: string;
  href?: string;
  tone?: 'default' | 'warn';
}) {
  if (value === undefined) return null;
  const body = (
    <>
      <p className="text-xs uppercase tracking-wide text-gray-400">{label}</p>
      <p className={`mt-1 text-2xl font-bold ${tone === 'warn' ? 'text-amber-600' : 'text-[#0D2546]'}`}>
        {value === null ? '…' : value}
      </p>
      {hint && <p className="mt-1 text-xs text-gray-500">{hint}</p>}
    </>
  );
  const cls = 'block bg-white rounded-lg border border-gray-200 p-5';
  return href ? (
    <Link href={href} className={`${cls} hover:border-[#0D2546] transition-colors`}>
      {body}
    </Link>
  ) : (
    <div className={cls}>{body}</div>
  );
}

const OPEN_REVIEW = 'pending,in_review,stale,upcoming_deadline';

/**
 * UX review 2026-10-13 (UX-31): what needs attention, per what the user can
 * do - replaces the old "Chức năng khả dụng: N" tile. Every tile is gated
 * by the same permission as its API and silently hidden when it fails.
 */
export function DashboardWidgets({
  session,
  can,
  isAdmin,
}: {
  session: Session;
  can: (code: string) => boolean;
  isAdmin: boolean;
}) {
  const canReview = can('digitization_review.manage');
  const canRead = can('document.read');
  const canPersonal = can('personal.manage');
  const canFolders = isAdmin && can('folder.read');
  const canAccounts = isAdmin && can('account.read');

  const reviewOpen = useLoad(
    canReview,
    () =>
      apiJson<ReviewRunSummaryPageResponse>(`/digitization-review/queue?status=${OPEN_REVIEW}&page=0&size=1`, session).then(
        (r) => r.totalElements,
      ),
    [session],
  );
  const reviewUrgent = useLoad(
    canReview,
    () =>
      apiJson<ReviewRunSummaryPageResponse>('/digitization-review/queue?status=stale,upcoming_deadline&page=0&size=1', session).then(
        (r) => r.totalElements,
      ),
    [session],
  );
  const quota = useLoad(canPersonal, () => apiJson<QuotaInfo>('/personal/quota', session), [session]);
  const recent = useLoad(canRead, () => apiJson<DocumentRow[]>('/documents?limit=5', session), [session]);
  const capacity = useLoad(canFolders, () => apiJson<WarehouseCapacity>('/warehouse/capacity', session), [session]);
  const accounts = useLoad(canAccounts, () => apiJson<AccountRow[]>('/accounts', session), [session]);

  const quotaPercent =
    quota && quota.quotaBytes > 0 ? Math.round((quota.usedBytes / quota.quotaBytes) * 100) : null;
  const blockedAccounts = accounts ? accounts.filter((a) => a.status !== 'active').length : accounts;

  return (
    <>
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 mb-8">
        <Tile label="Chờ kiểm duyệt số hoá" value={reviewOpen} href="/digitization" hint="Tài liệu bạn nạp cần xác nhận" />
        <Tile
          label="Sắp/quá hạn duyệt"
          value={reviewUrgent}
          href="/digitization"
          tone={typeof reviewUrgent === 'number' && reviewUrgent > 0 ? 'warn' : 'default'}
        />
        <Tile
          label="Lưu trữ cá nhân"
          value={quota === null ? null : quota === undefined ? undefined : `${quotaPercent ?? 0}%`}
          hint={quota ? `${formatBytes(quota.usedBytes)} / ${formatBytes(quota.quotaBytes)}` : undefined}
          href="/personal"
          tone={quotaPercent !== null && quotaPercent >= 85 ? 'warn' : 'default'}
        />
        <Tile
          label="Dung lượng kho"
          value={capacity === null ? null : capacity === undefined ? undefined : `${capacity.usedPercent.toFixed(1)}%`}
          hint={capacity ? `${formatBytes(capacity.usedBytes)} / ${formatBytes(capacity.maxCapacityBytes)}` : undefined}
          href="/admin/folders"
          tone={capacity && capacity.status !== 'OK' ? 'warn' : 'default'}
        />
        <Tile
          label="Tài khoản bị khóa / vô hiệu"
          value={blockedAccounts}
          hint={accounts ? `Trên tổng ${accounts.length} tài khoản` : undefined}
          href="/admin/accounts"
          tone={typeof blockedAccounts === 'number' && blockedAccounts > 0 ? 'warn' : 'default'}
        />
      </div>

      {recent !== undefined && (
        <div className="mb-8 bg-white rounded-lg border border-gray-200">
          <div className="flex items-center justify-between border-b border-gray-100 px-5 py-3">
            <h3 className="font-semibold text-[#0D2546]">Tài liệu mới nhất</h3>
            <Link href="/documents" className="text-sm font-semibold text-[#0D2546] hover:underline">
              Xem tất cả →
            </Link>
          </div>
          {recent === null ? (
            <p className="px-5 py-4 text-sm text-gray-400">Đang tải…</p>
          ) : recent.length === 0 ? (
            <p className="px-5 py-4 text-sm text-gray-400">Chưa có tài liệu nào trong phạm vi của bạn.</p>
          ) : (
            <ul className="divide-y divide-gray-100">
              {recent.map((d) => (
                <li key={d.id} className="flex items-center justify-between gap-3 px-5 py-2.5 text-sm">
                  <Link href={`/documents/${d.id}`} className="truncate font-medium text-[#0D2546] hover:underline">
                    {d.originalName}
                  </Link>
                  <span className="shrink-0 text-xs text-gray-400">
                    {d.uploaderUsername} · {new Date(d.createdAt).toLocaleDateString('vi-VN')}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </>
  );
}
