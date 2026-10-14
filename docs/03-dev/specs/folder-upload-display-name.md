# Tech Spec: folder-upload-display-name

> File: `docs/03-dev/specs/folder-upload-display-name.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/folder-upload-display-name.md` (STORY-folder-upload-display-name)
> Nguồn kiến trúc: `docs/01-sa/architecture/folder-upload-display-name.md` (QĐ1–QĐ9, R1–R14)
> Status: Approved
> Spec ID: `SPEC-folder-upload-display-name`

## 1. Tóm tắt kỹ thuật

- Backend: migration `V34`; thêm `display_name` cho `document`/`personal_file`/`upload_session`/`personal_ingest_item`, `relative_path` cho `personal_file`; bỏ dedup theo tên; `DisplayNameValidator` (+ bộ sinh hậu tố Java chỉ dùng khi khôi phục); 3 endpoint name-check + rate limit; map vi phạm unique -> `409 DISPLAY_NAME_CONFLICT`; from-personal nhận `displayNames`; khôi phục thùng rác kiểm trùng theo `display_name`; OpenSearch thêm `display_name`.
- Frontend: module kéo-thả (`webkitGetAsEntry` đệ quy), module `displayName` (TS thuần), bỏ `webkitdirectory`, luồng name-check -> sinh tên -> tải -> 409 -> sinh lại; popup khôi phục 2 lựa chọn; tooltip.
- Mã dùng chung: test vector JSON chung FE/BE cho tách đuôi/cắt 512/NFC/validator (R8).
- **Lưu ý cấu trúc:** giữ **1 file** spec (< 300 dòng), task chia nhóm Backend/Frontend (Q1 đã chốt); thay cho quyết định tách `-01-backend`/`-02-frontend` ở story (mục Phụ thuộc).

## 2. Data model

Migration `backend/src/main/resources/db/migration/V34__folder_upload_display_name.sql` (hiện tới V33):

```sql
-- chưa go live: không backfill; dev/stg xoá sạch hoặc UPDATE display_name = original_name trước khi SET NOT NULL
ALTER TABLE document ADD COLUMN display_name VARCHAR(512);
UPDATE document SET display_name = original_name WHERE display_name IS NULL;  -- an toàn nếu có dữ liệu dev/stg
ALTER TABLE document ALTER COLUMN display_name SET NOT NULL;
DROP INDEX IF EXISTS UQ_document_folder_relpath_name_clean;                   -- V10 dòng 97: là UNIQUE INDEX (không phải constraint)
CREATE UNIQUE INDEX UQ_document_display_name_alive ON document
  (COALESCE(folder_id, '00000000-0000-0000-0000-000000000000'::uuid),
   md5(relative_path || chr(0) || lower(display_name)))
  WHERE deleted_at IS NULL;                                                   -- R14: hash tránh vượt giới hạn btree; mọi status (Q3)

ALTER TABLE personal_file ADD COLUMN relative_path VARCHAR(1024) NOT NULL DEFAULT '';
ALTER TABLE personal_file ADD COLUMN display_name VARCHAR(512);  -- + backfill + NOT NULL như trên
CREATE UNIQUE INDEX UQ_personal_file_display_name ON personal_file
  (owner_sub /*V6*/, COALESCE(folder_id, sentinel), md5(relative_path || chr(0) || lower(display_name)));

ALTER TABLE upload_session ADD COLUMN display_name VARCHAR(512) NULL;
ALTER TABLE personal_ingest_item ADD COLUMN display_name VARCHAR(512) NULL;
ALTER TABLE personal_ingest_item ADD COLUMN name_retry_count INT NOT NULL DEFAULT 0;  -- Q4: đếm PUT display-name, ≤3
```

- Entity: `documents/Document.java`, `personal/PersonalFile.java`, `upload/UploadSession.java` (đã có `relativePath` cho personal session? kiểm tra), `personal/ingest/PersonalIngestItem.java` thêm `displayName`.
- `personal_file` xoá cứng -> unique không cần điều kiện partial. Bia mộ purge giữ nguyên `display_name` (đã có `deleted_at`, nằm ngoài unique).
- Đã xác minh (Q2): V10 tạo `UQ_document_folder_relpath_name_clean` bằng `CREATE UNIQUE INDEX` -> `DROP INDEX`; cột chủ sở hữu `personal_file` là `owner_sub` (V6).
- Unique `document` áp cho **mọi status** (`scanning`/`infected`/`quarantined`/`clean`), chỉ loại `deleted_at IS NOT NULL` (Q3, theo SA QĐ2).
- OpenSearch: `backend/src/main/resources/search/document-v1-index.json` thêm `"display_name": {"type":"keyword","index":false}`; `document_name` giữ = `original_name`. `search/index/SearchIndexOutboxPoller.java` (~473) và `DocumentIndexFieldsBuilder` thêm field. Chưa go live -> reindex toàn bộ.

