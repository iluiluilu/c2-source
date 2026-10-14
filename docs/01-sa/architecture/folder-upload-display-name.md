# Architecture: folder-upload-display-name

> File: `docs/01-sa/architecture/folder-upload-display-name.md` — Owner: SA
> Status: Approved

Đầu vào: `docs/_handoff/folder-upload-display-name.md` (2026-09-30), gồm mục
"Cập nhật 2026-09-30" (**ghi đè** mục "Đã chốt" mâu thuẫn; Q1–Q9 và phương
án B đã chốt). File này tự đủ ý, không cần đọc bản trước.

## 1. Bối cảnh / Vấn đề

- Hiện trạng (đã xác minh trong code):
  - `web/src/app/upload/page.tsx`, `web/src/app/personal/page.tsx` dùng
    `<input webkitdirectory>` → Chrome/Edge hiện popup gốc "Upload N files to
    this site?", không tắt được bằng JS; mỗi lượt chỉ 1 thư mục.
  - Kho chung: `document.relative_path` **bỏ** tên thư mục gốc được chọn.
    Kho cá nhân: `personal_file` không có `relative_path` → bị làm phẳng.
  - Tên hiển thị = `original_name`; không có tên định danh đọc được nào khác.
  - `storage_key`: mỗi lần ingest luôn `putObject` với key mới
    `documents/<UUID.randomUUID()>` (kho cá nhân `<sub>/<UUID>`) → **1
    document – 1 object**, kể cả cùng `sha256`; `findBySha256AndStatus` chỉ
    trả `duplicatesBySha`, không tái sử dụng object.
  - Tìm kiếm index `document_name = original_name`.
  - Kho cá nhân → kho chung: `POST /ingest/from-personal`, server mở rộng
    thành `personal_ingest_item`, worker xử lý bất đồng bộ.
  - Dedup theo tên: trùng `(folder_id, relative_path, original_name)` với
    tài liệu `clean` → `SKIPPED ALREADY_EXISTS` (`DocumentsService` ~dòng
    198 và from-personal; unique `UQ_document_folder_relpath_name_clean`,
    V10). **Bị bỏ** (phương án B, QĐ2).
- Mục tiêu:
  1. Tải thư mục chỉ bằng kéo-thả, cho thả **nhiều thư mục + tệp lẻ lẫn** trong
     1 lượt, ở cả `/upload` (kho chung) và `/personal` (kho cá nhân).
  2. Mỗi tệp có một **tên lưu trên server** (`display_name`) duy nhất trong
     vị trí lưu, đọc được, do **client sinh** sau khi gọi **API kiểm tra
     tên** (chỉ tệp trùng mới đổi tên); server chỉ kiểm tra hợp lệ + unique;
     UI vẫn hiển thị tên gốc. Tệp trùng tên luôn được lưu thành bản mới.
- In-scope: FE kéo-thả; `display_name` cho `document` + `personal_file`;
  `relative_path` cho `personal_file`; API kiểm tra tên (`/ingest`,
  `/personal`, from-personal); module sinh tên client; `DisplayNameValidator`
  server; bỏ dedup theo tên; popup trùng tên khi khôi phục (QĐ6); tooltip;
  migration `V34`.
- Out-of-scope: đổi tên thủ công; tạo thư mục con thật từ `relative_path`;
  đổi `/ingest/batches*` và giao thức chunked **ngoài** field `displayName`
  và `relativePath` ở init personal; retry/backoff mạng, pipeline quét, AC-3a;
  dữ liệu cũ (**chưa go live**); dedup `sha256` (`duplicatesBySha` giữ
  nguyên); logic giữ object của `TrashPurgeService` (R12).

## 2. Ràng buộc (Constraints) — đã chốt

- C1. Chỉ kéo-thả (`DataTransferItem.webkitGetAsEntry()` đệ quy); bỏ hẳn
  `<input webkitdirectory>`. Nút "Chọn tệp" chỉ chọn tệp lẻ.
- C2. `relative_path` = đường dẫn từ **tên thư mục gốc được thả** tới thư mục
  chứa tệp (`HoSo/2024/a.pdf` → `HoSo/2024`; tệp trực tiếp trong `HoSo` →
  `HoSo`; tệp lẻ → `''`). Chỉ để hiển thị, **không** tạo thư mục con.
