/**
 * audit-ui-readability.md: static catalog mapping raw audit_log
 * action/resource_type/outcome/metadata codes to Vietnamese labels, grouped
 * for the /admin/audit filter dropdowns and table. Deliberately static (no
 * API call) - see story AC-4. Any code missing here still renders (fallback
 * rules below, story AC-12) so a new backend action/resource type never
 * breaks the page, it just shows the raw code until this file is updated
 * (see the AGENTS.md checklist item this story adds).
 */

export interface AuditActionDef {
  code: string
  label: string
  group: AuditActionGroup
}

export interface AuditResourceTypeDef {
  code: string
  label: string
  icon: string
}

export const AUDIT_ACTION_GROUPS = [
  'Tài liệu',
  'Kho & thư mục',
  'Tải lên',
  'Kho cá nhân',
  'Số hoá',
  'Nhãn & ghi chú',
  'Nhóm',
  'Tài khoản & phân quyền',
  'Tìm kiếm',
  'Nhật ký & hệ thống',
  'Bảo mật & lỗi',
] as const // thứ tự optgroup trong dropdown

export type AuditActionGroup = (typeof AUDIT_ACTION_GROUPS)[number]

export const AUDIT_ACTIONS: AuditActionDef[] = [
  // Tài liệu
  { code: 'document.create', label: 'Tạo tài liệu', group: 'Tài liệu' },
  { code: 'document.download', label: 'Tải xuống tài liệu', group: 'Tài liệu' },
  { code: 'document.share', label: 'Chia sẻ tài liệu', group: 'Tài liệu' },
  { code: 'document.unshare', label: 'Huỷ chia sẻ tài liệu', group: 'Tài liệu' },
  { code: 'document.trash', label: 'Chuyển vào thùng rác', group: 'Tài liệu' },
  { code: 'document.trash.denied', label: 'Từ chối chuyển vào thùng rác', group: 'Tài liệu' },
  { code: 'document.restore', label: 'Khôi phục tài liệu', group: 'Tài liệu' },
  { code: 'document.purge', label: 'Xoá vĩnh viễn tài liệu', group: 'Tài liệu' },
  { code: 'document.reject', label: 'Loại bỏ tài liệu', group: 'Tài liệu' },
  {
    code: 'document.ownership_transferred',
    label: 'Chuyển quyền sở hữu tài liệu',
    group: 'Tài liệu',
  },
  {
    code: 'document.request_rejected',
    label: 'Yêu cầu bị từ chối (Tài liệu)',
    group: 'Tài liệu',
  },

  // Kho & thư mục
  { code: 'folder.create', label: 'Tạo thư mục kho', group: 'Kho & thư mục' },
  { code: 'folder.rename', label: 'Đổi tên thư mục kho', group: 'Kho & thư mục' },
  { code: 'folder.move', label: 'Di chuyển thư mục kho', group: 'Kho & thư mục' },
  { code: 'folder.delete', label: 'Xoá thư mục kho', group: 'Kho & thư mục' },
  {
    code: 'warehouse.capacity.update',
    label: 'Cập nhật sức chứa kho',
    group: 'Kho & thư mục',
  },

  // Tải lên
  { code: 'ingest.batch.open', label: 'Mở lô tải lên', group: 'Tải lên' },
  { code: 'ingest.batch.close', label: 'Đóng lô tải lên', group: 'Tải lên' },
  {
    code: 'ingest.upload_session.init',
    label: 'Khởi tạo phiên tải lên',
    group: 'Tải lên',
  },
  {
    code: 'ingest.request_rejected',
    label: 'Yêu cầu bị từ chối (Tải lên)',
    group: 'Tải lên',
  },
  {
    code: 'ingest.from_personal.file',
    label: 'Bổ sung tệp từ kho cá nhân vào kho',
    group: 'Tải lên',
  },
  {
    code: 'upload_session.request_rejected',
    label: 'Yêu cầu bị từ chối (Phiên tải lên)',
    group: 'Tải lên',
  },

  // Kho cá nhân
  { code: 'personal.batch.open', label: 'Mở lô tải lên cá nhân', group: 'Kho cá nhân' },
  { code: 'personal.batch.close', label: 'Đóng lô tải lên cá nhân', group: 'Kho cá nhân' },
  {
    code: 'personal.upload_session.init',
    label: 'Khởi tạo phiên tải lên cá nhân',
    group: 'Kho cá nhân',
  },
  { code: 'personal.file.create', label: 'Thêm tệp cá nhân', group: 'Kho cá nhân' },
  { code: 'personal.file.download', label: 'Tải xuống tệp cá nhân', group: 'Kho cá nhân' },
  { code: 'personal.file.move', label: 'Di chuyển tệp cá nhân', group: 'Kho cá nhân' },
  { code: 'personal.file.delete', label: 'Xoá tệp cá nhân', group: 'Kho cá nhân' },
  { code: 'personal.file.reject', label: 'Loại bỏ tệp cá nhân', group: 'Kho cá nhân' },
  { code: 'personal.folder.create', label: 'Tạo thư mục cá nhân', group: 'Kho cá nhân' },
  {
    code: 'personal.folder.rename',
    label: 'Đổi tên thư mục cá nhân',
    group: 'Kho cá nhân',
  },
  {
    code: 'personal.folder.move',
    label: 'Di chuyển thư mục cá nhân',
    group: 'Kho cá nhân',
  },
  { code: 'personal.folder.delete', label: 'Xoá thư mục cá nhân', group: 'Kho cá nhân' },
  {
    code: 'personal.request_rejected',
    label: 'Yêu cầu bị từ chối (Kho cá nhân)',
    group: 'Kho cá nhân',
  },

  // Số hoá
  {
    code: 'digitization_review.confirmed',
    label: 'Xác nhận kết quả số hoá',
    group: 'Số hoá',
  },
  {
    code: 'digitization_review.segment_added',
    label: 'Thêm đoạn nội dung',
    group: 'Số hoá',
  },
  {
    code: 'digitization_review.segment_revised',
    label: 'Sửa đoạn nội dung',
    group: 'Số hoá',
  },
  {
    code: 'digitization_review.segment_deleted',
    label: 'Xoá đoạn nội dung',
    group: 'Số hoá',
  },
  {
    code: 'digitization.auto_confirmed',
    label: 'Tự động xác nhận kết quả số hoá',
    group: 'Số hoá',
  },
  {
    code: 'digitization.review_stale',
    label: 'Cảnh báo quá hạn kiểm duyệt',
    group: 'Số hoá',
  },
  {
    code: 'digitization_review.request_rejected',
    label: 'Yêu cầu bị từ chối (Kiểm duyệt số hoá)',
    group: 'Số hoá',
  },

  // Nhãn & ghi chú
  { code: 'tag.create', label: 'Tạo nhãn', group: 'Nhãn & ghi chú' },
  { code: 'tag.rename', label: 'Đổi tên nhãn', group: 'Nhãn & ghi chú' },
  { code: 'tag.deactivate', label: 'Ngừng dùng nhãn', group: 'Nhãn & ghi chú' },
  { code: 'tag.assign', label: 'Gắn nhãn cho tài liệu', group: 'Nhãn & ghi chú' },
  { code: 'tag.unassign', label: 'Gỡ nhãn khỏi tài liệu', group: 'Nhãn & ghi chú' },
  {
    code: 'tag.request_rejected',
    label: 'Yêu cầu bị từ chối (Nhãn)',
    group: 'Nhãn & ghi chú',
  },
  { code: 'note.create', label: 'Thêm ghi chú', group: 'Nhãn & ghi chú' },
  { code: 'note.update', label: 'Sửa ghi chú', group: 'Nhãn & ghi chú' },

  // Nhóm
  { code: 'group.create', label: 'Tạo nhóm', group: 'Nhóm' },
  { code: 'group.update', label: 'Cập nhật nhóm', group: 'Nhóm' },
  { code: 'group.delete', label: 'Xoá nhóm', group: 'Nhóm' },
  { code: 'group.add_member', label: 'Thêm thành viên', group: 'Nhóm' },
  { code: 'group.remove_member', label: 'Xoá thành viên', group: 'Nhóm' },
  { code: 'group.change_member_role', label: 'Đổi vai trò thành viên', group: 'Nhóm' },
  { code: 'group.leave', label: 'Rời nhóm', group: 'Nhóm' },
  { code: 'group.request_rejected', label: 'Yêu cầu bị từ chối (Nhóm)', group: 'Nhóm' },

  // Tài khoản & phân quyền
  { code: 'account.create', label: 'Tạo tài khoản', group: 'Tài khoản & phân quyền' },
  {
    code: 'account.update',
    label: 'Cập nhật tài khoản',
    group: 'Tài khoản & phân quyền',
  },
  { code: 'account.lock', label: 'Khoá tài khoản', group: 'Tài khoản & phân quyền' },
  { code: 'account.unlock', label: 'Mở khoá tài khoản', group: 'Tài khoản & phân quyền' },
  {
    code: 'account.disable',
    label: 'Vô hiệu hoá tài khoản',
    group: 'Tài khoản & phân quyền',
  },
  {
    code: 'account.password_change',
    label: 'Đổi mật khẩu',
    group: 'Tài khoản & phân quyền',
  },
  {
    code: 'account.password_reset',
    label: 'Đặt lại mật khẩu',
    group: 'Tài khoản & phân quyền',
  },
  {
    code: 'account.quota_update',
    label: 'Cập nhật hạn mức lưu trữ',
    group: 'Tài khoản & phân quyền',
  },
  {
    code: 'account.request_rejected',
    label: 'Yêu cầu bị từ chối (Tài khoản)',
    group: 'Tài khoản & phân quyền',
  },
  {
    code: 'rbac.role_permission_update',
    label: 'Cập nhật quyền của vai trò',
    group: 'Tài khoản & phân quyền',
  },

  // Tìm kiếm
  { code: 'search.executed', label: 'Tìm kiếm', group: 'Tìm kiếm' },
  {
    code: 'search.request_rejected',
    label: 'Yêu cầu bị từ chối (Tìm kiếm)',
    group: 'Tìm kiếm',
  },
  { code: 'user.directory_search', label: 'Tra cứu danh bạ người dùng', group: 'Tìm kiếm' },

  // Nhật ký & hệ thống
  { code: 'audit.view', label: 'Xem nhật ký hoạt động', group: 'Nhật ký & hệ thống' },
  { code: 'audit.verify', label: 'Xác minh toàn vẹn nhật ký', group: 'Nhật ký & hệ thống' },
  {
    code: 'audit.archive',
    label: 'Lưu trữ phân vùng nhật ký',
    group: 'Nhật ký & hệ thống',
  },
  {
    code: 'trash.purge.skipped',
    label: 'Bỏ qua dọn thùng rác',
    group: 'Nhật ký & hệ thống',
  },
  {
    code: 'system_config.update',
    label: 'Cập nhật cấu hình hệ thống',
    group: 'Nhật ký & hệ thống',
  },
  {
    code: 'allowed_file_format.create',
    label: 'Thêm định dạng tệp cho phép',
    group: 'Nhật ký & hệ thống',
  },
  {
    code: 'allowed_file_format.delete',
    label: 'Xoá định dạng tệp cho phép',
    group: 'Nhật ký & hệ thống',
  },

  // Bảo mật & lỗi
  { code: 'access.denied', label: 'Truy cập bị từ chối', group: 'Bảo mật & lỗi' },
  { code: 'http.POST', label: 'Yêu cầu tạo/gửi bị lỗi', group: 'Bảo mật & lỗi' },
  { code: 'http.PUT', label: 'Yêu cầu cập nhật bị lỗi', group: 'Bảo mật & lỗi' },
  { code: 'http.PATCH', label: 'Yêu cầu sửa bị lỗi', group: 'Bảo mật & lỗi' },
  { code: 'http.DELETE', label: 'Yêu cầu xoá bị lỗi', group: 'Bảo mật & lỗi' },
]

