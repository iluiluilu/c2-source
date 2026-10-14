# Architecture: classification-notes

> File: `docs/01-sa/architecture/classification-notes.md` — Owner: SA
> Status: Approved

## 1. Bối cảnh / Vấn đề

- FR-15 (`docs/dac_ta_yeu_cau_chuc_nang_phi_chuc_nang.adoc` mục 2.13): R1
  gán/sửa nhãn cho tài liệu **trong kho dùng chung** theo 2 tiêu chí —
  *chủ đề* và *loại tài liệu*; lọc và báo cáo được theo nhãn. R2 chỉ đọc.
- FR-16: ghi chú (văn bản) cho tệp dữ liệu — thêm **ngay khi nạp** (FR-11) và
  thêm/sửa cho tệp bất kỳ **trong phạm vi quyền** (FR-08). R1 thêm/sửa, R2 xem.
- Hiện trạng code (đã kiểm chứng):
  - **Đã có:** bảng `tag(kind,label)` + `document_tag` (n-n, `V4__init_tags.sql`),
    `TagsService`/`TagsController`/`DocumentTagsController`; bảng `document_note`
    (`V5`), `NotesService`/`NotesController` (add/edit, **không có DELETE** — cố
    ý), optimistic lock qua `expectedUpdatedAt`, `NOTE_MAX_LENGTH = 4000`; ghi
    chú khi nạp đã nối vào `DocumentsService.ingest(..., note)`; lọc theo
    `tagId` đã có ở `DocumentsService.list` qua `DocumentSpecifications.hasTagId`.
  - **Cần sửa/bổ sung:** `TagKinds` còn `unit` và `secrecy` (trái SRS v1.1/v2.1);
    `TagsService.assign/unassign/listForDocument` **chưa đi qua**
    `DocumentScopeGuard` (notes đã đi qua) → lỗ hổng FR-08; chưa có ràng buộc
    "1 loại tài liệu/tài liệu"; chưa có hợp đồng phát sự kiện cho lập chỉ mục
    (FR-22/23); chưa có API tổng hợp theo nhãn cho báo cáo.
- **In-scope:** mô hình taxonomy nhãn, quan hệ nhãn↔tài liệu, quyền ghi/đọc,
  mô hình ghi chú + lịch sử, hợp đồng dữ liệu cho tìm kiếm và báo cáo.
- **Out-of-scope:** engine/mapping tìm kiếm (để `ai-search-infra` /
  `search-fulltext`); định dạng báo cáo (để `export-reporting`); nhãn cho tệp
  cá nhân (FR-14 — không áp dụng); UI chi tiết (BA/DEV).

## 2. Ràng buộc (Constraints)

- **Chỉ áp dụng cho kho dùng chung.** Tệp cá nhân nằm ở bảng `personal_file`
  (tách khỏi `document`) và không được lập chỉ mục (FR-14) → không gắn nhãn.
- **Nhãn KHÔNG phải cơ chế phân quyền** (SRS v1.1 đã loại "mức độ mật"; v2.1
  bỏ "đơn vị"). Phạm vi truy cập chỉ đến từ nhóm (`rbac-group-scope.md`
  Quyết định 3). Không tái giới thiệu `secrecy`/`unit` dưới bất kỳ dạng nào.
- Ma trận vai trò (SRS mục 3): FR-15 R1=X, R2=R, R3 **không** tham gia → danh
  mục nhãn **không** do R3 quản trị. FR-16 R1=X, R2=R.
- Mọi thao tác gán/sửa nhãn, thêm/sửa ghi chú phải ghi nhật ký theo
  `audit-logging.md` (Quyết định 3 — gọi ở tầng service; Quyết định 4 —
  taxonomy `<domain>.<verb>`, metadata allowlist, **không** đưa nội dung ghi
  chú vào `audit_log`).
- Ghi chú không được làm thay đổi tệp gốc (NFR-05).

## 3. NFR (Non-Functional Requirements)

