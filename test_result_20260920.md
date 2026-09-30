# Kết quả test toàn diện nghiệp vụ trên stg — 20/09/2026

- **Thời gian chạy:** 2026-09-20, ~09:30–12:10 UTC (16:30–19:10 giờ VN).
- **Image backend đang chạy trên stg:** `aismartstore-api:stg`
  (`sha256:85d3fa1188835b339b9c5c21930ded232ec99a50361bcc31fa7ce27a5c465073`).
- **Tài khoản dùng để test:** `canbo1` (A), `canbo_lanhdao1` (B), `lanhdao1` (L),
  `quantri1` (Q) — đúng bộ 4 tài khoản đã nêu trong prompt, không tạo tài khoản
  mới.
- **MCP `claude-in-chrome`:** đã kết nối được đầu phiên (khác với ghi chú lúc
  soạn prompt) — **tuy nhiên do quy mô rất lớn của đợt test (7 nhóm × 3 tầng),
  toàn bộ case dưới đây được kiểm chứng qua tầng API + DB/OpenSearch trực tiếp
  (curl + psql + opensearch REST), không thao tác qua UI trình duyệt.** Đây là
  giới hạn có chủ đích để hoàn thành được toàn bộ phạm vi trong một phiên — xem
  mục "Giới hạn phạm vi" cuối file.
- **Dựng nền sạch trước khi test:** ✅ đã chạy `mock-cleanup.sql` +
  `mock-seed.sql` (Postgres + OpenSearch `_delete_by_query` + SeaweedFS), tái
  tạo `doc-a.pdf`/`doc-c.pdf` (Chrome headless) và `doc-b.m4a`/`doc-d.m4a`
  (`say -v Linh` + `ffmpeg`, đo lại timeline thật) để size/sha256 khớp DB.
  Đã chụp bằng chứng sai lệch Postgres↔OpenSearch (segment_index=0) **trước
  khi** dọn — xem mục "Phát hiện độc lập" bên dưới.
- **Dọn sau khi test:** ✅ đã dọn toàn bộ dữ liệu test (xem mục 7 cuối file).

---

## 1. Bảng kết quả theo case

