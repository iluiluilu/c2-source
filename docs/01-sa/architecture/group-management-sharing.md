# Architecture: group-management-sharing

> File: `docs/01-sa/architecture/group-management-sharing.md` — Owner: SA
> Status: Approved

## 1. Bối cảnh / Vấn đề

- Hoàn thiện **FR-28** (tạo/cập nhật nhóm, quản lý thành viên và quyền) và
  **FR-29** (chia sẻ/gỡ chia sẻ tài liệu lên nhóm) theo SRS mục 2.9.
- Nền tảng đã Approved ở `rbac-group-scope.md` (Quyết định 2 + 3): data model
  `group` / `group_member` / `document_group_share` và lớp filter phạm vi
  (`BusinessDataScope`, `DocumentScopeGuard`). Tài liệu này **kế thừa nguyên
  vẹn** model đó, chỉ bổ sung field/vòng đời còn thiếu — **không thiết kế lại**.
- Hiện trạng code (`backend/src/main/java/.../groups/`): đã có `Group`,
  `GroupMember`, `DocumentGroupShare`, `GroupsService`, `GroupsController`,
  `DocumentSharesController` ở mức **tối thiểu đủ cho scoping**: tạo nhóm, liệt
  kê nhóm của mình, xem/thêm/gỡ thành viên (chỉ owner), share/unshare/list
  share theo tài liệu. Web (`web/src/app/documents/**`) **chưa có UI nhóm**.

**In-scope**

- FR-28 phần còn thiếu: cập nhật tên/mô tả nhóm, đổi `role_in_group` của thành
  viên, chuyển/bổ sung chủ nhóm, rời nhóm, xoá nhóm; ràng buộc tên nhóm.
- FR-29 phần còn thiếu: chia sẻ nhiều tài liệu một lần, liệt kê **tài liệu của
  một nhóm** (`GET /groups/{id}/documents`), gỡ chia sẻ theo nhóm.
- Ma trận quyền owner/member cho các thao tác trên.
- Vòng đời: rời nhóm / gỡ thành viên / xoá nhóm / tài khoản bị khoá khi tài
  liệu vẫn đang chia sẻ.
- Cơ chế thông báo thành viên (bước 3 luồng FR-29).
- Tương tác với hạn mức lưu trữ cá nhân (FR-14).
- Danh bạ người dùng tối giản để chọn thành viên (R1/R2 hiện không có API này).
- UI web quản lý nhóm + panel chia sẻ trên trang tài liệu.

**Out-of-scope (chỉ nêu điểm giao tiếp — contract)**

| Chủ đề | Thuộc slug | Contract của slug này |
|---|---|---|
| Filter phạm vi FR-08 | `rbac-group-scope` (Approved) | Tái dùng `BusinessDataScope`/`DocumentScopeGuard`, không viết lại điều kiện |
| Lập chỉ mục & lọc theo nhóm khi tìm kiếm | `ai-search-infra`, `search-fulltext` | Phát sự kiện share/unshare + field `group_ids` trên document index (Quyết định 7) |
| Báo cáo/thống kê theo nhóm | `export-reporting` | Chỉ đảm bảo dữ liệu `group`/`document_group_share` truy vấn được, có index |
| Xoá mềm / thùng rác tài liệu | `delete-restore-trash` | Tài liệu soft-delete → ẩn khỏi danh sách nhóm, **giữ** bản ghi share; hard-delete → xoá share (Quyết định 5) |
| RBAC tĩnh, tài khoản, hạn mức | `rbac-group-scope`, `account-lifecycle`, `personal-storage-quota` | Không thay đổi |
| Nhật ký | `audit-logging` | Dùng taxonomy `<domain>.<verb>`, ghi đồng bộ trong transaction nghiệp vụ |

## 2. Ràng buộc (Constraints)

- Mạng cách ly hoàn toàn: **không** email/SMS/push ngoài → thông báo chỉ có thể
  là in-app.