| Loại | Yêu cầu |
|---|---|
| Performance | Gán/gỡ nhãn, lưu ghi chú ≤ 1 s p95. Lọc danh sách tài liệu theo nhãn ≤ 2 s p95 (NFR-01), dùng index `document_tag(tag_id)` đã có + phân trang. Danh mục nhãn đọc từ cache (Redis, theo `system-config.md` Quyết định 4). |
| Security | Mọi endpoint đọc/ghi nhãn & ghi chú đi qua lớp filter phạm vi dùng chung (`DocumentScopeGuard`), fail-closed. Nhãn không cấp/thu quyền. Nội dung ghi chú không lọt vào `audit_log`. |
| Scalability | Quy mô mục tiêu: ≤ ~500 nhãn chủ đề, ≤ 10 nhãn/tài liệu, ≤ 20 ghi chú/tài liệu; ghi chú ≤ 4000 ký tự (giữ nguyên `NOTE_MAX_LENGTH`). |
| Availability | Lỗi ở kênh phát sự kiện lập chỉ mục **không** được làm fail thao tác gán nhãn/ghi chú (ghi outbox, retry). |
| Khác | Truy vết: mỗi thay đổi nhãn/ghi chú trả lời được ai/lúc nào/trên tài liệu nào. Xung đột sửa đồng thời → 409 kèm thông báo (đã có). |

## 4. Phương án & Quyết định (ADR)

### Quyết định 1: Taxonomy có kiểu (typed tag), 2 kiểu cố định

- **Ngữ cảnh:** FR-15 nêu đúng 2 tiêu chí (chủ đề, loại tài liệu). Code hiện
  có `kind` tự do gồm 4 giá trị, trong đó 2 giá trị đã bị SRS loại bỏ.
- **Các phương án xem xét:**
  - A) Tag phẳng tự do (một không gian nhãn, không phân kiểu).
  - B) Typed tag với tập `kind` **cố định trong code** = `{topic, doc_type}`.
  - C) Typed tag với `kind` do người dùng tự định nghĩa (meta-taxonomy).
- **Quyết định:** **B**.
- **Lý do:** FR-15 yêu cầu lọc/báo cáo **theo từng tiêu chí** ("theo chủ đề",
  "theo loại tài liệu") — tag phẳng (A) không phân biệt được, buộc suy đoán từ
  chuỗi nhãn. C vượt phạm vi SRS (không FR nào yêu cầu tạo tiêu chí mới) và
  mở đường tái sinh các tiêu chí đã bị loại bỏ (mật/đơn vị).
- **Hệ quả:** sửa `TagKinds` → chỉ còn `topic`, `doc_type`; cần migration
  **xoá dữ liệu và liên kết** của `kind IN ('unit','secrecy')`, đồng thời đổi
  `doc-type` → `doc_type` cho nhất quán. Thêm `kind` mới = đổi code (có chủ ý).

### Quyết định 2: Danh mục nhãn — đóng có kiểm soát, R1 quản lý; tạo tuỳ tiện là tham số cấu hình

- **Ngữ cảnh:** FR-15 điều kiện tiên quyết "có danh mục nhãn"; ngoại lệ "nhãn
  không tồn tại → tạo mới **hoặc** từ chối **theo cấu hình**". Hiện tại mọi R1
  đều tạo/xoá được nhãn tự do → nguy cơ phân mảnh danh mục.
- **Quyết định (đã chốt phương án lai):**
  - `doc_type`: **danh mục đóng**, seed cố định `văn bản / hình ảnh / âm thanh
    / video` (khớp 4 nhóm định dạng ở `data-ingestion.md` Quyết định 2). Không
    có API tạo/xoá nhãn `doc_type`.
  - `topic`: **danh mục mở có kiểm soát** — R1 quản lý qua màn hình danh mục
    riêng (tạo/đổi tên/ngừng dùng). **Không xoá cứng** nhãn đã gắn: thêm cột
    `active`, ngừng dùng thay vì DELETE (giữ tính toàn vẹn thống kê lịch sử).
  - Tạo nhãn *ngay tại lúc gắn* (ad-hoc) do tham số `system_config`
    `classification.allow_adhoc_topic_tag`, **mặc định `false`** (từ chối, báo
    "nhãn không tồn tại") — đúng ngoại lệ FR-15, mô hình hybrid ở
    `system-config.md` Quyết định 1.