## 3. API contract

| Method | Endpoint | Request | Response | Lỗi |
|---|---|---|---|---|
| POST | `/ingest/name-check` | `{folderId, files:[{relativePath, originalName}]}` (≤1000) | `200 [{taken}]` đúng thứ tự | 403 không quyền ghi; 413/400 >1000; 429 |
| POST | `/personal/name-check` | như trên (`folderId` = personal folder, null = gốc) | `[{taken}]` | 403; 400; 429 |
| POST | `/ingest/from-personal/name-check` | body như `/ingest/from-personal` | `[{personalFileId, relativePath, originalName, taken}]` | 403; 400; 429 |
| POST | `/ingest/batches/{id}/files` (sẵn) | multipart + `displayName`, `relativePath` | như cũ (bỏ `SKIPPED ALREADY_EXISTS`) | 422 `INVALID_DISPLAY_NAME{reason}`; 409 `DISPLAY_NAME_CONFLICT` |
| POST | `/personal/batches/{id}/files` (sẵn) | multipart + `displayName`, `relativePath` | như cũ | 422; 409 |
| POST | `/ingest/batches/{id}/upload-sessions`, `/personal/batches/{id}/upload-sessions` | thêm `displayName`, personal thêm `relativePath` | như cũ | 422 (gồm `ORIGINAL_NAME_TOO_LONG`) |
| POST | `/upload-sessions/{sid}/complete` | thêm `displayName?` (tên sinh lại) | như cũ | 422; 409 `DISPLAY_NAME_CONFLICT` (phiên giữ nguyên, commit lại, không tải lại chunk) |
| POST | `/ingest/from-personal` (sẵn) | thêm `displayNames:[{personalFileId, displayName}]` | như cũ | item `FAILED INVALID_DISPLAY_NAME` / `DISPLAY_NAME_MISSING` / `DISPLAY_NAME_CONFLICT` |
| PUT | `/ingest/from-personal/{batchId}/items/{itemId}/display-name` | `{displayName}` | `200`, item về `PENDING`, `name_retry_count += 1` | 422; 409 nếu item không ở `FAILED DISPLAY_NAME_CONFLICT`; 400 khi `name_retry_count` đã = 3 |
| POST | `/trash/{id}/restore`, `/trash/restore-batch` (sẵn) | **Không đổi DTO** (`RestoreBatchDto`: `onNameConflict` `AUTO_RENAME`\|`CANCEL` chung cả batch); bỏ OVERWRITE/chọn thư mục khi trùng tên | 200 | `409 NAME_CONFLICT` (kiểm theo `display_name`); `FOLDER_MISSING` giữ nguyên |
| GET | `/documents/{id}/content`, `/personal/files/{id}/content` (sẵn) | - | `Content-Disposition` dùng `original_name` | - |

- DTO list/detail (`DocumentListItem`, `PersonalFileView`, search hit) thêm `displayName`, `relativePath`; **không** có `storageKey`.
- Mã lỗi 422 `reason`: `EMPTY`, `NOT_NFC`, `TOO_LONG`, `FORBIDDEN_CHAR`, `BLANK_OR_DOT`, `SUFFIX_MISMATCH`, `ORIGINAL_NAME_TOO_LONG`.
- Xác minh lại trong code tên thực tế `ingest/dto/InitUploadSessionDto.java`, `personal/dto/OpenPersonalUploadSessionDto.java`, `personal/ingest/FromPersonalDto.java`, `trash/dto/RestoreDto.java`.

## 4. Luồng xử lý chính

