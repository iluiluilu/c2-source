# Architecture: delete-restore-trash

> File: `docs/01-sa/architecture/delete-restore-trash.md` — Owner: SA
> Status: Approved (2026-09-09)

## 1. Bối cảnh / Vấn đề

- **FR-26 — Xóa dữ liệu (thùng rác):** R1 xóa tài liệu trong **kho dùng chung
  đã số hóa** theo phạm vi quyền (FR-08). Xóa luôn là **xóa mềm**: tệp gốc +
  toàn bộ dữ liệu phái sinh (OCR FR-17/18, STT FR-19/20, mốc thời gian) vào
  thùng rác, biến mất khỏi tìm kiếm/tra cứu (FR-22–FR-25), tự gỡ hiệu lực chia
  sẻ nhóm (FR-29), ghi nhật ký (FR-09), không phá vỡ mã băm/nhật ký (NFR-05).
- **FR-27 — Phục hồi dữ liệu đã xóa:** hai nhánh — **R1: phục hồi từ thùng
  rác** (in-scope), **R3: phục hồi từ bản sao lưu** (out-of-scope, xem dưới).
- **Hiện trạng code (đã khảo sát):**
  - **Đã có:** cột `document.deleted_at` (V9, *schema-only*, chưa code path nào
    ghi), index bộ phận `IDX_document_folder_active ... WHERE deleted_at IS
    NULL`, unique index `UQ_document_folder_relpath_name_clean` (chỉ áp cho
    `status='clean' AND deleted_at IS NULL`), quy ước "dung lượng kho đã dùng
    **có tính** cả rác" (V10), `AuditService` hash-chain append-only,
    `ObjectStorageService.deleteObject()` (hiện chỉ dùng cho bucket cá nhân).
  - **Chưa có (phải xây mới):** API/UI thùng rác, luồng xóa mềm & phục hồi,
    **bất kỳ scheduler nào** (không có `@Scheduled`/Quartz/ShedLock trong
    backend), job xóa cứng, xóa object trên bucket kho, phát sự kiện gỡ chỉ
    mục.
- **In-scope:** xóa mềm tài liệu kho + dữ liệu phái sinh; thùng rác (xem/khôi
  phục); retention & xóa cứng theo lịch; gỡ chỉ mục; gỡ hiệu lực chia sẻ nhóm;
  audit; xử lý xung đột khi phục hồi.
- **Out-of-scope:**
  - **FR-27 nhánh R3 (phục hồi từ sao lưu):** chỉ **dẫn chiếu** slug
    `backup-restore` (FR-34/35) — chưa tồn tại. Lý do: phục hồi từ sao lưu là
    thao tác cấp hạ tầng (snapshot ZFS/backup store), khác hoàn toàn về cơ chế
    và quyền (R3) so với thùng rác; không đặc tả ở đây để tránh 2 tài liệu chốt
    chồng nhau. Hợp đồng duy nhất slug này cam kết: dữ liệu đã **xóa cứng**
    không còn phục hồi được từ thùng rác → phải đi đường sao lưu.
  - Xóa tệp trong **không gian cá nhân** (FR-14 — đã hard delete, có
    `personal-storage-quota.md`), xóa **nhóm** (đã chốt ở
    `group-management-sharing.md` Quyết định 5, không có khôi phục nhóm), xóa
    tài khoản (`account-lifecycle`).

## 2. Ràng buộc (Constraints)

- Mạng cách ly; PostgreSQL + SeaweedFS + Redis đã có; OpenSearch/RabbitMQ
  theo `ai-search-infra.md` (Approved) — slug này **không** chọn lại hạ tầng.
- Đồng bộ DB→chỉ mục **bắt buộc** dùng transactional outbox (`ai-search-infra.md`
  Quyết định 7), độ trễ mục tiêu ≤ 5s ⇒ không thể coi chỉ mục là nguồn sự thật
  về "đã xóa". (Phần "relay + indexer" của QĐ 7 hiện chạy trong `backend-api`
  chứ không phải tiến trình riêng — xem "Sai lệch có chủ đích khi hiện thực
  hoá" tại QĐ 7. Ràng buộc ở gạch đầu dòng này **không đổi**: nó phụ thuộc vào
  tính eventually-consistent của chỉ mục, không phụ thuộc nơi đặt tiến trình.)