- **Lý do:** R3 không có quyền ở FR-15 nên danh mục phải do R1 tự quản; mặc
  định `false` giữ chất lượng dữ liệu để báo cáo theo nhãn có ý nghĩa; vẫn bật
  được khi nghiệp vụ cần linh hoạt.
- **Hệ quả:** cần màn hình quản lý danh mục nhãn (mới) + tham số cấu hình mới;
  `DELETE /tags/{id}` hiện tại chuyển thành "ngừng dùng" (soft-deactivate).

### Quyết định 3: Quan hệ nhãn ↔ tài liệu — n-n phẳng, toàn cục, không phân cấp

- **Ngữ cảnh:** cần chốt bậc nhãn (cây/phẳng), phạm vi danh mục (toàn cục hay
  theo kho/nhóm) và số nhãn cùng kiểu trên một tài liệu.
- **Quyết định:**
  - Giữ n-n `document_tag` hiện có; **không phân cấp** nhãn (không `parent_id`).
  - Danh mục nhãn **toàn cục**, không scope theo kho/thư mục/nhóm.
  - Số lượng: `topic` nhiều nhãn/tài liệu; `doc_type` **đúng 1** nhãn/tài liệu.
  - Denormalize `tag_kind` vào `document_tag` để ép ràng buộc "1 `doc_type`"
    bằng partial unique index thay vì kiểm tra ở tầng ứng dụng.
- **Lý do:** phân cấp nhãn chỉ có giá trị khi taxonomy lớn (không đúng với quy
  mô ~500 nhãn) và làm phức tạp truy vấn "lấy cả nhánh con"; scope danh mục
  theo kho/nhóm sẽ tạo khái niệm phạm vi thứ hai — đi ngược nguyên tắc đã chốt
  ở `warehouse-structure.md` Quyết định 2 và `rbac-group-scope.md`.
- **Hệ quả:** cần giữ đồng bộ `tag_kind` khi đổi kiểu nhãn (thực tế kiểu nhãn
  bất biến sau khi tạo → chấp nhận được).

### Quyết định 4: Ranh giới nhãn ↔ phân quyền (FR-08) và điểm áp filter còn thiếu

- **Ngữ cảnh:** SRS v1.1 minh định nhãn phục vụ phân loại/tìm kiếm, **không**
  phải cơ chế truy cập. Nhưng code hiện tại lại **thiếu** filter phạm vi ở
  chính module tags.
- **Quyết định:**
  - **Cấm** mọi logic đọc nhãn để quyết định quyền truy cập; nhãn chỉ vào
    mệnh đề lọc/hiển thị, luôn **sau** filter phạm vi nhóm.
  - Bổ sung `DocumentScopeGuard.requireVisible()` vào
    `TagsService.assign/unassign/listForDocument` (giống `NotesService` đã
    làm) → gán/xem nhãn ngoài phạm vi bị từ chối và ghi nhật ký (FR-09).
  - Danh mục nhãn (`GET /tags`) **không** chứa dữ liệu nghiệp vụ nhạy cảm →
    đọc được bởi R1/R2 không cần filter phạm vi; nhưng **số đếm tài liệu theo
    nhãn** thì phải tính sau filter phạm vi của người gọi.
- **Lý do:** thiếu guard ở tags là rò rỉ metadata (biết tài liệu tồn tại +
  chủ đề của nó) đúng loại rủi ro "bỏ sót entrypoint" đã cảnh báo ở
  `rbac-group-scope.md` mục 6.
