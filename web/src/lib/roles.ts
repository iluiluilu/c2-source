import type { Session } from 'next-auth'

/**
 * Mirrors backend/src/auth/roles.constant.ts (minus the nest-keycloak-connect
 * `realm:` prefix, which is a backend-only concern). Keep the role slugs in
 * sync with keycloak/realm-export.json.
 */
export const Role = {
  /** R1 - Can bo (officer) */
  CAN_BO: 'can-bo',
  /** R2 - Lanh dao (leadership, read-only) */
  LANH_DAO: 'lanh-dao',
  /** R3 - Quan tri he thong (system admin) */
  QUAN_TRI: 'quan-tri',
} as const

/**
 * Vietnamese role labels, in R1→R3 order — the one place the UI translates
 * role slugs. Plain names only: the R1/R2/R3 codes are spec vocabulary.
 */
export const ROLE_LABELS: { slug: string; label: string }[] = [
  { slug: Role.CAN_BO, label: 'Cán bộ' },
  { slug: Role.LANH_DAO, label: 'Lãnh đạo' },
  { slug: Role.QUAN_TRI, label: 'Quản trị' },
]

/** Label for one role slug; unknown slugs are shown as-is. */
export function roleLabel(slug: string): string {
  return ROLE_LABELS.find((r) => r.slug === slug)?.label ?? slug
}

/** Friendly, comma-joined label for whatever roles a session holds (R1→R3 order). */
export function roleLabelsFor(roles: readonly string[]): string {
  return (
    ROLE_LABELS.filter((r) => roles.includes(r.slug))
      .map((r) => r.label)
      .join(', ') || 'Không có vai trò'
  )
}

export function hasRole(session: Session | null, role: string): boolean {
  return session?.roles?.includes(role) ?? false
}

/**
 * Union / ANY semantics: true if the user holds at least one of `roles`.
 * Mirrors the backend's `@Roles({ mode: RoleMatchingMode.ANY })`, so a
 * multi-role user gets the combined access of all their roles.
 */
export function hasAnyRole(session: Session | null, roles: readonly string[]): boolean {
  return roles.some((r) => hasRole(session, r))
}