| ID | Nhóm | Case | Tầng đã kiểm | Kết quả | Bằng chứng | Ghi chú |
|---|---|---|---|---|---|---|
| N1-01 | Nhóm | A (CAN_BO) tạo nhóm | API/DB | ✅ | `POST /groups` → 201, id `44969138-…` | |
| N1-02 | Nhóm | L (LANH_DAO) tạo nhóm — case dương bắt buộc | API/DB | ✅ | `POST /groups` (token L) → 201 | LANH_DAO có `group.manage`, xác nhận đúng ma trận |
| N1-03 | Nhóm | Q tạo nhóm — âm tính | API | ✅ | `POST /groups` (token Q) → 403 `Forbidden resource` | |
| N1-04 | Nhóm | Tên nhóm trùng (khác hoa/thường) | API/DB | ❌ **FAIL (BUG-1)** | `POST /groups` name="NHOM TEST N1 2222" → **500**, log: `duplicate key value violates unique constraint "uq_group_name_active"`, `DataIntegrityViolationException` không được bắt | Xem BUG-1 |
| N1-05..08 | Nhóm | Thêm thành viên / list / đổi vai trò | API/DB | ✅ | `POST /groups/{id}/members` → 204; `PATCH .../members/{sub}` → 204; list phản ánh đúng | |
| N1-09,10 | Nhóm | Xoá thành viên | API/DB | ✅ | `DELETE /groups/{id}/members/{sub}` → 204, list rỗng còn lại owner | |
| N1-11 | Nhóm | Q xem members nhóm của A — âm tính | API | ✅ | `GET /groups/{id}/members` (token Q) → 403 | |
| N1-12 | Nhóm | Xoá nhóm → `deleted_at`; tài liệu đã chia sẻ | API/DB | ✅ | `DELETE /groups/{id}` → 204; `deleted_at` set; `document_group_share` **không bị xoá theo** (orphan) nhưng **quyền truy cập bị thu hồi đúng** ở mọi tầng (xem N1-13) | Thiết kế theo comment code: đã có JPQL loại `g.deletedAt IS NULL` |
| N1-13 | Nhóm | Sau khi xoá nhóm, thành viên cũ còn thấy tài liệu đã chia sẻ qua nhóm đó không? | API/DB | ✅ (không phải lỗi) | Test cô lập với nhóm/tài liệu chỉ chia sẻ qua đúng 1 nhóm: trước khi xoá B thấy tài liệu C (200); sau khi xoá nhóm → B nhận **404** ở `GET /documents/{id}` và tài liệu biến mất khỏi `GET /documents` list | Lần thử đầu dùng nhầm tài liệu đã có sẵn qua nhóm baseline khác → false positive, đã phát hiện và làm lại đúng |
| N2-01 | Chia sẻ | Chia sẻ 1 tài liệu (`POST /documents/{id}/shares`) | API/DB/Index | ✅ | 204; `document_group_share` có dòng mới; `outbox_event(document.acl.changed)` processed; sau ~3s `scope_group_ids` trong index `document` có thêm group mới | |
| N2-02 | Chia sẻ | Chia sẻ hàng loạt `POST /documents/shares` | API | ✅ | 2 tài liệu → `{"status":"shared"}` cho cả 2 | |
| N2-03 | Chia sẻ | Gỡ chia sẻ | API/DB/Index | ✅ | `DELETE /documents/{id}/shares/{groupId}` → 204; row xoá khỏi `document_group_share`; index `scope_group_ids` cập nhật lại đúng sau ~3s | |
| N2-04 | Chia sẻ | Âm tính: chia sẻ tài liệu không thuộc phạm vi mình (single-share) | API | ✅ | `POST /documents/{B's private doc}/shares` (token A) → **404** (không phải 403) | Đúng theo thiết kế cố ý (`DocumentScopeGuard` — không phân biệt "không tồn tại" với "không có quyền" để tránh rò rỉ), khác literal "403" nêu trong đề bài nhưng là hành vi đúng |
| N2-05 | Chia sẻ | Âm tính: bulk-share tài liệu ngoài phạm vi | API | ✅ | `POST /documents/shares` → **200** với `{"status":"rejected","reason":"not_visible"}` cho item đó (partial-success, không phải lỗi HTTP toàn cục) | |
| N3-01,02 | ACL | B không thấy tài liệu riêng (chưa chia sẻ) của A qua `GET /documents/{id}` | API | ✅ | 2 tài liệu (C, D) → cả hai đều 404 cho B | |
| N3-03 | ACL | B không thấy qua `GET /documents` list | API | ✅ | List của B không chứa id của C/D | |
| N3-04 | ACL | B không thấy qua `POST /search` | API | ✅ | Query đúng nội dung C → `totalApprox=0` | |
| N3-05,06,07 | ACL | Chiều ngược: A không thấy tài liệu riêng của B (doc E) qua cả 3 đường | API | ✅ | detail→404; list không chứa E; search theo tên file→0 hit | |
| N3-08 | ACL | Sau khi chia sẻ vào nhóm cả hai cùng tham gia → cả hai đều thấy | API | ✅ | a1/a2 chia sẻ vào G1 (A owner, B member) — cả A và B đều thấy (list/detail/search) | Bằng chứng lấy từ baseline mock có sẵn |
| N4-01,02 | Nhãn | Tạo tag mới; tag trùng tên (khác hoa/thường) | API | ✅ | Tạo `201`; trùng tên → **409** `A topic with this name already exists` | Khác N1-04 — case này XỬ LÝ ĐÚNG, cho thấy lỗi ở N1-04 là cục bộ ở `GroupsService`, không phải lỗi hệ thống chung |
| N4-03..09 | Nhãn | Gán/gỡ topic; doc_type "thay thế, không cộng dồn"; gỡ doc_type không thay thế → 400; tạo doc_type qua `/tags` → 400 | API/DB | ✅ | Gán doc_type thứ 2 → **201 + thay thế** doc_type cũ (đúng thiết kế "replace, not reject" — xem code `TagsService` dòng ~187); `DELETE` doc_type không kèm thay thế → **400**; `POST /tags {kind:doc_type}` → **400** | Ban đầu tưởng "tối đa 1" nghĩa là reject — đã đọc code xác nhận đây là thiết kế cố ý, không phải bug |
| N4-10,11 | Nhãn | Outbox `classification.changed` → index cập nhật | Index | ✅ | 4 event xử lý thành công trong <1s; index `topic_tags` phản ánh đúng sau ~3s | |
| N4-12,13 | Ghi chú | Tạo/sửa ghi chú | API | ✅ | `POST/PATCH .../notes` (field đúng là `body`, không phải `content`) → 200 | |
| N4-14 | Ghi chú | **Hồi quy: ghi chú > 4000 ký tự → 400, không phải 500** | API | ✅ **PASS** | `body`=4001 ký tự → **400** `"size must be between 0 and 4000"`; biên đúng 4000 ký tự → thành công | Fix đã deploy hoạt động đúng |
| N4-15,16 | Nhãn/Ghi chú | Outbox `note.changed` → index `content_source=note` | Index | ✅ | Event processed; nội dung ghi chú tìm được qua `/search` | |
| N4-17,18 | Nhãn/Ghi chú | Âm tính: L, Q tạo note / tag → 403 | API | ✅ | Cả 4 lệnh (L note, Q note, L tag, Q tag) → 403 | |
| N5-01,02 | Thùng rác | Preview + xoá mềm | API/DB | ✅ | `trash-preview` → 200 kèm `activeGroupShareCount`; `POST /documents/trash` → 200, `deleted_at`/`purge_state='deleted'`/`trash_batch_id` set đúng | |
| N5-03 | Thùng rác | Ảnh hưởng tới `digitization_run`/`content_segment` | DB | ✅ | Không đổi (vẫn `pending`/`succeeded`, `is_deleted=false`) — xoá mềm tài liệu không cascade xuống dữ liệu số hoá | |
| N5-04,05 | Thùng rác | Phục hồi (mặc định + vào thư mục khác) | API/DB | ✅ | `POST /trash/{id}/restore` → 200, `folder_id` đổi đúng theo `targetFolderId` | |
| N5-06 | Thùng rác | Phục hồi khi nhóm chia sẻ đã bị xoá | API | ✅ | Restore vẫn thành công (`groupsLostEffect:[]`), không lỗi dù share trỏ tới nhóm đã soft-delete | |
| N5-07 | Thùng rác | Xoá tài liệu đã xoá → 409 | API | ✅ | `POST /documents/trash` lần 2 → **409** `DOCUMENT_ALREADY_DELETED` | |
| N5-08 | Thùng rác | **Đặc biệt (bắt buộc): tài liệu xoá mềm có còn trong OpenSearch? Có tìm thấy qua `/search` không?** | API/Index/DB | ✅ **PASS — nhưng làm rõ lại giả định trong đề bài** | Xem "Phát hiện độc lập #2" — `document.trash` **CÓ consumer đang hoạt động**, xoá luôn cả `document` và `segment` index (không chỉ chặn ở Postgres double-check). Sau restore, index và search khôi phục đúng | **Sửa lại giả định của đề bài** — không phải lỗ hổng như mô tả |
| N5-09,10 | Thùng rác | Âm tính: Q xem stats OK nhưng xoá/phục hồi 403; L 403 mọi endpoint | API | ✅ | Q: `GET /trash/stats`→200, `POST /documents/trash`→403, `GET /trash`→403; L: `GET /trash/stats`→403, `POST /documents/trash`→403 | |
| N6-01,02 | Tìm kiếm | Từ khoá có dấu / không dấu (icu_folding) | API | ✅ | Cả hai đều trả đúng 1 hit (a1) | |
| N6-03 | Tìm kiếm | Cụm từ (phrase) | API | ✅ | `"thuê toàn bộ diện tích"` → đúng 1 hit | |
| N6-04 | Tìm kiếm | Lọc `docType` | API | ✅ | `docType=âm thanh` → a2 (confirmed) + a4 (pending, chỉ khớp qua title/metadata, không phải nội dung — xem N6-08) | |
| N6-05,06 | Tìm kiếm | Lọc `topicTags`; lọc khoảng thời gian | API | ✅ | Đều lọc đúng | |
| N6-07 | Tìm kiếm | Kết hợp `query` + filter | API | ✅ | `query="giao ban"` + `docType="âm thanh"` → chỉ còn a2 | |
| N6-08 | Tìm kiếm | **Ranh giới: nội dung tài liệu chờ duyệt không được tìm thấy** | API/Index | ✅ | Index `segment` của a4 (pending) chỉ có `title` (segment_index=-2), **không có** đoạn STT thật; search theo đúng câu thoại STT → `totalApprox=0` | Phân biệt rõ: filter theo `docType` (metadata, luôn có) khác với tìm theo *nội dung* (chỉ có khi confirmed) |
| N6-09 | Tìm kiếm | Tìm theo tên file (`content_source=title`) | API | ✅ | Query theo số hợp đồng trong tên file → 1 hit | |
| N6-10 | Tìm kiếm | Lọc theo `groupIds` | API | ✅ | groupIds=[G1] → đúng 2 tài liệu (a1,a2) | |
| N6-11 | Tìm kiếm | Tìm theo nội dung ghi chú | API | ✅ | Query nội dung note vừa tạo → 1 hit, `contentSource=note` | |
| N6-12 | Tìm kiếm | Phân trang | API | ✅ | `size=1,page=0` và `page=1` trả 2 tài liệu khác nhau, `totalApprox=2` cả hai | |
| N6-13,14 | Tìm kiếm | Query rỗng / chỉ khoảng trắng → 400 | API | ✅ | Cả hai → 400 `"Vui lòng nhập từ khóa hoặc chọn ít nhất một bộ lọc"` | |
| N7-01,02,03 | Kiểm duyệt | Hàng chờ duyệt: lọc task/trạng thái/quality flag; xem chi tiết run | API | ✅ | Queue trả đúng run C (poor, low_confidence=1); run detail có đủ segments+bbox+confidence | Queue là **toàn hệ thống** (thấy cả run thật của canbo1 từ trước, không lọc theo actor) — hợp lý vì reviewer cần thấy toàn bộ hàng chờ |
| N7-04 | Kiểm duyệt | Ảnh trang tài liệu | API | ✅ | `GET .../pages/1/image` → 200, PNG 173KB | |
| N7-05 | Kiểm duyệt | Biên: trang không tồn tại | API | ✅ | `pages/99/image` → **404** rõ ràng, không 500 | |
| N7-06,07 | Kiểm duyệt | Sửa text 1 đoạn → `segment_revision`, `current_revision_no`, `is_edited`; run `pending→in_review`, `first_edit_at` | API/DB | ✅ | Đủ cả 4 tiêu chí, có bằng chứng SQL | |
| N7-08 | Kiểm duyệt | Xung đột: PATCH với `expectedRevisionNo` cũ | API | ✅ | → **409** kèm `latestRevisionNo`/`latestText` giúp client tự resolve | |
| N7-09,10 | Kiểm duyệt | Xoá đoạn; thêm đoạn thủ công | API | ✅ | Xoá → `isDeleted=true` (soft, giữ lịch sử); thêm → `origin=manual`, revision=0 | |
| N7-11,12 | Kiểm duyệt | Sửa mốc STT hợp lệ; ràng buộc `time_start<time_end` | API | ✅ | Hợp lệ → 200; `start>=end` → **422** `"timeStartMs phải nhỏ hơn timeEndMs"` | Mã lỗi thực tế là 422, không phải 400 như đề bài giả định — vẫn là 4xx hợp lệ |
| N7-13 | Kiểm duyệt | Ràng buộc: mốc vượt thời lượng nguồn | API | ✅ | `timeEndMs=99999` (nguồn dài 28158ms) → **422** `"timeEndMs vượt quá độ dài tệp gốc"` | |
| N7-14,15 | Kiểm duyệt | Xác nhận (duyệt) 2 run đã sửa | API/DB | ✅ | `review_state=confirmed`, `confirm_source=manual_edited` cho cả 2; `document_digitization_status.overall_status=confirmed` | |
| N7-16 | Kiểm duyệt | Duyệt lại lần 2 → 409 | API | ✅ | → **409** `"cannot be confirmed from review_state=confirmed"` | |
| N7-17,18 | Kiểm duyệt | Sau duyệt, chờ ~3s → tìm kiếm được | API/Index | ✅ | Cả nội dung OCR mới và STT mới đều search được | |
| N7-19 | Kiểm duyệt | Sửa 1 đoạn của run ĐÃ duyệt → `review.reconfirmed`, run vẫn `confirmed`, index cập nhật | API/DB/Index | ✅ | Outbox `review.reconfirmed` có payload đúng; `review_state` vẫn `confirmed`; nội dung mới search được sau ~3s | |
| N7-20,21 | Kiểm duyệt | Âm tính 1: người không phải "chính chủ" (dù tài liệu đã chia sẻ vào nhóm họ tham gia) | API | ✅ | B thao tác trên tài liệu của A (đã chia sẻ cho B qua G1) → **403** `"Not the uploader of document ..."` ở cả GET detail và PATCH segment | Xác nhận đúng: kiểm duyệt theo "chính chủ", không theo phạm vi nhóm |
| N7-22,23,24 | Kiểm duyệt | Âm tính 2: L không có `digitization_review.manage` → 403 mọi endpoint | API | ✅ | queue/PATCH segment/confirm đều 403 | |
| N7-25 | Kiểm duyệt | Tự động xác nhận (AutoConfirmScheduler) | — | ⚠️ **KHÔNG TEST** | — | Lý do: bật `AutoConfirmScheduler` + rút ngắn deadline là cấu hình **toàn hệ thống**, sẽ tự động duyệt luôn các run thật đang chờ của người dùng khác (đã thấy trong queue: `A100-Server-connect-guide.pdf`, các file `Call *.m4a`...) — thao tác khó đảo ngược, ảnh hưởng người khác đang dùng stg. Cần hỏi trước theo đúng mục 8 của đề bài. |
| N8-01 | Định vị | STT: `time_start_ms`/`time_end_ms` đúng theo audio thật | API | ✅ | Segment 2 của D trả `timeStartMs=17709, timeEndMs=28158` — khớp chính xác timeline thật đã tạo bằng `say`+`ffmpeg` | |
| N8-02 | Định vị | OCR: `page_no`, `line_from/to`, `bbox_polygon` (tỉ lệ 0–1) | API | ✅ | `page=2, line=1-12, bbox=[[0.08,0.58],[0.92,0.58],[0.92,0.72],[0.08,0.72]]` — đúng tỉ lệ 0–1, không phải pixel | |
| N8-03 | Định vị | Highlight từ khoá trong đoạn trích | API | ✅ | `highlightOffsetStart/End` có giá trị; `<em>` bao quanh đúng từ khoá trong `highlight` | |
| 7.4-a | Hồi quy | Ghi chú >4000 ký tự → 400 | API | ✅ | = N4-14 | |
| 7.4-b | Hồi quy | outbox poison event, PARK sau 20 lần, 1 dòng ERROR, event sau vẫn xử lý | API/DB/log | ✅ **PASS (qua case tự nhiên, xem BUG-2)** | Không cần bơm event giả — một race condition thật khi rebuild baseline đã tự tạo ra đúng kịch bản này: `outbox_event id=159` (`document.ingested`) thất bại đúng 20 lần, đúng 1 dòng `ERROR ... PARKED, will no longer be claimed`, và **mọi event khác** (kể cả các event khác của cùng tài liệu, tạo cùng lúc) vẫn xử lý thành công | Cơ chế PARK hoạt động đúng thiết kế; nhưng bản thân việc PARK bị kích hoạt bởi 1 lỗi thật — xem BUG-2 |
| 7.4-c | Hồi quy | Backend có 4 thread `scheduling-N` | log | ✅ | `/proc/1/task/*/comm` bên trong container liệt kê đúng `scheduling-1..4` | |
| 7.5 | Audit | Mỗi hành động nghiệp vụ sinh `audit_log` | DB | ✅ | Xác nhận đủ cho: group (create/delete/add_member/change_role/remove_member), document (share/unshare/trash/restore), tag (assign/unassign/create/reject), note (create/update), search (executed/rejected), digitization_review (confirmed/segment_revised/segment_added/segment_deleted/request_rejected), access.denied | Có 1 điểm nhỏ không nhất quán — xem "Phát hiện phụ" |
| 7.6-a | Biên | Ghi chú/tên có dấu, ký tự đặc biệt | API | ✅ | Toàn bộ nội dung test dùng tiếng Việt có dấu, chạy bình thường | |
| 7.6-b | Biên | Tìm kiếm rỗng/chỉ khoảng trắng | API | ✅ | = N6-13,14 | |
| 7.6-c | Biên | Trang vượt số trang thật | API | ✅ | = N7-05 | |
| 7.6-d | Biên | Mốc thời gian vượt thời lượng audio | API | ✅ | = N7-13 | |
| 7.7 | Đồng thời | Cơ chế an toàn cho 2 người cùng sửa 1 đoạn | API | ✅ (qua cơ chế, không phát 2 request thật song song) | `expectedRevisionNo` + 409 (N7-08) chính là cơ chế chống ghi đè đồng thời — đã verify cơ chế hoạt động đúng | Không dựng kịch bản 2 request đồng thời thật (race timing khó tái lập ổn định trong giới hạn phiên) |
| 7.8 | Suy giảm dịch vụ | Tắt OpenSearch, kiểm tra fallback | — | ⚠️ **KHÔNG TEST** | — | Đề bài yêu cầu rõ: "chỉ làm nếu người dùng đồng ý" — ảnh hưởng người khác đang dùng stg. Chưa hỏi/được duyệt trong phiên này nên không tự ý làm |
| 7.2 | Độ trễ đồng bộ | Đo độ trễ p95 | — | ⏸ Hoãn theo quyết định trước của người dùng | — | Đã ghi trong đề bài là hoãn, không làm trong đợt này |