```mermaid
sequenceDiagram
  participant C as Client
  participant S as Backend
  C->>C: drop -> duyệt đệ quy -> relativePath/originalName (NFC)
  C->>S: POST name-check (lô <=1000)
  S-->>C: [{taken}] (lỗi/429 -> coi tất cả taken)
  C->>C: sinh displayName (không trùng: giữ tên; trùng: hậu tố)
  C->>S: upload (multipart / init+chunks+complete) + displayName
  alt 409 DISPLAY_NAME_CONFLICT
    C->>C: sinh lại hậu tố mới (<=3 lần/tệp)
    C->>S: gửi lại (multipart: cả tệp; chunked: chỉ complete)
  end
  S-->>C: 201 + displayName cuối
```

1. Server (ingest/personal): quyền/quota/`assertIngestAllowed` hiện có -> `DisplayNameValidator` (422) -> ghi; bắt `DataIntegrityViolationException` theo tên constraint -> `409 DISPLAY_NAME_CONFLICT`. Validator kiểm: `original_name` ≤512 cp (AC-28, ở init/multipart/from-personal, trước khi nhận chunk); `displayName` theo QĐ4: NFC, ≤512 cp, cấm `/ \`, Cc, U+2028/2029, bidi U+202A–202E/U+2066–2069, blank/`.`/`..`; hợp lệ nếu `== NFC(original_name)` hoặc khớp `^<tên>_(\d{17})-([a-z0-9]{6})(\.<đuôi>)?$` (timestamp parse hợp lệ; `<tên>` = tên gốc hoặc tiền tố theo quy tắc cắt khi tổng đúng 512).
2. `DocumentsService.ingestCore` (~198): xoá bước `findFirst…OriginalName…` -> `Skipped("ALREADY_EXISTS")`; giữ `duplicatesBySha`.
3. Name-check: một truy vấn `IN` trên `md5(relative_path||chr(0)||lower(NFC(name)))` + `deleted_at IS NULL`; chỉ đọc; kiểm quyền ghi đích (`403`); giới hạn ≤1000.
4. from-personal: `relative_path` kho chung = đường dẫn cây `personal_folder` từ thư mục được chọn + `personal_file.relative_path` (AC-19); name-check dùng **cùng hàm mở rộng lượt** với `PersonalToWarehouseService`. Worker vướng unique -> item `FAILED DISPLAY_NAME_CONFLICT`; PUT đổi tên -> `PENDING` (≤3 lần/item, đếm ở cột `personal_ingest_item.name_retry_count`, server chặn lần thứ 4).
5. Khôi phục (`TrashService.restore` ~383): đổi kiểm trùng sang `display_name` (không phân biệt hoa/thường, `deleted_at IS NULL`, mọi status); trùng mà không `AUTO_RENAME` -> 409 `NAME_CONFLICT`; `AUTO_RENAME` ("Đổi tên"): sinh hậu tố bằng bộ sinh Java (thay `renamedName`), gán `display_name` **và** `original_name`; vướng unique -> sinh lại ≤3 lần rồi lỗi; audit ghi tên cũ/mới; outbox `document.restored` reindex. FE nhiều tệp trùng (AC-23, Q5): tích "Áp dụng cho tất cả" -> 1 request `restore-batch` với `AUTO_RENAME` ("Đổi tên") hoặc `CANCEL` ("Dừng khôi phục"); bỏ tích -> hỏi từng tệp, mỗi tệp gọi `/trash/{id}/restore` riêng.
6. Rate limit: bộ đếm in-memory (cửa sổ 1 phút) theo `(sub, endpoint)`, 30/phút, cấu hình `application.yml` (`app.name-check.rate-limit-per-minute`); vượt -> `429` + log WARN.
7. Frontend kéo-thả: `DataTransferItemList`; gọi `webkitGetAsEntry()` đồng bộ trong handler `drop`; `readEntries` lặp tới khi rỗng (AC-4), nhường event loop mỗi trang; `relativePath` = `fullPath` bỏ `/` đầu và tên tệp; gộp 1 batch.

## 5. Test plan

- Unit BE: `DisplayNameValidatorTest` (mọi lý do 422, NFC, cắt 512 không đôi cp, đuôi ≤/>16, `.env`, `a.tar.gz`, không đuôi, hậu tố hợp lệ/sai, tổng đúng 512); bộ sinh hậu tố Java; rate limiter; đọc test vector JSON chung `backend/src/test/resources/display-name-vectors.json` (đặt bản sao/đường dẫn chung với `web/`, CI kiểm cả 2 phía).
- Unit FE (Jest/Vitest theo repo): module `displayName` (cùng vector; `crypto.getRandomValues` được mock; tz `Asia/Ho_Chi_Minh`); module kéo-thả với mock `webkitGetAsEntry` (nhiều gốc + tệp lẻ, >100 tệp phân trang, tên thư mục gốc vào `relativePath`); hàm gán tên trùng cùng lượt (hoa/thường, NFC vs NFD).
- IT BE (`*IT`, Testcontainers): name-check 3 endpoint (thứ tự, hoa/thường, NFC, bỏ qua tệp trong thùng rác, 403, >1000, 429); ingest multipart + chunked (422 từng lý do, 409 riêng tệp, commit lại không tải lại chunk, `ORIGINAL_NAME_TOO_LONG` chặn trước chunk); tải lại cùng tên -> bản mới, không `SKIPPED`; `relative_path` 1024 + tên 512 cp tiếng Việt (R14); from-personal (`displayNames` sai/thiếu/conflict, PUT đổi tên ≤3 lần — lần 4 trả 400 theo `name_retry_count`, `relative_path` nối); unique chặn trùng với tệp `scanning`/`infected` (Q3); restore (409, AUTO_RENAME cả hai tên, retry unique, audit, reindex); Content-Disposition = `original_name`; response không có `storageKey`; index OpenSearch có `display_name`.
- **Sửa test cũ (bắt buộc):** `IngestControllerIT` (~353, `ALREADY_EXISTS`), `PersonalToWarehouseIT` (~502), `TrashServiceTest`, `TrashRestoreConflictControllerIT`; cập nhật `web/src/app/upload/__tests__/page.test.tsx`, `uploadOneChunked.test.ts` (cả `personal/__tests__`), `personalToWarehouse.test.tsx`, `trash/__tests__/page.test.tsx`, `components/trash/__tests__/RestoreDialog.test.tsx`.
- Biên: API name-check lỗi/timeout -> tải vẫn chạy (AC-12); 409 lặp 3 lần -> dòng lỗi; thả thư mục rỗng; tên chứa `#%&`; tệp lẻ `relativePath=''`; đóng popup khôi phục = dừng; "Áp dụng cho tất cả" bật/tắt; race 2 lượt cùng tên (unique làm trọng tài).
- Test tay: kéo-thả Chrome/Edge/Firefox/Safari (R6), không hiện popup gốc.

