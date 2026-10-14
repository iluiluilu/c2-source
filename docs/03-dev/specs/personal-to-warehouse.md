# Tech Spec: personal-to-warehouse

> File: `docs/03-dev/specs/personal-to-warehouse.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/personal-to-warehouse.md`
> Status: Approved

## 1. Tóm tắt kỹ thuật

- Kênh nạp mới của FR-11: `POST /ingest/from-personal` (trong `IngestController`, `@PreAuthorize("hasAuthority('PERM_document.upload')")` -> AC-1/AC-3). Trả `202` + `batchId`; xử lý tuần tự từng tệp trong executor `@Async` phía server (AC-17); client poll `GET /ingest/from-personal/{batchId}` lấy kết quả từng tệp.
- Tái dùng `upload_batch` với `kind = WAREHOUSE` (thoả `CK_upload_batch_kind_target`, tái dùng `reserve/incrementX/close`). **Không** thêm `BatchKind` mới: `PERSONAL` đã tồn tại và mang nghĩa "tải lên cá nhân" (V11) — khác với ý SA/BA ghi ("thêm PERSONAL"). Phân biệt nguồn bằng bảng kết quả riêng (mục 2).
- Tách phần lõi `DocumentsService.ingestBatchFile` (bước 3 -> 10) thành `ingestCore(user, batch, IngestSource src, note)` với `IngestSource{originalName, mimeType, size, StreamOpener open /* open() throws IOException, vì MultipartFile.getInputStream ném checked exception */, String relativePath, String expectedSha256, UUID sourcePersonalFileId}`. `ingestBatchFile` (multipart) chỉ còn buffer -> gọi core, hành vi không đổi (regression phải xanh: `IngestControllerIT`, `UploadSessionServiceTest`).
- Streaming (đã chốt, không dùng CopyObject): mở `getObject(key, personalBucket)` -> buffer ra temp file, tính SHA-256 trong lúc ghi -> so `personal_file.sha256` -> `allowlistService.checkFormat` -> ClamAV `scanStream` -> `putObject(documents/<uuid>)`. Dùng temp file (giống `bufferToTempFile`) vì `checkFormat` cần `File` và `putObject` nhận `File`; vẫn "server-side, không qua trình duyệt". (Chạy scan/hash "song song" trên 1 stream là tối ưu tuỳ chọn; ưu tiên tuần tự + temp file cho đơn giản/đúng.)
- Nguồn gốc: 2 cột mới trên `document`; cờ `addedToWarehouse` tính bằng `EXISTS` theo trang ở API danh sách cá nhân (AC-21).
- Lưu ý code hiện có: `personal_file` **xoá cứng** (`PersonalService.deleteFile`), không có `deleted_at` -> "tệp bị xoá" = không tìm thấy row (`findByIdAndOwnerSub` rỗng) -> `SOURCE_NOT_FOUND` (AC-11). Không cần đổi schema `personal_file`.

## 2. Data model

Migration `V33__personal_to_warehouse.sql` (V32 là bản mới nhất):

```sql
ALTER TABLE document
    ADD COLUMN source_kind VARCHAR(16) NULL CHECK (source_kind IN ('personal')),
    ADD COLUMN source_personal_file_id UUID NULL;   -- KHÔNG FK (AC-22)
CREATE INDEX IDX_document_source_personal_file
    ON document (source_personal_file_id)
    WHERE deleted_at IS NULL AND source_personal_file_id IS NOT NULL;

-- Kết quả từng tệp của một lượt (poll + audit UI). Không lưu tên/đường dẫn thư mục cá nhân.
CREATE TABLE personal_ingest_item (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id UUID NOT NULL REFERENCES upload_batch (id),
    personal_file_id UUID NOT NULL,            -- không FK
    original_name VARCHAR(512) NOT NULL,       -- tên tệp (được phép hiển thị/ghi)
    size BIGINT NOT NULL,
    relative_path VARCHAR(1024) NOT NULL DEFAULT '',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',  -- PENDING|INGESTED|SKIPPED|FAILED
    code VARCHAR(48) NULL,                     -- ALREADY_EXISTS|INVALID_FORMAT|FILE_TOO_LARGE|MALWARE_DETECTED|WAREHOUSE_CAPACITY_EXCEEDED|SOURCE_NOT_FOUND|CHECKSUM_MISMATCH|SCANNER_UNAVAILABLE|STORAGE_WRITE_FAILED
    document_id UUID NULL,
    duplicate_of UUID[] NULL,
    processed_at TIMESTAMPTZ NULL
);
CREATE INDEX IDX_personal_ingest_item_batch ON personal_ingest_item (batch_id);
```

