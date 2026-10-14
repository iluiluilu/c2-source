# Tech Spec: audit-ui-readability

> File: `docs/03-dev/specs/audit-ui-readability.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/audit-ui-readability.md`
> Status: Approved

## 1. Tóm tắt kỹ thuật

- **Chỉ sửa web.** Backend, API `GET /audit`, entity `AuditLog`, hash chain,
  scope filter R2 giữ nguyên.
- Thêm một file danh mục duy nhất `web/src/lib/auditCatalog.ts` (mã → nhãn,
  nhóm, icon + hàm tra cứu có fallback), dùng chung cho dropdown bộ lọc
  và các cột bảng.
- Sửa `web/src/app/admin/audit/page.tsx`: bỏ lọc `resourceId`, 2 ô lọc thành
  `<select>`, tách cột Loại tài nguyên / ID tài nguyên, Hành động /
  Kết quả hiện nhãn dạng text (không badge màu), metadata dạng key-value.
- Không thêm thư viện: icon dùng emoji (web chưa có icon lib), copy dùng
  `navigator.clipboard`.

## 2. Data model

Không đổi DB / DTO. `AuditLogRow` (`web/src/lib/api.ts`) giữ nguyên.

Kiểu mới trong `auditCatalog.ts`:

```ts
export interface AuditActionDef { code: string; label: string; group: AuditActionGroup }
export interface AuditResourceTypeDef { code: string; label: string; icon: string }

export const AUDIT_ACTION_GROUPS = [
  'Tài liệu', 'Kho & thư mục', 'Tải lên', 'Kho cá nhân', 'Số hoá', 'Nhãn & ghi chú',
  'Nhóm', 'Tài khoản & phân quyền', 'Tìm kiếm', 'Nhật ký & hệ thống', 'Bảo mật & lỗi',
] as const;                                   // thứ tự optgroup trong dropdown
export type AuditActionGroup = (typeof AUDIT_ACTION_GROUPS)[number];

export const AUDIT_ACTIONS: AuditActionDef[];             // mục 8.1
export const AUDIT_RESOURCE_TYPES: AuditResourceTypeDef[]; // mục 8.2

export function actionLabel(code: string): string;
export function resourceTypeInfo(code: string | null): { label: string; icon: string } | null;
export function outcomeLabel(outcome: string, statusCode: number | null): string;
export function metadataLabel(key: string): string;
export function shortResourceId(id: string): string;      // UUID → 8 ký tự đầu + '…', còn lại giữ nguyên
```

Quy tắc fallback (story AC-12):


| Hàm               | Mã có trong danh mục    | Mã lạ                                                                                                                             |
| ------------------ | -------------------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| `actionLabel`       | nhãn từ danh mục | `<x>.request_rejected` → `Yêu cầu bị từ chối (<nhãn loại x hoặc x>)`; còn lại → mã gốc |
| `resourceTypeInfo` | nhãn + icon               | `{ label: code, icon: '•' }`; `null`/rỗng → `null` (UI hiện `—`)                                                               |
| `outcomeLabel`      | bảng AC-11 của story      | outcome lạ → outcome gốc                                                                                  |
| `metadataLabel`    | mục 8.3                   | trả nguyên key                                                                                                                    |

## 3. API contract

Không đổi. Web vẫn gọi:


| Method | Endpoint | Request                                                                                             | Response            |
| ------ | -------- | --------------------------------------------------------------------------------------------------- | ------------------- |
| GET    | `/audit` | `from`, `to`, `actor`, `action`, `resourceType`, `page`, `size` (**không gửi `resourceId` nữa**) | `AuditPageResponse` |

## 4. Luồng xử lý chính

**Bộ lọc** (`page.tsx`)

1. `EMPTY_FILTERS` bỏ `resourceId`; `buildQuery` bỏ dòng `resourceId`.
2. "Hành động (action)" → `<select className={inputClass}>`:
   `<option value="">Tất cả hành động</option>` + mỗi group trong
   `AUDIT_ACTION_GROUPS` là một `<optgroup label>` chứa các `AUDIT_ACTIONS`
   thuộc group (giữ thứ tự khai báo). `value` = mã gốc.
3. "Loại tài nguyên" → `<select>`: `Tất cả loại` + `AUDIT_RESOURCE_TYPES`,
   text = `${icon} ${label}`, sắp theo `label` (`localeCompare('vi')`).
4. Xoá ô "ID tài nguyên"; lưới bộ lọc còn 5 ô (`md:grid-cols-3` giữ nguyên).
5. "Xóa bộ lọc" giữ logic `setFilters(EMPTY_FILTERS)` → dropdown về `""`.

**Bảng**

6. Header 7 cột theo AC-7; mọi `colSpan={6}` → `colSpan={7}`.
7. Hành động: `<td title={row.action}>{actionLabel(row.action)}</td>` — text
   thường, không badge/màu.