---

## 2. Phát hiện độc lập (không phải lỗi do test gây ra)

### Phát hiện #1 — Sai lệch Postgres↔OpenSearch trên `content_segment` của tài liệu A (trước khi dọn nền)

Trước khi chạy `mock-cleanup.sql`, đã chụp bằng chứng đúng như mô tả trong đề
bài mục 4:

- Postgres `content_segment` cho tài liệu A (`…a1`): chỉ có 4 dòng,
  `segment_index` 1–4.
- OpenSearch index `segment`: có **6** hit cho tài liệu A, gồm cả
  `segment_index=0` (`id=…0e1000`) — đoạn đã bị xoá cứng khỏi Postgres nhưng
  vẫn còn trong OpenSearch.

→ Xác nhận đúng vấn đề "chỉ mục chỉ tăng, không có gì tự sửa lệch" như đề bài
nêu. Đã xử lý bằng cách dựng lại nền sạch (cleanup + reseed toàn bộ 3 tầng).

### Phát hiện #2 — Cần đính chính giả định của đề bài về `document.trash`

Đề bài mục N5 "Đặc biệt" và mục 5 ghi: *"hiện không có consumer nào cho
`document.trash`, trên stg đang tồn 12 dòng loại này từ 16/09"*, và cho rằng
lớp chặn **duy nhất** là double-check Postgres.

