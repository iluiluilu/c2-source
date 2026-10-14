'use client';

import { signOut, useSession } from 'next-auth/react';
import { useEffect, useRef, useState } from 'react';
import { setSessionRefresher } from '@/lib/api';
import { sessionExpiredBus } from '@/lib/sessionExpiredBus';

/**
 * Wires the session into lib/api.ts and handles a session that is gone.
 *
 * - Registers useSession().update as the refresher apiFetch calls on a 401,
 *   so the refreshed token also lands in SessionProvider / every useSession().
 * - When the refresh fails (session.error = "RefreshAccessTokenError" -
 *   refresh token expired via SSO idle/max timeout, revoked, or from a wiped
 *   Keycloak DB) or api.ts reports a token it could not refresh, shows a
 *   blocking popup; its button signs out and returns to the login landing.
 *
 * Plain signOut (not federated logout): the Keycloak SSO session backing
 * this NextAuth session is usually already gone (that's why the refresh
 * failed), and if it *is* still alive, landing on "Đăng nhập" silently
 * issues fresh tokens — exactly what we want.
 */
export function SessionErrorGuard() {
  const { data: session, update } = useSession();
  const [expired, setExpired] = useState(false);
  const signingOut = useRef(false);

  const updateRef = useRef(update);
  useEffect(() => {
    updateRef.current = update;
  }, [update]);
  useEffect(() => {
    setSessionRefresher(() => updateRef.current());
    return () => setSessionRefresher(null);
  }, []);

  useEffect(() => sessionExpiredBus.subscribe(() => setExpired(true)), []);

  if (!expired && session?.error !== 'RefreshAccessTokenError') return null;

  const relogin = () => {
    if (signingOut.current) return;
    signingOut.current = true;
    void signOut({ callbackUrl: '/' });
  };

  return (
    <div className="fixed inset-0 z-[100] flex items-center justify-center bg-black/50 p-4">
      <div
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="session-expired-title"
        className="w-full max-w-sm rounded-lg bg-white p-6 shadow-2xl"
      >
        <h2 id="session-expired-title" className="text-lg font-bold text-[#0D2546]">
          Phiên đăng nhập đã hết hạn
        </h2>
        <p className="mt-2 mb-6 text-sm text-gray-600">
          Vui lòng đăng nhập lại để tiếp tục làm việc.
        </p>
        <div className="flex justify-end">
          <button
            type="button"
            autoFocus
            onClick={relogin}
            className="rounded-md bg-[#0D2546] px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-[#163a6b] cursor-pointer"
          >
            Đăng nhập lại
          </button>
        </div>
      </div>
    </div>
  );
}