8. Loại tài nguyên: `resourceTypeInfo(row.resourceType)` → `${icon} ${label}`
   hoặc `—`; `title` = mã gốc.
9. ID tài nguyên: nếu có → `<code title={id}>{shortResourceId(id)}</code>` +
   nút `📋` (`aria-label="Sao chép ID tài nguyên"`), `onClick` gọi
   `e.stopPropagation()` (không toggle dòng mở rộng) rồi
   `navigator.clipboard?.writeText(id)`; hiển thị "Đã sao chép" ~1.5s
   (state `copiedId`). Clipboard lỗi/không hỗ trợ → bỏ qua im lặng. Không có
   ID → `—`.
10. Kết quả: `outcomeLabel(row.outcome, row.statusCode)` → text thường, `title` =
    `HTTP ${statusCode}` nếu có.

**Dòng mở rộng**

11. Thêm dòng "Mã hành động: `<code>{row.action}</code>`".
12. `metadata`: render `<dl>` từng cặp `metadataLabel(key)`: giá trị
    (primitive → `String(v)`; object/array → `JSON.stringify(v)`); riêng
    `reason` qua `REASON_LABELS` (mục 8.3) nếu khớp.
13. Nút "Xem JSON gốc"/"Ẩn JSON gốc" (state theo `row.id`) bật khối
    `<code>` JSON như hiện tại.

## 5. Test plan

- **Unit** `web/src/lib/__tests__/auditCatalog.test.ts`:
  - `actionLabel`: mã đã biết; `foo.request_rejected` (x có/không trong danh
    mục loại); mã lạ → nguyên mã.
  - `resourceTypeInfo`: đã biết / lạ / `null` / `''`.
  - `outcomeLabel`: đủ các dòng bảng AC-11 (success, 401, 403, 404, 409, 422,
    500, 503, failure+null, skipped, outcome lạ).
  - `shortResourceId`: UUID chuẩn, chuỗi ngắn, chuỗi số.
  - Toàn vẹn danh mục: không trùng `code`; mọi `group` thuộc
    `AUDIT_ACTION_GROUPS`; mọi mã ở mục 8.1/8.2 có mặt (snapshot danh sách mã).
- **Component** `web/src/app/admin/audit/__tests__/page.test.tsx` (sửa):
  - Các assert `getByText('document.create')` → `'Tạo tài liệu'`.
  - Không còn ô "ID tài nguyên"; chọn dropdown Hành động/Loại → URL fetch
    chứa `action=document.create` / `resourceType=document`, không chứa
    `resourceId`.
  - Xóa bộ lọc → cả hai select về `""`.
  - Dòng có UUID hiện `xxxxxxxx…`, `title` đầy đủ; bấm 📋 gọi
    `clipboard.writeText` với ID đầy đủ và **không** mở dòng mở rộng.
  - Row có action/resourceType/outcome lạ vẫn render mã gốc.
  - Kết quả `failure` + 403 hiện "Không có quyền".
  - Dòng mở rộng: hiện "Mã hành động", metadata dạng nhãn, toggle JSON gốc.
- Case biên: `resourceId` null, `metadata` null/rỗng, `statusCode` null.
- Không cần integration test backend (không đổi backend).

## 6. Task breakdown

- [x]  Task 1: Tạo `web/src/lib/auditCatalog.ts` (kiểu, danh mục mục 8, hàm
  tra cứu) + unit test.
- [x]  Task 2: `page.tsx` — bộ lọc: bỏ `resourceId`, 2 dropdown.
- [x]  Task 3: `page.tsx` — bảng: 7 cột, nhãn text hành động/kết quả, cột loại,
  cột ID + copy.
- [x]  Task 4: `page.tsx` — dòng mở rộng: mã hành động, metadata key-value,
  toggle JSON.
- [x]  Task 5: Cập nhật `page.test.tsx`; chạy `vitest`, `tsc`/`lint` web.
- [ ]  Task 6: Rebuild service `web` (docker) và kiểm tra thủ công trên
  `/admin/audit` với dữ liệu thật.
- [x]  Task 7: Thêm 1 dòng checklist vào `AGENTS.md` hoặc
  `docs/00-overview/README.md`: "Thêm mã audit mới ở backend → cập nhật
  `web/src/lib/auditCatalog.ts`" (câu hỏi mở của story — đề xuất chốt
  phương án này; script đối chiếu SQL để sau).

## 7. Rủi ro kỹ thuật

- **Danh mục lệch với backend** khi thêm route/mã audit mới: bảng vẫn hiện
  mã gốc (fallback) nhưng dropdown thiếu lựa chọn. Giảm thiểu bằng Task 7.
  Đối chiếu nhanh trên stg:
  `select action, resource_type, count(*) from audit_log group by 1,2;`