- C3. `original_name` = tên gốc, không đổi khi tải lên/chuyển kho. **Không
  còn dedup theo tên** (phương án B): tệp trùng tên cùng vị trí đi theo C4
  và luôn tạo bản mới. Ngoại lệ có chủ đích duy nhất: "Đổi tên" khi khôi
  phục thùng rác đổi cả `original_name` (QĐ6, R10).
- C4. Luồng chốt (Q5): client đọc danh sách tệp → gọi **API kiểm tra tên**
  → client sinh `display_name` → tải lên → tệp trùng bị server trả lỗi
  **riêng tệp đó** → client tự sinh lại (tối đa **3 lần/tệp**, Q6). Tệp
  **không trùng giữ nguyên tên gốc**; tệp trùng đổi thành
  `<tên gốc>_<yyyyMMddHHmmssSSS>-<random 6>.<đuôi>` (Q7). Không hậu tố
  "(2)". Server **không** sinh tên thay client (trừ khôi phục — QĐ6).
- C5. Vượt 512 ký tự → chỉ cắt phần **tên gốc**; giữ nguyên
  `_<timestamp>-<random>` và `.<đuôi>`.
- C6. Tìm kiếm theo tên chỉ khớp `original_name`.
- C7. UI hiển thị tên gốc; tooltip hiện `relative_path` + `display_name`.
  Tải về mang tên `original_name` (Q1). `storage_key` không hiện cho bất kỳ
  vai trò nào (Q2).
- C8. Không xử lý dữ liệu cũ (chưa có dữ liệu thật): không backfill/tương
  thích; `V34` tối thiểu.
- C9. Migration kế tiếp là `V34` (hiện có tới `V33__personal_to_warehouse`).
- C10. Kho cá nhân → kho chung dùng **cùng cơ chế** kiểm tra trùng trước +
  đổi tên như luồng tải lên (Q4).

## 3. NFR

| Loại | Yêu cầu |
|---|---|
| Performance | 1 lần gọi API kiểm tra tên/lượt (chia lô ≤ 1000 tệp/lần), tra bằng unique index. Sinh tên client O(1). Validate O(len). Duyệt cây kéo-thả không chặn UI (`readEntries` lặp tới khi rỗng). |
| Security | API kiểm tra tên cùng quyền với thao tác ghi đích, sai quyền → `403`; chỉ trả `taken` cho đúng tên gửi lên; giới hạn số tệp + rate limit (R3). `displayName` là đầu vào không tin cậy (QĐ4). `Content-Disposition` dùng `original_name`, `filename*=UTF-8''`. `storage_key` không lộ ra API/UI. |
| Scalability | Nhiều gốc/lượt vẫn tôn trọng AC-3a. Unique theo vị trí, không điểm tranh chấp toàn cục. Bỏ dedup theo tên → dung lượng/số hoá tăng khi tải lại (R4). |
| Availability | API kiểm tra tên lỗi/timeout → client **không chặn**: coi mọi tệp là trùng (gắn hậu tố), dựa vào unique + 409. |
| Khác | API kiểm tra chỉ là gợi ý; đúng đắn do unique + 409 + sinh lại (≤ 3 lần). Trùng tên có hậu tố cần cùng ms + cùng random 6 `[a-z0-9]` (≈ 4,6·10⁻¹⁰). |

## 4. Phương án & Quyết định (ADR)

### QĐ1: Tải thư mục chỉ qua kéo-thả, nhiều gốc/lượt (đã chốt)

- Vùng thả nhận `DataTransferItemList`; thư mục → duyệt đệ quy, tệp → tệp
  lẻ; gộp **1 batch** (giữ `/ingest/batches*`, `/personal/batches*`).
  `relative_path` từ `entry.fullPath` bỏ `/` đầu và tên tệp. 1 module FE
  dùng chung. Gọi `webkitGetAsEntry()` đồng bộ trong handler `drop`.

### QĐ2: Mô hình dữ liệu (`V34`) + bỏ dedup theo tên (phương án B)

