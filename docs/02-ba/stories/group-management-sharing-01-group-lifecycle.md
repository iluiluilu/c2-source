# User Story: group-management-sharing-01-group-lifecycle

> File: `docs/02-ba/stories/group-management-sharing-01-group-lifecycle.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/group-management-sharing.md`
> Status: Approved

Phần **FR-28 còn thiếu** (vòng đời nhóm và thành viên), xây trên nền
`Group`/`GroupMember` đã có ở `rbac-group-scope` (Approved, đã implement).
Phần chia sẻ tài liệu (FR-29) nằm ở
`docs/02-ba/stories/group-management-sharing-02-document-sharing.md`.

## ⚠️ Đối chiếu với code hiện tại (bắt buộc đọc trước khi làm DEV spec)

`backend/src/main/java/.../groups/` (`GroupsController`, `GroupsService`,
`Group`, `GroupMember`, migration `V12__init_groups.sql`) **đã có**:

| Đã implement | Ghi chú |
|---|---|
| `POST /groups` — tạo nhóm, người tạo tự động là `owner` | Khớp Story 1 luồng bước 1-3 |
| `GET /groups` — liệt kê nhóm của chính mình | |
| `GET /groups/{id}/members` — chỉ thành viên xem được | |
| `POST /groups/{id}/members` — thêm thành viên, **chỉ owner** | KHÔNG kiểm tra giới hạn 200 thành viên/nhóm, 200 nhóm/user (Story 7) |
| `DELETE /groups/{id}/members/{userSub}` — gỡ thành viên, **chỉ owner** | Đây là "owner gỡ người khác", KHÔNG phải "tự rời nhóm" (Story 3) — hai việc khác nhau, hiện chỉ có việc đầu; **không có bất kỳ chặn nào** khi gỡ owner cuối cùng (mismatch với Quyết định 4 SA) |
| Permission `group.manage` gán cho cả `CAN_BO` và `LANH_DAO` | Đã khớp yêu cầu "cả 2 vai trò làm chủ nhóm được" — không cần sửa |

**Chưa implement (xác nhận bằng grep toàn `backend/src/main`, 0 kết quả)**:
sửa tên/mô tả nhóm, đổi `role_in_group`, tự rời nhóm, chặn owner-cuối-rời,
xoá nhóm (kể cả mềm), cột `deleted_at`, unique index tên nhóm, giới hạn số
lượng cứng, endpoint danh bạ người dùng, và toàn bộ UI `/groups` ở
`web/src/app` (thư mục không tồn tại).

Các story dưới đây **chỉ mô tả phần chưa có**; story nào tái dùng hành vi đã
đúng ở trên sẽ ghi rõ "đã đáp ứng, không cần code mới".

---

## Story 1 — Cập nhật tên/mô tả nhóm (FR-28)

**As a** chủ nhóm (owner)
**I want** sửa tên và mô tả nhóm đã tạo
**So that** thông tin nhóm phản ánh đúng mục đích sử dụng hiện tại

### Acceptance Criteria

```
Given tôi là owner của một nhóm
When tôi cập nhật tên và/hoặc mô tả nhóm với dữ liệu hợp lệ
Then nhóm được cập nhật, `updated_at` thay đổi, và thao tác được ghi nhật ký
```

- [ ] AC-1: Chỉ `owner` của nhóm cập nhật được (`member` → 403); người ngoài
      nhóm → 403/404 tương tự quy tắc "không lộ tồn tại" đã dùng ở
      `DocumentScopeGuard`.
- [ ] AC-2: Tên nhóm 3–255 ký tự, unique **toàn hệ thống** không phân biệt
      hoa/thường trong các nhóm **chưa xoá**; trùng tên (kể cả khác hoa/thường)
      → 409 kèm mã lỗi rõ ràng.
- [ ] AC-3: Mô tả tối đa 2000 ký tự (giữ nguyên giới hạn `CreateGroupDto`
      hiện có), có thể để trống.
