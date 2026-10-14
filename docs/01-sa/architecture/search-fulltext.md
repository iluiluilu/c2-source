# Architecture: search-fulltext

> File: `docs/01-sa/architecture/search-fulltext.md` — Owner: SA
> Status: Approved

## 1. Bối cảnh / Vấn đề

- Phủ **FR-22** (tìm kiếm toàn văn trên kho đã số hóa, trả tài liệu kèm đoạn
  liên quan), **FR-23** (tìm kiếm nâng cao: từ khóa + loại tài liệu + thời gian
  + nhãn + nhóm), **FR-25** (định vị chính xác: trang/dòng cho văn bản, mốc thời
  gian cho âm thanh/video) — SRS mục 2.7, `dac_ta_yeu_cau_chuc_nang_phi_chuc_nang.adoc`
  dòng 554–628.
- **Tầng ứng dụng/truy vấn trên hạ tầng đã chốt.** `ai-search-infra.md`
  (Status: **Approved**) là nguồn duy nhất cho: chọn OpenSearch (QĐ 1), đơn vị
  index `document` + `segment` (QĐ 6), cách nhúng ACL FR-08 vào chỉ mục (QĐ 5),
  đồng bộ outbox→RabbitMQ→indexer (QĐ 7), hợp đồng interface (mục 8). File này
  **chỉ dẫn chiếu, không quyết định lại** những hạng mục đó.
- **In-scope:** mapping/analyzer cụ thể cho `segment`/`document`; hình dạng truy
  vấn (collapse theo tài liệu); DSL/API tìm kiếm nâng cao; ranking; highlight và
  trả toạ độ định vị; cách áp `ScopeFilterBuilder` ở tầng query; phân trang &
  giới hạn; hợp đồng cho `search-semantic`.
- **Out-of-scope:** FR-24 tra cứu ngữ nghĩa, embedding, hợp nhất hybrid
  (→ `search-semantic`); OCR/STT và cách sinh segment (→ `digitization-pipeline`);
  luồng xác nhận và sự kiện `review.confirmed` (→ `digitization-review`); chọn
  engine/hạ tầng (→ `ai-search-infra`); UI/UX chi tiết và user story (→ BA).

## 2. Ràng buộc (Constraints)

- **Chỉ nội dung đã xác nhận (FR-18) mới có trong chỉ mục chính thức** —
  `digitization-review.md` QĐ 6: chỉ `review.confirmed` / `review.reconfirmed`
  mới kích hoạt index; run cũ `confirmed` vẫn phục vụ tìm kiếm cho tới khi run
  mới được xác nhận. ⇒ Nội dung mới có thể **chưa tra cứu được tới 24h**
  (`ocr_stt.auto_confirm_hours`).
- **Kho cá nhân không bao giờ được lập chỉ mục** (`personal-storage-quota.md`,
  FR-14; `ai-search-infra.md` mục 2) — chặn tại producer sự kiện, tìm kiếm
  không có "bộ lọc loại trừ kho cá nhân".
- **Phạm vi dữ liệu chỉ dựa trên nhóm/chia sẻ** (`rbac-group-scope.md` QĐ 2/3,
  FR-08, FR-28/29): thấy dữ liệu do chính mình nạp **hoặc** đã chia sẻ tới nhóm
  mình là thành viên. Không có đơn vị/mức độ mật. Fail-closed.
- **Ghi chú (FR-16) không có ACL riêng — thừa hưởng phạm vi của tài liệu cha**
  (chốt với người dùng 2026-08-16). ⇒ Segment `content_source = 'note'` dùng
  đúng `owner_user_id`/`scope_group_ids[]` của tài liệu; không cần mô hình
  quyền riêng cho ghi chú.
- **Cấu trúc kho không phải biên phân quyền** (`warehouse-structure.md` QĐ 2) —
  `folder_id` chỉ là tiêu chí lọc, không cấp/thu quyền. Cây tối đa 5 cấp (QĐ 1).
- **Taxonomy nhãn** (`classification-notes.md` QĐ 1/3): đúng 2 `kind` —
  `topic` (nhiều nhãn/tài liệu) và `doc_type` (**đúng 1** nhãn/tài liệu, danh
  mục đóng). Ghi chú (FR-16) phải tìm kiếm được (QĐ 7 cam kết cung cấp
  `tags[]`, `notes[]` qua outbox).
