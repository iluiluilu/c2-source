'use client';

import Link from 'next/link';
import { signIn, useSession } from 'next-auth/react';
import { useEffect } from 'react';
import { AppShell } from '@/components/AppShell';
import { visibleFeatures } from '@/lib/features';
import { usePermissions } from '@/lib/permissions';
import { DashboardWidgets } from '@/components/dashboard/DashboardWidgets';
import { Role, hasRole, roleLabelsFor } from '@/lib/roles';

/** One line per quick-access tile: what the feature is for. */
const FEATURE_DESCRIPTIONS: Record<string, string> = {
  personal: 'Kho riêng của bạn, không số hoá',
  upload: 'Nạp tệp/thư mục vào kho chung',
  documents: 'Duyệt tài liệu theo thư mục',
  search: 'Tìm theo từ khoá trong nội dung',
  'semantic-search': 'Hỏi bằng câu tự nhiên',
  groups: 'Chia sẻ tài liệu theo nhóm',
  digitization: 'Xác nhận kết quả nhận dạng',
  reports: 'Thống kê và xuất báo cáo',
  'reports-exports': 'Tải các tệp báo cáo đã xuất',
  'admin-folders': 'Thư mục, tài khoản, cấu hình',
  audit: 'Ai đã làm gì, khi nào',
  trash: 'Phục hồi tài liệu đã xoá',
};

export default function Home() {
  const { data: session, status } = useSession();
  const perms = usePermissions();

  // UX-38: no landing step - an anonymous visit goes straight to the Keycloak
  // form. The card below stays as a fallback (button) if the redirect stalls.
  useEffect(() => {
    if (status === 'unauthenticated') void signIn('keycloak');
  }, [status]);

  // ---- Loading ----
  if (status === 'loading') {
    return (
      <main className="min-h-screen bg-[#0B1527] flex items-center justify-center">
        <div className="animate-spin rounded-full h-10 w-10 border-b-2 border-[#D11A2A]" />
      </main>
    );
  }

  // ---- Not signed in: standalone login card (matches prototype 01) ----
  if (!session) {
    return (
      <main className="min-h-screen bg-[#0B1527] flex items-center justify-center p-4 relative overflow-hidden">
        <div
          className="absolute inset-0 z-0 opacity-20 pointer-events-none"
          style={{
            backgroundImage: `linear-gradient(rgba(255,255,255,0.1) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,0.1) 1px, transparent 1px)`,
            backgroundSize: '40px 40px',
          }}
        />
        <div className="z-10 w-full max-w-[480px] bg-white rounded-lg shadow-2xl overflow-hidden flex flex-col">
          <div className="bg-[#0D2546] text-white p-6 border-b-4 border-[#D11A2A] flex items-center gap-4">
            <div className="h-12 px-2.5 rounded-md bg-white flex items-center justify-center shrink-0">
              {/* eslint-disable-next-line @next/next/no-img-element -- small static logo, no optimization needed */}
              <img src="/logo.png" alt="AZM" className="h-9 w-auto" />
            </div>
            <div>
              <h1 className="font-bold text-sm tracking-wide leading-tight">
                HỆ THỐNG LƯU TRỮ DỮ LIỆU
              </h1>
              <h2 className="font-bold text-sm tracking-wide leading-tight">
                &amp; XỬ LÝ THÔNG TIN AI
              </h2>
            </div>
          </div>
          <div className="p-8 pb-10 flex flex-col justify-center">
            <div className="space-y-8">
              <div>
                <h3 className="text-2xl font-bold text-[#0D2546]">
                  Đăng nhập hệ thống
                </h3>
                <p className="text-sm text-gray-500 mt-1">
                  Đang chuyển đến trang đăng nhập… Nếu không tự chuyển, bấm nút bên dưới.
                </p>
              </div>
              <button
                onClick={() => signIn('keycloak')}
                className="w-full bg-[#D11A2A] hover:bg-red-700 text-white font-semibold py-3 px-4 rounded-md transition-colors text-lg cursor-pointer"
              >
                Đăng nhập
              </button>
            </div>
            <div className="mt-8 flex items-center gap-2 text-xs text-gray-400 justify-center">
              <div className="w-2 h-2 rounded-full bg-green-500" />
              <span>
                Kết nối nội bộ được mã hóa
              </span>
            </div>
          </div>
        </div>
      </main>
    );
  }

  // ---- Signed in: dashboard inside the app shell (matches prototype 02) ----
  const features = visibleFeatures(session, perms);
  const enabled = features.filter((f) => f.enabled && f.key !== 'dashboard');
  const now = new Date().toLocaleString('vi-VN', {
    dateStyle: 'short',
    timeStyle: 'short',
  });

  return (
    <AppShell title="Bảng điều khiển">
      <div className="max-w-7xl">
        <div className="mb-6">
          <h2 className="text-2xl font-bold text-[#0D2546]">
            Xin chào{session.displayName ? `, ${session.displayName}` : ''}
          </h2>
          <p className="text-sm text-gray-500 mt-1">
            {roleLabelsFor(session.roles ?? [])} · Cập nhật {now}
          </p>
        </div>

        <DashboardWidgets session={session} can={perms.can} isAdmin={hasRole(session, Role.QUAN_TRI)} />

        {/* Quick access to the built features the user can reach */}
        <h3 className="font-semibold text-[#0D2546] mb-3">Truy cập nhanh</h3>
        {enabled.length === 0 ? (
          <p className="text-sm text-gray-500">
            Không có chức năng nào khả dụng cho vai trò của bạn.
          </p>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
            {enabled.map((f) => (
              <Link
                key={f.key}
                href={f.href}
                className="bg-white rounded-lg border border-gray-200 p-5 hover:border-[#0D2546] hover:shadow-sm transition-all"
              >
                <p className="font-semibold text-[#0D2546]">{f.label}</p>
                <p className="text-sm text-gray-500 mt-1">{FEATURE_DESCRIPTIONS[f.key] ?? 'Mở chức năng →'}</p>
              </Link>
            ))}
          </div>
        )}
      </div>
    </AppShell>
  );
}
