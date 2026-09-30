# Prompt: Test toàn diện nghiệp vụ trên stg (20/09/2026)

> Dùng cho **một session mới**, không cần lịch sử hội thoại trước.
> File này là prompt — đọc hết rồi thực hiện.

---

## 1. Mục tiêu

Test đầy đủ **7 nhóm tính năng** dưới đây trên môi trường **stg**, ở cả **ba
tầng: Web UI → REST API → Database (+ OpenSearch)**. Ghi lại từng case và kết
quả vào `test_result_20260920.md` ở thư mục gốc repo.

1. Tạo/quản lý nhóm, thành viên; chia sẻ/gỡ chia sẻ tài liệu lên nhóm
2. Mở rộng phạm vi dữ liệu: thấy thêm dữ liệu đã chia sẻ tới nhóm mình tham gia
3. Phân loại, gắn nhãn tài liệu (chủ đề, loại tài liệu); ghi chú cho tệp
4. Xoá mềm vào thùng rác (kéo theo dữ liệu số hoá liên quan); phục hồi
5. Tìm kiếm nâng cao đa tiêu chí (từ khoá, loại tài liệu, thời gian, nhãn, nhóm)
6. Kết quả OCR/STT kèm mốc thời gian; **kiểm duyệt, chỉnh sửa, xác nhận** thủ
   công hoặc tự động
7. Định vị kết quả: trang/dòng (văn bản), mốc thời gian (âm thanh/video)

### Ngoài phạm vi

- **Không test luồng số hoá thật** (đẩy job vào queue → AI worker xử lý → ghi
  bảng `content_segment`). Thay vào đó **mock sẵn dữ liệu** ở trạng thái đã có
  kết quả số hoá, rồi test phần review/chỉnh sửa/xác nhận trên dữ liệu đó.

---

## 2. Chuẩn bị môi trường

**Tuyệt đối không chép secret vào file kết quả.** Đọc tại chỗ khi cần:

| Thông tin | Lấy ở đâu |
|---|---|
| SSH stg | `ssh linhhost` (đã có trong `~/.ssh/config`, host `14.225.222.72`) |
| Postgres / SeaweedFS / OpenSearch / Keycloak | `stg/.env` trong repo, và `/opt/aismartstore-stg/docker-compose.yml` trên server |
| Tên container | `ssh linhhost 'docker ps'` — tiền tố `aismartstore-stg-*` |

Cách truy cập nhanh:

```bash
# Postgres nghiệp vụ
ssh linhhost 'docker exec aismartstore-stg-postgres-1 psql -U aismartstore -d aismartstore -c "..."'
# Keycloak DB
ssh linhhost 'docker exec aismartstore-stg-postgres-1 psql -U aismartstore -d keycloak -c "..."'
# OpenSearch (HTTPS, self-signed)
ssh linhhost 'docker exec aismartstore-stg-opensearch-1 sh -c "curl -sk -u admin:\$OPENSEARCH_INITIAL_ADMIN_PASSWORD https://localhost:9200/_cat/indices?v"'
```

Domain: `web.green0tech.site` (UI), `api.green0tech.site` (API),
`auth.green0tech.site` (Keycloak).

### Lưu ý về công cụ trình duyệt

Phần "test từ web" cần MCP `claude-in-chrome`. Trong session tạo file này, MCP
đó đã **ngắt kết nối**. Kiểm tra đầu phiên; nếu không có, **không được bỏ qua
tầng UI trong im lặng** — hãy báo người dùng và ghi rõ trong file kết quả rằng
tầng UI chưa test cùng lý do.

---

## 3. Tài khoản test

Ma trận quyền đã tra từ bảng `role_permission` trên stg:

| Permission | Role được cấp |
|---|---|
| `document.upload`, `tag.manage`, `note.write`, `trash.manage`, `digitization_review.manage` | CAN_BO |
| `document.read`, `document.share`, `group.manage` | CAN_BO, LANH_DAO |
| `trash.stats.read`, `document.transfer_ownership` | QUAN_TRI |

Cần tối thiểu **hai người dùng khác nhau** để test cách ly dữ liệu, cộng một
tài khoản QUAN_TRI. Bộ tài khoản dưới đây đáp ứng được (kèm một hạn chế đã
biết, xem cuối mục này).

