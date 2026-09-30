> Status: Draft

# Tổng quan tài liệu nghiệp vụ Stage 2

Tài liệu này tổng hợp 8 tính năng đã được khảo sát trực tiếp từ code hiện tại
của hệ thống (backend Java/Spring `com.aismartstore.backend`, frontend
Next.js `web/`). Mỗi tính năng do một agent độc lập đọc code và viết, theo
đúng template 11 mục thống nhất (`Status`, `Mục tiêu nghiệp vụ`, `Tác nhân`,
`Phạm vi`, `Luồng nghiệp vụ chính`, `Sequence diagram` (Mermaid), `Luồng
ngoại lệ`, `Dữ liệu liên quan`, `File code tham chiếu`, `Điểm cần xác nhận
thêm`). Các file vượt ~300 dòng được tách theo sub-domain (hậu tố `-a`/`-b`).

Tất cả 12 file đang ở trạng thái `Draft` — cần người review và đổi thành
`Approved` trước khi dùng làm input cho bước BA/DEV tiếp theo (theo quy ước ở
`AGENTS.md` mục 3.9).

## Danh sách file

| # | Tính năng | File | Trạng thái |
|---|---|---|---|
| 1a | Quản lý nhóm và thành viên nhóm | `01-quan-ly-nhom-chia-se-tai-lieu-a.md` | Draft |
| 1b | Chia sẻ / gỡ chia sẻ tài liệu lên nhóm | `01-quan-ly-nhom-chia-se-tai-lieu-b.md` | Draft |
| 2 | Mở rộng phạm vi dữ liệu theo nhóm | `02-mo-rong-pham-vi-du-lieu-theo-nhom.md` | Draft |
| 3a | Phân loại, gắn nhãn tài liệu (topic/doc_type) | `03-phan-loai-gan-nhan-ghi-chu-a.md` | Draft |
| 3b | Ghi chú tài liệu (document note) | `03-phan-loai-gan-nhan-ghi-chu-b.md` | Draft |
| 4a | Xóa mềm vào thùng rác | `04-xoa-mem-thung-rac-phuc-hoi-a.md` | Draft |
| 4b | Phục hồi từ thùng rác | `04-xoa-mem-thung-rac-phuc-hoi-b.md` | Draft |
| 5 | Tìm kiếm nâng cao theo nhiều tiêu chí | `05-tim-kiem-nang-cao.md` | Draft |
| 6 | Tra cứu, lọc nhật ký hoạt động (Audit Log) | `06-nhat-ky-hoat-dong.md` | Draft |
| 7a | OCR/STT — chạy pipeline & kiểm duyệt thủ công | `07-ocr-stt-kiem-duyet-a.md` | Draft |
| 7b | OCR/STT — tự động xác nhận sau 24 giờ (scheduler) | `07-ocr-stt-kiem-duyet-b.md` | Draft |
| 8 | Định vị chính xác kết quả (trang/dòng, mốc thời gian) | `08-dinh-vi-ket-qua.md` | Draft |

## Bảng tra cứu nhanh: tính năng → module code chính

| Tính năng | Module/lớp code chính (backend) | Module/trang chính (frontend) |
|---|---|---|
| Quản lý nhóm & thành viên | `groups/GroupsController`, `GroupsService`, migration `V12__init_groups.sql`, `V23__group_lifecycle.sql` | — |
| Chia sẻ/gỡ chia sẻ tài liệu lên nhóm | `groups/DocumentSharesController`, `DocumentBulkSharesController`, `DocumentGroupShareRepository`, `AclChangeOutbox`, `NotificationService` | — |
| Mở rộng phạm vi dữ liệu theo nhóm | `groups/BusinessDataScope`, `DocumentScopeGuard`, `DocumentsService.list` | — |
| Phân loại, gắn nhãn (topic/doc_type) | `tag/TagsService`, `DocTypeAssignmentService`, `DocTypeBackfillRunner`, migration `V20` | — |
| Ghi chú tài liệu | `note/NotesController`, `DocumentsService.ingestBatchFile` | — |
| Xóa mềm vào thùng rác | `trash/TrashService.trash()` | — |
| Phục hồi từ thùng rác | `trash/TrashService.restore()/restoreBatch()`, `search/SearchIndexOutboxPoller` | — |
| Hard purge (liên quan thùng rác) | `trash/TrashPurgeService.purgeOne()` | — |
| Tìm kiếm nâng cao | `search/SearchController`, `SearchServiceImpl`, `SearchQueryBuilder`, `ScopeFilterBuilder`, `SearchResultHydrator`, `MetadataFallbackSearchService` | `web/src/app/search/page.tsx` |
| Nhật ký hoạt động (Audit Log) | `audit/AuditController`, `AuditService`, `AuditInterceptor`, `groups/AuditScopeFilter` | `web/src/app/admin/audit/page.tsx` |
| OCR/STT — chạy pipeline & kiểm duyệt | `digitization/DigitizationDispatchService`, `DigitizationRunLifecycleServiceImpl`, `DigitizationResultConsumer`, `review/ReviewController` | — |
| OCR/STT — tự động xác nhận sau 24h | `digitization/AutoConfirmScheduler`, `DigitizationRunRepository.autoConfirmDueBatch`, `SystemConfigService` | — |
| Định vị chính xác kết quả | `digitization/results/ContentSegment`, `search/HighlightMapper.toPosition()` | `SearchResultCard`, `SearchPageViewer`, `SegmentPlayer` |