- `Document`: thêm `sourceKind`, `sourcePersonalFileId` (+getter/setter). Bảng document dùng `deleted_at` cho trash nên partial index đúng convention; purge NFR-09 không đổi (cột thêm không ảnh hưởng). Kiểm tra bảng liệt kê 9 bảng phụ thuộc khi purge: không thêm FK nên không ảnh hưởng.
- `personal_ingest_item` là bảng mới của feature này; `upload_batch` không đổi. `personal_file` không đổi.
- `WAREHOUSE_CAPACITY_EXCEEDED` hiện là lỗi ném ở `warehouseCapacityService.assertIngestAllowed()` (kiểm tra mã lỗi thực tế khi implement; ánh xạ về `code`).

## 3. API contract


| Method | Endpoint                          | Request                                                                                                                           | Response                                                                                                              |
| ------ | --------------------------------- | --------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| POST   | `/ingest/from-personal`           | `{targetFolderId (bắt buộc), note? (<= NOTE_MAX_LENGTH), fileIds: UUID[], folderIds: UUID[]}` (ít nhất 1 trong 2 khác rỗng) | `202 {batchId, total, ineligible:[{personalFileId,name,code}], expiresAt}`                                            |
| GET    | `/ingest/from-personal/{batchId}` | - (owner-scoped)                                                                                                                  | `200 {batchId, status: OPEN|CLOSED, done, total, items:[{personalFileId,name,status,code,documentId,duplicateOf[]}]}` |
| GET    | `/personal/files` (đã có)      | -                                                                                                                                 | thêm trường`addedToWarehouse: boolean` mỗi tệp (`EXISTS` trên partial index; tính trong 1 query, không N+1)   |

Lỗi: `403` thiếu `PERM_document.upload` (AC-1); `404` thư mục kho không tồn tại (`warehouseService.folderExists`); `404` cho id tệp/thư mục cá nhân không phải của mình hoặc không tồn tại — cùng thông điệp, không lộ tồn tại (AC-2; dùng `findByIdAndOwnerSub`); `400 BATCH_LIMIT_EXCEEDED` (AC-8) — lưu ý `UploadBatchService` hiện trả **409** cho `BATCH_LIMIT_EXCEEDED`, endpoint này trả **400** theo story: dùng ApiException riêng, không sửa hàm cũ; `409/423 WAREHOUSE_CAPACITY_EXCEEDED` như luồng hiện có khi kho >= 95% ở bước tạo lượt.
Kết quả từng tệp dùng cùng mã trạng thái `/ingest` + `SOURCE_NOT_FOUND` (mới, FAILED) (AC-11).

Bổ sung khi implement (2026-09-29):
- `400 NO_ELIGIBLE_FILES` (kèm `ineligible[]`, không tạo batch) khi mọi tệp chọn đều không đủ điều kiện — vì `upload_batch.declared_file_count` có CHECK `> 0`.
- `503 SERVER_BUSY` khi hàng đợi executor `@Async` đầy (không nhận thêm lượt).
- Mã item thêm: `INTERNAL_ERROR` (lỗi ngoài dự kiến ở một tệp, FAILED), `BATCH_EXPIRED` (batch hết hạn giữa chừng).
- `GET .../{batchId}` chỉ trả `OPEN|CLOSED`; batch `EXPIRED`/`LIMIT_EXCEEDED` quy về `CLOSED`.
- Tệp không đủ điều kiện cũng được ghi audit `ingest.from_personal.file` ngay lúc tạo lượt.