### Tài khoản dùng cho test — đã xác minh đăng nhập được (20/09/2026)

| Vai | Tài khoản | Mật khẩu | Realm role | `sub` |
|---|---|---|---|---|
| **A** | `canbo1` | `Passw0rd!` | `can-bo` | `c09d17da-4b1a-4c34-ae5c-7427af318e57` |
| **B** | `canbo_lanhdao1` | `Passw0rd!` | `can-bo`, `lanh-dao` | `52881bc2-d3d0-4b62-962d-b2232bd66724` |
| **L** | `lanhdao1` | `Passw0rd!` | `lanh-dao` | `7bb416c9-b3d7-495b-8bc4-b5de1efb0a18` |
| **Q** | `quantri1` | `TempAdmin_ad4ad40d5804!` | `quan-tri` | `0c6d6576-f8e7-4e03-9d07-b21c140e78b9` |

Đã kiểm chứng trực tiếp, không phải giả định:
- Cả 4 lấy token thành công.
- `canbo1` → `POST /search` trả **200** kèm kết quả đúng.
- `lanhdao1` → `POST /tags` trả **403** (không có `tag.manage`),
  `POST /search` trả **200** (có `document.read`). Tức nhóm case âm tính
  LANH_DAO **chạy được thật**.

> ⚠️ File này chứa mật khẩu thật của stg. Thư mục `source_v2` hiện **không
> phải git repo** nên không có rủi ro commit nhầm, nhưng **đừng chia sẻ file
> này ra ngoài** và đừng chép mật khẩu sang `test_result_20260920.md`.
> Mật khẩu `quantri1` trông như mật khẩu tạm sinh tự động — nếu đăng nhập
> thất bại thì hỏi lại người dùng, đừng tự reset.

**Phân vai:**

- **A = `canbo1`** — chủ sở hữu phần lớn dữ liệu test; dùng cho hầu hết case
  nghiệp vụ (upload, gắn nhãn, ghi chú, kiểm duyệt, thùng rác)
- **B = `canbo_lanhdao1`** — người dùng thứ hai, dùng test **cách ly ACL**
  (A không được thấy tài liệu riêng của B và ngược lại). Mang cả hai role nên
  **không dùng** cho case âm tính về quyền.
- **L = `lanhdao1`** — LANH_DAO thuần, dùng cho **toàn bộ case âm tính
  LANH_DAO**: có `document.read`/`document.share`/`group.manage`, **không có**
  `tag.manage`, `note.write`, `trash.manage`, `digitization_review.manage`,
  `document.upload` → các endpoint đó phải trả **403**
- **Q = `quantri1`** — có `trash.stats.read` và `document.transfer_ownership`;
  **không có** `group.manage`, `tag.manage`, `note.write`, `trash.manage` →
  cũng dùng cho case âm tính

Bốn tài khoản này phủ đủ mọi nhánh quyền trong ma trận ở trên. Không cần tạo
tài khoản mới.

### Lấy token

```bash
CS=$(grep '^KEYCLOAK_CLIENT_SECRET=' stg/.env | cut -d= -f2-)
curl -s -X POST "https://auth.green0tech.site/realms/aismartstore/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=backend -d "client_secret=$CS" \
  --data-urlencode "username=canbo1" --data-urlencode "password=Passw0rd!"
```

Dùng `--data-urlencode` cho username/password vì mật khẩu có ký tự đặc biệt.

## 4. Dữ liệu mock cần có

### Đã có sẵn trên stg

Bộ mock do `canbo1` sở hữu, tiền tố id `00000000-0000-4000-8000-0000000000a*`:

| Tài liệu | Trạng thái | Dùng cho |
|---|---|---|
| Hợp đồng thuê nhà 07-2026 (PDF, 2 trang) | đã duyệt, 5 đoạn OCR, 1 đoạn đã hiệu đính | định vị trang/dòng, lịch sử sửa |
| Ghi âm giao ban 15-09 (m4a, 44s) | đã duyệt, 3 đoạn STT | định vị mốc thời gian |
| Báo cáo quý III (PDF) | **chờ duyệt**, quét xấu, 1 đoạn confidence 0.427 | màn kiểm duyệt OCR |
| Ghi âm phỏng vấn 17-09 (m4a, 28s) | **chờ duyệt** | màn kiểm duyệt STT |