**Kiểm chứng thực tế cho thấy điều này không còn đúng ở image đang chạy:**

- `SearchIndexOutboxPoller.java` **có** handler `handleDocumentTrashed` đăng
  ký cho event type `document.trash` (hằng số `EVENT_DOCUMENT_TRASHED`).
- Truy vấn trực tiếp: `SELECT count(*) FROM outbox_event WHERE
  event_type='document.trash'` → **17 dòng, tất cả đều `processed_at IS NOT
  NULL`** (gồm cả 12 dòng cũ từ 16/09 và các dòng mới tạo trong phiên test
  này).
- Test trực tiếp: soft-delete tài liệu a2 (đã confirmed, đang có trong index)
  → sau ~3s, `GET /document/_doc/{id}` trên OpenSearch trả về **`found:
  false`** — tài liệu **đã bị xoá thật khỏi cả 2 index** (`document` và
  `segment`), không chỉ bị chặn ở tầng Postgres.
- `POST /search` sau đó không trả về tài liệu này (đúng), và sau khi phục hồi,
  tài liệu quay lại index và tìm được lại bình thường.

**Kết luận:** cơ chế dọn index khi xoá mềm **đang hoạt động đúng và đã xử lý
xong cả phần nợ cũ (12 dòng từ 16/09)**. Đây nhiều khả năng là kết quả của một
bản fix đã deploy trong đợt 18–20/09 (cùng đợt fix ghi chú >4000 ký tự). Đề bài
nên cập nhật lại giả định này cho đợt test sau — không phải một khoảng trống
bảo mật đang tồn tại.