## 6. Task breakdown (map tới AC)

**Backend**
- [x] **BE-1** `V34` + entity (`Document`, `PersonalFile`, `UploadSession`, `PersonalIngestItem`) → AC-5, AC-7, AC-14, AC-16
- [x] **BE-2** `DisplayNameValidator` (+ quy tắc tách đuôi/cắt 512, bộ sinh Java) + test vector JSON chung → AC-9, AC-10, AC-13, AC-28
- [x] **BE-3** Ingest multipart/chunked nhận `displayName`/`relativePath`, bỏ `ALREADY_EXISTS`, map 409/422 (`DocumentsService`, `IngestController`, `UploadSessionService`, `InitUploadSessionDto`) → AC-7, AC-11, AC-13, AC-14, AC-15, AC-28
- [x] **BE-4** Kho cá nhân: `relative_path` + `displayName` ở `PersonalController`/`PersonalService`/`OpenPersonalUploadSessionDto` → AC-5, AC-13, AC-14, AC-15
- [x] **BE-5** name-check `/ingest`, `/personal` + quyền + giới hạn 1000 → AC-6, AC-16, AC-17
- [x] **BE-6** Rate limit name-check in-memory, cấu hình yml, 429 + log WARN → AC-17, AC-29
- [x] **BE-7** from-personal: name-check, `displayNames`, tính `relative_path`, item FAILED codes, PUT display-name (`PersonalToWarehouseService`, `PersonalIngestWorker`, `IngestController`) → AC-18, AC-19, AC-20, AC-28
- [x] **BE-8** `TrashService.restore`: kiểm trùng `display_name`, AUTO_RENAME hậu tố + retry + audit; bỏ overwrite/chọn thư mục khi trùng tên → AC-21, AC-22
- [x] **BE-9** Content-Disposition = `original_name`; DTO thêm `displayName`/`relativePath`, không lộ `storageKey`; search chỉ khớp `original_name`; thêm field index `display_name` + reindex → AC-24, AC-25, AC-26
- [x] **BE-10** Sửa IT/unit cũ (IngestControllerIT, PersonalToWarehouseIT, TrashServiceTest, TrashRestoreConflictControllerIT) + IT mới → AC-11, AC-20, AC-21 (phủ toàn bộ)