## 4. Luồng xử lý chính

1. Controller: kiểm tra quyền + validate DTO. `PersonalToWarehouseService.start(user, dto)`:
   - `warehouseService.folderExists(targetFolderId)` (AC-6, chỉ thư mục có sẵn).
   - Thu thập tệp: mỗi `fileIds` qua `findByIdAndOwnerSub`; mỗi `folderIds` qua `PersonalFolderRepository.findByIdAndOwnerSub`, duyệt đệ quy cây (nạp `findAllByOwnerSubOrderByNameAsc` 1 lần vào memory rồi dựng cây) -> `relative_path` **gồm cả tên thư mục được chọn** (chốt 2026-09-29, giống tải thư mục FR-11): chọn `A` chứa `B/x.pdf` -> `A/B`; tệp trực tiếp trong `A` -> `A`. Tệp chọn tay: `''`. (Cần cập nhật câu chữ AC-5 trong story cho khớp.) Khử trùng theo `personal_file.id`.
   - Lọc phía server: ngoài allowlist theo tên/mime (`allowlistService`, kiểm tra nhanh theo phần mở rộng/mime đã lưu; kiểm tra nội dung đầy đủ vẫn ở bước 3 mỗi tệp) và `size > INGEST_MAX_FILE_SIZE_BYTES` -> ghi item `FAILED INVALID_FORMAT/FILE_TOO_LARGE`, **không** tính vào giới hạn (AC-8).
   - Đếm trên tệp đủ điều kiện: `> maxFilesPerBatch` hoặc tổng size `> maxBatchSizeBytes` -> `400 BATCH_LIMIT_EXCEEDED` trước khi tạo batch (AC-8).
   - `warehouseCapacityService.assertIngestAllowed()` -> tạo `upload_batch` (WAREHOUSE, declared = eligible) + insert `personal_ingest_item` PENDING (mọi tệp, kể cả ineligible đã FAILED) trong 1 transaction; commit; kích hoạt `@Async` worker; trả 202.
2. Worker (`@Async` executor riêng, giới hạn concurrency, chạy tuần tự trong 1 lượt), mỗi item PENDING, bọc try/catch để 1 lỗi không dừng lượt (AC-15):
   a. Tải lại `personal_file` theo `(id, ownerSub)`; không thấy -> `FAILED SOURCE_NOT_FOUND`, `incrementRejected` (AC-11; xoá giữa lúc stream vẫn nạp).
   b. Gọi `documentsService.ingestCore(...)` theo thứ tự AC-10: `assertIngestAllowed` (95%) -> trùng vị trí -> size -> `reserve` -> stream về temp + SHA-256, **so `expectedSha256`** (lệch -> `FAILED CHECKSUM_MISMATCH`, không tạo document, AC-13) -> `checkFormat` -> ClamAV (nhiễm -> `persistFailure` INFECTED + `MALWARE_DETECTED`, AC-12) -> duplicate by sha (`duplicateOf`, AC-20) -> ghi document pending -> `putObject` key mới `documents/<uuid>` (AC-16) -> `clean` (đặt `source_kind='personal'`, `source_personal_file_id`, `uploader_sub` = người gọi, AC-18/AC-22) -> `document.create` audit (**bỏ `relativePath`** khi `source_kind='personal'`; chỉ ghi `documentId`, `targetFolderId`) -> `recomputeAfterWrite` (ngưỡng 85% cảnh báo giữ nguyên, AC-14) -> note -> `assignOnIngest` -> outbox `document.ingested` -> `DocumentIngestedEvent` (AC-19).
   c. Cập nhật item (status/code/documentId/duplicateOf) và ghi audit `ingest.from_personal.file` (AC-23).