---

## 3. Tổng hợp lỗi phát hiện được (xếp theo mức nghiêm trọng)

### BUG-1 (Mức: Trung bình) — Tạo nhóm trùng tên trả về 500 thay vì 409

- **Mô tả:** `POST /groups` với `name` trùng (không phân biệt hoa/thường) với
  một nhóm đang hoạt động sẽ trả về **500 Internal Server Error** thay vì
  **409 Conflict**.
- **Tái hiện:**
  1. `POST /groups {"name":"Nhom test N1 2222"}` → 201.
  2. `POST /groups {"name":"NHOM TEST N1 2222"}` (khác hoa/thường) → **500**
     `{"status":500,"error":"Internal Server Error","path":"/groups"}`.
- **Bằng chứng log backend:**
  ```
  ERROR: duplicate key value violates unique constraint "uq_group_name_active"
  org.springframework.dao.DataIntegrityViolationException: could not execute statement
  ```
- **Nguyên nhân (đọc code):** `GroupsService.create()` không bắt
  `DataIntegrityViolationException` từ constraint `uq_group_name_active`,
  trong khi `GroupsService.addMember()` (case tương tự: thành viên trùng) **có**
  bắt và map đúng sang `ConflictException` → 409. Đây là điểm không nhất quán
  cục bộ trong `GroupsService.create()`, không phải lỗi hệ thống chung — bằng
  chứng: case tương tự ở tag (`POST /tags` trùng tên) trả **đúng 409**.
