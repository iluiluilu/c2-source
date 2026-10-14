'use client';

import Link from 'next/link';
import { useSyncExternalStore } from 'react';
import { dismissToast, getToasts, subscribeToasts, type Toast } from '@/lib/toast';

const EMPTY: readonly Toast[] = [];

/**
 * Renders lib/toast.ts - fixed top-right under the header, so it never
 * shifts the page nor covers floating bottom bars (bulk actions).
 */
export function Toaster() {
  const items = useSyncExternalStore(subscribeToasts, getToasts, () => EMPTY);
  if (items.length === 0) return null;
  return (
    <div className="fixed top-[84px] right-5 z-[90] flex w-[22rem] max-w-[calc(100vw-2.5rem)] flex-col gap-2">
      {items.map((t) => (
        <div
          key={t.id}
          role="status"
          className={`flex items-start gap-3 rounded-lg border px-4 py-3 text-sm shadow-lg ${
            t.tone === 'success'
              ? 'border-green-200 bg-green-50 text-green-800'
              : 'border-blue-200 bg-blue-50 text-blue-800'
          }`}
        >
          <p className="flex-1">
            {t.message}
            {t.action && (
              <>
                {' '}
                <Link
                  href={t.action.href}
                  onClick={() => dismissToast(t.id)}
                  className="font-semibold underline"
                >
                  {t.action.label}
                </Link>
              </>
            )}
          </p>
          <button
            type="button"
            aria-label="Đóng thông báo"
            onClick={() => dismissToast(t.id)}
            className="shrink-0 cursor-pointer opacity-60 hover:opacity-100"
          >
            ✕
          </button>
        </div>
      ))}
    </div>
  );
}