- NFR-04 (tách biệt nhiệm vụ): **R3 không bao giờ** được cấp endpoint nhóm/chia
  sẻ — giữ đúng `@PreAuthorize("hasAnyRole('CAN_BO','LANH_DAO')")` hiện có.
- Định danh thành viên là Keycloak `sub` (`group_member.user_sub`); tên hiển
  thị phải lấy từ bảng tài khoản (`account-lifecycle`), không lưu trùng lặp.
- Chia sẻ là **liên kết siêu dữ liệu**, không di chuyển/sao chép tệp (FR-29).
- Không thêm hạ tầng mới (không WebSocket, không message broker) — broker chỉ
  xuất hiện ở `ai-search-infra`.
- Quy mô tham chiếu NFR-03: vài trăm nhóm, ~100 thành viên/nhóm.

## 3. NFR (Non-Functional Requirements)

| Loại | Yêu cầu |
|---|---|
| Performance | CRUD nhóm/thành viên: p95 ≤ 300 ms. `GET /groups/{id}/documents` (phân trang 50): p95 ≤ 800 ms với 10.000 tài liệu/nhóm. Chia sẻ hàng loạt ≤ 100 tài liệu/request, p95 ≤ 2 s. Ghi audit thêm ≤ 10 ms p95 (theo `audit-logging`). |
| Security | Mọi endpoint chỉ R1/R2; R3 → 403 + ghi log. Chỉ chia sẻ được tài liệu người dùng **đang thấy được** (bắt buộc qua `DocumentScopeGuard.requireVisible`). Fail-closed khi lỗi phân giải phạm vi. Gỡ chia sẻ có hiệu lực ở tầng DB **ngay trong cùng transaction** (0 s trễ). |
| Scalability | Giới hạn cứng: ≤ 200 thành viên/nhóm, ≤ 200 nhóm/người dùng, ≤ 50 nhóm/tài liệu, ≤ 1.000 nhóm toàn hệ thống. Vượt → 422 kèm mã lỗi rõ ràng. |
| Availability | Lỗi module thông báo **không** được làm rollback thao tác nhóm/chia sẻ (best-effort, tách khỏi transaction nghiệp vụ). Trễ đồng bộ chỉ mục search khi chia sẻ ≤ 60 s; khi **gỡ** chia sẻ phải đồng bộ ngay (không chấp nhận trễ — rủi ro rò rỉ). |
| Khác | 100% thao tác tạo/sửa/xoá nhóm, thêm/gỡ/đổi vai trò thành viên, chia sẻ/gỡ chia sẻ đều có bản ghi audit (thành công **và** thất bại do thiếu quyền). Tên nhóm: 3–255 ký tự, unique không phân biệt hoa/thường trong các nhóm chưa xoá. |

## 4. Phương án & Quyết định (ADR)

### Quyết định 1: Mô hình chia sẻ — share record theo nhóm

- **Ngữ cảnh:** FR-29 yêu cầu 1 tài liệu chia sẻ được cho nhiều nhóm, không sao
  chép tệp.
- **Các phương án xem xét:** A) bảng share `(document_id, group_id)` — như
  `rbac-group-scope` đã chốt; B) ACL tổng quát `(resource, principal, action)`
  hỗ trợ cả chia sẻ cho **cá nhân**; C) sao chép tài liệu vào không gian nhóm.
- **Quyết định:** A. Giữ nguyên `document_group_share`, **không** hỗ trợ chia sẻ
  trực tiếp cho cá nhân ở phiên bản này.
- **Lý do:** C vi phạm trực tiếp FR-29 (không sao chép tệp) và nhân đôi quota.
  B tạo ra khái niệm phạm vi thứ hai ngoài nhóm — đi ngược nguyên tắc đã chốt ở
  FR-08 ("phạm vi hoàn toàn dựa trên cơ chế nhóm"). A đã được lớp filter hiện
  tại dùng, không cần đổi read path.