- Filter phạm vi FR-08 nằm trong **lớp filter dùng chung ở tầng repository**
  (`rbac-group-scope.md` Quyết định 3) ⇒ điều kiện `deleted_at IS NULL` phải đặt
  **bên trong** lớp đó, không rải rác ở service.
- NFR-05: tệp gốc & mã băm bất biến, nhật ký không sửa/xóa ⇒ xóa mềm **không
  được** đụng tới `sha256`/`storage_key`; xóa cứng **không** được xóa bản ghi
  audit tương ứng.
- NFR-04: R3 không mặc nhiên xem được nội dung nghiệp vụ ⇒ R3 không được duyệt
  nội dung thùng rác.
- Retention lấy từ `system-config.md`: `retention.trash_retention_days` = **365**
  (tham số nhạy cảm, sửa phải xác nhận 2 bước).
- Backend hiện chạy 1 instance nhưng thiết kế job phải chịu được nhiều instance.
- Tài liệu chi tiết về chỉ mục toàn văn/ngữ nghĩa (`search-fulltext.md`,
  `search-semantic.md`) và nhật ký (`audit-logging.md`) đang **Draft** ⇒ chỉ dẫn
  chiếu **cơ chế nền đã chốt** ở `ai-search-infra.md`, không dựa vào chúng.

## 3. NFR (Non-Functional Requirements)

| Loại | Yêu cầu |
|---|---|
| Performance | Xóa mềm 1 tài liệu: p95 ≤ 500ms (chỉ UPDATE cờ + ghi outbox + audit). Xóa mềm hàng loạt ≤ 500 tài liệu/lượt (khớp `ingest.max_files_per_batch`): p95 ≤ 5s, chạy trong 1 transaction. Mở thùng rác/phân trang: p95 ≤ 1s. Phục hồi 1 tài liệu ≤ 1s. Job xóa cứng chạy ngoài giờ, không làm chậm giao dịch người dùng |
| Security | Fail-closed: thiếu ngữ cảnh người dùng → từ chối. Xóa/phục hồi bắt buộc qua filter FR-08. Ngay khi commit xóa mềm, mọi entrypoint đọc (kể cả kết quả trả về từ OpenSearch) phải **không** còn hiển thị nội dung — bảo đảm bằng hậu kiểm ở DB (Quyết định 4). R3 không xem được nội dung thùng rác (NFR-04) |
| Scalability | Thùng rác có thể tích lũy tới ~1 năm dữ liệu; job xóa cứng chạy theo lô (mặc định 200 tài liệu/lô, có nghỉ giữa lô), thời gian chạy tuyến tính theo số bản ghi hết hạn, không giữ transaction dài |
| Availability | Job xóa cứng **idempotent + resumable**: máy chủ chết giữa chừng, lượt sau chạy tiếp không mất/không xóa nhầm; lỗi ở 1 tài liệu không chặn cả lô. Search/RabbitMQ chết không được chặn thao tác xóa mềm (đã ghi outbox thì gỡ chỉ mục sẽ xảy ra khi phục hồi dịch vụ) |
| Khác | Retention mặc định 365 ngày; sai lệch DB↔chỉ mục ≤ 0,1% (job đối soát của `ai-search-infra`); mọi hành động xóa mềm/phục hồi/xóa cứng đều có bản ghi audit tương ứng 1-1 |

## 4. Phương án & Quyết định (ADR)

### Quyết định 1: Mô hình lưu trữ thùng rác (FR-26)

- **Ngữ cảnh:** thùng rác nằm cùng bảng nghiệp vụ hay tách bảng/kho riêng (kể
  cả object trên SeaweedFS)?
- **Các phương án xem xét:** A) cờ soft-delete tại chỗ trên `document`; B) bảng
  `trash_item` riêng + di chuyển bản ghi; C) như B và **di chuyển cả object**
  sang bucket `trash` trên SeaweedFS.
