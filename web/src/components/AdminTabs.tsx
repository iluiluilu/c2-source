'use client';

import Link from 'next/link';

/**
 * Admin section tabs. Only built screens are listed - the prototype's
 * placeholders (API & liên thông, Sao lưu & khôi phục, Hạn mức lưu trữ) stay
 * hidden until they exist.
 */
const ADMIN_TABS: { key: string; label: string; href: string }[] = [
  { key: 'folders', label: 'Cấu trúc kho', href: '/admin/folders' },
  { key: 'accounts', label: 'Tài khoản', href: '/admin/accounts' },
  { key: 'system-config', label: 'Cấu hình hệ thống', href: '/admin/system-config' },
  { key: 'allowed-formats', label: 'Định dạng cho phép', href: '/admin/allowed-formats' },
  { key: 'rbac', label: 'Phân quyền', href: '/admin/rbac' },
  { key: 'document-ownership', label: 'Chuyển quyền sở hữu', href: '/admin/document-ownership' },
  { key: 'trash-stats', label: 'Thùng rác', href: '/admin/trash-stats' },
];

export function AdminTabs({ active }: { active: string }) {
  return (
    <div className="flex gap-6 overflow-x-auto border-b border-gray-200 mb-5 text-sm">
      {ADMIN_TABS.map((tab) => {
        if (tab.key === active) {
          return (
            <span
              key={tab.key}
              className="pb-2 -mb-px whitespace-nowrap border-b-2 border-[#D11A2A] text-[#0D2546] font-semibold"
            >
              {tab.label}
            </span>
          );
        }
        return (
          <Link
            key={tab.key}
            href={tab.href}
            className="pb-2 whitespace-nowrap text-gray-500 hover:text-[#0D2546] transition-colors"
          >
            {tab.label}
          </Link>
        );
      })}
    </div>
  );
}
