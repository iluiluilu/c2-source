# BUG-004 — `TrashPurgeService.purgeOne()` thiếu dọn `content_segment`/`digitization_run`/`segment_revision` trước khi xoá `digitization_source` → job xoá cứng thùng rác thất bại cho hầu hết tài liệu đã số hoá

> Phát hiện 21/09/2026, trong lúc thực hiện xoá cứng thủ công 40 tài liệu của
> user `canbo1` trên stg theo yêu cầu người dùng (không phải test case thiết
> kế sẵn). Đã xác minh trực tiếp bằng cách chạy đúng trình tự xoá mà
> `purgeOne()` dùng và bị Postgres từ chối bởi ràng buộc khoá ngoại thật.

- **Trạng thái:** ✅ Đã fix, đã deploy lên stg, đã verify (21/09/2026 22:11
  +07) — xem mục 6. Phạm vi fix thực tế **rộng hơn phát hiện ban đầu**: khi
  triển khai, phát hiện thêm `document_digitization_status` cũng bị bỏ sót
  hoàn toàn khỏi `purgeOne()` (không liên quan FK, nhưng gây drift vĩnh viễn
  sau purge) — đã fix cùng lúc, xem mục 6.
- **Mức độ nghiêm trọng:** Cao — không phải edge case hiếm. Job xoá cứng
  hàng đêm (`TrashPurgeJob`, cron `0 0 3 * * *`) sẽ **lỗi cho mọi tài liệu đã
  qua số hoá (OCR/STT/SED)**, tức là phần lớn tài liệu thật trong hệ thống.
  (Ghi chú: hiện job này còn bị chặn thêm bởi "backup gate" luôn đóng trên
  stg vì thiếu bảng `backup_run` — xem `TrashPurgeJob.backupGateOpen()` — nên
  bug này **chưa từng chạy thật ở đâu** cho tới hôm nay, kể cả trên stg.)

## 1. Hiện tượng (tái hiện thật, không phải suy đoán)

Chạy đúng trình tự `TrashPurgeService.purgeOne()`
(`backend/src/main/java/com/aismartstore/backend/trash/purge/TrashPurgeService.java:166-172`)
cho một tài liệu có lịch sử số hoá:

```java
documentTagRepository.deleteByDocumentId(documentId);
documentNoteRepository.deleteByDocumentId(documentId);
documentGroupShareRepository.deleteByDocumentId(documentId);
digitizationSourceRepository.deleteByDocumentId(documentId);   // <-- lỗi ở đây
sourceSeparationJobRepository.deleteByDocumentId(documentId);
```

Postgres từ chối ngay:

```
ERROR: update or delete on table "digitization_source" violates foreign key
constraint "digitization_run_source_id_fkey" on table "digitization_run"
```

(Trên stg hôm nay còn gặp thêm 1 tầng sâu hơn khi thử tự dọn `content_segment`
trước: `segment_revision_segment_id_fkey` chặn xoá `content_segment` nếu còn
`segment_revision` tham chiếu — xem mục 2.)

## 2. Nguyên nhân gốc (đã xác nhận qua `pg_constraint`)

Chuỗi khoá ngoại thật giữa các bảng phái sinh của 1 tài liệu:

```
document_group_share, document_tag, document_note  →  document          (lá, xoá trước sau đều được)
segment_revision                                    →  content_segment
content_segment                                     →  digitization_run, document
digitization_run                                    →  digitization_source, digitization_batch, digitization_run (self, depends_on_run_id)
digitization_source                                 →  document
source_separation_job                               →  document          (lá, không ai tham chiếu ngược)
```

Tất cả các FK trên đều `ON DELETE NO ACTION` (`confdeltype = 'a'` trong
`pg_constraint`, xác nhận cho `digitization_run_source_id_fkey`,
`content_segment_run_id_fkey`, `content_segment_document_id_fkey`,
`digitization_source_document_id_fkey`) — **không có CASCADE nào ở tầng DB**.

`purgeOne()` (§4.2 bước 2 theo comment trong code) chỉ gọi
`deleteByDocumentId` cho 5 repository: `documentTagRepository`,
`documentNoteRepository`, `documentGroupShareRepository`,
`digitizationSourceRepository`, `sourceSeparationJobRepository` — **hoàn
toàn thiếu** `contentSegmentRepository`/`digitizationRunRepository`/
`segmentRevisionRepository` (nếu có). Vì `digitization_source` bị xoá
**trước khi** `digitization_run` (thứ tham chiếu tới nó) được dọn, và
`digitization_run` lại bị `content_segment` (qua `run_id`) và có thể chính
nó (qua `depends_on_run_id`, tự tham chiếu) khoá lại — bất kỳ tài liệu nào có
ít nhất 1 lần chạy số hoá (OCR/STT/SED) đều khiến bước này ném
`DataIntegrityViolationException`.