- **Hệ quả:** muốn chia sẻ cho 1 người phải tạo nhóm 2 thành viên → số nhóm có
  thể phình; đã tính vào giới hạn 1.000 nhóm ở NFR.

### Quyết định 2: Ma trận quyền owner / member

- **Ngữ cảnh:** FR-28 "chỉ chủ nhóm hoặc người được cấp quyền quản lý mới được
  cập nhật nhóm và thành viên"; FR-29 "người dùng thuộc nhóm **và có quyền chia
  sẻ**". Cần định nghĩa "có quyền chia sẻ" cụ thể.
- **Các phương án xem xét:** A) chỉ owner được chia sẻ; B) mọi thành viên được
  chia sẻ; C) thêm cột quyền chia sẻ per-member (`can_share`).
- **Quyết định:** B, với ma trận sau (2 vai trò trong nhóm, không thêm cột mới):

| Thao tác | owner | member |
|---|---|---|
| Xem nhóm, danh sách thành viên, tài liệu của nhóm | ✔ | ✔ |
| Sửa tên/mô tả nhóm | ✔ | ✘ |
| Thêm/gỡ thành viên, đổi `role_in_group` | ✔ | ✘ |
| Xoá nhóm | ✔ | ✘ |
| Rời nhóm | ✔ (nếu còn owner khác) | ✔ |
| Chia sẻ tài liệu **mình đang thấy được** vào nhóm | ✔ | ✔ |
| Gỡ chia sẻ | ✔ (mọi share của nhóm) | ✔ chỉ share do **chính mình** tạo, hoặc tài liệu do **chính mình** nạp |

- **Lý do:** A biến owner thành nút thắt cổ chai vận hành; C thêm chiều quyền
  thứ ba cho một hệ thống đã chốt RBAC tĩnh, không tương xứng giá trị. B khớp
  câu chữ FR-29 ("người dùng **thuộc nhóm**") và vẫn an toàn vì điều kiện chặn
  thật nằm ở `requireVisible` — không ai chia sẻ được thứ mình không thấy.
- **Bổ sung:** cho phép **nhiều owner** trong một nhóm (`role_in_group=owner`);
  `group.owner_sub` giữ nghĩa "người tạo" (bất biến, dùng cho truy vết). Bất
  biến hệ thống: mỗi nhóm chưa xoá luôn có **≥ 1 owner**.
- **Hệ quả:** một member "vô tình" có thể phơi bày tài liệu nhạy cảm ra cả
  nhóm → bù bằng audit đầy đủ + quyền gỡ của owner (xem Rủi ro).

### Quyết định 3: Thông báo thành viên — in-app tối giản

- **Ngữ cảnh:** luồng FR-29 bước 3 có "thông báo cho thành viên nhóm"; hệ thống
  chưa có module notification nào (`grep` toàn backend: 0 kết quả).
- **Các phương án xem xét:** A) bỏ, coi là out-of-scope; B) bảng `notification`
  trong PostgreSQL + polling từ web; C) WebSocket/SSE realtime; D) email.
- **Quyết định:** B — bảng `notification (id, recipient_sub, type, payload_json,
  created_at, read_at)`, web polling **60 s**, chỉ 3 loại sự kiện:
  `group.member_added`, `group.member_removed`, `document.shared_to_group`.
- **Lý do:** D bất khả thi (mạng cách ly, không SMTP). C thêm hạ tầng/kết nối
  giữ mở cho một nhu cầu không realtime. A bỏ sót câu chữ SRS. B rẻ, không thêm
  thành phần hạ tầng, đủ đáp ứng tiêu chí chấp nhận.
- **Ràng buộc triển khai:** fan-out ghi **ngoài** transaction nghiệp vụ
  (best-effort, không rollback thao tác chia sẻ nếu lỗi); gộp
  (`document.shared_to_group`) theo lô khi chia sẻ hàng loạt → tối đa **1** thông
  báo/nhóm/lượt chia sẻ, không phải 1/tài liệu; TTL dọn sau 90 ngày.