export const AUDIT_RESOURCE_TYPES: AuditResourceTypeDef[] = [
  { code: 'document', label: 'Tài liệu', icon: '📄' },
  { code: 'document_note', label: 'Ghi chú tài liệu', icon: '📝' },
  { code: 'tag', label: 'Nhãn', icon: '🏷️' },
  { code: 'warehouse', label: 'Kho', icon: '🏛️' },
  { code: 'warehouse_folder', label: 'Thư mục kho', icon: '📁' },
  { code: 'upload_batch', label: 'Lô tải lên', icon: '📦' },
  { code: 'upload_session', label: 'Phiên tải lên', icon: '⬆️' },
  { code: 'ingest', label: 'Tải lên', icon: '📥' },
  { code: 'personal', label: 'Kho cá nhân', icon: '🗂️' },
  { code: 'personal_file', label: 'Tệp cá nhân', icon: '📄' },
  { code: 'personal_folder', label: 'Thư mục cá nhân', icon: '📁' },
  { code: 'allowed_file_format', label: 'Định dạng tệp cho phép', icon: '📑' },
  { code: 'digitization_run', label: 'Lượt số hoá', icon: '🤖' },
  { code: 'digitization_review', label: 'Kiểm duyệt số hoá', icon: '🔍' },
  { code: 'content_segment', label: 'Đoạn nội dung', icon: '🧩' },
  { code: 'group', label: 'Nhóm', icon: '👥' },
  { code: 'account', label: 'Tài khoản', icon: '👤' },
  { code: 'user', label: 'Người dùng', icon: '🧑' },
  { code: 'role', label: 'Vai trò', icon: '🛡️' },
  { code: 'search', label: 'Tìm kiếm', icon: '🔎' },
  { code: 'audit_log', label: 'Nhật ký hoạt động', icon: '📜' },
  { code: 'audit_log_partition', label: 'Phân vùng nhật ký', icon: '🗄️' },
  { code: 'system', label: 'Hệ thống', icon: '⚙️' },
  { code: 'system_config', label: 'Cấu hình hệ thống', icon: '🔧' },
]

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