- **Toạ độ định vị có sẵn** (`digitization-pipeline.md` QĐ 3): OCR có `page_no`,
  `line_from`/`line_to`, `bbox_polygon`, `reading_order`; STT/SED có
  `time_start_ms`/`time_end_ms` (mili-giây, ràng buộc `≤ duration_ms`).
  **Không có** `content_source = native_text` (PDF luôn qua OCR).
- Mọi truy vấn đi qua `search-service`; frontend/module nghiệp vụ **không** gọi
  thẳng OpenSearch (`ai-search-infra.md` mục 8).
- Mạng cách ly, một máy chủ, OpenSearch single-node (NFR-04, `ai-search-infra.md`
  mục 2) ⇒ không có replica; mọi tính năng phải chạy được offline.

## 3. NFR (Non-Functional Requirements)

Các số dưới đây **kế thừa `ai-search-infra.md` mục 3** (đã được người dùng chấp
nhận 2026-08-16); phần thuộc tầng truy vấn cũng **đã được chốt 2026-08-16**
(ghi `[chốt]`), kiểm chứng lại bằng đo tải khi có hạ tầng thật.

| Loại | Yêu cầu |
|---|---|
| Performance | FR-22/23/25: p95 ≤ 1s, p99 ≤ 2s end-to-end với 20 truy vấn đồng thời, trong đó OpenSearch ≤ 500ms (kế thừa). `[chốt]` Timeout mỗi truy vấn OpenSearch `search.query_timeout_ms` = **1.500ms** (< p99 2s), quá hạn → báo lỗi rõ, **không** trả kết quả một phần không lọc phạm vi |
| Performance | `[chốt]` Chi phí double-check ACL khi hydrate ≤ 100ms cho 1 trang kết quả (truy vấn PostgreSQL 1 lần theo lô `document_id`, không N+1; cùng lượt lấy `bbox_polygon` phục vụ FR-25) |
| Latency dữ liệu | Từ `review.confirmed` tới lúc tìm được ≤ 5s (p95) / ≤ 30s (p99) (kế thừa); từ thao tác nghiệp vụ (đổi nhãn/ghi chú/chia sẻ) tới lúc phản ánh vào bộ lọc ≤ 5s |
| Security | Mọi truy vấn **bắt buộc** qua `ScopeFilterBuilder`, fail-closed: thiếu ngữ cảnh người dùng → 403, không truy vấn. Kết quả luôn được double-check bằng row-level filter của `rbac-group-scope.md` khi hydrate |
| Audit (FR-09) | `[chốt]` Mỗi lần tìm kiếm ghi `search.executed` gồm `actor`, thời điểm, bộ lọc, `result_count` và **từ khóa nguyên văn, cắt ≤ 200 ký tự**. **Không** ghi nội dung đoạn trích/ghi chú (`classification-notes.md` mục 3). Bản ghi audit nằm trong `audit_log` append-only ⇒ chịu cùng chế độ bảo vệ/hạn chế truy cập như mọi nhật ký khác (chỉ R3/kiểm toán theo FR-10) |
| Scalability | ~1 triệu tài liệu / ~20 triệu segment, chỉ mục toàn văn ~300GB (kế thừa). `[chốt]` Deep paging giới hạn `max_result_window` = 10.000; vượt → yêu cầu thu hẹp bộ lọc |
| Availability | OpenSearch lỗi → **suy giảm chức năng có kiểm soát**: báo lỗi rõ ràng, tuyệt đối không rơi về chế độ bỏ filter phạm vi (kế thừa). `[chốt]` Lọc thuần metadata (FR-23 không từ khóa) vẫn chạy được bằng SQL khi chỉ mục hỏng |
| Khác | `total` là **giá trị gần đúng** (hệ quả double-check ACL, `ai-search-infra.md` QĐ 5) — UI hiển thị "khoảng N kết quả", không hứa con số tuyệt đối |

## 4. Phương án & Quyết định (ADR)

### Quyết định 1: Mapping & analyzer tiếng Việt cho `segment`/`document`

- **Ngữ cảnh:** `ai-search-infra.md` QĐ 1 đã chốt **`analysis-icu`**
  (`icu_tokenizer` + `icu_normalizer` + `icu_folding`) + stopword tự quản, chấp
  nhận hạn chế "không tách từ ghép". File này chốt **mapping cụ thể**.