- **Hệ quả:** trễ tối đa 60 s; đây là điểm cần khách hàng xác nhận (mục 7).

### Quyết định 4: Rời nhóm / gỡ thành viên khi tài liệu vẫn đang chia sẻ

- **Ngữ cảnh:** share gắn với **nhóm**, không gắn với người; cần định rõ số
  phận các share do người rời nhóm tạo ra.
- **Các phương án xem xét:** A) giữ nguyên mọi share; B) tự động gỡ các share do
  người đó tạo (`shared_by_sub`); C) hỏi lại người thao tác từng trường hợp.
- **Quyết định:** A. Rời nhóm/bị gỡ **không** ảnh hưởng bất kỳ share nào; người
  đó mất quyền xem tài liệu của nhóm **ngay lập tức** ở lượt truy vấn kế tiếp,
  trừ tài liệu do chính họ nạp (quy tắc `uploaded_by` của FR-08).
- **Lý do:** B gây mất dữ liệu chung ngoài ý muốn (một người nghỉ việc làm cả
  nhóm mất tài liệu đang dùng); C không khả thi khi số share lớn.
- **Bổ sung:** owner **cuối cùng** rời nhóm → từ chối (409), phải chuyển quyền
  owner cho thành viên khác trước. Tài khoản bị `locked`/`disabled`
  (`account-lifecycle`) → **giữ nguyên** membership, chặn ở tầng đăng nhập, không
  tự gỡ khỏi nhóm.

### Quyết định 5: Xoá nhóm — soft delete

- **Ngữ cảnh:** nhóm bị xoá trong khi còn N tài liệu đang chia sẻ và audit đã
  tham chiếu `group_id`.
- **Các phương án xem xét:** A) hard delete cascade; B) chặn xoá nếu còn share;
  C) soft delete (`deleted_at`), share giữ nguyên nhưng mất hiệu lực.
- **Quyết định:** C. `DELETE /groups/{id}` (chỉ owner) đặt `deleted_at`; lớp
  filter phạm vi bổ sung điều kiện `group.deleted_at IS NULL` → toàn bộ tài
  liệu của nhóm lập tức không còn hiển thị qua nhóm đó. Không có chức năng khôi
  phục nhóm ở MVP.
- **Lý do:** A làm mất khả năng tra cứu ngược audit (bản ghi log chỉ giữ
  `group_id` dạng chuỗi, không còn tên nhóm) và mất dữ liệu thống kê lịch sử;
  B khiến nhóm không bao giờ xoá được trong thực tế.
- **Hệ quả:** mọi truy vấn nhóm phải đi qua lớp filter dùng chung có
  `deleted_at IS NULL`; unique tên nhóm phải là **partial unique index**
  (`lower(name) WHERE deleted_at IS NULL`); cần job dọn dữ liệu nhóm đã xoá
  (đề xuất giữ vĩnh viễn ở MVP, chỉ ẩn).

### Quyết định 6: Tương tác với hạn mức lưu trữ cá nhân (FR-14)

- **Ngữ cảnh:** `personal-storage-quota.md` định nghĩa quota owner-scoped theo
  `storage_quota_bytes` của từng tài khoản.
- **Các phương án xem xét:** A) chỉ tính cho người **nạp** tài liệu
  (`uploaded_by`); B) tính cho mọi thành viên nhóm nhận chia sẻ; C) nhóm có
  quota riêng.
- **Quyết định:** A. Chia sẻ/gỡ chia sẻ **không thay đổi** dung lượng đã dùng
  của bất kỳ ai; tài liệu luôn tính vào quota người nạp cho tới khi bị xoá cứng.