const REQUEST_REJECTED_RE = /^(.+)\.request_rejected$/

export function actionLabel(code: string): string {
  const found = AUDIT_ACTIONS.find((a) => a.code === code)
  if (found) return found.label

  const match = REQUEST_REJECTED_RE.exec(code)
  if (match) {
    const resourceCode = match[1]
    const resourceLabel = AUDIT_RESOURCE_TYPES.find((r) => r.code === resourceCode)?.label ?? resourceCode
    return `Yêu cầu bị từ chối (${resourceLabel})`
  }

  return code
}

export function resourceTypeInfo(code: string | null): { label: string; icon: string } | null {
  if (!code) return null
  const found = AUDIT_RESOURCE_TYPES.find((r) => r.code === code)
  if (found) return { label: found.label, icon: found.icon }
  return { label: code, icon: '•' }
}

/** Story AC-11. */
export function outcomeLabel(outcome: string, statusCode: number | null): string {
  if (outcome === 'success') return 'Thành công'

  if (outcome === 'failure') {
    if (statusCode === null) return 'Thất bại'
    if (statusCode === 401) return 'Chưa đăng nhập'
    if (statusCode === 403) return 'Không có quyền'
    if (statusCode === 404) return 'Không tìm thấy'
    if (statusCode === 409) return 'Xung đột dữ liệu'
    if (statusCode >= 500) return 'Lỗi hệ thống'
    if (statusCode >= 400) return 'Yêu cầu không hợp lệ'
  }

  if (outcome === 'skipped') return 'Bỏ qua'

  return outcome
}

