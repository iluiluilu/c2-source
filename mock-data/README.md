# Mock data — 1 tài liệu OCR + 1 tài liệu Audio (đã số hoá, đã duyệt)

Bộ dữ liệu giả lập coi như đã đi hết pipeline: SeaweedFS (file gốc) →
PostgreSQL (metadata + content_segment) → OpenSearch (document-v1/segment-v1)
→ đã duyệt (FR-18) → đã chia sẻ vào nhóm → tìm được qua `/search` → xem được
ở `/documents/{id}` và `/digitization/runs/{runId}`.

- **Tài liệu 1 (OCR):** `Hợp đồng thuê nhà số 07/2026/HĐTN.pdf`, 2 trang, 4
  đoạn OCR, 1 đoạn đã được cán bộ sửa lỗi (minh hoạ `segment_revision`).
- **Tài liệu 2 (Audio):** `Ghi âm cuộc họp giao ban 15-09-2026.mp3`, 3 đoạn
  STT, giữ nguyên bản AI (không sửa).

## Cách dùng

1. **PostgreSQL** — chạy `01-postgres-seed.sql` bằng `psql` trên DB đã migrate
   đủ tới `V31` (dùng `documents`/`content_segment`/... đúng schema hiện tại):
   ```bash
   psql "$DATABASE_URL" -f mock-data/01-postgres-seed.sql
   ```
2. **OpenSearch** — nạp `02-opensearch-seed.bulk.ndjson` qua `_bulk` API (map
   thẳng vào 2 alias `document`/`segment` đã bootstrap sẵn khi service khởi
   động):
   ```bash
   curl -s -H 'Content-Type: application/x-ndjson' \
     -XPOST http://localhost:9200/_bulk --data-binary @mock-data/02-opensearch-seed.bulk.ndjson
   ```
3. **SeaweedFS (S3-compatible, bucket `documents`)** — repo này không sinh
   được file PDF/MP3 thật, nên chỉ mock phần **metadata trỏ tới file**
   (`storage_key`/`file_path`). Muốn `/documents/{id}/pages/{pageNo}/image`
   (OCR) hoặc phát audio thật render ra được, upload 1 file PDF/MP3 bất kỳ
   đúng object key bên dưới (`aws --endpoint-url` hoặc `mc`):
   ```bash
   # Tài liệu 1 (OCR) - bucket "documents", key "documents/00000000-0000-4000-8000-0000000000b1"
   aws --endpoint-url http://localhost:8333 s3 cp any-2-page.pdf \
     s3://documents/documents/00000000-0000-4000-8000-0000000000b1

   # Tài liệu 2 (Audio) - key "documents/00000000-0000-4000-8000-0000000000b2"
   aws --endpoint-url http://localhost:8333 s3 cp any-audio.mp3 \
     s3://documents/documents/00000000-0000-4000-8000-0000000000b2
   ```
   (`raw_output_path` — file JSON kết quả AI thô — không có màn hình nào đọc
   trực tiếp, có thể bỏ qua khi mock.)

## Danh tính dùng trong bộ mock (không có FK tới bảng user thật — sub Keycloak)

| Tên | `sub` | Vai trò trong kịch bản |
|---|---|---|
| Nguyễn Văn An | `sub-nguyen-van-an` | Cán bộ nạp + duyệt tài liệu 2, chủ nhóm 1 |
| Phạm Lan Hương | `sub-pham-lan-huong` | Cán bộ nạp tài liệu 2 + duyệt (sửa lỗi OCR) tài liệu 1, chủ nhóm 2 |
| Trần Thị Bình | `sub-tran-thi-binh` | Thành viên nhóm 1, chỉ xem |

Đăng nhập bằng Keycloak thật với `sub` khác thì **sẽ không thấy** 2 tài liệu
này trong search/documents (đúng theo ACL — xem `document_group_share`) trừ
khi bạn đổi lại các giá trị `sub-*`/`group_id` trong file SQL cho khớp user
thật của bạn.

## ID dùng để tra cứu nhanh

| Thực thể | ID |
|---|---|
| Group "Phòng Hành chính - Tổng hợp" | `00000000-0000-4000-8000-000000000001` |
| Group "Ban Giám đốc" | `00000000-0000-4000-8000-000000000002` |
| Tài liệu 1 (OCR, PDF) | `00000000-0000-4000-8000-0000000000a1` |
| Tài liệu 2 (Audio, MP3) | `00000000-0000-4000-8000-0000000000a2` |
| Run OCR (task=ocr) | `00000000-0000-4000-8000-0000000000c1` |
| Run STT (task=stt) | `00000000-0000-4000-8000-0000000000c2` |

Sau khi nạp xong, thử ngay: `POST /search` với `query: "thuê nhà"` hoặc
`query: "giao ban"`, hoặc mở `/documents/00000000-0000-4000-8000-0000000000a1`.