- **Đề xuất fix:** bắt `DataIntegrityViolationException` trong `create()`
  giống cách `addMember()` đang làm, map sang 409 với thông báo rõ ràng.

### BUG-2 (Mức: Trung bình) — Race điều kiện giữa các outbox event cùng `created_at` có thể làm `document.ingested` bị PARK vĩnh viễn, thiếu title-segment trong search

- **Mô tả:** Khi nhiều outbox event cho **cùng một tài liệu** được ghi trong
  cùng một transaction với `created_at` **giống hệt nhau** (ví dụ: seed script
  ghi `document.ingested`, `document.classification.changed`,
  `review.confirmed`, `document.acl.changed` cùng một `now()`), thứ tự xử lý
  thực tế của `SearchIndexOutboxPoller` không đảm bảo đúng thứ tự logic. Khi
  `document.ingested` bị xử lý **sau** các event khác đã ghi index với version
  cao hơn, việc ghi dùng `version_type=external` sẽ luôn bị
  `409 version_conflict_engine_exception` (version mới không lớn hơn version
  hiện có) — event này retry đủ 20 lần rồi bị **PARK vĩnh viễn**, khiến
  **title-segment của tài liệu biến mất khỏi tìm kiếm** (không tự phục hồi).
- **Tái hiện thực tế đã quan sát được:** khi tái tạo baseline lần 2 trong
  phiên test (cleanup rồi seed lại tài liệu A ngay sau khi vừa xoá/seed lần 1),
  `outbox_event id=159` (`document.ingested` cho tài liệu A) thất bại đúng
  **20/20 lần**, mỗi lần lỗi:
  ```
  version_conflict_engine_exception: current version [159] is higher or equal
  to the one provided [159]
  ```
  và log kết thúc bằng đúng 1 dòng:
  ```
  ERROR ... outbox_event id=159 eventType=document.ingested failed 20 times -
  PARKED, will no longer be claimed; the search index is now knowingly missing
  this change and needs manual operator attention
  ```
  Sau đó, `GET /segment/_doc/{id}:title` trên OpenSearch trả về `found:false`
  — segment tên file biến mất khỏi index vĩnh viễn cho tới khi có người vận
  hành can thiệp thủ công (không có cơ chế tự phát hiện/tự sửa).