- **Quyết định:** **A** — giữ nguyên bản ghi và **giữ nguyên object tại đúng
  `storage_key` cũ trong bucket kho**. Bổ sung trên `document`: `deleted_at`
  (đã có), `deleted_by_sub`, `deleted_reason` (tùy chọn), `purge_after`
  (= `deleted_at + retention` **chốt cứng tại thời điểm xóa**). Thêm bảng
  `trash_batch(id, actor_sub, created_at, item_count)` để một lượt xóa nhiều
  tài liệu phục hồi lại được **theo lô**.
- **Lý do:** B/C phải chuyển bản ghi + FK (`document_group_share`, dữ liệu phái
  sinh, audit `resource_id`) → phá vỡ tham chiếu và làm phục hồi phức tạp; C còn
  vi phạm tinh thần NFR-05 (copy/move object làm thay đổi vị trí bằng chứng, tốn
  I/O bằng toàn bộ dung lượng xóa). A tận dụng đúng cột/index **đã có sẵn** (V9)
  và quy ước "rác vẫn tính dung lượng" (V10).
- **Hệ quả:** mọi truy vấn nghiệp vụ phải có `deleted_at IS NULL` — đặt trong
  lớp filter dùng chung, cấm truy vấn `document` ngoài repository chuyên trách.
  `purge_after` chốt cứng ⇒ đổi tham số retention **không** hồi tố các item đang
  ở trong thùng rác (an toàn, dễ giải thích cho người dùng). Rác **vẫn chiếm**
  dung lượng kho (cảnh báo 85% / chặn 95% của `warehouse-structure.md`) — UI phải
  hiển thị "dung lượng thùng rác" để R1 hiểu vì sao kho đầy. Hạn mức **cá nhân**
  (FR-14) không bị ảnh hưởng vì kho dùng chung không tính vào quota cá nhân.

### Quyết định 2: Phạm vi lan tỏa của một thao tác xóa (tệp gốc + phái sinh)

- **Ngữ cảnh:** FR-26 bắt buộc xóa tệp gốc kéo theo xóa mềm mọi dữ liệu phái
  sinh (OCR/STT/mốc thời gian, ghi chú FR-16, nhãn FR-15).
- **Các phương án xem xét:** A) nhân bản cờ `deleted_at` xuống mọi bảng phái
  sinh; B) **một cờ duy nhất trên `document`**, phái sinh chỉ truy cập qua tài
  liệu cha; C) xóa cứng ngay dữ liệu phái sinh (sinh lại được bằng OCR/STT).
- **Quyết định:** **B**. `document.deleted_at` là **nguồn sự thật duy nhất**;
  mọi bảng phái sinh (segment OCR/STT, mốc thời gian, ghi chú, gán nhãn, share)
  bắt buộc truy cập qua join với `document` trong lớp filter dùng chung.
- **Lý do:** A tạo hàng chục nghìn UPDATE cho một thao tác xóa (1 tài liệu có
  thể ~20 segment, xóa 500 tài liệu → hàng chục nghìn dòng) và dễ sót bảng mới;
  C làm phục hồi FR-27 không "về đúng trạng thái trước khi xóa" (mất bản OCR đã
  được kiểm duyệt FR-18) và tốn GPU chạy lại.
- **Hệ quả:** phái sinh **không** query độc lập được — ràng buộc thiết kế bắt
  buộc cho các slug `digitization-*`, `search-*`, `classification-notes`,
  `export-reporting`. Chỉ mục search vẫn phải xóa riêng (Quyết định 4).

### Quyết định 3: Gỡ chia sẻ nhóm khi xóa (FR-29)

- **Ngữ cảnh:** FR-26 yêu cầu "tự động gỡ liên kết chia sẻ khỏi các nhóm liên
  quan"; nhưng `group-management-sharing.md` (Approved, Quyết định 5) đã chốt
  **giữ** bản ghi `document_group_share` khi tài liệu soft-delete, chỉ xóa khi
  hard-delete.
