import { resetPermissionsStore } from '@/lib/permissions'
import type { MockHandler } from './fetchMock'

/** Permission codes of story r2-business-parity-01 (R2 default grants). */
export const R2_BUSINESS_PERMISSIONS = [
  'document.upload',
  'tag.manage',
  'note.write',
  'digitization_review.manage',
  'trash.manage',
] as const

/** Default for page tests: story-01 permissions + document.share (R1/R2 both hold it, V12). */
export const DEFAULT_TEST_PERMISSIONS = [...R2_BUSINESS_PERMISSIONS, 'document.share'] as const

/** `installFetchMock({ '/me/permissions': permissionsHandler([...]) })` */
export function permissionsHandler(permissions: readonly string[], roles: string[] = []): MockHandler {
  return { status: 200, body: { roles, permissions } }
}

/** Call in beforeEach: usePermissions() is a module singleton and leaks across tests otherwise. */
export function resetPermissions(): void {
  resetPermissionsStore()
}

/**
 * Adds a default `/me/permissions` handler granting the 5 r2-business-parity-01
 * permissions unless the test supplied its own. Use via a file-local
 * `installFetchMock` wrapper so existing page tests keep their call sites.
 */
export function withPermissions(
  handlers: Record<string, MockHandler>,
  permissions: readonly string[] = DEFAULT_TEST_PERMISSIONS,
): Record<string, MockHandler> {
  return '/me/permissions' in handlers
    ? handlers
    : { ...handlers, '/me/permissions': permissionsHandler(permissions) }
}