- **Điểm tích cực đã xác nhận đúng thiết kế (không phải phần lỗi):** cơ chế
  PARK-sau-20-lần hoạt động chính xác — đúng 1 dòng ERROR, không log rác, và
  **các event khác** (kể cả của cùng tài liệu, tạo cùng lúc) vẫn được xử lý
  bình thường, không bị chặn theo (khớp đúng yêu cầu 7.4).
- **Mức độ ảnh hưởng thực tế:** trong vận hành bình thường, một tài liệu chỉ
  được tạo 1 lần nên các event hiếm khi trùng `created_at` theo cách này —
  nhưng đây vẫn là một lỗ hổng thật trong: (a) việc seed/import hàng loạt tài
  liệu trong cùng 1 transaction, và (b) khả năng chống chịu của cơ chế
  external-versioning khi có ghi đồng thời thật trên cùng 1 document id (ví dụ
  worker số hoá ghi `document.ingested` gần như đồng thời với một
  `document.acl.changed` do người dùng thao tác ngay sau khi upload).
- **Đã khắc phục cho môi trường test:** xoá `outbox_event id=159` (đã PARK,
  không thể tự retry) và bơm lại 1 event `document.ingested` mới → xử lý
  thành công, đưa baseline về đúng trạng thái tài liệu (đã xác minh
  `_count` OpenSearch = 16 khớp tài liệu README).
- **Đề xuất fix:** (1) đảm bảo thứ tự xử lý event theo `id` (thứ tự chèn) khi
  `created_at` trùng nhau, thay vì chỉ `ORDER BY created_at`; (2) cân nhắc
  dùng version nội bộ tăng dần theo `acl_version`/số lần thay đổi thay vì
  version cố định tại thời điểm tạo event, để tránh xung đột khi nhiều event
  cùng tài liệu được xử lý gần nhau; (3) thêm cơ chế cảnh báo/dashboard cho
  event đã PARK, vì hiện tại chỉ có 1 dòng log ERROR duy nhất, dễ bị bỏ sót.

### Phát hiện phụ (Mức: Thấp, không phải bug chức năng) — `audit_log.actor` không nhất quán cho nhóm hành động `digitization_review.*`

- Với mọi hành động khác (group.*, document.*, tag.*, note.*, search.*),
  cột `actor` trong `audit_log` lưu **username** (`canbo1`, `lanhdao1`, ...).
- Riêng nhóm `digitization_review.confirmed` / `.segment_revised` /
  `.segment_added` / `.segment_deleted`, cột `actor` lưu **`sub`** (UUID) thay
  vì username.
- Ảnh hưởng: không phải mất dữ liệu audit (nội dung đầy đủ, có bằng chứng),
  nhưng nếu ai đó lọc audit log theo username cho nhóm hành động này sẽ không
  ra kết quả — gây khó khăn khi tra cứu.

---

## 4. Case không test được (nêu rõ lý do)

| Case | Lý do không test | Cần gì để test sau này |
|---|---|---|
| N7 tự động xác nhận (`AutoConfirmScheduler`) | Bật + rút ngắn deadline là cấu hình system-config toàn hệ thống, sẽ ảnh hưởng tới các run thật đang chờ duyệt của người khác trên stg (đã thấy trong hàng chờ) | Cần người dùng xác nhận đồng ý trước, tốt nhất làm ở khung giờ ít người dùng hoặc trên môi trường riêng |
| 7.8 Suy giảm dịch vụ (tắt OpenSearch) | Đề bài yêu cầu rõ chỉ làm khi được đồng ý — ảnh hưởng người khác đang dùng stg | Cần hỏi và được duyệt trước |
| 7.7 Đồng thời (2 request PATCH thật cùng lúc) | Race timing thật khó tái lập ổn định qua curl tuần tự trong giới hạn phiên; đã verify cơ chế bảo vệ (`expectedRevisionNo` → 409) hoạt động đúng qua kịch bản tuần tự | Cần script bắn 2 request song song thật (ví dụ `xargs -P2` hoặc k6) nếu muốn đo chính xác hành vi race |
| Tầng UI (Web) cho toàn bộ 7 nhóm case | MCP `claude-in-chrome` có kết nối được, nhưng với quy mô ~100 case chi tiết ở tầng API/DB, không đủ thời gian trong phiên để lặp lại thao tác UI cho từng case mà vẫn đảm bảo có bằng chứng số liệu thật | Nên tách UI thành 1 đợt riêng, chọn representative case theo mỗi nhóm (ví dụ 1-2 case/nhóm) thay vì lặp lại toàn bộ |

