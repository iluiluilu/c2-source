import type { Session } from 'next-auth'
import { Role, hasAnyRole } from './roles'

/**
 * UI feature registry: the single source of truth for the sidebar nav and
 * route guards. A feature is visible if the user holds ANY of its `roles`
 * (union semantics — see hasAnyRole), so a multi-role user sees the combined
 * set. `enabled: false` items are shown in the nav (to mirror the prototype)
 * but greyed out and non-navigable — the route isn't built yet.
 *
 * This governs *presentation* only. The backend remains the real authority —
 * every route it exposes enforces its own @PreAuthorize permission gate, so hiding a feature
 * here is UX, not a security boundary.
 *
 * Keep each built feature's `roles` aligned with the backend controller that
 * backs its `href`. NFR-07: `documents` is deliberately NOT visible to
 * quan-tri (admins never see business content), matching the backend.
 */
export interface Feature {
  /** Stable key for React lists / lookups / route guards. */
  key: string
  /** Vietnamese label shown in the nav. */
  label: string
  /** App route. */
  href: string
  /** Icon key rendered by the sidebar (see AppShell's icon map). */
  icon: string
  /** User sees the feature if they hold any of these roles. */
  roles: readonly string[]
  /**
   * When set, visibility is gated by these backend permission codes (ANY, via
   * /me/permissions - r2-business-parity-03) instead of `roles`. Fail-closed
   * while permissions are loading or errored.
   */
  permissions?: readonly string[]
  /** False = shown greyed in the nav; the route isn't built yet. */
  enabled: boolean
  /**
   * True = not rendered in the sidebar at all (reached from elsewhere, e.g.
   * an admin tab or the user menu) but still guardable via canSeeFeature.
   */
  hidden?: boolean
  /**
   * Sidebar active-state prefix when it differs from `href` (e.g. the admin
   * entry stays highlighted across every /admin/* route).
   */
  activePrefix?: string
  /**
   * Routes under this feature's prefix that belong to ANOTHER feature (e.g. `/search/semantic`
   * lives under `/search`) - the sidebar must not highlight both.
   */
  activeExcludePrefixes?: readonly string[]
  /** Shown by the route guard (AccessDenied) when the user lacks the roles. */
  deniedHint?: string
}

const ALL_ROLES = [Role.CAN_BO, Role.LANH_DAO, Role.QUAN_TRI]