**Frontend**
- [x] **FE-1** Module kéo-thả dùng chung (`web/src/lib/dropEntries.ts`), bỏ `webkitdirectory`/`dirInputRef`, "Chọn tệp" chỉ tệp lẻ ở `app/upload/page.tsx`, `app/personal/page.tsx` → AC-1, AC-2, AC-3, AC-4, AC-5
- [x] **FE-2** Module `web/src/lib/displayName.ts` (NFC, đuôi, hậu tố `Asia/Ho_Chi_Minh`, `crypto.getRandomValues`, cắt 512) + vector chung → AC-7, AC-8, AC-9, AC-10
- [x] **FE-3** Luồng tải: name-check theo lô 1000, gán tên, 409 -> sinh lại ≤3, chặn sớm `original_name` >512, 429/lỗi -> coi taken; sửa `uploadOneChunked` (commit lại không tải lại chunk) cả 2 trang → AC-6, AC-8, AC-11, AC-12, AC-14, AC-15, AC-28, AC-29
- [x] **FE-4** UI tải lên: dòng path/tên gốc/trạng thái, tooltip `display_name`, số tệp bị đổi tên → AC-11, AC-27
- [x] **FE-5** `PersonalToWarehouseDialog.tsx` + `lib/personalToWarehouse.ts`: name-check, `displayNames`, PUT đổi tên item ≤3 lần → AC-18, AC-20
- [x] **FE-6** Popup khôi phục (`components/trash/RestoreDialog.tsx`, `app/trash/page.tsx`): "Đổi tên"/"Dừng khôi phục", 1 popup + "Áp dụng cho tất cả" (tích -> `restore-batch` chung; bỏ tích -> `/trash/{id}/restore` từng tệp) → AC-21, AC-22, AC-23
- [x] **FE-7** Hiển thị `original_name` + tooltip (thư mục đích + `relative_path`, `display_name`) ở `documents/page.tsx`, `documents/[id]/page.tsx`, `personal/page.tsx`, search; không hiện `storage_key` → AC-24, AC-25
- [x] **FE-8** Cập nhật test FE (mục 5) → phủ AC-1..AC-27
- Thứ tự: BE-1,2 -> BE-3..9 -> FE-1,2 -> FE-3..7 -> BE-10/FE-8. Kiểm tra: `mvn -o test -Dtest=<Lớp>`, `npx tsc --noEmit`, `npm run lint`; đọc `web/AGENTS.md` trước khi code web.

## 7. Observability

- Log: INFO name-check (sub, endpoint, số tệp, số taken — không log tên tệp); WARN 422 `INVALID_DISPLAY_NAME` (reason, không log nguyên tên), WARN 429; INFO 409 `DISPLAY_NAME_CONFLICT` (context ingest/personal/restore); audit restore ghi tên cũ/mới.
- Metric (Micrometer nếu đã có): `name_check_requests_total{endpoint,result}`, `display_name_conflict_total{source}`, `display_name_invalid_total{reason}`, tỉ lệ `taken`.
- Alert (tuỳ chọn): tăng đột biến 409/429; tăng dung lượng bản sao sau tải lại (R4).

## 8. Security checklist

- [ ] Validate `displayName`/`originalName` mọi điểm nhận (QĐ4), 422 kèm lý do; không tin client
- [ ] name-check cùng quyền ghi đích, sai quyền `403`; chỉ trả `taken`; ≤1000/lần (R3)
- [ ] Rate limit 30/phút/người/endpoint, `429` (AC-29)
- [ ] `storage_key` không xuất hiện trong DTO/UI/log phản hồi; `display_name` không bao giờ thành object key (QĐ7)
- [ ] `Content-Disposition` `filename*=UTF-8''` + percent-encode `original_name`, chống CRLF (đã cấm Cc)
- [ ] Không log tên tệp thô ở WARN/metric label; SQL dùng tham số (truy vấn `IN`)

## 9. Migration & Rollback plan