- **Các phương án xem xét:** A) một field text duy nhất — B) **multi-field**
  (giữ dấu + bỏ dấu + shingle) — C) chờ plugin tách từ tiếng Việt.
- **Quyết định:** **B**. Trường nội dung `text` là multi-field:
  - `text` — analyzer `vi_text`: `icu_normalizer` (NFKC_CF) → `icu_tokenizer`
    → stopword tiếng Việt → **giữ nguyên dấu** (field chính, boost cao nhất).
  - `text.folded` — thêm `icu_folding` (bỏ dấu): bắt truy vấn gõ không dấu,
    boost thấp.
  - `text.shingle` — shingle 2–3 token trên `vi_text`: bù precision cụm từ do
    ICU chỉ tách theo âm tiết (đúng hướng giảm thiểu đã nêu ở
    `ai-search-infra.md` QĐ 1).
  - `index_options: offsets` trên `text` để dùng unified highlighter mà **không**
    bật `term_vector` (xem QĐ 4).
  - Trường định vị/lọc để `keyword`/`integer`/`date`, `index: true, doc_values:
    true`. **`bbox_polygon` không lưu trong OpenSearch** (dữ liệu hình học,
    không dùng để lọc/xếp hạng): giữ ở PostgreSQL và lấy kèm ở bước hydrate để
    trả về theo QĐ 4 — không phát sinh round-trip mới vì hydrate vốn đã truy vấn
    PostgreSQL theo lô.
  - `refresh_interval` `[ĐX]` 1s (đáp ứng mục tiêu ≤ 5s), số shard = 1 primary
    + 0 replica (single-node), reindex đổi shard qua alias.
- **Lý do:** hầu hết truy vấn nghiệp vụ là tên riêng/thuật ngữ có dấu ⇒ field
  giữ dấu cho precision; `.folded` cứu trường hợp gõ thiếu dấu; `.shingle` là
  cách rẻ nhất để nâng precision cụm từ khi chưa có tách từ. C bị loại vì chặn
  tiến độ và đã có lối thoát reindex ở giai đoạn 2.
- **Hệ quả:** chỉ mục lớn hơn ~2–2,5× so với một field đơn (đã tính trong ước
  lượng 300GB); mọi thay đổi analyzer **bắt buộc reindex** theo quy trình alias
  (`ai-search-infra.md` QĐ 7).

### Quyết định 2: Hình dạng truy vấn — index `segment` phẳng + `collapse` theo tài liệu

- **Ngữ cảnh:** FR-22 yêu cầu trả **danh sách tài liệu** kèm đoạn liên quan;
  FR-25 yêu cầu **vị trí trong tài liệu**. Đơn vị index là `segment`
  (`ai-search-infra.md` QĐ 6) ⇒ cần gộp nhiều segment về một tài liệu.
- **Các phương án xem xét:** A) truy vấn `document` rồi truy vấn `segment` lần
  hai để lấy đoạn — B) parent-child (`join`) — C) **truy vấn `segment` một lần,
  `collapse` theo `document_id` + `inner_hits` top-N segment**.
- **Quyết định:** **C**, kèm hai hệ quả bắt buộc:
  1. **Denormalize metadata lọc của tài liệu vào từng segment** (`doc_type`,
     `topic_tags[]`, `folder_path`, `uploaded_at`, `scope_group_ids[]`,
     `owner_user_id`, `media_type`, `confirm_source`, `run_id`) ⇒ FR-22 + FR-23
     chỉ cần **một** truy vấn, xếp hạng và phân trang đúng.
  2. **Văn bản không thuộc trang/đoạn cũng thành segment tổng hợp**:
     `content_source = 'note'` (ghi chú FR-16, hợp đồng
     `classification-notes.md` QĐ 7) và `content_source = 'title'` (tên tệp +
     tiêu đề + nhãn). Nhờ vậy tài liệu chưa số hóa xong vẫn tìm được theo tên và
     mọi kết quả đi qua **một** đường ACL duy nhất.
     - **Đã chốt 2026-08-16:** giá trị `title` được **bổ sung chính thức vào
       enum `content_source`** ở `ai-search-infra.md` mục 8 (file Approved đã
       cập nhật), kèm quy ước `position_type = none` cho `title`/`note`. Thiết
       kế collapse + `inner_hits` giữ nguyên như mô tả trên.
  - **Truy vấn không có từ khóa** (FR-23 lọc thuần metadata): đi thẳng index
    `document` (hoặc SQL khi chỉ mục hỏng), sort theo thời gian, phân trang
    chuẩn — không collapse.