3. Khi hết item -> `uploadBatchService.close`. Client poll GET mỗi **2s** khi batch `OPEN`; dừng khi `status=CLOSED` hoặc người dùng đóng dialog/rời trang.
4. Dọn dữ liệu: `@Scheduled` job hằng ngày xoá `personal_ingest_item` thuộc batch đã `CLOSED`/hết hạn quá **30 ngày** (cấu hình `personal-ingest.item-retention-days=30`). Dấu vết lâu dài còn ở audit log + `document.source_personal_file_id`.
5. Gián đoạn (restart backend): item còn PENDING; batch hết hạn 24h. Chạy lại = người dùng gửi lại cùng request, tệp đã vào bị `SKIPPED ALREADY_EXISTS` (AC-15); không cần scheduler resume. (Xem rủi ro.)
6. Audit `ingest.from_personal.file`: `{sourcePersonalFileId, originalName, size, documentId, targetFolderId, result/code}`; tuyệt đối không có tên/đường dẫn thư mục cá nhân. Audit `document.create` của kênh này không ghi `relativePath` (chốt 2026-09-29); kênh multipart giữ nguyên. Thêm code vào `web/src/lib/auditCatalog.ts` và đảm bảo `AuditInstrumentationCoverageTest` xanh (handler phải gọi `AuditService`).
7. Frontend `web/src/app/personal/page.tsx`: nút "Bổ sung vào kho" chỉ khi user có `PERM_document.upload` (ẩn với R2/R3); chọn tệp/thư mục -> dialog dùng `FolderTree.tsx` chọn đích + ô ghi chú -> màn xác nhận liệt kê tệp + đích, lọc client theo `/ingest/allowed-formats` + `/ingest/limits` và đánh dấu "không đủ điều kiện", cảnh báo sớm 500 tệp/5 GB (AC-7/AC-8) -> gọi POST -> poll GET (2s, dừng khi CLOSED/unmount) hiển thị bảng kết quả -> badge "đã bổ sung" từ `addedToWarehouse` (AC-21). Thêm hàm/type vào `web/src/lib/api.ts`.

## 5. Test plan

- Unit (`PersonalToWarehouseServiceTest`, mock repo/storage/clamd): thu thập đệ quy + `relative_path` gồm tên thư mục chọn (`A`, `A/B`; dấu, ký tự tự do, thư mục rỗng, lồng sâu); job dọn item > 30 ngày (không xoá batch OPEN/item mới); lọc ineligible; tính giới hạn sau lọc; ánh xạ mã kết quả; thứ tự kiểm tra AC-10; SHA mismatch. Cập nhật `DocumentsService` test/IT cho `ingestCore` để hành vi multipart không đổi.
- Integration (Testcontainers, `PersonalToWarehouseIT`; lưu ý baseline IT hiện đang hỏng ApplicationContext — xem memory, cần chạy được hoặc ghi rõ):
  - AC-1/AC-3: R1 202, R2/R3 403; ma trận quyền không đổi (`RolePermissionSeedTest` xanh).
  - AC-2: id tệp của người khác (kể cả R3 admin) -> 404 cùng thông điệp với id không tồn tại.
  - AC-4/AC-5: tệp lẻ (`''`) + thư mục `A` chứa `B/x.pdf` -> `relative_path='A/B'`; không tạo `warehouse_folder`.
  - AC-6: `targetFolderId` không tồn tại -> 404.
  - AC-8: 501 tệp đủ điều kiện -> 400 `BATCH_LIMIT_EXCEEDED`, không tạo batch/item; 501 tệp nhưng 10 ngoài allowlist -> vẫn OK.
  - AC-10-AC-13: ClamAV mock nhiễm -> `MALWARE_DETECTED`, không document; SHA lệch -> lỗi, không document; xoá `personal_file` giữa lượt -> `SOURCE_NOT_FOUND`, các tệp khác vẫn INGESTED.
  - AC-14: dung lượng >= 95% -> `WAREHOUSE_CAPACITY_EXCEEDED`; 85-95% vẫn nạp + cảnh báo.
  - AC-15: 1 tệp lỗi không dừng lượt; gửi lại -> tệp đã vào `SKIPPED ALREADY_EXISTS`.
  - AC-16: tệp cá nhân còn nguyên; `storage_key` document khác key cá nhân, object tồn tại ở bucket kho.
  - AC-18/AC-19/AC-22: `uploader_sub` = caller, `source_kind`/`source_personal_file_id` đúng, event số hoá + outbox `document.ingested` phát; xoá tệp cá nhân không ảnh hưởng document.
  - AC-20: bổ sung lại cùng vị trí -> SKIPPED; đích/relative_path khác cùng nội dung -> INGESTED + `duplicateOf`.
  - AC-21: `GET /personal/files` `addedToWarehouse=true`; xoá mềm document (trash) -> false; khôi phục -> true.
  - AC-23: audit_log có id/tên/size/documentId/folder/kết quả; assert cả `ingest.from_personal.file` lẫn `document.create` của kênh này không chứa `relativePath`/tên thư mục cá nhân; `document.create` kênh multipart vẫn có `relativePath` (regression).
  - AC-24: `sumSizeByOwnerSub` không đổi; hạn mức cá nhân đầy vẫn bổ sung được.