- Thứ tự deploy: `V34` -> backend -> reindex OpenSearch -> frontend ngay sau backend (FE cũ gửi thiếu `displayName` sẽ bị 422; giữ cửa sổ ngắn). Không feature flag (chưa go live).
- Rollback: `V34` không backward-compatible (drop unique cũ, NOT NULL). Phương án: backup DB trước; rollback = khôi phục bản backup/ script ngược thủ công (drop index mới, tạo lại `UQ_document_folder_relpath_name_clean` — lỗi nếu đã có bản trùng tên). Xoá sạch dữ liệu dev/stg được phép (C8); **stg có dữ liệu thử** -> dùng `UPDATE display_name = original_name` trong V34.
- Flyway: stg từng gặp checksum drift; không sửa V cũ, chỉ thêm V34.

## 10. Rủi ro kỹ thuật

- R2 lan rộng (ingest, personal, from-personal, trash, search, UI) -> IT từng luồng; grep `originalName`/`ALREADY_EXISTS`.
- R8 lệch JS–Java (đuôi/cắt/NFC/`lower`) -> vector JSON chung ở CI; đếm code point.
- R14 unique trên `md5` -> IT tên 512 cp + path 1024; name-check và unique dùng cùng biểu thức (`lower`, `md5` ở DB, không tính ở Java).
- R4 tải lại tạo bản sao tốn quota/quét/số hoá; R13 `toLowerCase` JS vs `lower()` PG (409 làm lưới cuối).
- Unique nay tính mọi status (trước chỉ `clean`): tệp `scanning`/`infected`/`quarantined` cũng chiếm tên (chủ đích, Q3) — tệp nhiễm vẫn giữ tên tới khi bị xoá.
- Rate limit in-memory không chia sẻ giữa nhiều instance (chấp nhận, ghi nhận).
- R12 `TrashPurgeService` object mồ côi cùng sha256: ngoài phạm vi, đã có `bugs/bug-005-...md`.
- Kéo-thả khó test tự động (R6); thiết bị cảm ứng không tải thư mục (R5).

## 11. Definition of Done

- [ ] Tất cả AC-1..AC-29 pass (đối chiếu QA report)
- [ ] Test unit/IT xanh (gồm IT sửa theo phương án B), vector JSON chung xanh ở cả BE/FE
- [ ] `mvn -o compile`, `npx tsc --noEmit`, `npm run lint` sạch
- [ ] Không giảm coverage đáng kể; `IngestControllerIT`/`PersonalToWarehouseIT`/`TrashServiceTest`/`TrashRestoreConflictControllerIT` đã sửa
- [ ] Gắn "Đã thay thế bởi `folder-upload-display-name`" vào các file cũ (SA mục 7) sau khi Approved
- [x] Đã rebuild service docker bị ảnh hưởng (backend, web); migration & rollback đã cân nhắc

## 12. Câu hỏi mở

- [x] Q1: Story chốt tách 2 file spec (`-01-backend`, `-02-frontend`) nhưng giao việc yêu cầu 1 file; giữ 1 file (≈300 dòng) hay tách khi duyệt? → **Chốt:** giữ 1 file (172 dòng < 300), task chia nhóm BE/FE; story mục Phụ thuộc cần cập nhật tương ứng (cập nhật: mục 1)
- [x] Q2: Tên thực tế index/constraint unique V10 và cột chủ sở hữu `personal_file` cần xác minh khi viết V34 (spec dùng tên giả định). → **Chốt:** `UQ_document_folder_relpath_name_clean` là UNIQUE INDEX (V10 dòng 97) -> `DROP INDEX`; cột chủ sở hữu `owner_sub` (V6) (cập nhật: mục 2)
- [x] Q3: Unique mới áp cho mọi `status` (không chỉ `clean`) — tệp `infected`/đang quét có chiếm tên không? → **Chốt:** có, mọi status chiếm tên, chỉ loại tệp trong thùng rác (SA QĐ2) (cập nhật: mục 2, 5, 10)
- [x] Q4: Bộ đếm lần thử lại 3 lần/item (PUT display-name) lưu ở đâu? → **Chốt:** cột `personal_ingest_item.name_retry_count INT NOT NULL DEFAULT 0` trong V34, server chặn lần thứ 4 (cập nhật: mục 2, 3, 4, 5)
- [x] Q5: Khôi phục theo batch — "Áp dụng cho tất cả" gửi `onNameConflict` chung hay từng `id`? → **Chốt:** giữ `RestoreBatchDto`; tích -> 1 `restore-batch` với `AUTO_RENAME`/`CANCEL`; bỏ tích -> `/trash/{id}/restore` từng tệp (cập nhật: mục 3, 4, 6 FE-6)