- **Lý do:** A sai xếp hạng và tốn 2 round-trip; B (join) buộc parent/child cùng
  shard, truy vấn `has_child` đắt và cập nhật ACL/metadata phức tạp hơn
  `update_by_query` phẳng. C giữ đúng ngữ nghĩa "tài liệu khớp kèm đoạn liên
  quan" của FR-22 và trực tiếp phục vụ FR-25 bằng chính `inner_hits`.
- **Hệ quả (trade-off):**
  - Đổi nhãn/ghi chú/chia sẻ ⇒ `update_by_query` trên **mọi segment** của tài
    liệu (giống cơ chế `acl.changed` ở `ai-search-infra.md` QĐ 5) — thao tác
    gắn nhãn hàng loạt phải gom lô, xem rủi ro mục 6.
  - `total` khi collapse là số **segment** khớp, không phải số tài liệu ⇒ dùng
    `cardinality` aggregation trên `document_id` làm ước lượng số tài liệu (gần
    đúng, khớp với ràng buộc "total gần đúng" ở mục 3).

### Quyết định 3: API/DSL tìm kiếm nâng cao (FR-23)

- **Ngữ cảnh:** FR-23 cần kết hợp từ khóa + nhiều tiêu chí lọc; cần cân bằng
  giữa sức mạnh biểu đạt và an toàn/hiệu năng.
- **Các phương án xem xét:** A) mở `query_string` của Lucene cho người dùng —
  B) **API JSON có cấu trúc + cú pháp rút gọn hạn chế trong ô từ khóa** —
  C) chỉ có ô từ khóa + checkbox, không toán tử.
- **Quyết định:** **B**.
  - **Bộ lọc là các trường có cấu trúc** (`bool.filter`, không ảnh hưởng điểm
    số, cache được): `doc_type` (1 giá trị), `topic_tags[]` (AND/OR do người
    dùng chọn), `uploaded_at` + `from`/`to`, `group_ids[]`, `folder_path`
    (prefix ⇒ lọc cả cây con, ≤ 5 cấp), `media_type`, `content_source[]`,
    `confirm_source` (phục vụ bộ lọc "rà soát lại bản tự xác nhận" —
    `digitization-review.md` mục 6).
  - **Trường thời gian = `uploaded_at` (thời điểm nạp kho), là trường duy nhất
    ở giai đoạn 1 — đã chốt 2026-08-16.** Không thêm `document_date`/"ngày ban
    hành" (hiện không tài liệu SA nào định nghĩa nguồn dữ liệu cho trường này).
    Nếu nghiệp vụ yêu cầu ở giai đoạn sau: bổ sung `document_date` vào
    `document` + segment denormalized, cho phép chọn trường thời gian trong API
    — thay đổi cộng thêm, không phá vỡ hợp đồng hiện tại.
  - **Lọc theo nhóm phải giao với phạm vi người dùng:** `group_ids[] ∩
    groupIds(user)`; giá trị ngoài phạm vi bị **loại bỏ im lặng khỏi bộ lọc và
    ghi audit**, không báo "nhóm không tồn tại" (tránh lộ sự tồn tại của nhóm).
  - **Ô từ khóa** dùng `simple_query_string` với flags
    `PHRASE|AND|OR|NOT|PRECEDENCE|WHITESPACE`; **tắt** `FUZZY`, `WILDCARD`,
    `PREFIX`, `REGEX`. Hỗ trợ `"cụm từ chính xác"`, `-loại trừ`, `+bắt buộc`,
    `OR`. `default_operator = AND`.
  - Giới hạn đầu vào `[ĐX]`: từ khóa ≤ 256 ký tự, ≤ 32 token, ≤ 20 giá trị mỗi
    bộ lọc; từ khóa rỗng + không bộ lọc nào → 400 (đúng ngoại lệ FR-22).
  - **0 kết quả → chẩn đoán nới điều kiện** (ngoại lệ FR-23): chạy thêm tối đa
    5 truy vấn `count` (msearch) mỗi lần bỏ một bộ lọc, trả về gợi ý "bỏ bộ lọc
    X sẽ có N kết quả". Chỉ chạy khi `total = 0`.