- **Hệ quả:** hai người dùng khác phạm vi có thể thấy cùng danh mục nhãn nhưng
  số liệu thống kê khác nhau — cần nêu rõ trong AC để không bị coi là lỗi.

### Quyết định 5: Mô hình ghi chú — nhiều ghi chú/tài liệu, sửa theo phạm vi, lịch sử dựa audit

- **Ngữ cảnh:** FR-16 nói "thêm ghi chú **mới** hoặc chỉnh sửa ghi chú sẵn có
  … cho bất kỳ tệp thuộc **phạm vi quyền** của mình" và "lịch sử/vết chỉnh sửa
  được ghi nhận".
- **Quyết định:**
  - **Nhiều ghi chú trên một tài liệu** (giữ mô hình `document_note` hiện có),
    không phải một trường ghi chú duy nhất.
  - Quyền sửa: **mọi R1 trong phạm vi** sửa được ghi chú của tài liệu đó
    (không giới hạn tác giả) — bám đúng chữ "trong phạm vi quyền" của SRS. R2
    chỉ đọc. R3 không đọc nội dung (NFR-04/07).
  - **Không xoá** ghi chú (giữ nguyên: không có route DELETE).
  - Lịch sử: **dựa audit log** (`note.create` / `note.update` với metadata chỉ
    gồm `documentId`, `noteId`) + hiển thị `updated_by/updated_at` trên UI.
    **Không** tạo bảng phiên bản nội dung ở giai đoạn này.
  - Xung đột sửa đồng thời: giữ optimistic lock `expectedUpdatedAt` → 409.
- **Lý do:** "vết chỉnh sửa" trong tiêu chí chấp nhận chỉ yêu cầu ai/lúc nào;
  audit_log đã là nguồn duy nhất cho lịch sử thay đổi (`audit-logging.md`
  Quyết định 2), thêm bảng phiên bản là cơ chế lịch sử thứ hai. Nội dung ghi
  chú không được phép nằm trong `audit_log` (Quyết định 4 của audit).
- **Hệ quả (trade-off):** **không khôi phục được nội dung ghi chú cũ** nếu bị
  sửa đè. Giảm thiểu: cho phép "thêm ghi chú mới" thay vì sửa đè trong hướng
  dẫn sử dụng. Nếu nghiệp vụ xác nhận cần khôi phục → bổ sung bảng
  `document_note_revision` append-only (xem mục 7).

### Quyết định 6: Gán `doc_type` tự động khi nạp — bắt buộc trên mọi tài liệu

- **Ngữ cảnh:** `doc_type` (văn bản/ảnh/âm thanh/video) suy ra được từ MIME
  type đã kiểm tra ở FR-12; bắt R1 gán tay là thao tác thừa và dễ sai.
- **Các phương án xem xét:** A) thủ công hoàn toàn; B) tự động từ MIME khi nạp,
  R1 sửa được; C) tự động và khoá cứng.
- **Quyết định:** **B**. Bảng ánh xạ `mime → doc_type` đi kèm
  `allowed_file_format` (`data-ingestion.md` Quyết định 2).
- **Lý do:** giảm công gán nhãn, đảm bảo mọi tài liệu luôn có `doc_type` cho
  báo cáo; vẫn cho sửa vì có trường hợp lệch (PDF chứa ảnh scan).
- **Hệ quả:** nghiệp vụ có thể hiểu nhầm `doc_type` = định dạng tệp; cần đặt
  nhãn hiển thị rõ ràng ở UI.
- **Xác nhận (2026-08-16):** `doc_type` **bắt buộc** trên mọi tài liệu (không
  chấp nhận "chưa phân loại") → thêm ràng buộc **NOT NULL** (qua bảng ánh xạ,
  không phải cột riêng — enforce ở tầng ứng dụng khi partial unique index đã
  đảm bảo tối đa 1 `doc_type`/tài liệu, cộng thêm kiểm tra "tồn tại đúng 1").
  Yêu cầu **backfill**: migration mới phải suy `doc_type` từ MIME lưu sẵn cho
  **toàn bộ tài liệu đã nạp trước đây**, chạy trước hoặc cùng migration bật
  Quyết định 6; tài liệu có MIME không map được vào 4 nhóm phải được liệt kê
  ra để R1 xử lý thủ công trước khi coi migration hoàn tất (không tự ý gán
  giá trị đoán).

