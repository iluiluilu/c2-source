# Mock dữ liệu số hoá trên stg — tài khoản `canbo1`

Bộ mock 4 tài liệu (2 OCR + 2 audio/STT) phủ 2 kịch bản:

| # | Kịch bản | Tài liệu | Trạng thái |
|---|---|---|---|
| 1 | Đã lưu đủ SeaweedFS + Postgres + OpenSearch, **đã duyệt**, đã chia sẻ nhóm, tìm kiếm/hiển thị được | A (OCR, PDF), B (STT, m4a) | `review_state=confirmed`, `overall_status=confirmed` |
| 2 | Đã lưu đủ SeaweedFS + Postgres, **số hoá xong đang chờ duyệt** | C (OCR, PDF), D (STT, m4a) | `review_state=pending`, `overall_status=pending_review` |

Kịch bản 2 **cố ý chưa có trong OpenSearch**: theo hợp đồng của
`SearchIndexOutboxPoller`, chỉ run `confirmed` mới được đẩy vào chỉ mục.

## ID tra cứu nhanh

| Thực thể | ID |
|---|---|
| Nhóm "Phòng Hành chính - Tổng hợp" (canbo1 = owner) | `00000000-0000-4000-8000-000000000001` |
| Nhóm "Ban Giám đốc" (canbo1 = member) | `00000000-0000-4000-8000-000000000002` |
| Thư mục gốc `kho_so_hoa_mock` | `00000000-0000-4000-8000-0000000000f0` |
| A — Hợp đồng thuê nhà số 07-2026-HĐTN.pdf | `...-0000000000a1` / run `...-0000000000c1` |
| B — Ghi âm cuộc họp giao ban 15-09-2026.m4a | `...-0000000000a2` / run `...-0000000000c2` |
| C — Báo cáo tổng kết quý III năm 2026.pdf | `...-0000000000a3` / run `...-0000000000c3` |
| D — Ghi âm phỏng vấn cán bộ 17-09-2026.m4a | `...-0000000000a4` / run `...-0000000000c4` |

Mọi id mock đều có tiền tố `00000000-0000-4000-8000-`, nên lọc/dọn rất dễ và
không bao giờ đụng dữ liệu thật.

## Chi tiết có chủ đích

- **A** có 5 đoạn OCR / 2 trang; đoạn `segment_index=1` mô phỏng lỗi OCR thật
  (`Trần Hưng Đao`, `001185OO7733`) đã được cán bộ hiệu đính → `is_edited=true`,
  `current_revision_no=1`, có bản ghi `segment_revision`, run
  `confirm_source='manual_edited'`.
- **B/D** là audio TTS tiếng Việt thật (`say -v Linh`), nên `time_start_ms`/
  `time_end_ms` của từng đoạn STT **khớp đúng** thời lượng phát — tua theo
  timestamp trên UI sẽ đúng chỗ. Xem `audio-timeline.json`.
- **C** mô phỏng bản quét xấu: `input_quality_flag='poor_scan'`,
  `output_quality_flag='poor'`, 1 đoạn `confidence=0.427`,
  `auto_confirm_blocked=true` → hàng chờ duyệt hiển thị cờ rủi ro.
- Deadline duyệt của C/D đặt theo `now() + INTERVAL`, nên luôn còn hạn dù chạy
  seed vào lúc nào.

## Cách tái tạo

```bash
# 1. Sinh lại file nội dung (macOS: Chrome headless + say + ffmpeg)
python3 gen_seed.py            # đọc doc-*.pdf / doc-*.m4a để lấy size + sha256

# 2. Nạp Postgres (dry-run trước: đổi COMMIT -> ROLLBACK)
docker cp mock-seed.sql aismartstore-stg-postgres-1:/tmp/
docker exec aismartstore-stg-postgres-1 \
  psql -U aismartstore -d aismartstore -v ON_ERROR_STOP=1 -f /tmp/mock-seed.sql

# 3. Nạp SeaweedFS (bucket documents, key documents/<...b1..b4>)
docker exec aismartstore-stg-seaweedfs-1 curl -s -F 'file=@/tmp/doc-a.pdf;type=application/pdf' \
  http://localhost:8888/buckets/documents/documents/00000000-0000-4000-8000-0000000000b1
```

OpenSearch **không nạp thủ công**: `mock-seed.sql` ghi `outbox_event`
(`document.ingested`, `document.classification.changed`, `review.confirmed`,
`document.acl.changed`) và `SearchIndexOutboxPoller` của backend tự đẩy vào
chỉ mục trong ~2s. Đây là đường ghi thật của hệ thống, không phải bulk giả.

## Yêu cầu hạ tầng

Index `document`/`segment` chỉ tạo được khi OpenSearch có plugin `analysis-icu`
và file `config/analysis/vi_stopwords.txt` (mapping dùng `icu_tokenizer` +
`vi_stop`). Dev compose dùng image custom `main-workspace/opensearch/Dockerfile`;
stg từng dùng image `opensearchproject/opensearch:2.17.1` thuần nên
`SearchIndexBootstrap` fail im lặng và backend spam `no such index [segment]`.
Đã build `aismartstore-opensearch:stg` từ đúng Dockerfile đó trên server và trỏ
`stg/docker-compose.yml` sang nó.

## Dọn dẹp

```bash
docker exec aismartstore-stg-postgres-1 \
  psql -U aismartstore -d aismartstore -f /tmp/mock-cleanup.sql

# OpenSearch
docker exec aismartstore-stg-opensearch-1 sh -c 'curl -sk -XPOST \
  -u admin:$OPENSEARCH_INITIAL_ADMIN_PASSWORD -H "Content-Type: application/json" \
  https://localhost:9200/segment,document/_delete_by_query -d "{\"query\":{\"prefix\":
  {\"document_id\":\"00000000-0000-4000-8000-0000000000a\"}}}"'

# SeaweedFS
docker exec aismartstore-stg-seaweedfs-1 sh -c 'for k in b1 b2 b3 b4; do curl -s -XDELETE \
  http://localhost:8888/buckets/documents/documents/00000000-0000-4000-8000-0000000000$k; done'
```