- Frontend (jest, `web/src/app/personal/__tests__/page.test.tsx`): nút ẩn/hiện theo permission (AC-1), lọc client + cảnh báo giới hạn (AC-7/AC-8), dialog chọn đích bắt buộc (AC-6), badge đã bổ sung (AC-21), bảng kết quả + poll 2s dừng khi CLOSED/unmount (fake timers) (AC-11/AC-17); `auditCatalog.test.ts` + snapshot.
- Case biên: tên tệp trùng nhau ở hai thư mục con khác nhau; thư mục chứa 0 tệp; `fileIds` và `folderIds` trùng tệp; note dài quá giới hạn; tệp 0 byte; ClamAV down (`SCANNER_UNAVAILABLE`, không tạo document); `putObject` lỗi; đồng thời hai lượt cùng tệp/đích.

## 6. Task breakdown

- [x]  T1: Migration `V33` (cột `document` + index + bảng `personal_ingest_item`); cập nhật entity `Document`, entity/repo `PersonalIngestItem`. (AC-21, AC-22)
- [x]  T2: Refactor `DocumentsService.ingestBatchFile` -> `ingestCore` + `IngestSource` (nhận `StreamOpener`, `expectedSha256`, thông tin nguồn); bỏ `relativePath` khỏi audit `document.create` khi nguồn personal; giữ nguyên hành vi multipart; chạy lại test ingest. (AC-10, AC-16, AC-22)
- [x]  T3: `PersonalToWarehouseService`: thu thập/duyệt cây, owner check, lọc ineligible, giới hạn 400, tạo batch + item. (AC-2, AC-4-AC-6, AC-8)
- [x]  T4: Worker `@Async` (executor cấu hình) + mã `SOURCE_NOT_FOUND`/`CHECKSUM_MISMATCH`, ghi item, close batch; `@Scheduled` job dọn `personal_ingest_item` > 30 ngày. (AC-11-AC-15, AC-17)
- [x]  T5: Endpoint `POST/GET /ingest/from-personal` + DTO + audit `ingest.from_personal.file` + cập nhật `auditCatalog.ts`. (AC-1, AC-3, AC-23)
- [x]  T6: `GET /personal/files` thêm `addedToWarehouse` (EXISTS theo trang/danh sách). (AC-21)
- [x]  T7: Frontend: `api.ts`, nút + dialog chọn đích (`FolderTree`) + màn xác nhận + bảng kết quả/poll 2s + badge. (AC-1, AC-6-AC-9, AC-17, AC-21)
- [x]  T8: Test unit/IT/frontend (vitest, không phải jest) theo mục 5 (map AC ở trên); chạy `RolePermissionSeedTest`, `AuditInstrumentationCoverageTest`.
- [x]  T9: Rebuild docker service backend/web sau khi sửa code (theo memory người dùng) và smoke test trên stg-like. Đã deploy stg 2026-09-29 (V33 applied) và test API bằng `canbo1`: 15/15 kịch bản đạt (validate 400/404, NO_ELIGIBLE_FILES, tệp lẻ + thư mục -> INGESTED với `relative_path` `''`/`A`/`A/B`, trùng -> ALREADY_EXISTS, `addedToWarehouse`, audit, tải về đúng SHA-256, OCR queued). Chưa test 403 (R2/R3) trên stg. UI stg đã test 2026-09-29 (dialog, xác nhận, kết quả, badge); bổ sung hiển thị đường dẫn thư mục đích ở màn xác nhận (AC-7, `folderPathNames`), đã deploy web.