- **Quyết định:** giữ nguyên quyết định đã Approved và diễn giải FR-26 ở mức
  **hiệu lực**: xóa mềm → tài liệu **biến mất khỏi mọi danh sách/tra cứu/thống
  kê theo nhóm ngay lập tức** (lớp filter dùng chung đã có `deleted_at IS NULL`)
  nhưng bản ghi share được **giữ để phục hồi nguyên trạng** (FR-27 "về trạng
  thái trước khi xóa"); **xóa cứng → xóa hẳn** bản ghi share cùng transaction.
- **Lý do:** thỏa cả hai: người dùng nhóm không còn thấy tài liệu (đúng ý FR-26)
  và phục hồi trả lại đúng danh sách nhóm cũ mà không cần người dùng chia sẻ lại.
- **Hệ quả:** UI xác nhận xóa phải cảnh báo "N nhóm sẽ mất quyền xem tài liệu
  này"; API danh sách nhóm không được đếm tài liệu đã xóa mềm.

### Quyết định 4: Gỡ chỉ mục search — đồng bộ hay bất đồng bộ

- **Ngữ cảnh:** rò rỉ nội dung đã xóa qua kết quả tìm kiếm là rủi ro bảo mật cao
  nhất của slug này; nhưng `ai-search-infra.md` đã chốt đồng bộ **eventual**
  (outbox → RabbitMQ → indexer, ≤ 5s), và OpenSearch chết không được phép
  chặn thao tác xóa.
- **Các phương án xem xét:** A) gọi xóa chỉ mục đồng bộ trong transaction, lỗi
  thì rollback; B) chỉ bất đồng bộ qua outbox; C) **bất đồng bộ qua outbox
  (priority) + hậu kiểm bắt buộc ở DB cho mọi kết quả tìm kiếm**.
- **Quyết định:** **C**. Xóa mềm ghi `outbox_event` kiểu `document.deleted`
  trong **cùng transaction** với UPDATE cờ, đẩy vào **priority queue** sự kiện
  thay đổi phạm vi (đã dự trù ở `ai-search-infra.md` Quyết định 3); song song,
  tầng tìm kiếm **bắt buộc** lọc lại danh sách ID trả về từ OpenSearch
  bằng một truy vấn DB có `deleted_at IS NULL` trước khi hiển thị (fail-closed:
  ID không tra được ở DB → loại bỏ).
- **Lý do:** A mâu thuẫn quyết định Approved và biến search thành điểm chết của
  chức năng xóa; B để hở cửa sổ ≤ 5s (và lâu hơn nếu indexer chết) — không chấp
  nhận với dữ liệu mật. C cho phép nhất quán tức thì về **hiệu lực** mà vẫn giữ
  kiến trúc bất đồng bộ.
- **Hệ quả (bù trừ lỗi):** indexer dùng `event_id` + `version` (idempotent,
  chống ghi đè lùi) sẵn có; sự kiện lỗi vào DLQ + retry backoff; job đối soát
  DB↔index định kỳ phát hiện "còn trong index nhưng `deleted_at IS NOT NULL`" và
  phát lại outbox. Chi phí: mỗi truy vấn tìm kiếm thêm một round-trip DB theo
  danh sách ID trang hiện tại (rẻ, index theo khóa chính).

### Quyết định 5: Xóa cứng theo lịch (retention 365 ngày)

- **Ngữ cảnh:** chưa có bất kỳ scheduler nào trong backend; cần xóa cứng an
  toàn, idempotent, kể cả object trên SeaweedFS.
- **Các phương án xem xét:** A) Spring `@Scheduled` thuần; B) `@Scheduled` +
  **ShedLock** (khóa qua PostgreSQL); C) Quartz cluster.
- **Quyết định:** **B**. Job `trash-purge` chạy hằng ngày (mặc định 03:00, sau
  cửa sổ sao lưu 02:00 của `system-config`), theo **máy trạng thái** trên
  `document`: `deleted → purging → purged`, xử lý theo lô 200 bản ghi:
  1. Chọn `WHERE deleted_at IS NOT NULL AND purge_after <= now() AND
     purge_state='deleted'` → đặt `purge_state='purging'` (khóa hàng, commit
     ngay) ⇒ chạy lại sau sự cố không chọn trùng.
  2. Xóa dữ liệu phái sinh + `document_group_share` + tài liệu khỏi chỉ mục
     (outbox `document.purged`).
  3. **Xóa object trên SeaweedFS** — chỉ khi không còn bản ghi `document` sống
     nào khác trỏ tới cùng `storage_key` (bảo vệ trường hợp khử trùng lặp theo
     `sha256`); object đã mất → coi là thành công (idempotent).
  4. Xóa/ẩn bản ghi `document`: **giữ lại một dòng bia mộ tối thiểu**
     (`id`, `sha256`, `original_name`, `deleted_at`, `purged_at`) để audit và
     tra cứu chứng cứ vẫn tham chiếu được; xóa các cột nội dung/metadata còn
     lại.
  5. Ghi audit `document.purge` với `actor = system`.