- `document` + `display_name VARCHAR(512) NOT NULL`; unique **partial**
  `(COALESCE(folder_id, <sentinel>), relative_path, lower(display_name))
  WHERE deleted_at IS NULL` — theo vị trí lưu, **không tính hàng trong
  thùng rác** (đã chốt Q8, xem QĐ6); **không phân biệt hoa/thường** (Q9,
  giữ quyết định gốc của handoff): `lower()` trên chuỗi đã NFC, giá trị
  lưu giữ nguyên hoa/thường. **Cài đặt index:** `relative_path` (≤ 1024) +
  `display_name` (≤ 512) Unicode có thể vượt giới hạn dòng btree (~2,7 KB) →
  unique trên `md5(relative_path || chr(0) || lower(display_name))`
  (R14); name-check dùng cùng biểu thức. `PATCH /{id}/move` không đổi
  `folder_id` tài liệu → không va chạm.
- **Bỏ dedup theo tên:** xoá kiểm tra `findFirstByFolderIdAndRelativePath…`
  → `SKIPPED ALREADY_EXISTS` ở `/ingest` và from-personal; `V34` **drop**
  `UQ_document_folder_relpath_name_clean` (V10) — nếu giữ, bản thứ 2 cùng
  `original_name` sẽ vỡ constraint khi chuyển `clean`. Khôi phục kiểm trùng
  theo `display_name` (QĐ6).
- **Không unique toàn bảng:** tên phổ biến (`scan.pdf`) sẽ luôn bị đổi tên,
  và API kiểm tra tên lộ tên ở thư mục không có quyền (R3).
- `personal_file` + `relative_path VARCHAR(1024) NOT NULL DEFAULT ''` +
  `display_name VARCHAR(512) NOT NULL`; unique `(chủ sở hữu,
  COALESCE(folder_id, <sentinel>), relative_path, lower(display_name))`.
- `upload_session` + `display_name VARCHAR(512) NULL` (init, đổi được lúc
  commit); phiên personal dùng `relative_path`. Không unique ở bảng này.
- `personal_ingest_item` + `display_name VARCHAR(512) NULL` (QĐ5).
- Dữ liệu cũ (C8): không backfill; cho phép xoá sạch dữ liệu dev/stg trước
  `V34` (hoặc `display_name = original_name` một câu SQL).

### QĐ3: API kiểm tra tên + sinh `display_name` ở client

- **API:** `POST /ingest/name-check`, `POST /personal/name-check` — body
  `{folderId, files: [{relativePath, originalName}]}` (≤ 1000/lần); trả
  đúng thứ tự `[{taken: bool}]` (không trạng thái khác, không
  `ALREADY_EXISTS`). `taken` khi `lower(NFC(originalName))` trùng
  `lower(display_name)` của hàng cùng vị trí trong phạm vi unique (QĐ2).
  Chỉ đọc, không giữ chỗ.
- **Quy tắc sinh ở client (Q7):**
  - Không trùng → `display_name = NFC(original_name)`.
  - Trùng → tên có hậu tố (C4) khi: API trả `taken`; trùng
    `(relative_path, lower(NFC(tên)))` với tệp khác **trong cùng lượt**
    (`toLowerCase()` có thể lệch `lower()` PostgreSQL ở vài ký tự hiếm →
    unique + 409 là lưới cuối, R13); bị 409;
    hoặc API kiểm tra lỗi. Sinh lại sau 409 luôn dùng hậu tố mới.
  - Tải lại thư mục cũ → mọi tệp `taken` → lưu thành bản mới có hậu tố
    (không `SKIPPED`; hệ quả R4).
- **Module client** `displayName` (TS thuần):
  - Đuôi = phần sau dấu `.` cuối nếu vị trí `> 0` (`.env` → không đuôi;
    `a.tar.gz` → tên `a.tar`, đuôi `gz`). Không đuôi → `<tên>_<suffix>`.
  - `suffix = <yyyyMMddHHmmssSSS>-<random>`: timestamp ms theo
    `Asia/Ho_Chi_Minh`; random 6 `[a-z0-9]` **bắt buộc** từ
    `crypto.getRandomValues` (cấm `Math.random`). NFC tên gốc trước khi ghép.
  - Tổng > 512 code point → cắt `<tên>` theo code point (không cắt đôi
    surrogate/tổ hợp dấu); giữ `_<suffix>` và `.<đuôi>`. Đuôi > 16 code
    point → coi như không đuôi (còn ≥ 470 code point cho tên gốc: 512 − 25
    suffix − 17 đuôi).
- **Quy tắc tách đuôi + cắt 512 dùng chung** client (sinh), server (kiểm
  QĐ4; sinh khi khôi phục QĐ6); chống lệch bằng **test vector JSON chung**
  FE/BE (R8).