- **Lý do:** A cho phép cú pháp `field:value` → lộ cấu trúc chỉ mục, dễ ném lỗi
  cú pháp 400 và mở đường cho truy vấn wildcard làm sập cụm single-node.
  `simple_query_string` không ném lỗi cú pháp (bỏ qua ký tự sai). C không đáp
  ứng "tổ hợp tiêu chí" của FR-23.
- **Hệ quả:** cần tài liệu hướng dẫn cú pháp rút gọn cho người dùng cuối (BA);
  mọi tiêu chí lọc mới trong tương lai phải khai báo tường minh trong API, không
  "thả cửa" qua chuỗi truy vấn; bộ lọc thời gian hiện chỉ phản ánh **thời điểm
  nạp**, không phải thời điểm ban hành của tài liệu — BA phải nêu rõ trong UI.

### Quyết định 4: Ranking, highlight và trả vị trí (FR-22 + FR-25)

- **Ngữ cảnh:** FR-22 cần "đoạn liên quan"; FR-25 cần trang/dòng hoặc mốc thời
  gian và liên kết nhảy đến; ngoại lệ FR-25: thiếu vị trí → trả cấp tài liệu
  kèm cảnh báo.
- **Quyết định:**
  - **Ranking:** BM25 mặc định, `bool.should` gồm `text^3`, `text.shingle^2`,
    `text.folded^0.5`, cộng boost nguồn `title^4` / `note^1.5` (boost theo
    `content_source` bằng `constant_score`/`boosting`), và `match_phrase` trên
    `text` (`boost 2`, `slop 1`). Điểm của tài liệu = **điểm segment cao nhất**
    (collapse). **Không** dùng function_score theo độ mới ở giai đoạn 1 (dữ liệu
    có tính chứng cứ — độ mới không đồng nghĩa liên quan hơn); cho phép người
    dùng chuyển sang `sort` theo thời gian.
  - **Highlight:** `unified` highlighter dựa trên `index_options: offsets`
    (không bật `term_vector` — tiết kiệm ~30–50% dung lượng chỉ mục),
    `fragment_size` `[ĐX]` 200 ký tự, `number_of_fragments` 1 cho mỗi segment,
    `boundary_scanner: sentence`, tối đa `[ĐX]` **3 segment (`inner_hits`) mỗi
    tài liệu** ở danh sách kết quả.
  - **Vị trí trả về cho mỗi hit** (kế thừa trường ở `ai-search-infra.md` mục 8):
    `segment_id`, `document_id`, `segment_index`, và:
    - văn bản: `page_no`, `line_from`, `line_to`, **`bbox_polygon`** + offset ký
      tự trong đoạn (từ highlight) ⇒ deep-link `?page=&highlight=` và khoanh
      vùng trực tiếp trên ảnh trang;
    - âm thanh/video: `time_start_ms`, `time_end_ms` ⇒ deep-link `?t=`;
    - `note`/`title`: `position_type = none` + cảnh báo "chỉ định vị được ở cấp
      tài liệu" (đúng ngoại lệ FR-25, khớp quy ước ở `ai-search-infra.md` mục 8).
  - **Độ mịn của "dòng" — đã chốt 2026-08-16:** trả **khoảng dòng của block OCR**
    (`line_from`–`line_to`) **kèm `bbox_polygon`** của block
    (`digitization-pipeline.md` QĐ 3). **Không** làm bước ánh xạ offset ký tự →
    số dòng chính xác: bbox đã đủ để UI khoanh vùng đúng chỗ trên ảnh, chi phí
    thấp hơn nhiều và không phụ thuộc chất lượng phân dòng của OCR.
    `bbox_polygon` lấy ở bước hydrate từ PostgreSQL (QĐ 1), không lưu ở chỉ mục.
  - **Nhiều vị trí khớp:** liệt kê theo `inner_hits`, sắp xếp theo điểm rồi theo
    `page_no`/`time_start_ms` (đúng ngoại lệ FR-25).
  - Kết quả có `confirm_source = 'auto'` phải trả kèm cờ để UI hiển thị rõ
    (giảm thiểu rủi ro đã nêu ở `digitization-review.md` mục 6).
