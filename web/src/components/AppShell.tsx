'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { signOut, useSession } from 'next-auth/react';
import { useEffect, useRef, useState } from 'react';
import { visibleFeatures } from '@/lib/features';
import { Role, hasAnyRole, roleLabelsFor } from '@/lib/roles';
import { NotificationBell } from '@/components/NotificationBell';
import { PermissionsBanner } from '@/components/PermissionsBanner';
import { Toaster } from '@/components/Toaster';
import { usePermissions } from '@/lib/permissions';

/** Minimal stroke icons keyed by Feature.icon (features.ts). */
function NavIcon({ name }: { name: string }) {
  const paths: Record<string, string> = {
    dashboard: 'M3 3h7v7H3zM14 3h7v7h-7zM14 14h7v7h-7zM3 14h7v7H3z',
    personal: 'M4 4h16v4H4zM4 12h16M4 12v8h16v-8',
    upload: 'M12 19V5M5 12l7-7 7 7',
    documents: 'M7 3h7l5 5v13H7zM14 3v5h5',
    search: 'M11 4a7 7 0 100 14 7 7 0 000-14zM20 20l-3.5-3.5',
    groups: 'M17 21v-2a4 4 0 00-4-4H5a4 4 0 00-4 4v2M9 11a4 4 0 100-8 4 4 0 000 8zM23 21v-2a4 4 0 00-3-3.87M16 3.13a4 4 0 010 7.75',
    digitization: 'M12 3a9 9 0 100 18 9 9 0 000-18zM12 8v8M8 12h8',
    reports: 'M4 20V10M10 20V4M16 20v-8M22 20H2',
    admin: 'M12 3l7 4v5c0 4-3 7-7 9-4-2-7-5-7-9V7z',
    audit: 'M4 6h16M4 12h16M4 18h10',
    trash: 'M3 6h18M8 6V4a2 2 0 012-2h4a2 2 0 012 2v2m3 0v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6h14zM10 11v6M14 11v6',
  };
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      className="w-[18px] h-[18px] shrink-0"
      aria-hidden="true"
    >
      <path d={paths[name] ?? paths.dashboard} />
    </svg>
  );
}

function Sidebar({ open, onNavigate }: { open: boolean; onNavigate: () => void }) {
  const { data: session } = useSession();
  const pathname = usePathname();
  const perms = usePermissions();
  const features = visibleFeatures(session, perms);

  const isActive = (href: string, activePrefix?: string) => {
    const prefix = activePrefix ?? href;
    return prefix === '/' ? pathname === '/' : pathname.startsWith(prefix);
  };

  return (
    <aside
      className={`fixed inset-y-0 left-0 w-64 bg-gradient-to-b from-[#0D2546] to-[#070f1c] text-white flex flex-col z-40 transition-transform lg:translate-x-0 ${
        open ? 'translate-x-0' : '-translate-x-full'
      }`}
    >
      {/* Logo */}
      <div className="h-[72px] flex items-center gap-3 px-5 border-b border-white/10 shrink-0">
        <div className="h-10 px-2 rounded-md bg-white flex items-center justify-center shrink-0">
          {/* eslint-disable-next-line @next/next/no-img-element -- small static logo, no optimization needed */}
          <img src="/logo.png" alt="AZM" className="h-7 w-auto" />
        </div>
        <div className="leading-tight">
          <p className="font-bold text-xs tracking-wide">LƯU TRỮ &amp; XỬ LÝ</p>
          <p className="font-bold text-xs tracking-wide">THÔNG TIN AI</p>
          <p className="text-[10px] text-white/40 mt-0.5">v1.0 · on-premise</p>
        </div>
      </div>

      {/* Nav */}
      <nav className="flex-1 py-3 overflow-y-auto">
        {features.map((f) => {
          const active =
            f.enabled &&
            isActive(f.href, f.activePrefix) &&
            !f.activeExcludePrefixes?.some((p) => pathname.startsWith(p));
          const base =
            'flex items-center gap-3 px-5 py-3 text-sm border-l-4 transition-colors';
          if (!f.enabled) {
            return (
              <div
                key={f.key}
                className={`${base} border-transparent text-white/30 cursor-not-allowed select-none`}
                title="Sắp có"
              >
                <NavIcon name={f.icon} />
                <span className="flex-1">{f.label}</span>
                <span className="text-[9px] uppercase tracking-wide bg-white/10 text-white/40 px-1.5 py-0.5 rounded">
                  Sắp có
                </span>
              </div>
            );
          }
          return (
            <Link
              key={f.key}
              href={f.href}
              onClick={onNavigate}
              className={`${base} ${
                active
                  ? 'border-[#D11A2A] bg-[#D11A2A]/15 text-white font-semibold'
                  : 'border-transparent text-white/70 hover:text-white hover:bg-white/5'
              }`}
            >
              <NavIcon name={f.icon} />
              <span>{f.label}</span>
            </Link>
          );
        })}
      </nav>
    </aside>
  );
}