## 7. Rủi ro kỹ thuật

- Refactor `ingestBatchFile` chạm đường nạp trọng yếu: giữ thứ tự bước và thông điệp lỗi; bảo vệ bằng IT hiện có. Baseline IT từng hỏng (ApplicationContext, OpenSearch host null) — đã sửa 2026-09-29: `src/test/resources/application.yml` che mất file main, chuyển sang `src/test/resources/config/application.yml` (được merge đè lên). Toàn bộ 33 IT chưa chạy lại.
- Xử lý `@Async` mất khi backend restart: item PENDING treo tới hết hạn batch; chấp nhận, người dùng gửi lại (idempotent nhờ luật trùng vị trí). Có thể thêm job dọn batch quá hạn sau.
- Race: hai lượt cùng đích/tệp đồng thời có thể cùng qua bước "trùng vị trí" -> tạo 2 document; đã có sẵn ở `/ingest` hiện tại, không xử lý thêm trừ khi có unique index (ngoài phạm vi).
- Temp file cho tệp lớn (tối đa 5 GB/lượt) — tuần tự từng tệp, xoá ngay trong `finally`; cần đủ đĩa `/tmp` container.
- Rò thông tin: `relative_path` (gồm tên thư mục cá nhân được chọn) được lưu ở `document` và hiển thị trong kho — chủ đích theo quyết định #1. Audit của kênh này không ghi `relativePath` (quyết định #2); cần test để kênh multipart không bị ảnh hưởng.
- Story ghi "thêm `BatchKind.PERSONAL`" nhưng giá trị đã tồn tại với nghĩa khác -> dùng kind WAREHOUSE (xem mục 1).
- Việc kiểm tra allowlist sớm phía server dựa trên mime/tên đã lưu ở `personal_file` có thể lệch kết quả `checkFormat` theo nội dung; kết quả cuối cùng luôn theo bước 5 trong core.
- Mã kết quả `409` (batch limit hiện có) khác `400` theo story: endpoint mới dùng riêng, tránh vỡ client cũ.

## Quyết định đã chốt (2026-09-29)

1. `relative_path` gồm tên thư mục được chọn: chọn `A` chứa `B/x.pdf` -> `A/B` (giống FR-11). Cần cập nhật câu chữ AC-5 trong story.
2. Audit `document.create` của kênh personal->kho **không** ghi `relativePath`; kênh multipart giữ nguyên.
3. Client poll `GET /ingest/from-personal/{batchId}` mỗi 2s, dừng khi `CLOSED` hoặc đóng dialog/rời trang.
4. `personal_ingest_item` được dọn bởi job hằng ngày sau 30 ngày kể từ khi batch đóng/hết hạn.