- **Lý do:** offsets postings đủ chất lượng cho highlight trên field lớn với chi
  phí thấp hơn term vectors; giới hạn 3 đoạn/tài liệu giữ p95 ≤ 1s khi tài liệu
  có hàng nghìn segment khớp; bbox là dữ liệu **đã có sẵn**, chỉ cần đưa vào hợp
  đồng response.
- **Hệ quả:** đổi highlighter/analyzer ⇒ reindex; UI phải xử lý 3 dạng vị trí
  (trang/dòng + bbox, thời gian, không có); response nặng hơn do kèm toạ độ
  polygon — giới hạn 3 hit/tài liệu giữ kích thước ở mức chấp nhận được.

### Quyết định 5: Áp filter phạm vi FR-08 ở tầng truy vấn

- **Ngữ cảnh:** cơ chế đã chốt ở `ai-search-infra.md` QĐ 5 (ACL denormalized
  theo **group id** trong chỉ mục + double-check ở bước hydrate). File này chỉ
  chốt **cách dùng** ở tầng query.
- **Quyết định:**
  - Mọi truy vấn ghép **bắt buộc** một mệnh đề do `ScopeFilterBuilder` sinh, đặt
    trong `bool.filter`: `terms(scope_group_ids, groupIds(user)) OR
    term(owner_user_id, user)` — dựng ở **một chỗ duy nhất** trong
    `search-service`, cấm module khác tự ghép truy vấn.
  - `groupIds(user)` đọc từ Redis cache (`ai-search-infra.md` mục 5); cache
    miss/lỗi → đọc DB; **không lấy được** → 403 fail-closed, không truy vấn.
  - **Double-check khi hydrate:** metadata hiển thị lấy từ PostgreSQL qua đúng
    lớp row-level filter của `rbac-group-scope.md` QĐ 3; hit nào không qua được
    thì bị loại. ⇒ Điều hoà với `classification-notes.md` QĐ 7 ("không nhúng ACL
    vào chỉ mục"): chỉ mục là **bộ lọc thứ nhất** (hiệu năng), DB là **bộ lọc
    quyết định** (đúng đắn) — không mâu thuẫn về kết quả, `ai-search-infra.md`
    là SSOT. Ghi chú (FR-16) không có ACL riêng nên không cần lớp kiểm tra thứ
    ba (mục 2).
  - **Over-fetch để bù double-check:** lấy `size × 1,5 + 10` hit từ OpenSearch,
    lọc lại, cắt đúng `size`; thiếu thì lấy tiếp trang kế (tối đa 2 vòng).
  - **Audit FR-09 (đã chốt 2026-08-16):** ghi `search.executed` với `actor`,
    thời điểm, bộ lọc, `result_count`, và **từ khóa nguyên văn cắt ≤ 200 ký
    tự**; **không** ghi nội dung đoạn trích/ghi chú (ràng buộc
    `classification-notes.md` mục 3). Bản ghi nằm trong `audit_log` append-only,
    chỉ tra cứu được theo FR-10.
- **Lý do:** giữ đúng ngữ nghĩa FR-08 mà không post-filter toàn bộ; fail-closed
  ưu tiên hơn tính khả dụng (NFR-04). Lưu nguyên văn từ khóa giúp truy vết hành
  vi khai thác dữ liệu — mục đích chính của FR-09/FR-10.
- **Hệ quả:** trang cuối có thể ngắn hơn `size`; `total` gần đúng (đã chấp nhận
  ở `ai-search-infra.md` mục 7); nhật ký tìm kiếm chứa từ khóa nghiệp vụ ⇒ phải
  được bảo vệ như dữ liệu nghiệp vụ (xem rủi ro mục 6).

### Quyết định 6: Phân trang, deep paging và giới hạn

- **Ngữ cảnh:** collapse + over-fetch + double-check khiến phân trang không thể
  "chính xác tuyệt đối"; single-node không chịu nổi deep paging tự do.
- **Quyết định:**
  - UI dùng `from`/`size`, `size ≤ search.max_results_per_page` — **đã chốt
    2026-08-16: mặc định 20, tối đa 50** (tham số `system-config`, FR-01).
  - `from + size ≤ 10.000` (`max_result_window` mặc định); vượt → 400 kèm thông
    báo "hãy thu hẹp bộ lọc", **không** nâng `max_result_window`.
  - Duyệt/xuất số lượng lớn (phục vụ `export-reporting` sau này) dùng
    `search_after` + PIT trên khoá sắp xếp ổn định (`_score`, `document_id`),
    không dùng `from` sâu; scroll không dùng.
  - Timeout `search.query_timeout_ms` = **1.500ms** (đã chốt, mục 3); quá hạn →
    lỗi rõ ràng, không trả kết quả một phần.
- **Lý do:** giữ p95 ≤ 1s trên cụm 1 node; deep paging là nguyên nhân OOM phổ
  biến nhất của OpenSearch.
- **Hệ quả:** BA phải thiết kế luồng "thu hẹp bộ lọc" thay vì phân trang vô hạn.

## 5. Tech stack đề xuất

- Backend: Spring Boot — `search-service` (mới): `SearchQueryBuilder`,
  `ScopeFilterBuilder` (dùng chung, `ai-search-infra.md` mục 5),
  `SearchResultHydrator` (double-check ACL + lấy metadata hiển thị +
  `bbox_polygon`), `HighlightMapper` (map offset → vị trí trang/dòng/thời gian).
  Client: OpenSearch Java client.
- Frontend: 1 ô từ khóa + panel bộ lọc nâng cao; kết quả nhóm theo tài liệu, mỗi
  tài liệu ≤ 3 đoạn trích kèm nút "nhảy tới trang/mốc thời gian" và khoanh vùng
  bbox trên ảnh trang.
- Data store: OpenSearch 2.x (`segment-v1` alias `segment`, `document-v1` alias
  `document`), PostgreSQL (hydrate + `bbox_polygon` + fallback lọc metadata),
  Redis (cache `groupIds(user)`).
- Infra/Deploy: không thêm thành phần mới ngoài `ai-search-infra.md`. Plugin bắt
  buộc: `analysis-icu` (cài offline).
- Tham số `system-config` (FR-01) — **giá trị chính thức, chốt 2026-08-16**:
  `search.max_results_per_page` = 20 (tối đa 50), `search.query_timeout_ms` =
  1500. Không đưa analyzer/`refresh_interval`/tên index vào UI runtime
  (đổi ⇒ reindex).
- **Hợp đồng cho `search-semantic` (FR-24):** `search-service` cung cấp một
  interface nội bộ `lexicalSearch(query, filters, scopeCtx, topK) →
  List<(segment_id, bm25_score)>` để slug ngữ nghĩa hợp nhất (RRF/hybrid) trên
  cùng khoá `segment_id`; `ScopeFilterBuilder`, hydrate và audit **dùng chung**,
  không được sao chép logic.

## 6. Rủi ro

| Rủi ro | Mức độ | Giảm thiểu |
|---|---|---|
| Precision cụm từ tiếng Việt kém do ICU tách theo âm tiết (kế thừa `ai-search-infra.md`) | Trung bình | `.shingle` + boost `match_phrase` (QĐ 1/4); **tập truy vấn vàng do nghiệp vụ/QA cung cấp** (đã chốt trách nhiệm 2026-08-16), đo recall/precision **trước go-live**; lối thoát reindex với plugin tách từ ở giai đoạn 2 |
| **Rò rỉ dữ liệu ngoài phạm vi** qua kết quả tìm kiếm (quên `ScopeFilterBuilder`, ACL lệch, hit từ segment `note`/`title`) | **Cao** | Bắt buộc một điểm dựng truy vấn duy nhất + double-check khi hydrate (QĐ 5); test hồi quy "A không thấy tài liệu của B" cho **mọi** `content_source`; audit truy vấn (FR-09) |
| Nhật ký audit chứa **từ khóa nguyên văn** (QĐ 5) → bản thân nhật ký trở thành dữ liệu nhạy cảm | Trung bình | Cắt ≤ 200 ký tự; `audit_log` append-only, chỉ tra cứu qua FR-10 với quyền hạn chế; không ghi đoạn trích/nội dung tài liệu; nêu rõ trong tài liệu vận hành |
| Gắn nhãn/chia sẻ hàng loạt sinh bão `update_by_query` trên hàng triệu segment (hệ quả denormalize ở QĐ 2) | Trung bình | Gom lô sự kiện theo `document_id`, throttle `update_by_query` (`requests_per_second`), chạy ngoài giờ cao điểm; giám sát độ trễ hàng đợi |
| Người dùng không tìm thấy nội dung vừa nạp (trễ tới 24h do FR-18) và quy cho tìm kiếm lỗi | Trung bình | UI nêu rõ trạng thái "chờ kiểm duyệt"; cho tìm theo tên tệp/nhãn ngay (segment `title`, QĐ 2); BA viết thông điệp rỗng tường minh |
| `total` gần đúng + trang cuối ngắn gây nghi ngờ tính đầy đủ của kết quả (chứng cứ) | Trung bình | Hiển thị "khoảng N kết quả"; cung cấp bộ lọc thu hẹp; ghi rõ trong tài liệu người dùng; xuất danh sách đầy đủ qua `search_after` khi cần đối chiếu |
| Truy vấn nặng (từ khóa phổ biến + highlight + collapse) làm chậm cụm 1 node | Trung bình | Giới hạn đầu vào (QĐ 3), `inner_hits ≤ 3`, timeout 1.5s, chặn wildcard/fuzzy, giám sát search thread pool queue |
| Chỉ mục hỏng/mất → toàn bộ FR-22/23/25 ngừng hoạt động | Trung bình | Dựng lại từ PostgreSQL (RTO ≤ 12h, `ai-search-infra.md`); fallback lọc metadata bằng SQL để FR-23 không chết hoàn toàn |
| **Phụ thuộc chưa `Approved` khi viết file này** — `digitization-pipeline.md`, `digitization-review.md`, `classification-notes.md` đang `Draft` (đã chốt 2026-08-16: **chấp nhận rủi ro, làm song song**) | Trung bình | Nếu 3 file đó thay đổi khi duyệt, rà soát lại: toạ độ định vị (`page_no`/`line_from`/`line_to`/`bbox_polygon`/`time_*` — pipeline QĐ 3) ở QĐ 1/4, luồng `review.confirmed` ở mục 2, taxonomy nhãn (`topic`/`doc_type`) và hợp đồng ghi chú ở QĐ 2/3. DEV kiểm tra lại trước khi viết tech spec |
| Chỉ có bộ lọc thời gian theo `uploaded_at` (QĐ 3) — người dùng có thể hiểu nhầm là ngày ban hành tài liệu | Thấp | Đặt nhãn UI rõ "Ngày nạp kho"; bổ sung `document_date` ở giai đoạn sau nếu nghiệp vụ yêu cầu (thay đổi cộng thêm) |

## 7. Câu hỏi mở

Toàn bộ câu hỏi mở đã được người dùng chốt ngày **2026-08-16**:

- [x] **`content_source` thêm `title`** — sửa nhẹ enum ở `ai-search-infra.md`
      mục 8 (đã cập nhật), giữ nguyên thiết kế collapse + `inner_hits` (QĐ 2).
- [x] **Độ mịn FR-25** — khoảng dòng theo block OCR (`line_from`/`line_to`)
      **kèm `bbox_polygon`**; không làm bước map offset → dòng (QĐ 4).
- [x] **Bộ lọc thời gian FR-23** — chỉ `uploaded_at`; `document_date` để ngỏ cho
      giai đoạn sau (QĐ 3).
- [x] **Quyền ghi chú (FR-16)** — thừa hưởng phạm vi tài liệu cha, không có ACL
      riêng (mục 2, QĐ 5).
- [x] **Audit từ khóa** — ghi nguyên văn, cắt ≤ 200 ký tự (mục 3, QĐ 5); rủi ro
      kèm theo đã ghi ở mục 6.
- [x] **Giá trị mặc định** — `search.max_results_per_page` = 20 (tối đa 50),
      `search.query_timeout_ms` = 1500ms (mục 3, QĐ 6, mục 5).
- [x] **Tập truy vấn vàng tiếng Việt** — **nghiệp vụ/QA chịu trách nhiệm cung
      cấp**; đo recall/precision trước go-live (action item ở mục 6).
- [x] **Phụ thuộc còn `Draft`** — chấp nhận rủi ro, triển khai song song; ghi
      chú rà soát lại đã đưa vào mục 6.

**Action item còn theo dõi (không chặn bước BA):** nghiệp vụ/QA xây tập truy vấn
vàng tiếng Việt và chạy đo recall/precision trước go-live.