- [ ] AC-4: Thao tác ghi audit `group.update` (thành công và thất bại do
      thiếu quyền).

---

## Story 2 — Đổi vai trò thành viên trong nhóm (FR-28)

**As a** chủ nhóm (owner)
**I want** đổi `role_in_group` của một thành viên giữa `owner` và `member`
**So that** tôi có thể bổ sung owner khác hoặc hạ một owner xuống member mà
không cần gỡ rồi thêm lại

### Acceptance Criteria

```
Given tôi là owner của nhóm và nhóm có một thành viên khác
When tôi đổi role_in_group của thành viên đó
Then vai trò thành viên thay đổi ngay, và nếu là hạ owner cuối cùng xuống
     member thì hệ thống từ chối
```

- [ ] AC-1: Chỉ `owner` đổi được vai trò thành viên khác; **không giới hạn
      số owner tối đa** trong một nhóm (Quyết định 2 SA — cho phép nhiều
      owner đồng thời).
- [ ] AC-2: Hạ vai trò `owner` cuối cùng của nhóm xuống `member` → từ chối
      (409) — bất biến hệ thống "mỗi nhóm chưa xoá luôn có ≥ 1 owner"
      (Quyết định 2 SA) áp dụng cho **cả** đổi vai trò lẫn gỡ/rời nhóm
      (Story 3, Story 4).
- [ ] AC-3: Ghi audit `group.change_member_role` với `userSub`, vai trò cũ,
      vai trò mới.

---

## Story 3 — Rời nhóm (FR-28)

**As a** thành viên nhóm (owner hoặc member)
**I want** tự rời khỏi một nhóm tôi đang tham gia
**So that** tôi ngừng nhận quyền xem tài liệu chia sẻ tới nhóm đó khi không
còn liên quan

### Acceptance Criteria

```
Given tôi là thành viên của một nhóm
When tôi chọn rời nhóm
Then tư cách thành viên của tôi bị xoá; nếu tôi là owner cuối cùng thì hệ
     thống từ chối và yêu cầu chuyển quyền owner cho người khác trước
```

- [ ] AC-1: `member` rời nhóm bất kỳ lúc nào, không cần điều kiện thêm.
- [ ] AC-2: `owner` rời được **nếu còn owner khác** trong nhóm; là owner
      **cuối cùng** → từ chối (409) — phải dùng Story 2 để bổ sung owner
      khác trước (Quyết định 4 SA).
- [ ] AC-3: Rời nhóm **không** ảnh hưởng các `document_group_share` do người
      đó tạo trước đó — share vẫn còn hiệu lực cho các thành viên còn lại
      (Quyết định 4 SA, đã chốt: dữ liệu chung không mất khi một người rời).
      Người rời mất quyền xem tài liệu của nhóm **ngay lập tức** ở lượt
      truy vấn kế tiếp (qua `BusinessDataScope` đã có ở `rbac-group-scope`,
      không cần sửa lớp filter).
- [ ] AC-4: Ghi audit `group.leave`.

## Ngoài phạm vi Story 3

- Gỡ thành viên bởi owner khác (`DELETE /groups/{id}/members/{userSub}`) đã
  có ở code hiện tại — Story này **bổ sung thêm** cùng bất biến "≥ 1 owner"
  (AC-2 Story 2) vào endpoint đó, không viết endpoint mới cho việc gỡ.

---

## Story 4 — Xoá nhóm (FR-28)

**As a** chủ nhóm (owner)
**I want** xoá một nhóm không còn dùng
**So that** nhóm không còn xuất hiện trong danh sách và không còn cấp quyền
xem tài liệu qua nhóm đó

### Acceptance Criteria

```
Given tôi là owner của một nhóm
When tôi xoá nhóm
Then nhóm chuyển sang trạng thái đã xoá (soft delete); nhóm và mọi tài liệu
     chia sẻ qua nhóm đó không còn hiển thị cho bất kỳ ai qua đường nhóm này
```