## Phát hiện liên-tính-năng đáng chú ý nhất

Tổng hợp lại các điểm mà nhiều agent độc lập cùng chạm tới, cho thấy tính
nhất quán (và một vài điểm bất đối xứng) trong cách hệ thống áp dụng quyền
truy cập theo nhóm:

- **`BusinessDataScope` là điểm áp quyền dùng chung** cho đọc tài liệu (#2),
  xóa mềm (#4a), và một trong hai lớp lọc của tìm kiếm (#5) — cùng công thức
  `uploaderSub = user OR document được share tới nhóm user tham gia`.
- **Nhưng quyền không đối xứng ở một số thao tác**: quyền đọc/xóa mềm qua
  nhóm không mở rộng sang quyền phục hồi (`restore` chỉ cho phép
  `deletedBySub`/`uploaderSub`, #4b) hay quyền gỡ chia sẻ (`unshare` chỉ cho
  owner nhóm/người tạo share/uploader, #1b) — đã được các file tương ứng ghi
  vào mục "Điểm cần xác nhận thêm".
- **Xóa mềm không cascade xuống dữ liệu số hoá** (#4a): `document_group_share`,
  `content_segment`, `digitization_run`... vẫn còn nguyên trong Postgres, chỉ
  bị lọc khỏi query qua `deletedAt`, và bị gỡ khỏi OpenSearch. "Phục hồi dữ
  liệu số hoá" (#4b) thực chất là dựng lại index OpenSearch từ Postgres, không
  phải khôi phục dữ liệu đã mất. Hard purge (`TrashPurgeService`) lại **không**
  xóa cứng một số bảng số hoá (`digitization_run`, `content_segment`,
  `digitization_batch`...) — điểm mơ hồ giữa "chủ ý" và "thiếu sót" được ghi
  nhận trong #4a.
- **Tìm kiếm nâng cao lọc quyền hai lớp** (#5): lọc tại OpenSearch
  (`ScopeFilterBuilder`) và tái kiểm tra tại Postgres
  (`SearchResultHydrator`, cùng công thức `BusinessDataScope`) để chống lệch
  dữ liệu index — cùng cơ chế fail-closed (lỗi resolve scope → 403, không bao
  giờ rơi về "match all") xuất hiện lặp lại ở #2 và #6.
- **Định vị kết quả (#8) phụ thuộc Postgres, không phải OpenSearch**:
  `HighlightMapper.toPosition()` luôn đọc `ContentSegment` gốc để dựng toạ độ
  chính xác (bbox), OpenSearch chỉ mang dữ liệu thô phục vụ tìm kiếm nhanh.
- **Tự động xác nhận OCR/STT sau 24h (#7b) là điều kiện SQL nguyên tử, một
  chiều**: một run chỉ cần chuyển sang `in_review` một lần (dù người dùng bỏ
  dở) là vĩnh viễn mất tư cách tự động xác nhận.

## Điểm cần người dùng xác nhận trên toàn bộ tập tài liệu

Các "Điểm cần xác nhận thêm" đáng chú ý nhất từ từng file (danh sách đầy đủ
nằm trong mục tương ứng của mỗi file):

1. Cap 50 nhóm/tài liệu chỉ áp dụng cho bulk share, không áp dụng share đơn lẻ
   lặp lại (#1b) — khả năng là khoảng trống thực thi.
2. Response API danh sách tài liệu không có cờ phân biệt "của tôi" vs "nhóm
   chia sẻ" (#2).
3. `QUAN_TRI` không có quyền đọc tài liệu/quản lý nhóm/nhãn/ghi chú theo seed
   hiện tại — cần nghiệp vụ xác nhận đây là chủ ý (#1a, #2, #3a, #3b).
4. Sửa ghi chú không kiểm tra tác giả — ai có quyền `note.write` và thấy được
   tài liệu đều sửa được ghi chú người khác tạo, không giới hạn theo tác giả
   (#3b).
5. Hard purge không xóa cứng một số bảng số hoá — chưa rõ chủ ý hay thiếu sót
   (#4a, #4b).
6. Khi OpenSearch mất kết nối và có từ khóa, API trả HTTP 200 với kết quả rỗng
   (degraded mode) thay vì báo lỗi rõ ràng — có thể gây hiểu nhầm UX (#5).
7. Bộ lọc audit log R2 (Lãnh đạo) chỉ có luật thu hẹp cho `resource_type =
   document`; các loại tài nguyên khác hoàn toàn không hiển thị với R2 — code
   xác nhận là chủ ý, nên vẫn cần xác nhận nghiệp vụ (#6).
8. `auto_confirm_hours`/`auto_confirm_enabled` là cấu hình runtime
   (`system_config`), giá trị mặc định 24h/bật có thể đã bị đổi trên môi
   trường thực tế — cần đối chiếu giá trị đang chạy thật (#7b).
9. Khi `pageNo` null nhưng có `bboxPolygon`, frontend mặc định về trang 1
   (`?? 1`) — có thể gây lệch trang hiển thị (#8).