- **Bản ghi `resource_type` NULL** (~530 trên stg: `access.denied`, `http.*`)
  không lọc được bằng dropdown Loại — chấp nhận (lọc qua Hành động).
- `navigator.clipboard` chỉ có trên HTTPS/localhost — stg dùng HTTPS nên ổn;
  lỗi thì bỏ qua, không chặn UI.
- Thêm 1 cột → bảng rộng hơn; đã có `overflow-x-auto`, ID rút gọn bù lại.

## 8. Phụ lục — danh mục ban đầu

Nguồn: `select distinct action, resource_type from audit_log` trên stg
(2026-09-28, 70 cặp) ∪ các literal trong `AuditEntry(...)` ở backend
(đánh dấu `*` = chỉ có trong code, chưa xuất hiện trên stg).

### 8.1 Hành động (`AUDIT_ACTIONS`)


| Nhóm                      | Mã → Nhãn                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| -------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Tài liệu                 | `document.create` Tạo tài liệu · `document.download` Tải xuống tài liệu · `document.share` Chia sẻ tài liệu · `document.unshare` Huỷ chia sẻ tài liệu · `document.trash` Chuyển vào thùng rác · `document.trash.denied`* Từ chối chuyển vào thùng rác · `document.restore` Khôi phục tài liệu · `document.purge`* Xoá vĩnh viễn tài liệu · `document.reject` Loại bỏ tài liệu · `document.ownership_transferred`* Chuyển quyền sở hữu tài liệu · `document.request_rejected` Yêu cầu bị từ chối (Tài liệu)                                                                                                                                                                                              |
| Kho & thư mục            | `folder.create` Tạo thư mục kho · `folder.rename`* Đổi tên thư mục kho · `folder.move`* Di chuyển thư mục kho · `folder.delete` Xoá thư mục kho · `warehouse.capacity.update` Cập nhật sức chứa kho                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| Tải lên                  | `ingest.batch.open` Mở lô tải lên · `ingest.batch.close` Đóng lô tải lên · `ingest.upload_session.init` Khởi tạo phiên tải lên · `ingest.request_rejected` Yêu cầu bị từ chối (Tải lên) · `upload_session.request_rejected` Yêu cầu bị từ chối (Phiên tải lên)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| Kho cá nhân              | `personal.batch.open` Mở lô tải lên cá nhân · `personal.batch.close` Đóng lô tải lên cá nhân · `personal.upload_session.init` Khởi tạo phiên tải lên cá nhân · `personal.file.create` Thêm tệp cá nhân · `personal.file.download` Tải xuống tệp cá nhân · `personal.file.move` Di chuyển tệp cá nhân · `personal.file.delete` Xoá tệp cá nhân · `personal.file.reject`* Loại bỏ tệp cá nhân · `personal.folder.create` Tạo thư mục cá nhân · `personal.folder.rename` Đổi tên thư mục cá nhân · `personal.folder.move` Di chuyển thư mục cá nhân · `personal.folder.delete` Xoá thư mục cá nhân · `personal.request_rejected` Yêu cầu bị từ chối (Kho cá nhân) |
| Số hoá                   | `digitization_review.confirmed` Xác nhận kết quả số hoá · `digitization_review.segment_added` Thêm đoạn nội dung · `digitization_review.segment_revised` Sửa đoạn nội dung · `digitization_review.segment_deleted` Xoá đoạn nội dung · `digitization.auto_confirmed` Tự động xác nhận kết quả số hoá · `digitization.review_stale` Cảnh báo quá hạn kiểm duyệt · `digitization_review.request_rejected` Yêu cầu bị từ chối (Kiểm duyệt số hoá)                                                                                                                                                                                                                                                                                                        |
| Nhãn & ghi chú           | `tag.create` Tạo nhãn · `tag.rename`* Đổi tên nhãn · `tag.deactivate`* Ngừng dùng nhãn · `tag.assign` Gắn nhãn cho tài liệu · `tag.unassign` Gỡ nhãn khỏi tài liệu · `tag.request_rejected` Yêu cầu bị từ chối (Nhãn) · `note.create` Thêm ghi chú · `note.update` Sửa ghi chú                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| Nhóm                      | `group.create` Tạo nhóm · `group.update` Cập nhật nhóm · `group.delete` Xoá nhóm · `group.add_member` Thêm thành viên · `group.remove_member` Xoá thành viên · `group.change_member_role` Đổi vai trò thành viên · `group.leave`* Rời nhóm · `group.request_rejected` Yêu cầu bị từ chối (Nhóm)                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| Tài khoản & phân quyền | `account.create` Tạo tài khoản · `account.update`* Cập nhật tài khoản · `account.lock` Khoá tài khoản · `account.unlock` Mở khoá tài khoản · `account.disable` Vô hiệu hoá tài khoản · `account.password_change` Đổi mật khẩu · `account.password_reset` Đặt lại mật khẩu · `account.quota_update` Cập nhật hạn mức lưu trữ · `account.request_rejected` Yêu cầu bị từ chối (Tài khoản) · `rbac.role_permission_update`* Cập nhật quyền của vai trò                                                                                                                                                                                                                                                                 |
| Tìm kiếm                 | `search.executed` Tìm kiếm · `search.request_rejected` Yêu cầu bị từ chối (Tìm kiếm) · `user.directory_search` Tra cứu danh bạ người dùng                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| Nhật ký & hệ thống     | `audit.view` Xem nhật ký hoạt động · `audit.verify` Xác minh toàn vẹn nhật ký · `audit.archive`* Lưu trữ phân vùng nhật ký · `trash.purge.skipped` Bỏ qua dọn thùng rác · `system_config.update` Cập nhật cấu hình hệ thống · `allowed_file_format.create` Thêm định dạng tệp cho phép · `allowed_file_format.delete`* Xoá định dạng tệp cho phép                                                                                                                                                                                                                                                                                                                                                                                                             |
| Bảo mật & lỗi           | `access.denied` Truy cập bị từ chối · `http.POST` Yêu cầu tạo/gửi bị lỗi · `http.PUT` Yêu cầu cập nhật bị lỗi · `http.PATCH` Yêu cầu sửa bị lỗi · `http.DELETE` Yêu cầu xoá bị lỗi                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |

