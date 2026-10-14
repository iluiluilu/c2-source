'use client';

import { useSession } from 'next-auth/react';
import { useEffect, useState } from 'react';
import { apiJson } from '@/lib/api';
import type { TrashExpiryBucket, TrashStats } from '@/lib/api';
import { AccessDenied } from '@/components/AccessDenied';
import { AdminTabs } from '@/components/AdminTabs';
import { AppShell } from '@/components/AppShell';
import { canSeeFeature } from '@/lib/features';

function formatSize(bytes: number): string {
  if (bytes >= 1_073_741_824) return `${(bytes / 1_073_741_824).toFixed(1)} GB`;
  if (bytes >= 1_048_576) return `${(bytes / 1_048_576).toFixed(1)} MB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${bytes} B`;
}

/**
 * delete-restore-trash-01-xoa-mem-thung-rac.md §4.5: 5 bucket cố định, biên
 * đã chốt ở tầng backend (DocumentRepository#countByExpiryBucket) - FE chỉ
 * đổi nhãn sang tiếng Việt, không tự nhóm lại.
 */
const BUCKET_ORDER: TrashExpiryBucket['bucket'][] = [
  'expired',
  'within_7_days',
  'within_30_days',
  'within_90_days',
  'later',
];

const BUCKET_LABELS: Record<TrashExpiryBucket['bucket'], string> = {
  expired: 'Đã quá hạn',
  within_7_days: 'Trong 7 ngày tới',
  within_30_days: 'Trong 30 ngày tới',
  within_90_days: 'Trong 90 ngày tới',
  later: 'Sau 90 ngày',
};

/**
 * delete-restore-trash-01-xoa-mem-thung-rac.md Story 4 (R3): thống kê thùng
 * rác toàn hệ thống, KHÔNG lộ tên tệp/đường dẫn nào (AC-4/AC-5) - chỉ gọi GET
 * /trash/stats, không gọi thêm API nào khác.
 */
export default function TrashStatsPage() {
  const { data: session, status } = useSession();
  const [stats, setStats] = useState<TrashStats | null>(null);
  const [error, setError] = useState<string | null>(null);

  const canView = canSeeFeature(session, 'admin-trash-stats');

  useEffect(() => {
    if (!canView) return;
    apiJson<TrashStats>('/trash/stats', session)
      .then(setStats)
      .catch((e: Error) => setError(e.message));
  }, [canView, session]);

  if (status === 'loading') return null;
  if (!session || !canView) {
    return <AccessDenied featureKey="admin-trash-stats" />;
  }

  const byBucket = new Map(stats?.byExpiryBucket.map((b) => [b.bucket, b]) ?? []);

  return (
    <AppShell title="Quản trị hệ thống">
      <div className="max-w-7xl">
        <div className="mb-5">
          <h2 className="text-2xl font-bold text-[#0D2546]">Thùng rác</h2>
          <p className="text-sm text-gray-500 mt-1">
            Thống kê thùng rác toàn hệ thống — không hiển thị tên tệp/đường dẫn cụ thể
          </p>
        </div>

        <AdminTabs active="trash-stats" />

        {error && (
          <div className="mb-4 bg-red-50 border border-red-200 rounded-md p-3 text-sm text-red-800">
            {error}
          </div>
        )}

        {stats === null ? (
          <p className="text-sm text-gray-400">Đang tải…</p>
        ) : (
          <div className="space-y-5">
            <div className="bg-white rounded-lg border border-gray-200 p-6">
              <h3 className="font-semibold text-[#0D2546] mb-4">Tổng quan</h3>
              <div className="grid md:grid-cols-2 gap-6 text-sm">
                <div>
                  <p className="text-xs uppercase tracking-wide text-gray-400">Tổng số tài liệu</p>
                  <p className="text-2xl font-bold text-[#0D2546] mt-1">{stats.totalItems}</p>
                </div>
                <div>
                  <p className="text-xs uppercase tracking-wide text-gray-400">Tổng dung lượng</p>
                  <p className="text-2xl font-bold text-[#0D2546] mt-1">{formatSize(stats.totalBytes)}</p>
                </div>
              </div>
            </div>

            <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
              <h3 className="font-semibold text-[#0D2546] p-6 pb-4">Theo hạn xóa cứng</h3>
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-xs uppercase tracking-wide text-gray-400 border-b border-gray-200">
                    <th className="py-3 px-6 font-medium">Nhóm</th>
                    <th className="py-3 px-6 font-medium">Số lượng</th>
                    <th className="py-3 px-6 font-medium">Dung lượng</th>
                  </tr>
                </thead>
                <tbody>
                  {BUCKET_ORDER.map((bucket) => {
                    const row = byBucket.get(bucket);
                    return (
                      <tr key={bucket} className="border-b border-gray-100 last:border-0">
                        <td className="py-3 px-6 text-gray-800 font-medium">{BUCKET_LABELS[bucket]}</td>
                        <td className="py-3 px-6 text-gray-600">{row?.count ?? 0}</td>
                        <td className="py-3 px-6 text-gray-600">{formatSize(row?.bytes ?? 0)}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>

            <div className="bg-white rounded-lg border border-gray-200 p-6">
              <h3 className="font-semibold text-[#0D2546] mb-2">Tài khoản không thể khôi phục</h3>
              <p className="text-xs text-gray-500 mb-4">
                Cả người xóa lẫn người nạp gốc đều đã bị khóa/vô hiệu hóa
              </p>
              <p className="text-2xl font-bold text-[#0D2546]">
                {stats.unrecoverableAccounts.count}
              </p>
              {stats.unrecoverableAccounts.nearestPurgeAfter && (
                <p className="text-xs text-gray-500 mt-2">
                  Gần nhất sẽ bị xóa cứng vào{' '}
                  {new Date(stats.unrecoverableAccounts.nearestPurgeAfter).toLocaleString('vi-VN')}
                </p>
              )}
            </div>
          </div>
        )}
      </div>
    </AppShell>
  );
}