export const METADATA_LABELS: Record<string, string> = {
  reason: 'Lý do',
  documentId: 'ID tài liệu',
  noteId: 'ID ghi chú',
  groupId: 'ID nhóm',
  folderId: 'ID thư mục',
  batchId: 'ID lô',
  tagId: 'ID nhãn',
  userSub: 'ID người dùng',
  roleInGroup: 'Vai trò trong nhóm',
  name: 'Tên',
  label: 'Nhãn',
  kind: 'Loại',
  oldLabel: 'Tên cũ',
  newLabel: 'Tên mới',
  oldValue: 'Giá trị cũ',
  newValue: 'Giá trị mới',
  targetUsername: 'Tài khoản đích',
  changed: 'Trường thay đổi',
  rolesAdded: 'Vai trò thêm',
  rolesRemoved: 'Vai trò bỏ',
  temporary: 'Mật khẩu tạm',
  permissionsAdded: 'Quyền thêm',
  permissionsRemoved: 'Quyền bỏ',
  extensions: 'Phần mở rộng',
  category: 'Nhóm định dạng',
  task: 'Tác vụ',
  size: 'Kích thước (byte)',
  sha256: 'SHA-256',
  from: 'Từ',
  to: 'Đến',
  q: 'Từ khoá',
  sourcePersonalFileId: 'ID tệp cá nhân nguồn',
  originalName: 'Tên tệp',
  targetFolderId: 'ID thư mục kho đích',
  result: 'Kết quả',
  code: 'Mã kết quả',
}

export const REASON_LABELS: Record<string, string> = {
  out_of_scope: 'Ngoài phạm vi được phép',
  role_mismatch: 'Vai trò không đủ quyền',
  scope_resolution_failed: 'Không xác định được phạm vi dữ liệu',
  forbidden: 'Bị cấm',
  backup_gate_not_satisfied: 'Chưa đạt điều kiện sao lưu',
}

export function metadataLabel(key: string): string {
  return METADATA_LABELS[key] ?? key
}

/** UUID -> 8 ký tự đầu + '…'; chuỗi khác giữ nguyên (story AC-10). */
export function shortResourceId(id: string): string {
  if (UUID_RE.test(id)) return `${id.slice(0, 8)}…`
  return id
}