function federatedLogout(idToken?: string) {
  void signOut({ redirect: false }).then(() => {
    if (idToken) {
      const issuer =
        process.env.NEXT_PUBLIC_KEYCLOAK_ISSUER ||
        'http://localhost:8080/realms/master';
      window.location.href = `${issuer}/protocol/openid-connect/logout?id_token_hint=${idToken}&post_logout_redirect_uri=${encodeURIComponent(
        window.location.origin,
      )}`;
    } else {
      window.location.reload();
    }
  });
}

function TopBar({ title, onMenu }: { title: string; onMenu: () => void }) {
  const { data: session } = useSession();
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const onClick = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setMenuOpen(false);
      }
    };
    document.addEventListener('mousedown', onClick);
    return () => document.removeEventListener('mousedown', onClick);
  }, []);

  const roles = session?.roles ?? [];
  const roleText = roleLabelsFor(roles);
  const displayName = session?.displayName || 'Người dùng';
  const initials = displayName
    .split(' ')
    .map((w) => w[0])
    .slice(-2)
    .join('')
    .toUpperCase();

  return (
    <header className="h-[72px] bg-white border-b border-gray-200 flex items-center justify-between gap-3 px-4 lg:px-6 sticky top-0 z-10">
      <div className="flex min-w-0 items-center gap-3">
        <button
          type="button"
          onClick={onMenu}
          aria-label="Mở menu"
          className="lg:hidden cursor-pointer rounded-md p-2 text-[#0D2546] hover:bg-gray-100"
        >
          ☰
        </button>
        <h1 className="truncate text-lg font-bold text-[#0D2546]">{title}</h1>
      </div>

      <div className="flex shrink-0 items-center gap-3 lg:gap-5">
        <span className="hidden xl:flex items-center gap-2 text-sm text-green-700 bg-green-50 px-3 py-1.5 rounded-full">
          <span className="w-2 h-2 rounded-full bg-green-500" />
          Hệ thống bình thường
        </span>

        <span className="text-sm text-gray-500 hidden md:inline">
          Vai trò:{' '}
          <span className="font-semibold text-[#0D2546]">{roleText}</span>
        </span>

        {/* The inbox API is CAN_BO/LANH_DAO only - polling it as R3 403s every minute into the audit log. */}
        {hasAnyRole(session, [Role.CAN_BO, Role.LANH_DAO]) && (
          <NotificationBell session={session} />
        )}

        <div className="relative" ref={menuRef}>
          <button
            onClick={() => setMenuOpen((o) => !o)}
            className="flex items-center gap-2.5 cursor-pointer"
          >
            <span className="w-9 h-9 rounded-full bg-[#0D2546] text-white text-xs font-semibold flex items-center justify-center">
              {initials}
            </span>
            <span className="text-sm font-semibold text-[#0D2546] hidden sm:inline">
              {displayName}
            </span>
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              className="w-4 h-4 text-gray-400"
            >
              <path d="M6 9l6 6 6-6" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>

          {menuOpen && (
            <div className="absolute right-0 mt-2 w-48 bg-white border border-gray-200 rounded-md shadow-lg py-1 text-sm">
              <div className="px-4 py-2 text-xs uppercase tracking-wide text-gray-400 border-b border-gray-100 cursor-default select-none">
                {roleText}
              </div>
              <Link
                href="/account/password"
                onClick={() => setMenuOpen(false)}
                className="block px-4 py-2 text-[#0D2546] hover:bg-gray-50"
              >
                Đổi mật khẩu
              </Link>
              <button
                onClick={() => federatedLogout(session?.idToken)}
                className="w-full text-left px-4 py-2 text-[#D11A2A] hover:bg-gray-50 font-semibold cursor-pointer"
              >
                Đăng xuất
              </button>
            </div>
          )}
        </div>
      </div>
    </header>
  );
}

/** Authenticated app chrome: dark sidebar + topbar, light content area. */
export function AppShell({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  // Below lg the sidebar is an off-canvas drawer opened from the top bar.
  const [navOpen, setNavOpen] = useState(false);
  return (
    <div className="min-h-screen bg-[#f1f5f9]">
      <Sidebar open={navOpen} onNavigate={() => setNavOpen(false)} />
      {navOpen && (
        <div className="fixed inset-0 z-30 bg-black/40 lg:hidden" onClick={() => setNavOpen(false)} aria-hidden />
      )}
      <div className="lg:ml-64 flex flex-col min-h-screen">
        <TopBar title={title} onMenu={() => setNavOpen(true)} />
        <PermissionsBanner />
        <main className="flex-1 p-4 lg:p-6 min-w-0">{children}</main>
      </div>
      <Toaster />
    </div>
  );
}