/** Ordered as they appear in the sidebar (role-filtered per session). */
export const FEATURES: readonly Feature[] = [
  {
    key: 'dashboard',
    label: 'Bảng điều khiển',
    href: '/',
    icon: 'dashboard',
    roles: ALL_ROLES,
    enabled: true,
  },
  {
    key: 'personal',
    label: 'Lưu trữ cá nhân',
    href: '/personal',
    icon: 'personal',
    roles: ALL_ROLES,
    enabled: true,
    deniedHint: 'Lưu trữ cá nhân yêu cầu đăng nhập với vai trò hợp lệ.',
  },
  {
    key: 'upload',
    label: 'Nạp dữ liệu',
    href: '/upload',
    icon: 'upload',
    roles: [Role.CAN_BO],
    permissions: ['document.upload'],
    enabled: true,
    deniedHint: 'Chức năng nạp dữ liệu yêu cầu quyền nạp dữ liệu (mặc định: Cán bộ, Lãnh đạo).',
  },
  {
    key: 'documents',
    label: 'Kho tài liệu',
    href: '/documents',
    icon: 'documents',
    roles: [Role.CAN_BO, Role.LANH_DAO],
    enabled: true,
    deniedHint: 'Kho tài liệu dành cho Cán bộ và Lãnh đạo.',
  },
  {
    key: 'search',
    label: 'Tìm kiếm & tra cứu',
    href: '/search',
    icon: 'search',
    roles: [Role.CAN_BO, Role.LANH_DAO],
    enabled: true,
    activeExcludePrefixes: ['/search/semantic'],
  },
  {
    // search-semantic AC-1.4 (FR-24): its own page, same roles as /search. `enabled` is a static value -
    // the backend flag app.semantic.enabled answers 404 when off and the page shows its error banner.
    key: 'semantic-search',
    label: 'Tra cứu ngữ nghĩa',
    href: '/search/semantic',
    icon: 'search',
    roles: [Role.CAN_BO, Role.LANH_DAO],
    enabled: true,
    deniedHint: 'Tra cứu ngữ nghĩa dành cho Cán bộ và Lãnh đạo.',
  },
  {
    key: 'groups',
    label: 'Nhóm chia sẻ',
    href: '/groups',
    icon: 'groups',
    // group-management-sharing-01-group-lifecycle.md: R3 never manages
    // groups/shares (NFR-04) - only CAN_BO/LANH_DAO hold group.manage.
    roles: [Role.CAN_BO, Role.LANH_DAO],
    enabled: true,
    deniedHint: 'Quản lý nhóm chia sẻ dành cho Cán bộ và Lãnh đạo.',
  },
  {
    key: 'digitization',
    label: 'Kiểm duyệt số hoá',
    href: '/digitization',
    icon: 'digitization',
    roles: [Role.CAN_BO],
    permissions: ['digitization_review.manage'],
    enabled: true,
    deniedHint: 'Kiểm duyệt số hoá yêu cầu quyền kiểm duyệt số hoá (mặc định: Cán bộ, Lãnh đạo), chỉ thấy tài liệu do chính mình nạp.',
  },
  {
    key: 'reports',
    label: 'Báo cáo',
    href: '/reports',
    icon: 'reports',
    // export-reporting: gated by the report.read permission (seeded for R1/R2, never assignable to R3).
    roles: [Role.CAN_BO, Role.LANH_DAO],
    permissions: ['report.read'],
    enabled: true,
    activePrefix: '/reports',
    deniedHint: 'Báo cáo yêu cầu quyền xem báo cáo (mặc định: Cán bộ, Lãnh đạo).',
  },
  {
    key: 'reports-exports',
    label: 'Tệp báo cáo đã xuất',
    href: '/reports/exports',
    icon: 'reports',
    roles: [Role.CAN_BO, Role.LANH_DAO],
    permissions: ['report.export'],
    enabled: true,
    hidden: true, // reached from the reports page
    deniedHint: 'Xuất và tải tệp báo cáo yêu cầu quyền xuất báo cáo (mặc định: Cán bộ, Lãnh đạo).',
  },
  {
    key: 'admin-folders',
    label: 'Quản trị hệ thống',
    href: '/admin/folders',
    icon: 'admin',
    roles: [Role.QUAN_TRI],
    enabled: true,
    activePrefix: '/admin',
    // /admin/audit has its own sidebar entry ("Nhật ký hoạt động").
    activeExcludePrefixes: ['/admin/audit'],
    deniedHint: 'Quản trị hệ thống dành cho Quản trị hệ thống.',
  },
  {
    key: 'admin-accounts',
    label: 'Tài khoản',
    href: '/admin/accounts',
    icon: 'admin',
    roles: [Role.QUAN_TRI],
    enabled: true,
    hidden: true, // reached via the admin section tabs, not the sidebar
    deniedHint: 'Quản lý tài khoản dành cho Quản trị hệ thống.',
  },
  {
    key: 'admin-system-config',
    label: 'Cấu hình hệ thống',
    href: '/admin/system-config',
    icon: 'admin',
    roles: [Role.QUAN_TRI],
    enabled: true,
    hidden: true, // reached via the admin section tabs, not the sidebar
    deniedHint: 'Cấu hình hệ thống dành cho Quản trị hệ thống.',
  },
  {
    key: 'admin-allowed-formats',
    label: 'Định dạng cho phép',
    href: '/admin/allowed-formats',
    icon: 'admin',
    roles: [Role.QUAN_TRI],
    enabled: true,
    hidden: true, // reached via the admin section tabs, not the sidebar
    deniedHint: 'Quản lý định dạng cho phép dành cho Quản trị hệ thống.',
  },
  {
    key: 'admin-rbac',
    label: 'Phân quyền (RBAC)',
    href: '/admin/rbac',
    icon: 'admin',
    roles: [Role.QUAN_TRI],
    enabled: true,
    hidden: true, // reached via the admin section tabs, not the sidebar
    deniedHint: 'Phân quyền dành cho Quản trị hệ thống.',
  },
  {
    key: 'admin-document-ownership',
    label: 'Chuyển quyền sở hữu',
    href: '/admin/document-ownership',
    icon: 'admin',
    roles: [Role.QUAN_TRI],
    enabled: true,
    hidden: true, // reached via the admin section tabs, not the sidebar
    deniedHint: 'Chuyển quyền sở hữu tài liệu dành cho Quản trị hệ thống.',
  },
  {
    key: 'account-password',
    label: 'Đổi mật khẩu',
    href: '/account/password',
    icon: 'admin',
    roles: ALL_ROLES,
    enabled: true,
    hidden: true, // reached via the user menu in the topbar
    deniedHint: 'Đổi mật khẩu yêu cầu đăng nhập với vai trò hợp lệ.',
  },
  {
    key: 'audit',
    label: 'Nhật ký hoạt động',
    href: '/admin/audit',
    icon: 'audit',
    roles: [Role.LANH_DAO, Role.QUAN_TRI],
    enabled: true,
    deniedHint: 'Nhật ký hoạt động dành cho Lãnh đạo và Quản trị hệ thống.',
  },
  {
    key: 'trash',
    label: 'Thùng rác',
    href: '/trash',
    icon: 'trash',
    roles: [Role.CAN_BO],
    permissions: ['trash.manage'],
    enabled: true,
    deniedHint: 'Thùng rác yêu cầu quyền quản lý thùng rác (mặc định: Cán bộ, Lãnh đạo).',
  },
  {
    key: 'admin-trash-stats',
    label: 'Thùng rác',
    href: '/admin/trash-stats',
    icon: 'admin',
    roles: [Role.QUAN_TRI],
    enabled: true,
    hidden: true, // reached via the admin section tabs, not the sidebar
    deniedHint: 'Thống kê thùng rác dành cho Quản trị hệ thống.',
  },
]

/** Minimal permission view needed by the registry (see usePermissions). */
export interface PermissionChecker {
  can: (code: string) => boolean
}

function featureAllowed(f: Feature, session: Session | null, perms?: PermissionChecker): boolean {
  if (f.permissions) {
    // Fail-closed: no checker (or still loading/errored) => not allowed.
    return perms ? f.permissions.some((code) => perms.can(code)) : false
  }
  return hasAnyRole(session, f.roles)
}

/** Features the given session may see, in registry order (roles, or permissions where declared). */
export function visibleFeatures(session: Session | null, perms?: PermissionChecker): Feature[] {
  return FEATURES.filter((f) => !f.hidden && featureAllowed(f, session, perms))
}

/** Whether a specific feature is visible to the session (e.g. for route guards). */
export function canSeeFeature(session: Session | null, key: string, perms?: PermissionChecker): boolean {
  const feature = FEATURES.find((f) => f.key === key)
  return feature ? featureAllowed(feature, session, perms) : false
}

/** Look up a feature's denial hint by key (for AccessDenied). */
export function featureDeniedHint(key: string): string | undefined {
  return FEATURES.find((f) => f.key === key)?.deniedHint
}