Kèm 2 nhóm: *Phòng Hành chính - Tổng hợp* (canbo1 là owner, có 2 tài liệu đã
chia sẻ) và *Ban Giám đốc* (canbo1 là member, **chưa có tài liệu nào** — dùng
để test thao tác thêm tài liệu vào nhóm).

Script tái tạo/dọn dẹp ở `mock-data/stg-canbo1/` — **đọc `README.md` ở đó
trước khi đụng vào**.

> **Lưu ý về sai lệch đã phát hiện ngày 20/09:** tài liệu *Hợp đồng thuê nhà*
> hiện có **4** `content_segment` trong Postgres (segment_index 1–4) nhưng
> **5** đoạn OCR trong index `segment` (index 0–4). Đoạn `segment_index=0`
> (`...0e1000`) đã bị xoá cứng khỏi Postgres nhưng **vẫn còn trong
> OpenSearch** — không phải xoá mềm qua API kiểm duyệt (không có
> `segment_revision` nào ghi `deleted=true`, và `is_deleted` của các đoạn còn
> lại đều `false`).
>
> Đây là **tình trạng có sẵn, không phải lỗi do test gây ra**. Ghi nhận vào
> file kết quả như một phát hiện độc lập (nó chứng minh đúng vấn đề "chỉ mục
> chỉ tăng, không có gì tự sửa lệch"). Bước dựng nền sạch ở cuối mục này sẽ
> xoá nó đi — **chụp lại bằng chứng trước khi dọn**.

### Cần tạo thêm

**Bắt buộc: ít nhất 1 tài liệu thuộc sở hữu của `canbo_lanhdao1`, không chia
sẻ cho ai** — không có nó thì không test được nhóm N3 (cách ly ACL), vốn là
nhóm quan trọng nhất.

Dùng **tiền tố id riêng** để phân biệt với bộ mock sẵn có và dễ dọn, ví dụ
`00000000-0000-4000-8000-2222...`. Nếu cần thêm tài liệu cho các case khác
(ví dụ tài liệu chờ duyệt thứ hai để thử hai luồng song song), dùng cùng tiền
tố đó.

Cách sinh file thật (PDF tiếng Việt qua Chrome headless + audio TTS có mốc
thời gian thật qua `say`/`ffmpeg`) xem `mock-data/stg-canbo1/gen_seed.py`.
File trên SeaweedFS phải khớp `size`/`sha256` ghi trong DB, nếu không case
xem/phát file sẽ sai.

**Quan trọng:** để dữ liệu vào được OpenSearch, **không bulk thủ công** — ghi
`outbox_event` đúng loại rồi để `SearchIndexOutboxPoller` của backend tự index
(chu kỳ 2s). Đó là đường ghi thật của hệ thống; bulk tay sẽ bỏ qua đúng phần
logic cần test.

### Nguyên tắc với dữ liệu — được phép clear (chốt 20/09/2026)

Người dùng đã đồng ý **xoá sạch dữ liệu test** để không để lại rác. Nên:

**Trước khi test — dựng nền sạch.** Chạy `mock-data/stg-canbo1/mock-cleanup.sql`
rồi seed lại bằng `mock-seed.sql`. Việc này đồng thời **xử lý luôn sai lệch
Postgres ↔ OpenSearch** nêu ở trên, cho một nền tham chiếu đáng tin.

Nhớ dọn cả ba tầng, không chỉ Postgres:

```bash
# 1. Postgres
docker cp mock-cleanup.sql aismartstore-stg-postgres-1:/tmp/
ssh linhhost 'docker exec aismartstore-stg-postgres-1 psql -U aismartstore -d aismartstore -f /tmp/mock-cleanup.sql'

# 2. OpenSearch — cả hai index.
#    LUÔN chạy _count trước với ĐÚNG query đó để biết sẽ xoá bao nhiêu doc,
#    rồi mới đổi _count thành _delete_by_query. (Đã kiểm chứng quoting chạy
#    đúng trên stg: trả {"count":16} với bộ mock hiện tại.)
ssh linhhost 'docker exec aismartstore-stg-opensearch-1 sh -c "curl -sk -XPOST \
  -u admin:\$OPENSEARCH_INITIAL_ADMIN_PASSWORD -H \"Content-Type: application/json\" \
  https://localhost:9200/segment,document/_delete_by_query -d \
  \"{\\\"query\\\":{\\\"prefix\\\":{\\\"document_id\\\":\\\"00000000-0000-4000-8000-\\\"}}}\""'

# 3. SeaweedFS
ssh linhhost 'docker exec aismartstore-stg-seaweedfs-1 sh -c "for k in b1 b2 b3 b4; do \
  curl -s -XDELETE http://localhost:8888/buckets/documents/documents/00000000-0000-4000-8000-0000000000\$k; done"'
```

**Sau khi test — dọn sạch.** Xoá toàn bộ dữ liệu do đợt test tạo ra, gồm cả
nhóm, nhãn, ghi chú, `outbox_event`, và bản ghi `audit_log` nếu có thoả thuận
riêng (mặc định **giữ** audit — nó là dữ liệu chứng cứ, xoá là sai nguyên tắc).

**Quy tắc đặt id để dọn được:** mọi thực thể tự tạo dùng tiền tố
`00000000-0000-4000-8000-` (bộ mock gốc) hoặc một tiền tố riêng cho đợt test
này. **Không** để dữ liệu test lẫn id ngẫu nhiên — sẽ không lọc ra được.

**Vẫn phải cẩn thận:** stg có dữ liệu thật của `canbo1` upload từ trước
(các file `Call *.m4a`, `A100-Server-connect-guide.pdf`, ...) **không** mang
tiền tố mock. Tuyệt đối không xoá nhầm. Trước khi chạy bất kỳ lệnh `DELETE`
nào, chạy `SELECT` cùng điều kiện để xem sẽ xoá đúng những dòng nào.

Ghi vào file kết quả: đã dựng nền sạch trước khi test chưa, và sau khi test đã
dọn những gì.

---

## 5. Nhóm test case

Với **mỗi** case, kiểm chứng đủ ba tầng và ghi lại:
**UI** (thao tác + kết quả nhìn thấy) → **API** (endpoint, status, phần thân
đáng chú ý) → **DB/Index** (bảng nào đổi, index có phản ánh không).

### N1. Nhóm và thành viên
- Tạo nhóm (CAN_BO và LANH_DAO đều có `group.manage`)
- Thêm/xoá thành viên, đổi vai trò trong nhóm
- Tên nhóm trùng (có unique index trên `lower(name)` khi `deleted_at IS NULL`)
- Xoá nhóm → kiểm tra `deleted_at`, và điều gì xảy ra với tài liệu đã chia sẻ
- L (`lanhdao1`) **có** `group.manage` → tạo/sửa nhóm được (case dương)
- **Âm tính:** Q (`quantri1`) không có `group.manage` → phải 403

### N2. Chia sẻ tài liệu lên nhóm
- Chia sẻ 1 tài liệu, chia sẻ hàng loạt (`DocumentBulkSharesController`)
- Gỡ chia sẻ
- Kiểm tra bảng `document_group_share` + `outbox_event('document.acl.changed')`
- **Chờ ~2s** rồi kiểm tra `scope_group_ids` trong cả index `document` và
  `segment` đã cập nhật
- **Âm tính:** chia sẻ tài liệu không thuộc phạm vi mình → 403

### N3. Mở rộng phạm vi dữ liệu (ACL) — **nhóm quan trọng nhất**
- **B (`canbo_lanhdao1`) không thấy tài liệu riêng của A (`canbo1`)** qua cả
  **ba đường**: danh sách tài liệu, `GET /documents/{id}`, và `POST /search`.
  Phải kiểm cả ba vì chúng đi qua các lớp lọc khác nhau — riêng `/search` có
  hai lớp (filter ở OpenSearch + double-check Postgres ở
  `SearchResultHydrator`).
- Chiều ngược lại: A không thấy tài liệu riêng của B
- Sau khi A chia sẻ tài liệu vào nhóm có B → B **thấy được** (chờ độ trễ đồng
  bộ ~2s)
- Sau khi A gỡ chia sẻ → B **không còn thấy**, kiểm lại đủ ba đường
- Tài liệu trong nhóm mà **cả hai** cùng tham gia: cả hai đều thấy

### N4. Nhãn và ghi chú
- Gán/gỡ `doc_type` (tối đa 1 — có unique index
  `uq_document_tag_one_doc_type`) và nhiều `topic`
- Tạo tag mới, tag trùng tên (unique `(kind, lower(label))`)
- Ghi chú: tạo, sửa; **ghi chú > 4000 ký tự phải trả 400, không phải 500**
  (đây là bug đã sửa và deploy 20/09 — coi như case hồi quy)
- Kiểm tra `outbox_event('document.classification.changed'/'document.note.changed')`
  rồi đối chiếu `doc_type`/`topic_tags` trong index sau ~2s
- **Âm tính (đã kiểm chứng chạy được):** L (`lanhdao1`) gọi `POST /tags`,
  gán nhãn, hay ghi chú → phải **403**. Q (`quantri1`) cũng vậy.

### N5. Thùng rác
- Xoá mềm 1 tài liệu → `deleted_at`, `purge_state`, `trash_batch`
- Kiểm tra dữ liệu số hoá liên quan (`digitization_run`, `content_segment`) bị
  ảnh hưởng thế nào
- Phục hồi, phục hồi vào thư mục khác, phục hồi khi nhóm chia sẻ đã bị xoá
- Xoá tài liệu đã xoá → 409
- **Đặc biệt — ✅ ĐÃ DUYỆT, case bắt buộc:** sau khi xoá mềm, tài liệu **vẫn
  nằm trong OpenSearch** (hiện không có consumer nào cho `document.trash`,
  trên stg đang tồn 12 dòng loại này từ 16/09). Phải xác minh nó **không xuất
  hiện** trong kết quả `POST /search`. Lớp chặn **duy nhất** là double-check
  Postgres ở `SearchResultHydrator` (`BusinessDataScope` lọc
  `deletedAt IS NULL`) — không có lớp thứ hai, nên phải test trực tiếp thay vì
  tin là nó đang hoạt động. Kiểm tra thêm: sau khi phục hồi, tài liệu tìm lại
  được.
- **Âm tính:** Q (`quantri1`) có `trash.stats.read` nhưng **không** có
  `trash.manage` → xem thống kê được, xoá/phục hồi thì 403. L (`lanhdao1`)
  không có cả hai → 403 ở mọi endpoint thùng rác.

### N6. Tìm kiếm nâng cao
- Từ khoá: có dấu, **không dấu** (phải ra kết quả nhờ `icu_folding`), cụm từ
- Lọc: `doc_type`, khoảng thời gian, nhãn `topic`, nhóm, thư mục
- Kết hợp nhiều tiêu chí cùng lúc
- Tìm theo tên file (segment `content_source='title'`)
- Tìm theo nội dung ghi chú (segment `content_source='note'`)
- **Ranh giới:** nội dung của tài liệu **chờ duyệt** phải **không** tìm được
  (chỉ run `confirmed` mới vào chỉ mục)
- Phân trang, sắp xếp
- Chờ ~2–5s sau mỗi thay đổi rồi mới kiểm tra kết quả tìm kiếm (chỉ mục là
  eventually consistent; **không** cần đo độ trễ trong đợt này — xem 7.2)

### N7. Kiểm duyệt / chỉnh sửa / xác nhận kết quả số hoá
- Hàng chờ duyệt: lọc theo task, theo trạng thái, cờ chất lượng
- Xem chi tiết run; ảnh trang tài liệu
  (`GET /digitization-review/runs/{id}/pages/{n}/image`)
- Sửa text một đoạn → kiểm tra `segment_revision`, `current_revision_no`,
  `is_edited`; run chuyển `pending` → `in_review`, có `first_edit_at`
- Sửa mốc thời gian đoạn STT (ràng buộc `time_start < time_end`, và phải nằm
  trong thời lượng nguồn)
- Xoá đoạn, thêm đoạn thủ công
- **Xung đột:** hai request cùng `expectedRevisionNo` → 409
- Xác nhận (duyệt) → `review_state='confirmed'`, `confirm_source`
  (`manual_edited` nếu có sửa, `manual_unchanged` nếu không),
  `document_digitization_status.overall_status='confirmed'`
- Duyệt lại lần hai → 409
- **Sau khi duyệt, chờ ~2s** rồi xác minh nội dung tìm kiếm được
- **Sửa một đoạn của run ĐÃ duyệt** → sinh `outbox_event('review.reconfirmed')`,
  run vẫn `confirmed`, nội dung mới được cập nhật vào index
- **Âm tính 1:** người không phải người nạp tài liệu → 403 (kiểm duyệt là
  "chính chủ", **không** theo phạm vi nhóm — kể cả khi tài liệu đã chia sẻ vào
  nhóm mà người đó tham gia)
- **Âm tính 2:** L (`lanhdao1`) không có `digitization_review.manage` → 403 ở
  mọi endpoint `/digitization-review/*`
- Tự động xác nhận: nếu test được thì bật `AutoConfirmScheduler` qua
  system-config và rút ngắn deadline; nếu không, ghi rõ là chưa test

### N8. Định vị kết quả
- Kết quả OCR trả đúng `page_no`, `line_from`/`line_to`, và `bbox_polygon`
  (toạ độ tỉ lệ 0–1, không phải pixel)
- Kết quả STT trả đúng `time_start_ms`/`time_end_ms`; bấm vào nhảy đúng chỗ
  trong trình phát (mock audio có mốc thời gian thật nên kiểm chứng được)
- Highlight từ khoá trong đoạn trích

---

## 6. Ghi kết quả

Ghi vào `test_result_20260920.md` ở thư mục gốc repo. Mỗi case một dòng bảng:

| ID | Nhóm | Case | Tầng đã kiểm | Kết quả | Bằng chứng | Ghi chú |
|---|---|---|---|---|---|---|
| N3-04 | ACL | B không thấy tài liệu riêng của A qua /search | UI/API/DB | ✅ | `POST /search` → 0 hit; `document_group_share` rỗng | |

Yêu cầu về cách ghi:

- **PASS/FAIL phải dựa trên bằng chứng thật**, kèm số liệu (status code, số
  dòng DB, số hit). Không ghi PASS vì "nhìn có vẻ đúng".
- Case **không test được** thì ghi rõ **lý do**, không im lặng bỏ qua.
- Mở đầu file: ngày giờ, phiên bản image đang chạy trên stg
  (`docker inspect aismartstore-stg-backend-1 --format '{{.Image}}'`), danh
  sách tài khoản dùng để test.
- Cuối file: **tổng hợp lỗi phát hiện được**, xếp theo mức nghiêm trọng, mỗi
  lỗi kèm cách tái hiện.

---

## 7. Chiến lược test bổ sung

Người dùng chưa mô tả trong yêu cầu ban đầu. **Trạng thái duyệt (20/09/2026):**

| Đề xuất | Trạng thái |
|---|---|
| Test âm tính + cách ly ACL (7.1, và nhóm case N3) | ✅ **ĐÃ DUYỆT — bắt buộc** |
| Case thùng rác × tìm kiếm (nằm trong N5, mục "Đặc biệt") | ✅ **ĐÃ DUYỆT — bắt buộc** |
| Đo độ trễ đồng bộ (7.2) | ⏸ **HOÃN** — người dùng tự triển khai sau |
| Dọn dẹp dữ liệu test (7.9) | ✅ **ĐÃ DUYỆT — bắt buộc** |
| 7.3 – 7.8 | Chưa trao đổi riêng; tự cân nhắc theo thời gian có |


**7.1. Test âm tính và cách ly ACL — ưu tiên cao nhất. ĐÃ DUYỆT, bắt buộc làm.**
Phần lớn giá trị nằm ở "người không được phép thì **không** thấy". Với mỗi
tính năng, test cả hướng ngược: sai vai trò → 403, không phải chủ sở hữu →
403, ngoài phạm vi nhóm → không thấy. Riêng tìm kiếm phải kiểm tra bằng **cả
ba đường** (danh sách, chi tiết, search) vì chúng đi qua các lớp lọc khác nhau.

**7.2. Đo và ghi lại độ trễ đồng bộ — HOÃN.**
Người dùng đã quyết định (20/09) tự triển khai sau, **không làm trong đợt test
này**. Vẫn giữ mô tả ở đây để không mất dấu: chỉ mục là eventually consistent
(mục tiêu ≤ 5s p95), cần đo độ trễ thực từ lúc thay đổi tới lúc search phản
ánh — số liệu này hiện chưa ai có.

Trong đợt này chỉ cần **chờ đủ** (~2–5s) trước khi kiểm tra kết quả tìm kiếm,
không cần bấm giờ.

**7.3. Kiểm tra nhất quán chéo giữa ba nguồn.**
Sau mỗi nhóm case, đối chiếu: số `content_segment` trong Postgres so với số
doc trong index `segment` của cùng tài liệu; `doc_type`/`topic_tags`/
`scope_group_ids` trong index so với Postgres. Lệch là lỗi, kể cả khi UI trông
vẫn đúng.

**7.4. Hồi quy cho ba fix vừa deploy 18–20/09.**
- Ghi chú > 4000 ký tự → **400**, không phải 500
- Bơm một `outbox_event` hỏng vĩnh viễn (ví dụ `document.acl.changed` với
  `documentId` không phải UUID) → phải **park sau đúng 20 lần**, ghi **đúng
  một** dòng ERROR chứa `PARKED`, và **các event phía sau vẫn được xử lý**.
  Nhớ xoá dòng độc sau khi test.
- Kiểm tra backend có nhiều thread `scheduling-N` (pool size 4)

**7.5. Kiểm tra `audit_log`.**
Mỗi hành động nghiệp vụ (chia sẻ, gắn nhãn, xoá, duyệt) phải sinh bản ghi
audit. Đối chiếu `audit_log` sau mỗi nhóm case — FR-09 yêu cầu, và dễ bị bỏ
sót vì không nhìn thấy trên UI.

**7.6. Test biên và dữ liệu xấu.**
Tên file/nhãn/ghi chú có dấu tiếng Việt, emoji, ký tự đặc biệt; tên rất dài;
chuỗi tìm kiếm rỗng hoặc chỉ có dấu cách; trang vượt quá số trang thật; mốc
thời gian vượt thời lượng audio.

**7.7. Đồng thời.**
Hai người cùng sửa một đoạn (đã có `expectedRevisionNo`); hai người cùng chia
sẻ một tài liệu vào cùng nhóm; duyệt trong khi người khác đang sửa.

**7.8. Suy giảm dịch vụ.**
Tắt tạm OpenSearch → `POST /search` phải chuyển sang
`MetadataFallbackSearchService` chứ không lỗi 500, và các chức năng khác
(upload, gắn nhãn, ghi chú) vẫn phải chạy bình thường. **Nhớ bật lại.**
Chỉ làm nếu người dùng đồng ý, vì nó ảnh hưởng người khác đang dùng stg.

**7.9. Dọn dẹp. ✅ ĐÃ DUYỆT — bắt buộc.**
Viết sẵn script dọn toàn bộ dữ liệu test (Postgres + SeaweedFS + OpenSearch +
tài khoản Keycloak test) và chạy sau khi xong. Dùng tiền tố id riêng để lọc.
Ghi lại trong file kết quả là đã dọn hay còn giữ lại gì.

---

## 8. Nguyên tắc khi thực hiện

- stg đang có người dùng thật (`canbo1` có dữ liệu riêng). **Không sửa/xoá dữ
  liệu không phải của test.** Mọi thứ tạo ra phải có tiền tố id nhận diện được.
- Thao tác khó đảo ngược (xoá cứng, tắt service, reset mật khẩu người khác)
  → **hỏi trước**.
- Không chép secret vào file kết quả hay vào git.
- Nếu một case làm hỏng dữ liệu hoặc service, **dừng lại và báo ngay**, đừng
  chạy tiếp cho hết danh sách.