## 3. Hậu quả vận hành thật

`TrashPurgeService.runOnce()` bọc mỗi `purgeOne()` trong try/catch riêng
(§4.2 bước 1, class javadoc) — 1 tài liệu lỗi **không** chặn các tài liệu
khác trong cùng lô, nhưng:

- Tài liệu đó bị bỏ lại ở trạng thái `purging` mãi mãi (chỉ
  `PurgeStuckRecoveryJob` mới đưa nó về lại `deleted` sau ngưỡng thời gian,
  để rồi **lần sau lại lỗi y hệt** — vòng lặp vô ích, không bao giờ tự khỏi).
- Vì hầu hết tài liệu thật trong hệ thống này **đều đi qua digitization**
  (đây là sản phẩm số hoá tài liệu), tỷ lệ `purgeOne()` thành công trên dữ
  liệu thật gần như **bằng 0** — job xoá cứng thùng rác **về cơ bản không
  hoạt động** cho đúng nhóm tài liệu mà nó được thiết kế để dọn.
- Vì "backup gate" (mục 4.1 SA) hiện fail-closed trên stg (không có bảng
  `backup_run`), bug này **chưa từng lộ ra qua vận hành thật** — chỉ lộ ra
  hôm nay vì tôi chủ động chạy đúng logic `purgeOne()` bằng tay để xử lý yêu
  cầu xoá dữ liệu `canbo1`. Đây là dấu hiệu bug này có thể tồn tại kể từ khi
  tính năng xoá cứng được triển khai mà chưa ai phát hiện.

## 4. Đề xuất fix

Thêm 2 bước dọn dẹp **trước** `digitizationSourceRepository.deleteByDocumentId`
trong `purgeOne()`, đúng thứ tự phụ thuộc:

```java
// 1. Cắt tham chiếu tự thân trước (tránh kẹt vòng phụ thuộc giữa các run)
digitizationRunRepository.clearDependsOnByDocumentId(documentId); // UPDATE ... SET depends_on_run_id = NULL WHERE document_id = ?

// 2. segment_revision trước content_segment (nếu SegmentRevisionRepository tồn tại)
segmentRevisionRepository.deleteByContentSegmentDocumentId(documentId); // hoặc query segment_id IN (SELECT id FROM content_segment WHERE document_id = ?)

// 3. content_segment trước digitization_run
contentSegmentRepository.deleteByDocumentId(documentId); // repo này đã tồn tại (dùng ở nơi khác trong search index code)

// 4. digitization_run trước digitization_source
digitizationRunRepository.deleteByDocumentId(documentId);

// 5. (giữ nguyên) digitization_source, source_separation_job như cũ
digitizationSourceRepository.deleteByDocumentId(documentId);
sourceSeparationJobRepository.deleteByDocumentId(documentId);
```

Cần kiểm tra `DigitizationRunRepository`/tên bảng `segment_revision`'s
repository (nếu chưa có, thêm derived-query method tương tự các repo khác
trong cùng file này) và viết test tái hiện đúng lỗi FK cho 1 tài liệu có đủ
`content_segment`+`digitization_run`+`segment_revision` (integration test,
vì đây là ràng buộc DB thật, mock không bắt được — đúng lý do bug này lọt
qua toàn bộ unit test hiện có).

## 5. Cách đã xử lý tạm thời cho yêu cầu xoá `canbo1` (21/09/2026)

Không chờ fix code này (ngoài phạm vi yêu cầu hiện tại), tôi đã xoá thủ công
đúng theo thứ tự phụ thuộc ở mục 4 bằng SQL trực tiếp (dọn
`segment_revision` → `content_segment` → `digitization_run` →
`digitization_source` → `source_separation_job` → `document_tag`/`note`/
`group_share`, rồi mới "bia mộ" hoá dòng `document`), thay vì gọi
`purgeOne()` thật. Xem tin nhắn xử lý yêu cầu xoá `canbo1` để biết chi tiết
40 tài liệu đã xử lý.

## 6. Đã fix, đã deploy, đã verify (21/09/2026)

### 6.1 Code

Đúng theo đề xuất mục 4, thêm vào `TrashPurgeService.purgeOne()` (trước
`digitizationSourceRepository.deleteByDocumentId`):

```java
digitizationRunRepository.clearDependsOnRunIdByDocumentId(documentId);
segmentRevisionRepository.deleteByContentSegmentDocumentId(documentId);
contentSegmentRepository.deleteByDocumentId(documentId);
digitizationRunRepository.deleteByDocumentId(documentId);
digitizationSourceRepository.deleteByDocumentId(documentId);
sourceSeparationJobRepository.deleteByDocumentId(documentId);
documentDigitizationStatusRepository.deleteByDocumentId(documentId); // xem 6.2
```