- **Lý do:** ShedLock rẻ (chỉ 1 bảng PostgreSQL), không kéo thêm Quartz; xóa
  object là thao tác **không thể hoàn tác** nên phải tách rõ trạng thái và
  chống chạy song song 2 instance.
- **Hệ quả:** cần **mở đường xóa cho bucket kho** trong `ObjectStorageService`
  — hiện chỉ bucket cá nhân được xóa (ghi chú NFR-09 "originals immutable").
  Bắt buộc: một phương thức riêng, chỉ gọi được từ job purge, có audit; không
  mở API xóa object công khai. Job cần metric + cảnh báo khi lô lỗi > ngưỡng.
- **Xóa cứng thủ công:** **không có ở MVP** — R1 xóa vĩnh viễn được sẽ phá
  nguyên tắc chứng cứ (NFR-05), còn R3 không được chạm nội dung nghiệp vụ
  (NFR-04). R3 chỉ điều chỉnh `retention.trash_retention_days` (2 bước). Nếu
  khách hàng yêu cầu sau, bổ sung dạng "R1 đề nghị → R2 duyệt" (xem mục 7).

### Quyết định 6: Quyền xóa / xem thùng rác / phục hồi (FR-26, FR-27)

- **Quyết định (bám ma trận vai trò mục 3 SRS: FR-26 = R1; FR-27 = R1 thùng rác,
  R3 sao lưu):**
  - **Xóa mềm:** R1, chỉ trên tài liệu **trong phạm vi FR-08** của mình
    (người nạp, hoặc thành viên nhóm được chia sẻ tài liệu đó). R2 (Lãnh đạo)
    **không** có quyền xóa; R3/R4 không có.
  - **Xem thùng rác:** mặc định **thùng rác cá nhân** — mỗi R1 chỉ thấy item do
    **chính mình** xóa (`deleted_by_sub = self`). Không có "thùng rác nhóm"
    dùng chung ở MVP: một item bị xóa mà cả nhóm thấy trong thùng rác sẽ mở
    kênh đọc lại nội dung đã xóa, ngược với mục đích FR-26.
  - **Phục hồi:** R1 đã xóa item đó; **thêm** người nạp tài liệu gốc
    (`uploader_sub`) — để tránh mồ côi khi người xóa nghỉ/bị vô hiệu hóa.
  - **R3:** thấy được **thống kê** thùng rác (số item, dung lượng, hạn xóa
    cứng) nhưng **không** thấy tên tệp/nội dung (NFR-04).
- **Lý do:** bám sát ma trận vai trò của SRS, hạn chế tối đa bề mặt lộ dữ liệu
  đã xóa.
- **Hệ quả:** cần báo cáo "item trong thùng rác của tài khoản đã bị vô hiệu hóa"
  để người nạp/quản trị xử lý trước hạn 365 ngày.

### Quyết định 7: Xử lý xung đột khi phục hồi (FR-27, nhánh R1)