- **Đã loại:** hậu tố "(2)"; server sinh tên khi tải lên (Q3);
  server trả danh sách "được chấp nhận" (Q5); luôn gắn hậu tố (Q7); giữ
  dedup theo tên → `SKIPPED` (phương án A).

### QĐ4: Server kiểm tra `displayName` từ client (`DisplayNameValidator`)

- **Điểm nhận:** `POST …/batches/{id}/files` (multipart, field
  `displayName`), init/commit upload session (`displayName` tuỳ chọn ở
  commit khi sinh lại), from-personal (QĐ5). Cả `/ingest` và `/personal`.
- **Ký tự và độ dài (Q9):** `display_name` ≤ **512 code point** (khớp
  `VARCHAR(512)`); suffix cố định 6 ký tự `[a-z0-9]`. **Cấm:** `/`, `\`,
  ký tự điều khiển (Cc: U+0000–001F, U+007F–009F), dấu phân dòng/đoạn
  U+2028/2029, ký tự điều hướng bidi (U+202A–202E, U+2066–2069), và tên chỉ
  gồm khoảng trắng hoặc đúng `.`/`..`. Các ký tự khác (dấu tiếng Việt, khoảng
  trắng giữa, `#%&()[]` …) **được phép**; tên tải về dùng `original_name`
  nên không phải lọc thêm ký tự cho hệ tệp. `original_name` cũng phải
  ≤ 512 (cột `document.original_name VARCHAR(512)`; `upload_session` cho
  1024 → DEV kiểm tra kết quả khi vượt) và qua cùng bộ ký tự cấm.
- **`422 INVALID_DISPLAY_NAME`** (kèm lý do): thiếu/rỗng; không NFC; > 512
  code point; chứa ký tự cấm; hợp lệ khi **một trong hai**: (i) bằng `NFC(original_name)`;
  (ii) khớp `^<tên>_(\d{17})-([a-z0-9]{6})(\.<đuôi>)?$`, timestamp hợp lệ,
  đuôi = đuôi của `NFC(original_name)` **theo quy tắc chung của QĐ3** (kể
  cả ngưỡng 16 code point và trường hợp không đuôi),
  `<tên>` = tên gốc NFC — hoặc, chỉ khi tổng đúng 512, là tiền tố
  code-point theo quy tắc cắt. Không kiểm lệch timestamp (R7).
- Server không lưu kết quả API kiểm tra; unique index là trọng tài duy nhất.
- **Trùng unique → `409 DISPLAY_NAME_CONFLICT` riêng tệp đó** (bắt lỗi
  constraint lúc ghi). Client sinh lại ≤ 3 lần/tệp, quá → tệp báo lỗi.
  Chunked: 409 ở commit → commit lại với tên mới, **không** tải lại chunk.
  Multipart: gửi lại cả tệp.
- Thứ tự server: kiểm tra hiện có (quyền, quota, `assertIngestAllowed`, …)
  → validator (422) → ghi (409). Không còn bước dedup tên. 422 log WARN.

### QĐ5: Kho cá nhân → kho chung — kiểm tra trước + đổi tên (Q4)

- `relative_path` kho chung = đường dẫn cây `personal_folder` từ thư mục
  được chọn (`A` chứa `B/x.pdf` → `A/B`) **nối thêm**
  `personal_file.relative_path` nếu khác rỗng (→ `A/B/HoSo/2024`); > 1024
  xử lý như quy tắc hiện có.
- `original_name` = `personal_file.original_name`; `display_name` sinh theo
  QĐ3 từ `original_name` (không lấy `personal_file.display_name`).
- **Luồng (bỏ luồng 2 bước "chờ đặt tên"):**
  1. `POST /ingest/from-personal/name-check` — body như from-personal; server
     mở rộng lựa chọn bằng **cùng hàm** tạo lượt, trả `[{personalFileId,
     relativePath, originalName, taken}]`. Chỉ đọc.
  2. Client sinh tên, gọi `POST /ingest/from-personal` + `displayNames:
     [{personalFileId, displayName}]`; sai → item `FAILED
     INVALID_DISPLAY_NAME`; thiếu → `FAILED DISPLAY_NAME_MISSING`.
  3. Worker vi phạm unique → item `FAILED DISPLAY_NAME_CONFLICT`; client
     `PUT /ingest/from-personal/{batchId}/items/{itemId}/display-name` →
     item về `PENDING`; tối đa 3 lần/item.