- **Lý do:** chia sẻ chỉ là liên kết siêu dữ liệu (FR-29) — không phát sinh byte
  vật lý; B/C sẽ tính trùng cùng một tệp nhiều lần và mâu thuẫn với định nghĩa
  "không gian cá nhân" của FR-14.
- **Hệ quả:** người nạp không thể "đẩy" chi phí lưu trữ sang nhóm; khi người nạp
  xoá tài liệu, quota được giải phóng và **mọi nhóm mất tài liệu đó** — luồng
  cảnh báo trước khi xoá thuộc `delete-restore-trash`.
- **Ghi chú làm rõ (2026-09-29, `personal-to-warehouse.md` QĐ 6):** "quota"
  ở đây là **dung lượng kho chung**, không phải hạn mức cá nhân FR-14
  (`storage_quota_bytes`). Tài liệu kho không trừ vào hạn mức cá nhân của
  người nạp — hạn mức cá nhân chỉ tính `personal_file`.

### Quyết định 7: Danh bạ người dùng để chọn thành viên

- **Ngữ cảnh:** thêm thành viên hiện phải nhập thủ công `user_sub` (UUID
  Keycloak) — không dùng được trên UI; API tra cứu tài khoản hiện chỉ mở cho R3.
- **Các phương án xem xét:** A) mở `admin/accounts` cho R1/R2; B) endpoint
  danh bạ riêng, trường tối thiểu; C) không làm, R3 cung cấp `sub` ngoài hệ
  thống.
- **Quyết định:** B — `GET /users/directory?q=` cho R1/R2, chỉ trả về
  `sub`, `username`, `display_name`, `role`; chỉ tài khoản `active`; bắt buộc
  `q` ≥ 2 ký tự, trả tối đa 20 kết quả, có audit `user.directory_search`.
- **Lý do:** A rò rỉ trường quản trị (trạng thái khoá, quota, lịch sử); C không
  dùng được trên thực tế.
- **Hệ quả:** chấp nhận R1/R2 biết được danh sách người dùng nội bộ — rủi ro
  thấp trong mạng cách ly, được bù bằng audit.

### Quyết định 8: Điểm giao tiếp với chỉ mục tìm kiếm

- **Ngữ cảnh:** FR-29 yêu cầu "cập nhật chỉ mục"; FR-25/FR-30 lọc theo nhóm.
- **Quyết định:** document index giữ field `group_ids[]`; **nguồn chân lý về
  quyền vẫn là PostgreSQL**. Khi **chia sẻ** → cập nhật index bất đồng bộ (≤ 60 s).
  Khi **gỡ chia sẻ / xoá nhóm** → cập nhật index đồng bộ trong luồng request;
  nếu cập nhật thất bại → trả lỗi và rollback thao tác gỡ.
- **Lý do:** trễ khi thêm quyền chỉ gây bất tiện; trễ khi **thu hồi** quyền là
  lỗ hổng rò rỉ dữ liệu — phải fail-closed.
- **Hệ quả:** `search-fulltext`/`ai-search-infra` phải cung cấp API cập nhật
  đồng bộ cho nhánh thu hồi; ghi rõ ràng buộc này trong handoff sang 2 slug đó.

## 5. Tech stack đề xuất

- Backend: Spring Boot, mở rộng module `groups/` hiện có (`GroupsService`,
  `GroupsController`, `DocumentSharesController`); JPA Specification tái dùng
  `BusinessDataScope`/`DocumentScopeGuard`, không viết điều kiện phạm vi mới.
- Frontend: Next.js (`web/`) — trang `/groups` (danh sách/chi tiết/thành viên),
  panel "Chia sẻ" trên `web/src/app/documents/[id]`, badge thông báo polling.
- Data store: PostgreSQL. Bổ sung so với `rbac-group-scope`:
  `group.deleted_at`; partial unique index `lower(name) WHERE deleted_at IS NULL`;
  index `document_group_share(group_id, shared_at)`; bảng mới `notification`.