- [ ] AC-1: Chỉ `owner` xoá được nhóm (không cần **mọi** owner đồng ý — 1
      owner bất kỳ được phép, theo ma trận Quyết định 2 SA "Xoá nhóm: owner
      ✔").
- [ ] AC-2: Xoá là **soft delete** (`deleted_at`) — giữ nguyên bản ghi
      `group`, `group_member`, `document_group_share` để tra cứu ngược audit
      và thống kê lịch sử (Quyết định 5 SA); **không** có chức năng khôi
      phục ở MVP.
- [ ] AC-3: Ngay sau khi xoá, nhóm biến mất khỏi `GET /groups` và mọi tài
      liệu chỉ đang được thấy qua nhóm đó (không phải người nạp) không còn
      xuất hiện ở bất kỳ entrypoint đọc dữ liệu nào (`BusinessDataScope`
      phải cộng thêm điều kiện `group.deleted_at IS NULL`).
- [ ] AC-4: Tên nhóm đã xoá **được phép trùng** với tên nhóm mới tạo sau đó
      (unique index chỉ áp trên nhóm `deleted_at IS NULL` — Quyết định 5).
- [ ] AC-5: Ghi audit `group.delete`.

---

## Story 5 — Danh bạ người dùng để chọn thành viên (FR-28, hạ tầng dùng
chung cho Story 1-4 và cho UI)

**As a** chủ nhóm
**I want** tìm người dùng theo tên/username khi thêm thành viên
**So that** tôi không phải nhập tay UUID Keycloak (`user_sub`) không thấy
được trên UI

### Acceptance Criteria

```
Given tôi là R1/R2 đang thêm thành viên vào nhóm
When tôi gõ tối thiểu 2 ký tự vào ô tìm kiếm người dùng
Then hệ thống trả về tối đa 20 tài khoản active khớp tên/username
```

- [ ] AC-1: `GET /users/directory?q=` chỉ cho R1/R2 (`CAN_BO`, `LANH_DAO`);
      R3 → 403 (endpoint nhóm/chia sẻ **không bao giờ** cấp cho R3, NFR-04).
- [ ] AC-2: `q` bắt buộc ≥ 2 ký tự; thiếu/ngắn hơn → 400.
- [ ] AC-3: Chỉ trả tài khoản `active`; trường trả về tối thiểu: `sub`,
      `username`, `display_name`, `role` — **không** trả trạng thái khoá,
      quota hay các trường quản trị khác (Quyết định 7 SA).
- [ ] AC-4: Tối đa 20 kết quả/lượt gọi.
- [ ] AC-5: Ghi audit `user.directory_search`.

---

## Story 6 — Giới hạn số lượng cứng (FR-28, áp cho toàn bộ thao tác ghi nhóm)

**As a** hệ thống
**I want** chặn số lượng nhóm/thành viên vượt ngưỡng cấu hình cứng
**So that** hệ thống không bị lạm dụng để phình dữ liệu vượt quy mô tham
chiếu NFR-03

### Acceptance Criteria

```
Given một thao tác tạo nhóm / thêm thành viên / chia sẻ tài liệu sắp vượt
      giới hạn cứng
When thao tác đó được thực hiện
Then hệ thống từ chối với 422 kèm mã lỗi rõ ràng, không tạo bản ghi
```

- [ ] AC-1: ≤ 200 thành viên/nhóm — `POST /groups/{id}/members` vượt → 422.
- [ ] AC-2: ≤ 200 nhóm mà một người dùng là thành viên — áp cho cả
      `POST /groups` (người tạo tự thêm mình) và `POST /groups/{id}/members`.
- [ ] AC-3: ≤ 1.000 nhóm toàn hệ thống (đếm nhóm `deleted_at IS NULL`) —
      `POST /groups` vượt → 422.
- [ ] AC-4: Giới hạn ≤ 50 nhóm/tài liệu thuộc Story chia sẻ
      (`group-management-sharing-02-document-sharing.md`), không lặp lại ở
      đây.