- Bỏ `SKIPPED ALREADY_EXISTS` theo tên; các kiểm tra AC-10 khác giữ thứ tự.

### QĐ6: Khôi phục thùng rác — popup "Đổi tên" / "Dừng khôi phục"

- **Phạm vi unique (Q8, đã chốt (i)):** unique + name-check chỉ tính
  `deleted_at IS NULL` — tải lại sau khi xoá giữ tên gốc; popup có ý nghĩa
  khi tên đã bị tệp mới chiếm. (Phương án (ii) tính cả thùng rác bị loại:
  khôi phục về chỗ cũ không bao giờ trùng, popup gần như không xuất hiện,
  và tải lại sau khi xoá bị gắn hậu tố dù không thấy tệp trùng.) Chi phí:
  khôi phục phải kiểm trùng (đã có chỗ kiểm trong `TrashService`). Tài
  liệu đã purge (bia mộ) có `deleted_at` → không chiếm tên.
- **Xung đột:** `display_name` tệp cần khôi phục đã có ở vị trí đích (hàng
  còn sống) → `409 NAME_CONFLICT`; UI bật popup đúng 2 lựa chọn:
  - **"Đổi tên"**: server sinh hậu tố `_<yyyyMMddHHmmssSSS>-<random 6>`
    theo quy tắc chung và gán **cả** `display_name` **lẫn** `original_name`
    = tên mới; vi phạm unique → sinh lại, tối đa 3 lần, quá → báo lỗi.
    Thay `<tên> (khôi phục yyyyMMdd-HHmm)` / `TrashService.renamedName`.
  - **"Dừng khôi phục"** (đóng popup = dừng): tệp ở lại thùng rác.
  - Bỏ ghi đè; bỏ "chọn thư mục khác" khi trùng tên (luồng `FOLDER_MISSING`
    khi thư mục gốc đã mất giữ nguyên).
- **Khôi phục nhiều tệp** có nhiều tệp trùng: **1 popup** nêu số tệp
  trùng, 2 nút + ô "Áp dụng cho tất cả"; bỏ chọn ô → hỏi từng tệp.
- **Hệ quả:** sau "Đổi tên", tìm kiếm (`document_name`) và tải về dùng tên
  mới; reindex qua outbox `document.restored` hiện có. **Ngoại lệ có chủ
  đích** của "`original_name` không đổi" (C3, R10).

### QĐ7: Quan hệ `display_name` ↔ `storage_key` — giữ 2 cột riêng

| | `display_name` | `storage_key` |
|---|---|---|
| Vai trò | Tên lưu/định danh **đọc được** của 1 bản ghi | Địa chỉ object trong SeaweedFS |
| Dạng | Tên gốc, hoặc `<tên gốc>_<ts>-<rand>.<đuôi>` (Unicode, ≤ 512) | `documents/<UUID>`, `<sub>/<UUID>` (ASCII, UUID mới mỗi lần ghi) |
| Quan hệ | 1 bản ghi – 1 tên, duy nhất theo vị trí; có thể đổi khi khôi phục (QĐ6) | 1 bản ghi – 1 object (không dùng chung dù cùng `sha256`); chuyển kho → key mới; không đổi |
| Đối tượng xem | Người dùng (tooltip) | Chỉ hệ thống (log/DB); không hiện cho vai trò nào |

- **Quyết định:** không dùng `display_name` làm object key: object WORM
  (NFR-09) không đổi tên được, trong khi `display_name` có thể đổi (QĐ6);
  key Unicode dài gây lỗi URL path-style (SeaweedFS stg); UUID không lộ tên
  tệp trong log/URL; tên do client đặt không được thành địa chỉ lưu trữ.

### QĐ8: Tìm kiếm — chỉ theo tên gốc

- `document_name` giữ nghĩa = `original_name`. Thêm field `display_name`
  `keyword`, `"index": false` (cho tooltip). Chưa go live → reindex toàn bộ,
  không cần alias. `segment` không đổi.

### QĐ9: Hiển thị & tải về

- Danh sách, chi tiết, tìm kiếm: cột tên = `original_name`; tooltip hiện
  thư mục đích + `relative_path` và `display_name`.
- Màn hình tải lên: path trong máy · tên gốc · trạng thái; `display_name`
  ở tooltip, xác nhận khi `201` (đổi nếu 409).