- Auth: Keycloak (không đổi), role `CAN_BO`/`LANH_DAO`.
- Infra/Deploy: **không thêm thành phần mới**.

## 6. Rủi ro

| Rủi ro | Mức độ | Giảm thiểu |
|---|---|---|
| Mọi thành viên được chia sẻ (Quyết định 2) → tài liệu nhạy cảm bị phơi bày ngoài ý định của người nạp | Cao | Audit đầy đủ `document.share` kèm `shared_by`; owner + người nạp gỡ được bất kỳ share nào; UI hiển thị rõ "N nhóm đang thấy tài liệu này" trước khi xác nhận |
| Gỡ chia sẻ ở DB thành công nhưng chỉ mục search chưa cập nhật → dữ liệu vẫn lộ trong kết quả tìm kiếm | Cao | Quyết định 8: nhánh thu hồi cập nhật index đồng bộ, thất bại thì rollback; job đối soát DB↔index định kỳ |
| Quên điều kiện `group.deleted_at IS NULL` ở một entrypoint sau khi thêm soft delete | Cao | Đặt điều kiện **bên trong** lớp filter dùng chung (`BusinessDataScope`), cấm truy vấn `group` trực tiếp ngoài repository chuyên trách; test hồi quy fail-closed |
| Fan-out thông báo khi chia sẻ 100 tài liệu × nhóm 200 thành viên → 20.000 bản ghi/lượt | Trung bình | Quyết định 3: gộp 1 thông báo/nhóm/lượt; ghi ngoài transaction; TTL 90 ngày |
| Nhóm mồ côi (owner cuối rời/bị vô hiệu hoá) → không ai quản trị được nhóm | Trung bình | Chặn owner cuối rời nhóm (409); báo cáo định kỳ nhóm có owner ở trạng thái `disabled` để R1/R2 xử lý |
| Số nhóm phình do dùng nhóm thay cho chia sẻ cá nhân (hệ quả Quyết định 1) | Thấp | Giới hạn 1.000 nhóm; theo dõi số nhóm ở báo cáo vận hành; xem lại nhu cầu chia sẻ cá nhân ở phiên bản sau |
| API danh bạ lộ danh sách người dùng nội bộ | Thấp | Trường tối thiểu, chỉ tài khoản active, bắt buộc từ khoá ≥ 2 ký tự, giới hạn 20 kết quả, có audit |

## 7. Câu hỏi mở

Tất cả đã chốt với khách hàng (2026-08-16):

- [x] **Thông báo thành viên:** chốt phương án B — in-app, bảng PostgreSQL +
      polling 60 s, 3 loại sự kiện.
- [x] **Vai trò owner/member map quyền chia sẻ:** chốt ma trận ở Quyết định 2 —
      mọi thành viên đều chia sẻ được.
- [x] **Nhiều owner trong một nhóm:** chốt cho phép nhiều owner đồng thời
      (không giới hạn 1 owner/nhóm, không cần thao tác "chuyển quyền chủ
      nhóm" tường minh).
- [x] **Người nạp tài liệu gỡ share do người khác tạo:** chốt **có** — người
      nạp luôn gỡ được mọi share của tài liệu mình nạp.
- [x] **Khôi phục nhóm đã xoá:** chốt **không** làm ở MVP — soft delete chỉ để
      giữ dữ liệu tra cứu audit/thống kê, không có UI/API khôi phục.
- [x] **Unique tên nhóm:** chốt unique **toàn hệ thống**, không phân biệt
      hoa/thường, trong các nhóm chưa xoá.
- [x] **Giới hạn số** (200 thành viên/nhóm, 200 nhóm/người dùng, 50 nhóm/tài
      liệu, 1.000 nhóm toàn hệ thống): chốt **hard-code**, không đưa vào
      `system-config` ở MVP.
- [x] **Hiển thị `shared_by`:** chốt **mọi thành viên** trong nhóm đều xem
      được ai đã chia sẻ một tài liệu vào nhóm.