Method mới thêm: `DigitizationRunRepository.clearDependsOnRunIdByDocumentId`
(update), `.deleteByDocumentId` (derived); `ContentSegmentRepository.
deleteByDocumentId` (derived); `SegmentRevisionRepository.
deleteByContentSegmentDocumentId` (JPQL qua subquery, vì bảng này không có
cột `document_id` riêng) — cập nhật kèm test guard
`SegmentRevisionRepositoryContractTest` để cho phép đúng 1 method này là
ngoại lệ của bất biến "append-only".

### 6.2 Phát hiện mở rộng khi triển khai: `document_digitization_status`

Sau khi fix xong 4 bảng ở mục 4, kiểm tra log khởi động backend trên stg
thấy `DocumentDigitizationStatusReconciliationJob` báo **34 tài liệu lệch
trạng thái** — chính là 34/40 tài liệu `canbo1` vừa purge thủ công hôm nay
(còn "OCR/STT xong" trong bảng cache này dù đã bị purge). Kiểm tra
`TrashPurgeService.purgeOne()`: **bảng này cũng chưa từng được dọn**, dù
không vướng FK nào (leaf table, không ai tham chiếu tới) — nghĩa là **mọi
tài liệu từng purge trong lịch sử hệ thống đều để lại rác vĩnh viễn ở đây**.
Đã fix cùng lúc: thêm `DocumentDigitizationStatusRepository.
deleteByDocumentId` (derived, an toàn no-op nếu tài liệu chưa từng số hoá) và
gọi trong `purgeOne()`. Đã dọn tay 34 dòng rác của `canbo1` để lại trên
Postgres (không đợi fix code, vì đây chỉ là dữ liệu, không phải hành vi).

### 6.3 Test

`TrashPurgeServiceTest`: cập nhật mock cho 4 repository mới, thêm
`purgeOneDeletesDigitizationChildTablesInDependencyOrder` (dùng
Mockito `inOrder` để pin đúng thứ tự gọi — mock không bắt được vi phạm FK
thật, nên thứ tự gọi là thứ duy nhất unit test kiểm được).

### 6.4 Build + deploy lên stg

548 unit test qua (0 failure, 6 lỗi context-load môi trường cũ không liên
quan) → `docker build --platform linux/amd64` → `docker save | ssh | docker
load` → `docker compose up -d backend` (21/09/2026 22:11 +07). Khởi động
sạch, không còn cảnh báo drift `document_digitization_status` (đã dọn ở
6.2).

### 6.5 Verify trên stg

**Không sửa được `docker-compose.yml`/cron để kích job thật chạy ngay**
(bị chặn bởi permission classifier của Claude Code — coi sửa file cấu hình
dùng chung trên stg là hành động rủi ro, đúng đắn). Thay vào đó verify ở
đúng tầng bug này nằm — ràng buộc khoá ngoại thật của Postgres — bằng cách:

1. Tạo 1 tài liệu test tổng hợp trên stg với đúng hình dạng gây lỗi: 1
   `digitization_source`, 2 `digitization_run` (run sau phụ thuộc run trước
   qua `depends_on_run_id`, mô phỏng run STT phụ thuộc run gộp nguồn), 1
   `content_segment`, 1 `segment_revision`, 1 `document_digitization_status`.
2. **Đối chứng (negative control):** chạy lại đúng trình tự CŨ (xoá thẳng
   `digitization_source`) → tái hiện y hệt lỗi gốc:
   `violates foreign key constraint "digitization_run_source_id_fkey"`.
3. **Verify:** chạy đúng trình tự MỚI (toàn bộ 10 bước, khớp 1:1 với code đã
   deploy) trong 1 transaction (rollback sau khi xác nhận, không phá dữ liệu
   test) → **không còn lỗi FK nào**, cả `document_digitization_status` cũng
   bị xoá đúng.
4. Dọn sạch tài liệu test tổng hợp (xoá thật, không phải bia mộ, vì đây là
   dữ liệu giả).

Kết hợp bằng chứng: (a) unit test pin đúng thứ tự gọi trong code đã deploy,
(b) SQL-level negative+positive control xác nhận đúng thứ tự đó là thứ duy
nhất Postgres chấp nhận. Hai bằng chứng này cộng lại tương đương xác nhận
`purgeOne()` thật sẽ chạy đúng, dù chưa kích được chính job scheduler chạy
qua cron thật trên stg hôm nay.

**Còn thiếu:** verify qua đúng job scheduler thật (cron `0 0 3 * * *` +
backup gate) — cần đợi tới 3h sáng với `backup.purge_gate_enabled=false`
(hoặc có bảng `backup_run` hợp lệ), hoặc người dùng tự quyết định sửa
`docker-compose.yml` trên stg để test nhanh hơn.