- [ ] AC-5: Giới hạn **hard-code** trong code, KHÔNG đưa vào `system-config`
      ở MVP (đã chốt với khách hàng, xem SA mục 7).

---

## Story 7 — Trang quản lý nhóm trên web (FR-28, UI)

**As a** người dùng R1/R2
**I want** một trang `/groups` để tạo/xem/sửa nhóm và quản lý thành viên
**So that** tôi không cần gọi API thủ công để dùng tính năng nhóm

### Acceptance Criteria

```
Given tôi đã đăng nhập với vai trò R1 hoặc R2
When tôi vào trang /groups
Then tôi thấy danh sách nhóm của mình, tạo được nhóm mới, và vào chi tiết
     một nhóm để sửa tên/mô tả, thêm/gỡ/đổi vai trò thành viên, rời hoặc
     xoá nhóm theo đúng quyền owner/member
```

- [ ] AC-1: Danh sách nhóm (`/groups`) — tên, số thành viên, vai trò của tôi
      trong từng nhóm.
- [ ] AC-2: Trang tạo nhóm — form tên/mô tả + chọn thành viên ban đầu qua ô
      tìm kiếm (Story 5).
- [ ] AC-3: Trang chi tiết nhóm (`/groups/{id}`) — sửa tên/mô tả (Story 1,
      chỉ hiện nút nếu tôi là owner), danh sách thành viên kèm hành động
      đổi vai trò/gỡ (Story 2, chỉ owner), nút "Rời nhóm" (Story 3, mọi
      thành viên) và nút "Xoá nhóm" (Story 4, chỉ owner, có xác nhận).
- [ ] AC-4: Ẩn hoàn toàn hành động owner-only khỏi UI khi tôi là `member`
      (UI không dựa vào việc API trả lỗi để ẩn nút).
- [ ] AC-5: Route `QUAN_TRI` (R3) không truy cập được trang này (khớp
      NFR-04 — không có menu, không có link trong `AppShell`).

---

## Ngoài phạm vi (Out of scope)

- Chia sẻ/gỡ chia sẻ tài liệu, liệt kê tài liệu của nhóm, thông báo —
  `group-management-sharing-02-document-sharing.md`.
- Filter phạm vi đọc dữ liệu (FR-08) — đã Approved & implement ở
  `rbac-group-scope`, story này chỉ bổ sung điều kiện `deleted_at` (Story 4
  AC-3), không viết lại `BusinessDataScope`.
- Khôi phục nhóm đã xoá — chốt không làm ở MVP.
- Chia sẻ trực tiếp cho một cá nhân (không qua nhóm) — chốt không làm
  (Quyết định 1 SA).

## Phụ thuộc / Ghi chú kiến trúc liên quan

- `docs/01-sa/architecture/group-management-sharing.md`: Quyết định 2 (ma
  trận quyền), Quyết định 4 (rời nhóm/gỡ thành viên), Quyết định 5 (soft
  delete), Quyết định 7 (danh bạ), mục 3 NFR (giới hạn số lượng).
- `docs/01-sa/architecture/rbac-group-scope.md`: nền tảng `Group`/
  `GroupMember`/`BusinessDataScope` — **kế thừa nguyên vẹn**.
- `docs/02-ba/stories/account-lifecycle.md`: nguồn `display_name`/`active`
  cho Story 5 (danh bạ) — không lưu trùng lặp thông tin tài khoản.

## Câu hỏi mở cho DEV

- [x] Story 2 AC-2 và Story 3 AC-2 dùng chung bất biến "≥ 1 owner" — cân nhắc
      viết một hàm kiểm tra dùng chung trong `GroupsService` thay vì lặp
      logic đếm owner ở 3 chỗ (đổi vai trò, gỡ thành viên, rời nhóm).
      **Đã chốt (2026-09-02): dùng chung 1 hàm** — xem
      `requireNotLastOwner` ở `docs/03-dev/specs/group-management-sharing-01-group-lifecycle.md`
      mục 4.3, gọi từ cả 3 nơi (đổi vai trò, gỡ thành viên, rời nhóm).
