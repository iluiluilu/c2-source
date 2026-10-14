import Link from 'next/link';
import { FEATURES } from '@/lib/features';

/**
 * The "no access" panel a route renders when the caller lacks a feature's
 * roles. Copy shown is sourced from the feature registry (features.ts), so a
 * route's denial message stays in sync with what the nav advertises.
 *
 * This is a UX guard only — the backend still enforces the real RBAC on every
 * API call the page would make.
 */
export function AccessDenied({ featureKey }: { featureKey: string }) {
  const hint = FEATURES.find((f) => f.key === featureKey)?.deniedHint;
  return (
    <main className="min-h-screen bg-[#0B1527] flex items-center justify-center p-4">
      <div className="bg-white rounded-lg p-8 max-w-md text-center space-y-4">
        <h1 className="text-xl font-bold text-[#0D2546]">
          Không có quyền truy cập
        </h1>
        {hint && <p className="text-sm text-gray-500">{hint}</p>}
        <Link href="/" className="text-[#D11A2A] font-semibold text-sm">
          ← Về trang chính
        </Link>
      </div>
    </main>
  );
}