- `Content-Disposition` = `original_name` (Q1), cả 2 kho.

## 5. Tech stack đề xuất

- Backend: Spring Boot; `java.text.Normalizer`; `DisplayNameValidator` + bộ
  sinh hậu tố Java (chỉ cho khôi phục), package `documents`, dùng lại ở
  `personal`/`trash`; map `DataIntegrityViolationException` constraint
  `display_name` → 409; API kiểm tra tên = truy vấn `IN` theo unique index.
- Frontend: Next.js; module sinh tên (`crypto.getRandomValues`,
  `normalize('NFC')`, `Intl.DateTimeFormat`); module kéo-thả; tooltip tên;
  popup khôi phục trùng tên.
- Test vector chung FE/BE (JSON). PostgreSQL `V34`; OpenSearch thêm field.
- Deploy: `V34` → backend → reindex → frontend ngay sau backend.

## 6. Rủi ro

| # | Rủi ro | Mức độ | Giảm thiểu |
|---|---|---|---|
| R1 | Race giữa API kiểm tra tên và lúc ghi | Thấp | Unique + 409 riêng tệp + sinh lại (≤ 3 lần) |
| R2 | Lan rộng: ingest (multipart + chunked), kho cá nhân, personal-to-warehouse, thùng rác, tìm kiếm, danh sách/chi tiết/tải về | Trung bình | Tách spec `-01-backend`, `-02-frontend`; grep `original_name`/`originalName`/`document_name`/`ALREADY_EXISTS`; IT từng luồng (422/409) |
| R3 | API kiểm tra tên thành "oracle" dò tên tệp | Trung bình | Chỉ trong phạm vi có quyền ghi/dữ liệu của mình; chỉ trả `taken`; ≤ 1000/lần + rate limit; log |
| R4 | Bỏ dedup theo tên (người dùng chấp nhận): tải lại thư mục cũ tạo bản sao → tốn quota kho cá nhân, chạm ngưỡng dung lượng `assertIngestAllowed` sớm hơn, quét virus/số hoá/embedding chạy lại cho bản trùng | Trung bình | Chấp nhận; UI hiện số tệp trùng (bị đổi tên); `duplicatesBySha` vẫn trả để cảnh báo; theo dõi dung lượng |
| R5 | Không có nút chọn thư mục → cảm ứng không tải được thư mục | Thấp | Chấp nhận (C1); tệp lẻ vẫn chọn được |
| R6 | Kéo-thả chỉ mock được trong test tự động | Trung bình | Unit test hàm thuần + mock `webkitGetAsEntry`; test tay Chrome, Edge, Firefox, Safari |
| R7 | Client lệch giờ / random yếu / client sửa đổi | Thấp | Unique + 409 + sinh lại; bắt buộc `crypto.getRandomValues` |
| R8 | Tách đuôi/cắt 512/NFC lệch JS–Java → 422 oan | Trung bình | Test vector JSON chung ở CI; đếm code point |
| R9 | Tên gốc NFD không khớp truy vấn NFC khi tìm kiếm | Thấp | Kiểm tra analyzer; nếu không, NFC `original_name` lúc build index |
| R10 | "Đổi tên" khi khôi phục đổi cả `original_name` → tìm kiếm/tải về theo tên mới | Thấp | Ngoại lệ có chủ đích (QĐ6); tên mới vẫn chứa tên gốc; audit restore ghi tên cũ/mới |
| R11 | Phạm vi unique (i): tệp mới chiếm được tên của tệp trong thùng rác | Thấp | Chủ đích; khôi phục kiểm trùng + popup (QĐ6) |
| R12 | `TrashPurgeService` (~dòng 205) không xoá object nếu còn document khác chưa purge cùng `sha256`, nhưng mỗi document có `storage_key` riêng → có thể giữ lại object mồ côi | Thấp | Ngoài phạm vi; DEV kiểm tra riêng (bỏ dedup tên làm bản cùng SHA phổ biến hơn) |
| R13 | So sánh không phân biệt hoa/thường: `toLowerCase()` JS lệch `lower()` PostgreSQL ở vài ký tự hiếm (`İ`, `ß`…) → tệp cùng lượt không bị phát hiện trùng ở client | Thấp | Unique `lower()` + 409 + sinh lại; name-check và unique cùng dùng `lower()` phía DB; test vector có ca hoa/thường |
| R14 | Unique `(relative_path, lower(display_name))` trên chuỗi Unicode dài có thể vượt giới hạn dòng btree → lỗi INSERT | Trung bình | Index trên hash `md5(...)` (QĐ2); IT với `relative_path` 1024 + tên 512 ký tự tiếng Việt |