### Quyết định 7: Hợp đồng dữ liệu cho lập chỉ mục tìm kiếm (FR-22/23) và báo cáo

- **Ngữ cảnh:** FR-16 nêu "nội dung ghi chú được lập chỉ mục để tìm kiếm"; hạ
  tầng tìm kiếm **chưa được đặc tả** (`ai-search-infra`/`search-fulltext`).
  Tài liệu này chỉ chốt **hợp đồng**, không chốt engine/mapping.
- **Quyết định:**
  - Module này **không gọi trực tiếp** search engine. Khi nhãn hoặc ghi chú
    thay đổi, ghi một bản ghi **outbox** trong cùng transaction nghiệp vụ, với
    sự kiện: `document.classification.changed` và `document.note.changed`,
    payload tối thiểu `{documentId, changedAt, changedBySub}`.
  - Trường **cam kết cung cấp** cho bên lập chỉ mục (đọc lại từ DB theo
    `documentId`): `tags[] = {kind, label}` và `notes[] = {noteId, body,
    createdAt, updatedAt}`.
  - Bên lập chỉ mục chịu trách nhiệm: mapping trường, phân tích tiếng Việt,
    quyền lọc kết quả theo phạm vi (áp dụng lại FR-08 tại thời điểm truy vấn —
    **không** nhúng ACL vào chỉ mục).
  - **Lọc theo nhãn ở MVP không phụ thuộc search engine**: dùng SQL
    (`DocumentSpecifications.hasTagId`, đã có) → FR-15 vẫn nghiệm thu được kể
    cả khi chỉ mục chưa sẵn sàng.
  - Báo cáo (FR-30/31, `export-reporting` chưa đặc tả): cam kết cung cấp API
    tổng hợp `count(document) GROUP BY tag` có lọc theo `kind`, khoảng thời
    gian, thư mục kho — **luôn tính sau filter phạm vi của người gọi**.
- **Lý do:** tách phụ thuộc theo hướng một chiều (classification → outbox →
  indexer) để hai slug tiến hoá độc lập; outbox trong cùng transaction đảm bảo
  không mất sự kiện mà không làm fail thao tác người dùng khi indexer chết.
- **Hệ quả:** chỉ mục **eventual consistency** (trễ vài giây/phút) — kết quả
  tìm kiếm theo ghi chú có thể chậm hơn dữ liệu thật; cần nêu trong AC.

## 5. Tech stack đề xuất

- Backend: Spring Boot 3 — mở rộng module `tags/` và `notes/` sẵn có; JPA
  Specification cho lọc theo nhãn (đã dùng); `DocumentScopeGuard` cho phạm vi.
- Frontend: Next.js — bổ sung màn hình quản lý danh mục nhãn (R1), panel
  nhãn + ghi chú ở trang chi tiết tài liệu, bộ lọc theo nhãn ở danh sách kho.
- Data store: PostgreSQL — `tag` (thêm `active`), `document_tag` (thêm
  `tag_kind` + partial unique index cho `doc_type`), `document_note` (giữ
  nguyên), bảng outbox sự kiện lập chỉ mục; Flyway migration mới (sau `V13`)
  gồm cả bước xoá `kind IN ('unit','secrecy')`. Redis cache danh mục nhãn.
- Infra/Deploy: không thay đổi.

## 6. Rủi ro