- **Quyết định:**

  | Xung đột | Xử lý |
  |---|---|
  | Thư mục cha đã bị xóa (`document.folder_id = NULL` do `ON DELETE SET NULL` ở V9) | Chặn phục hồi "về chỗ cũ"; API yêu cầu **chọn thư mục đích** (`targetFolderId`); UI gợi ý thư mục gần nhất còn tồn tại |
  | Trùng tên (vi phạm `UQ_document_folder_relpath_name_clean`) | Trả 409 kèm lựa chọn: (a) đổi tên tự động `<tên> (khôi phục yyyyMMdd-HHmm)`, (b) chọn thư mục khác, (c) hủy. Không tự ghi đè |
  | Kho đầy (≥95% — `warehouse-structure.md`) | **Không chặn**: byte của item trong thùng rác đã được tính vào dung lượng đã dùng (V10) ⇒ phục hồi không làm tăng dung lượng |
  | Hạn mức cá nhân (FR-14) | Không liên quan — kho dùng chung không tính vào quota cá nhân |
  | Người nạp/người xóa đã bị vô hiệu hóa | Không chặn phục hồi (danh tính chỉ là dữ liệu lịch sử); nếu người xóa bị vô hiệu hóa thì người nạp là người phục hồi (Quyết định 6) |
  | Nhóm được chia sẻ đã bị xóa (`group.deleted_at`) | Phục hồi tài liệu thành công, share tới nhóm đó tự mất hiệu lực qua lớp filter — không báo lỗi, chỉ ghi cảnh báo trong kết quả |
  | Item đã quá hạn / đã xóa cứng | 410 Gone kèm hướng dẫn chuyển sang phục hồi từ sao lưu (slug `backup-restore`) |
  | Đang trong trạng thái `purging` | 409, yêu cầu thử lại sau (không cho phục hồi item đang bị xóa cứng dở) |

- **Cơ chế:** phục hồi = đảo cờ (`deleted_at=NULL`, xóa `purge_after`), khôi
  phục hiệu lực share, phát outbox `document.restored` để **lập chỉ mục lại**
  (không cần chạy lại OCR/STT nhờ Quyết định 2), tất cả trong 1 transaction.
  Phục hồi theo **lô** (`trash_batch`) hoặc từng item.

### Quyết định 8: Nhật ký (FR-09, NFR-05)

- **Quyết định:** dùng **`AuditService` hash-chain append-only đã có sẵn**
  (`hash = SHA256(fields || prevHash)`, DB trigger chặn UPDATE/DELETE) — không
  xây cơ chế log mới. Ghi **đồng bộ trong cùng transaction nghiệp vụ**, taxonomy
  `<domain>.<verb>`: `document.trash`, `document.restore`, `document.purge`
  (actor `system`), `document.trash.denied` cho thao tác bị từ chối.
  `metadata` tối thiểu: `documentId`, `folderId`, `batchId`, `groupIds` bị ảnh
  hưởng, `purgeAfter`. Xóa cứng **tuyệt đối không** xóa bản ghi audit liên quan.
- **Hệ quả:** audit tham chiếu `resource_id` = `document.id` phải còn tra được
  sau xóa cứng ⇒ bắt buộc giữ dòng bia mộ (Quyết định 5 bước 4).

## 5. Tech stack đề xuất

- Backend: Spring Boot (module mới `trash/` + mở rộng `documents/`,
  `warehouse/`); Spring `@Scheduled` + **ShedLock** (mới) cho job purge;
  `AuditService` (đã có); `outbox_event` + RabbitMQ (theo `ai-search-infra`).
- Frontend: Next.js — màn hình "Thùng rác" (danh sách, bộ lọc theo ngày xóa/hạn
  xóa cứng, phục hồi từng item/theo lô), dialog xác nhận xóa nêu rõ số nhóm bị
  ảnh hưởng và hạn 365 ngày; badge dung lượng thùng rác trong màn hình kho.
- Data store: PostgreSQL — `document`: `deleted_at` (đã có) + `deleted_by_sub`,
  `deleted_reason`, `purge_after`, `purge_state`, `purged_at`; bảng mới
  `trash_batch`, `shedlock`; index `(purge_state, purge_after)` và
  `(deleted_by_sub, deleted_at DESC) WHERE deleted_at IS NOT NULL`.
- Infra/Deploy: SeaweedFS (bổ sung đường xóa object bucket kho, chỉ cho job
  purge); OpenSearch gỡ chỉ mục qua indexer; không thêm thành phần hạ
  tầng mới.

## 6. Rủi ro