### 8.2 Loại tài nguyên (`AUDIT_RESOURCE_TYPES`)


| Mã                   | Icon | Nhãn                       |  | Mã                    | Icon | Nhãn                  |
| --------------------- | ---- | --------------------------- | - | ---------------------- | ---- | ---------------------- |
| `document`            | 📄   | Tài liệu                  |  | `digitization_run`     | 🤖   | Lượt số hoá        |
| `document_note`       | 📝   | Ghi chú tài liệu         |  | `digitization_review`  | 🔍   | Kiểm duyệt số hoá  |
| `tag`                 | 🏷️ | Nhãn                       |  | `content_segment`      | 🧩   | Đoạn nội dung       |
| `warehouse`           | 🏛️ | Kho                         |  | `group`                | 👥   | Nhóm                  |
| `warehouse_folder`    | 📁   | Thư mục kho               |  | `account`              | 👤   | Tài khoản            |
| `upload_batch`        | 📦   | Lô tải lên               |  | `user`                 | 🧑   | Người dùng          |
| `upload_session`      | ⬆️ | Phiên tải lên            |  | `role`*                | 🛡️ | Vai trò               |
| `ingest`              | 📥   | Tải lên                   |  | `search`               | 🔎   | Tìm kiếm             |
| `personal`            | 🗂️ | Kho cá nhân               |  | `audit_log`            | 📜   | Nhật ký hoạt động |
| `personal_file`       | 📄   | Tệp cá nhân              |  | `audit_log_partition`* | 🗄️ | Phân vùng nhật ký  |
| `personal_folder`     | 📁   | Thư mục cá nhân         |  | `system`               | ⚙️ | Hệ thống             |
| `allowed_file_format` | 📑   | Định dạng tệp cho phép |  | `system_config`        | 🔧   | Cấu hình hệ thống  |

### 8.3 Nhãn metadata (`METADATA_LABELS`, `REASON_LABELS`)

- Khoá: `reason` Lý do · `documentId` ID tài liệu · `noteId` ID ghi chú ·
  `groupId` ID nhóm · `folderId` ID thư mục · `batchId` ID lô · `tagId` ID
  nhãn · `userSub` ID người dùng · `roleInGroup` Vai trò trong nhóm · `name`
  Tên · `label` Nhãn · `kind` Loại · `oldLabel` Tên cũ · `newLabel` Tên mới ·
  `oldValue` Giá trị cũ · `newValue` Giá trị mới · `targetUsername` Tài khoản
  đích · `changed` Trường thay đổi · `rolesAdded` Vai trò thêm ·
  `rolesRemoved` Vai trò bỏ · `temporary` Mật khẩu tạm · `permissionsAdded`
  Quyền thêm · `permissionsRemoved` Quyền bỏ · `extensions` Phần mở rộng ·
  `category` Nhóm định dạng · `task` Tác vụ · `size` Kích thước (byte) ·
  `sha256` SHA-256 · `from` Từ · `to` Đến · `q` Từ khoá.
- Giá trị `reason`: `out_of_scope` Ngoài phạm vi được phép ·
  `role_mismatch` Vai trò không đủ quyền · `scope_resolution_failed` Không
  xác định được phạm vi dữ liệu · `forbidden` Bị cấm ·
  `backup_gate_not_satisfied` Chưa đạt điều kiện sao lưu.