| Rủi ro | Mức độ | Giảm thiểu |
|---|---|---|
| `TagsService` chưa áp filter phạm vi → rò rỉ metadata tài liệu ngoài phạm vi | Cao | Quyết định 4: bắt buộc `DocumentScopeGuard` ở assign/unassign/list; test hồi quy cho từng endpoint |
| Người dùng dùng nhãn như "mức độ mật" (đặt nhãn kiểu "Mật/Nội bộ") rồi tưởng đã được bảo vệ | Cao | Ràng buộc ở Quyết định 1 (chỉ 2 `kind`); chặn tên nhãn thuộc danh sách từ khoá mật khi tạo; cảnh báo trên UI "nhãn không giới hạn quyền truy cập" |
| Danh mục `topic` phân mảnh (nhãn trùng nghĩa, sai chính tả) → báo cáo vô nghĩa | Trung bình | Mặc định tắt tạo ad-hoc (Quyết định 2), chuẩn hoá chuỗi (trim, gộp khoảng trắng), unique không phân biệt hoa/thường trên `(kind, label)` |
| Sửa đè ghi chú không khôi phục được nội dung cũ | Trung bình | Quyết định 5 + hướng dẫn "thêm ghi chú mới"; nâng cấp sang bảng revision nếu nghiệp vụ xác nhận cần |
| Migration xoá `unit`/`secrecy` làm mất liên kết đã gắn ở môi trường đang chạy | Trung bình | Kiểm kê số liên kết trước khi xoá; export CSV các liên kết bị xoá kèm migration; xác nhận đây là dữ liệu demo như trường hợp `casefiles` |
| `doc_type` bắt buộc (Quyết định 6, xác nhận 2026-08-16) nhưng tài liệu cũ chưa có, hoặc MIME không map được vào 4 nhóm | Trung bình | Migration backfill chạy trước khi bật ràng buộc bắt buộc; liệt kê tài liệu MIME không map được để R1 xử lý thủ công, không tự đoán giá trị |
| Ghi chú chứa dữ liệu nhạy cảm bị lập chỉ mục và lộ qua tìm kiếm cho người ngoài phạm vi | Trung bình | Quyết định 7: chỉ mục **không** nhúng ACL, luôn áp lại FR-08 khi truy vấn; ràng buộc này phải được `ai-search-infra` tiếp nhận |
| Outbox không được tiêu thụ (chưa có indexer) → bảng phình vô hạn | Thấp | Retention cho bản ghi outbox đã xử lý/quá hạn; giám sát độ trễ hàng đợi |

## 7. Câu hỏi mở

Tất cả câu hỏi dưới đây đã được người dùng xác nhận ngày **2026-08-16**; giữ
lại làm hồ sơ quyết định, đã phản ánh vào các mục ADR/Rủi ro tương ứng.

- [x] **Ghi chú có áp dụng cho tệp cá nhân (FR-14) không?** → **Không**, theo
      đề xuất SA (mục 1 "Out-of-scope").
- [x] **Có cần khôi phục nội dung ghi chú cũ không?** → **Không cần**, giữ
      nguyên Quyết định 5 (lịch sử chỉ dựa `audit_log`, không có bảng
      revision).
- [x] **Danh sách nhãn `topic` khởi tạo (seed)?** → **Để trống**, R1 tự xây
      danh mục dần qua màn hình quản lý (Quyết định 2 không cần seed `topic`,
      chỉ `doc_type` có seed cố định).
- [x] **Giá trị mặc định của `classification.allow_adhoc_topic_tag`?** →
      **`false`**, đúng đề xuất SA (Quyết định 2).
- [x] **Ai được ngừng dùng / đổi tên nhãn trong danh mục?** → **Mọi R1**,
      khớp ma trận vai trò SRS hiện có (FR-15: R1=X); không cần thêm vai trò
      "quản trị danh mục" riêng, không cần bổ sung FR mới.
- [x] **`doc_type` bắt buộc hay tuỳ chọn?** → **Bắt buộc** trên mọi tài liệu
      → cập nhật Quyết định 6 (thêm yêu cầu backfill dữ liệu cũ) và bảng Rủi
      ro (mục 6).