| Rủi ro | Mức độ | Giảm thiểu |
|---|---|---|
| Nội dung đã xóa vẫn xuất hiện trong kết quả tìm kiếm do chỉ mục trễ/indexer chết | Cao | Quyết định 4: hậu kiểm ID ở DB fail-closed + priority queue + job đối soát DB↔index |
| Xóa cứng nhầm/xóa quá tay (không thể hoàn tác) | Cao | Máy trạng thái `purging`, ShedLock chống chạy song song, `purge_after` chốt cứng lúc xóa, tham số retention là "nhạy cảm" (xác nhận 2 bước), dry-run + metric số item sắp xóa, audit đầy đủ |
| Xóa object trên SeaweedFS làm hỏng tài liệu khác dùng chung `storage_key` (khử trùng lặp theo `sha256`) | Cao | Kiểm tra tham chiếu trước khi xóa object (Quyết định 5 bước 3); test hồi quy cho trường hợp trùng `sha256` |
| Quên `deleted_at IS NULL` ở một entrypoint mới → lộ dữ liệu đã xóa | Cao | Đặt điều kiện trong lớp filter dùng chung, cấm query `document` ngoài repository chuyên trách, test hồi quy fail-closed |
| Thùng rác chiếm dung lượng kho suốt 365 ngày → kho chạm ngưỡng 95% chặn nạp mới | Trung bình | Hiển thị dung lượng thùng rác + cảnh báo riêng; R3 giảm được retention; báo cáo top item rác lớn nhất |
| Job purge chạy lâu/chết giữa chừng làm treo hàng loạt bản ghi ở `purging` | Trung bình | Timeout khôi phục trạng thái `purging` quá hạn về `deleted`, chạy lô nhỏ, cảnh báo khi lỗi vượt ngưỡng |
| Người dùng kỳ vọng "phục hồi từ sao lưu" ngay khi hết hạn thùng rác nhưng `backup-restore` chưa làm | Trung bình | Thông điệp lỗi 410 nêu rõ quy trình; ưu tiên hoàn thành slug `backup-restore` trước go-live |
| Không có thùng rác nhóm → người dùng khác không biết ai đã xóa tài liệu chung | Thấp | Nhật ký FR-10 tra được; cân nhắc thông báo tới nhóm khi tài liệu bị gỡ khỏi nhóm do xóa |

## 7. Câu hỏi mở

- [x] **Xóa cứng thủ công trước hạn 365 ngày:** **không làm ở MVP** — đã xác
      nhận với khách hàng (2026-08-16). Chỉ có xóa mềm + chờ hết hạn retention;
      nếu phát sinh nhu cầu sau, bổ sung dạng "R1 đề nghị → R2 duyệt".
- [x] **Phạm vi thùng rác:** **cá nhân** (chỉ người xóa thấy item mình xóa) —
      đã xác nhận (2026-08-16). Không có thùng rác theo nhóm ở MVP (Quyết
      định 6 giữ nguyên).
- [x] **Xóa mềm ở mức thư mục kho:** **không** trong scope slug này — đã xác
      nhận (2026-08-16). Phạm vi chỉ là **tài liệu**; xóa thư mục vẫn theo quy
      tắc hiện có ở `warehouse-structure.md` (chỉ thư mục rỗng/không còn dữ
      liệu hoạt động).
- [x] **Thông báo khi tài liệu chia sẻ bị xóa:** **chỉ ghi nhật ký**, không
      gửi thông báo in-app chủ động — đã xác nhận (2026-08-16). Thành viên
      nhóm tra cứu qua audit log (FR-10) nếu cần.
- [x] **Giờ chạy job purge vs cửa sổ sao lưu:** **đã trả lời tại
      `backup-restore.md` Quyết định 3** (xác nhận 2026-09-09) — giữ purge
      03:00, gate theo "điểm nhất quán của ngày đã tạo xong" (không chờ nhân
      bản NAS xong); cấm purge trong `[02:00, 02:00+δ]`; không thỏa gate ⇒ bỏ
      lượt, ghi audit `trash.purge.skipped`, cảnh báo R3. **Thứ tự triển
      khai đã chốt cùng ngày:** `backup-restore` (bảng `backup_run`, API
      `/internal/backup/runs`) đi **trước**, `delete-restore-trash`
      (`TrashPurgeJob`) đi **sau** — job purge chỉ bắt đầu code khi gate có
      dữ liệu thật để đọc, không dựa vào nhánh fail-closed tạm thời làm
      phương án chính (xem `docs/03-dev/specs/delete-restore-trash-02-xoa-cung-xung-dot.md`
      §7).