Đã đóng: luồng 2 bước "chờ đặt tên"; reindex dài; R4 cũ (SKIPPED dù đã đổi tên) — bỏ dedup theo tên.

## 7. Thay thế

Gắn dòng "Đã thay thế bởi `folder-upload-display-name`" **sau khi** file này
Approved; không sửa nội dung khác:

| File | Mục/section bị thay thế |
|---|---|
| `docs/01-sa/architecture/data-ingestion.md` | QĐ1: "tải 1 thư mục/lượt", `relative_path` bỏ tên gốc; phát hiện trùng theo tên → `SKIPPED` |
| `docs/02-ba/stories/data-ingestion.md` | AC-8 (bỏ tên thư mục gốc); AC trùng tên → "đã tồn tại" |
| `docs/03-dev/specs/data-ingestion.md` | `relativePathOf` mục 3 (`…/files` + `displayName`; `POST /ingest/name-check`) và 4.2 (bước duplicate detection theo tên + `UQ_document_folder_relpath_name_clean` bị bỏ); mục 5 Frontend; Task 12; mục 7 rủi ro kéo-thả |
| `personal-storage-quota` (SA/BA/DEV) | Kho cá nhân không có `relative_path`/`display_name`; thêm `POST /personal/name-check` |
| `docs/03-dev/specs/chunked-file-upload-02-personal-chunked-upload.md` (+ spec chunked kho chung) | "Phiên personal `relative_path=''`"; "Không có `relativePath`" ở init; `displayName` ở init/commit + 422/409 |
| `personal-to-warehouse` (SA; BA AC-5; DEV mục thu thập tệp) | Cách tính `relative_path`; bỏ `SKIPPED ALREADY_EXISTS` theo tên; thêm `from-personal/name-check`, `displayNames`, đổi tên item + `DISPLAY_NAME_*` |
| `docs/01-sa/architecture/delete-restore-trash.md` QĐ7 + BA/DEV `delete-restore-trash*` tương ứng | (a) đổi tên `(khôi phục …)` / (b) chọn thư mục khác / (c) huỷ → popup "Đổi tên" (hậu tố chung, đổi cả `original_name` + `display_name`, ≤ 3 lần) / "Dừng khôi phục", "áp dụng cho tất cả"; kiểm trùng theo `display_name` |
| `docs/01-sa/architecture/search-fulltext.md` | Thêm field `display_name` (không index) |
| `docs/_archive/2026-09-30-data-ingestion-web-drop.md` | Thay thế toàn bộ; tái sử dụng mục 5 (test plan), rủi ro (b), (d) mục 7 |

Ghi chú DEV: test `IngestControllerIT` (~dòng 353, `ALREADY_EXISTS`),
`PersonalToWarehouseIT` (~dòng 502), `TrashServiceTest`,
`TrashRestoreConflictControllerIT` phải sửa theo phương án B/QĐ6.

## 8. Câu hỏi mở

Đã chốt 2026-09-30: Q1 tải về dùng `original_name`; Q2 không hiện
`storage_key`; Q3 client sinh, server validate + unique; Q4 from-personal
kiểm tra trước + đổi tên; Q5 kiểm tra → sinh → tải → 409 → sinh lại; Q6 ≤ 3
lần/tệp; Q7 chỉ tệp trùng gắn hậu tố; phương án B bỏ dedup theo tên; unique
`(folder_id, relative_path, display_name)`; khôi phục trùng → popup "Đổi
tên"/"Dừng khôi phục"; Q8 unique + API kiểm tra tên không tính tệp trong
thùng rác (`deleted_at IS NULL`); Q9 unique và name-check **không phân
biệt hoa/thường** (`lower()`), ký tự cấm + giới hạn độ dài do SA chốt
(QĐ4).

Không còn câu hỏi mở. Ghi chú cho DEV (không cần người dùng quyết):
`personal_file` xoá cứng (không có `deleted_at`) nên unique cá nhân không
cần điều kiện partial; bia mộ sau purge phải vẫn thoả `display_name NOT
NULL` (giữ nguyên giá trị khi rỗng hoá).