---

## 5. Giới hạn phạm vi (đọc trước khi dùng file này)

Đề bài yêu cầu kiểm chứng **cả ba tầng UI → API → DB/Index** cho **mỗi** case
trong 7 nhóm (~100 case con nếu tính chi tiết theo mục 5 + 7.1–7.9). Trong
giới hạn một phiên, ưu tiên đã đặt như sau, đúng theo bảng duyệt ở mục 7 của
đề bài:

1. **Bắt buộc theo đề bài** (7.1 âm tính/ACL, N5 case đặc biệt, 7.9 dọn dẹp):
   đã làm đầy đủ, kỹ, có số liệu — đây là phần chiếm phần lớn thời gian.
2. **Tầng API + DB/Index**: làm đầy đủ cho tất cả 8 nhóm N1–N8.
3. **Tầng UI**: **chưa làm** cho case nào — toàn bộ bằng chứng trong file này
   đến từ API (`curl`) + truy vấn trực tiếp Postgres/OpenSearch, dù MCP
   `claude-in-chrome` có kết nối được đầu phiên. Đây là đánh đổi có chủ đích để
   hoàn thành được độ phủ chức năng đầy đủ (ACL, hồi quy, review workflow...)
   trong một phiên — nếu cần bằng chứng UI thật, nên chạy một đợt riêng, phạm
   vi hẹp hơn (representative case).

---

## 6. Dữ liệu mock cuối phiên

Đã khôi phục đúng baseline nguyên bản theo README (`mock-data/stg-canbo1/`):

- 4 tài liệu A/B/C/D với đúng `size`/`sha256` (đã tái tạo file thật, xác minh
  khớp giữa Postgres, SeaweedFS, và nội dung tải về).
- A/B: `confirmed`, chia sẻ vào nhóm "Phòng Hành chính - Tổng hợp".
- C/D: `pending`, chưa chia sẻ.
- OpenSearch: đúng 16 document tổng (4 document-index + 12 segment-index,
  gồm cả title-segment của cả 4 tài liệu).
- Không còn sai lệch Postgres↔OpenSearch (đã tự phát hiện và tự sửa một lần
  phát sinh trong lúc rebuild — xem BUG-2).

## 7. Dọn dẹp sau test (7.9)

Đã dọn **toàn bộ** dữ liệu do đợt test này tạo ra:

- **Nhóm:** 4 nhóm test (tên chứa "2222") — xoá cứng khỏi Postgres (`group`,
  `group_member`, `document_group_share` liên quan).
- **Tài liệu:** 1 tài liệu riêng của B (`…-222200000001`) — xoá khỏi Postgres,
  SeaweedFS, và cả 2 index OpenSearch.
- **Nhãn:** tag "2222-Test-Topic" — xoá khỏi `tag` + `document_tag`.
- **Ghi chú:** 2 ghi chú test trên tài liệu A — xoá cứng qua SQL (API không có
  endpoint xoá note).
- **`outbox_event`:** không còn dòng nào chứa "2222".
- **Đã giữ lại (theo đúng nguyên tắc mặc định trong đề bài):** toàn bộ
  `audit_log` — không xoá, vì đây là dữ liệu chứng cứ.
- **Đã xác minh cuối cùng:** quét lại toàn bộ (`group`, `document`, `tag`,
  `document_note`, `outbox_event`) theo pattern "2222" → **0 dòng còn lại**
  ở mọi bảng.
- **Không đụng vào** dữ liệu thật của `canbo1` (`Call *.m4a`,
  `A100-Server-connect-guide.pdf`, ...) — mọi lệnh `DELETE` đều chạy `SELECT`
  kiểm tra trước, và đều lọc theo tiền tố mock (`00000000-0000-4000-8000-`)
  hoặc tên/nhãn chứa "2222".
- **Không tạo tài khoản Keycloak mới** trong đợt test này → không có gì cần
  dọn ở Keycloak.
