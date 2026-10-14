'use client';
import { SessionProvider } from 'next-auth/react';
import { SessionErrorGuard } from '@/components/SessionErrorGuard';
import { ConfirmProvider } from '@/components/ConfirmDialog';

// No refetchInterval: a background poll refreshes the access token and so
// counts as activity for Keycloak's SSO idle timeout, keeping an abandoned
// tab logged in. Expired tokens are refreshed on demand instead, when a
// backend call answers 401 (lib/api.ts fetchWithToken).
export function Providers({ children }: { children: React.ReactNode }) {
  return (
    <SessionProvider>
      <SessionErrorGuard />
      <ConfirmProvider>{children}</ConfirmProvider>
    </SessionProvider>
  );
}
