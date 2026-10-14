# User Story: search-semantic

> File: `docs/02-ba/stories/search-semantic.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/search-semantic.md`
> Status: Approved

## User Story

**As a** người dùng nội bộ R1/R2 (có phạm vi truy cập dữ liệu được cấp qua
nhóm/FR-08)
**I want** đặt câu hỏi hoặc mô tả ý bằng ngôn ngữ tự nhiên và nhận về các
đoạn nội dung liên quan trong phạm vi dữ liệu của tôi, mỗi đoạn kèm dẫn nguồn
chính xác (tài liệu + vị trí)
**So that** tôi tìm được nội dung/bằng chứng liên quan mà không cần nhớ đúng
từ khoá, và có thể tin cậy tra cứu tiếp tới đúng vị trí trong tài liệu gốc

## Acceptance Criteria

### AC-1 — Hỏi bằng ngôn ngữ tự nhiên, luôn chạy hybrid (không có lựa chọn "chỉ ngữ nghĩa"/"chỉ từ khoá")

```
Given tôi là R1/R2 đã đăng nhập, đang ở trang tra cứu FR-24 (`/search/semantic`)
When tôi nhập một câu hỏi hoặc mô tả ý bằng ngôn ngữ tự nhiên (không nhất
     thiết đúng từ khoá) và bấm tìm
Then hệ thống luôn chạy đồng thời nhánh từ khoá (lexical) và nhánh ngữ nghĩa
     (vector), hợp nhất và xếp hạng bằng RRF (không rerank) trước khi trả
     kết quả
And giao diện KHÔNG hiển thị lựa chọn "chỉ tìm theo ngữ nghĩa" hoặc "chỉ tìm
     theo từ khoá" cho người dùng chọn thủ công
And khi chạy hybrid bình thường, UI không hiển thị nhãn chế độ nào; chỉ khi
     hệ thống tự suy giảm mới hiển thị banner cảnh báo (AC-5.4) — banner là
     thông tin, không phải nút bấm chọn chế độ
```

- [ ] AC-1.1: Ô nhập hỗ trợ câu hỏi/mô tả ý tự nhiên (không giới hạn phải là
      từ khoá đơn), có thể dùng lại panel bộ lọc có cấu trúc đã có ở tra cứu
      toàn văn (FR-23) để thu hẹp phạm vi kết quả.
- [ ] AC-1.2: Không tồn tại control UI nào cho phép người dùng ép chế độ
      `semantic`-only hoặc `lexical`-only ở màn hình FR-24 trong điều kiện
      vận hành bình thường.
- [ ] AC-1.3: Với truy vấn ngắn gồm mã số/tên riêng văn bản mà **mọi từ**
      đều có mặt trong một đoạn (nhánh từ khoá khớp theo AND — SA QĐ 2), đoạn
      đó luôn có trong kết quả. Với câu hỏi dài trộn mô tả ý + mã số, hệ
      thống **không** cam kết bắt được mã số (kết quả chủ yếu dựa vào nhánh
      ngữ nghĩa); ô nhập có dòng gợi ý nguyên văn: **"Tìm theo số hiệu hoặc
      tên văn bản chính xác? Dùng trang Tìm kiếm toàn văn."** (liên kết tới
      `/search`).
- [ ] AC-1.4: FR-24 là trang riêng `/search/semantic`, mục menu **"Tra cứu
      ngữ nghĩa"** khai báo trong `web/src/lib/features.ts`, chỉ hiện với
      R1 (CAN_BO) và R2 (LANH_DAO) — cùng vai trò với `/search`. Trang dùng
      lại panel bộ lọc FR-23 và cách nhảy tới vị trí của `/search`
      (`search-fulltext-05-frontend.md`); không sửa trang `/search`.

### AC-2 — Kết quả là đoạn trích liên quan kèm dẫn nguồn, không phải câu trả lời tổng hợp

```
Given hệ thống trả về danh sách kết quả cho một câu hỏi FR-24
When tôi xem một kết quả trong danh sách
Then mỗi kết quả là một đoạn nội dung trích từ tài liệu (không phải câu văn
     do hệ thống tự sinh/tổng hợp — giai đoạn 1 không có LLM sinh văn)
And mỗi kết quả bắt buộc kèm dẫn nguồn: tên/loại tài liệu, và vị trí chính
     xác trong tài liệu (số trang + dòng, hoặc mốc thời gian với nội dung
     âm thanh, hoặc đánh dấu "không có vị trí cụ thể" với tiêu đề/ghi
     chú/tóm tắt video)
And tôi có thể bấm vào dẫn nguồn để nhảy tới đúng vị trí đó trong tài liệu
     gốc (khoanh vùng/tô vị trí tương ứng)
And nhãn/UI thể hiện rõ đây là "các đoạn nội dung liên quan", KHÔNG dùng
     ngôn ngữ gợi ý đây là "câu trả lời" do hệ thống đưa ra
```

- [ ] AC-2.1: Danh sách kết quả hiển thị tối thiểu: đoạn trích, tên tài liệu,
      vị trí (trang/dòng hoặc mốc thời gian), cờ nguồn nội dung là tự động
      hay đã xác nhận thủ công. Nội dung tự động xác nhận dùng **đúng badge
      vàng** của trang toàn văn, nguyên văn: **"Nội dung tự động xác nhận —
      cân nhắc kỹ khi dùng làm chứng cứ"** (`search-fulltext-05-frontend.md`).
- [ ] AC-2.2: Nhãn tiêu đề khu vực kết quả và các văn bản hướng dẫn liên quan
      dùng từ ngữ kiểu "các đoạn liên quan", không dùng từ ngữ kiểu "câu trả
      lời", "trả lời của hệ thống" gây hiểu nhầm có sinh văn bản. Tiêu đề
      khu vực kết quả, nguyên văn: **"Các đoạn nội dung liên quan"**. Mọi
      copy UI của FR-24 chỉ bằng tiếng Việt (đúng tiền lệ
      `search-fulltext-05-frontend.md`).
- [ ] AC-2.3: Giai đoạn 1 **không tô sáng (highlight)** trong đoạn trích —
      mọi kết quả hiển thị nguyên đoạn (hợp đồng `lexicalSearch` không trả
      vị trí khớp; không tô màu suy đoán). Kết quả có khớp ở nhánh từ khoá
      (`matchedLexical = true`) hiển thị nhãn nhỏ, nguyên văn: **"Khớp từ
      khoá"**.
- [ ] AC-2.4: Sửa nội dung một đoạn qua luồng kiểm duyệt (FR-18) rồi tìm lại
      với câu hỏi liên quan thì kết quả phải hiển thị đúng nội dung mới nhất
      (không lộ nội dung cũ đã lỗi thời kèm dẫn nguồn — sai nguồn là lỗi
      nghiêm trọng nhất theo mục 6 file kiến trúc).
- [ ] AC-2.5: Kết quả là **tóm tắt nội dung video** (đã qua xác nhận FR-18 —
      `digitization-results-store.md` §7 điểm 2) hiển thị nhãn nguyên văn
      **"Tóm tắt nội dung video"**, vị trí là "không có vị trí cụ thể"; bấm
      dẫn nguồn mở tài liệu video từ đầu. Kết quả nhãn sự kiện âm thanh
      (`sed`) **không** xuất hiện trong FR-24.

### AC-3 — Không đủ căn cứ thì trả rỗng, không suy diễn

```
Given tôi đặt một câu hỏi mà trong phạm vi dữ liệu của tôi không có đoạn
      nào đạt ngưỡng tương đồng ngữ nghĩa (cosine ≥ `semantic.min_score`),
      và cũng không có đoạn nào khớp đủ mọi từ của câu hỏi ở nhánh từ khoá
When hệ thống trả kết quả
Then danh sách kết quả rỗng
And hiển thị thông điệp "không đủ căn cứ" (nguyên văn ở AC-3.3)
And hệ thống KHÔNG tự hạ ngưỡng để cố trả về gì đó, KHÔNG trả "kết quả gần
     đúng nhất" chưa đạt ngưỡng dưới bất kỳ hình thức nào (kể cả gắn nhãn
     "có thể liên quan")
```

- [ ] AC-3.1: Thông điệp rỗng phân biệt được với trạng thái lỗi hệ thống
      (timeout/503) — người dùng hiểu đây là "đã tìm nhưng không đủ căn cứ",
      không phải "hệ thống đang gặp sự cố".
- [ ] AC-3.2: Không có bất kỳ đường dẫn UI/hành vi nào (kể cả nút "tìm rộng
      hơn", "bỏ qua ngưỡng") khiến hệ thống trả kết quả không qua ngưỡng
      căn cứ (cosine ≥ `semantic.min_score` hoặc khớp đủ từ khoá).
- [ ] AC-3.3: Thông điệp rỗng hiển thị dạng trạng thái rỗng trung tính tại
      vị trí danh sách kết quả (không phải banner đỏ/vàng), nguyên văn:
      **"Không tìm thấy đoạn nội dung nào đủ căn cứ trong phạm vi dữ liệu
      của bạn. Hãy thử diễn đạt lại câu hỏi hoặc điều chỉnh bộ lọc."**

### AC-4 — Bảo mật phạm vi: luôn lọc theo nhóm được cấp, không có chế độ bỏ lọc

```
Given tôi chỉ được cấp quyền xem dữ liệu của một số nhóm nhất định (FR-08)
When tôi đặt câu hỏi bằng mô tả ý (không chứa từ khoá cụ thể) mà có thể
     "gợi trúng" nội dung thuộc nhóm tôi không có quyền
Then kết quả trả về CHỈ gồm các đoạn thuộc phạm vi (nhóm/sở hữu) tôi được
     phép xem — không rò rỉ nội dung ngoài phạm vi, kể cả khi không dùng từ
     khoá chính xác
And không tồn tại control/tham số nào (UI hay ẩn) cho phép "bỏ filter để
     tăng recall"
And nếu hệ thống không xác định được phạm vi/nhóm của tôi tại thời điểm truy
     vấn, yêu cầu bị từ chối (không trả kết quả rỗng lặng lẽ, không trả kết
     quả không lọc)
```

- [ ] AC-4.1: Test hồi quy bắt buộc trước go-live: người dùng A không thể moi
      được nội dung thuộc phạm vi của người dùng B bằng bất kỳ câu hỏi mô tả
      ý nào (đúng kỳ vọng "Rủi ro rò rỉ" — mục 6 file kiến trúc).
- [ ] AC-4.2: Khi hệ thống không lấy được thông tin nhóm của người dùng hiện
      tại, yêu cầu tra cứu bị chặn (403), banner đỏ phía trên danh sách,
      nguyên văn: **"Không xác định được phạm vi dữ liệu của bạn nên chưa thể
      tra cứu. Vui lòng thử lại sau hoặc liên hệ quản trị viên."** — không
      âm thầm trả kết quả.
- [ ] AC-4.3: Mọi kết quả hiển thị cho người dùng đã được hệ thống xác nhận
      hai lớp (lọc trước khi truy vấn + kiểm tra lại khi lấy nội dung hiển
      thị) — không có kết quả nào hiển thị chỉ dựa trên một lớp kiểm tra.

### AC-5 — Suy giảm có kiểm soát khi hệ ngữ nghĩa lỗi

```
Given một trong hai nhánh tìm kiếm đang lỗi hoặc quá thời gian — nhánh ngữ
      nghĩa (vector search hoặc mô hình embedding) hoặc nhánh từ khoá
When tôi đặt câu hỏi
Then hệ thống vẫn trả kết quả bằng nhánh còn lại (không phải lỗi trắng
     trang, trừ khi cả hai nhánh cùng lỗi)
And UI hiển thị cảnh báo rõ ràng kết quả hiện chỉ theo một nhánh (chất
     lượng/độ phủ có thể thấp hơn bình thường), không đánh lừa là kết quả
     hybrid đầy đủ
And phạm vi dữ liệu (lọc theo nhóm/quyền) vẫn được áp dụng đầy đủ như bình
     thường — suy giảm KHÔNG BAO GIỜ đồng nghĩa với bỏ lọc phạm vi
```

- [ ] AC-5.1: Khi chỉ nhánh ngữ nghĩa lỗi/timeout: kết quả trả về là kết quả
      lexical-only kèm cảnh báo trên UI; khi chỉ nhánh từ khoá lỗi: kết quả
      là semantic-only kèm cảnh báo tương ứng.
- [ ] AC-5.2: Khi cả hai nhánh cùng lỗi (503): banner đỏ phía trên danh
      sách, nguyên văn: **"Dịch vụ tra cứu đang tạm thời không khả dụng, vui
      lòng thử lại sau."** — không trả danh sách rỗng như trường hợp "không
      đủ căn cứ" ở AC-3; kết quả cũ (nếu có) bị xoá, không hiển thị kết quả
      một phần.
- [ ] AC-5.3: Khi hệ thống vượt hạn mức truy vấn ngữ nghĩa đồng thời (429 —
      áp dụng ở **mọi** chế độ, không chỉ khi suy giảm; SA QĐ 5): banner
      vàng kèm nút **"Thử lại"** (cùng hành vi nút ở AC-5.4), nguyên văn:
      **"Hệ thống tra cứu ngữ nghĩa đang bận, vui lòng thử lại sau ít
      giây."** — không phải lỗi chung chung.
- [ ] AC-5.4: Cảnh báo suy giảm là **banner vàng** phía trên danh sách kết
      quả (khác màu banner lỗi đỏ — đúng tiền lệ AC-9/AC-10
      `search-fulltext-05-frontend.md`), nguyên văn:
      - `degradedMode = lexical_only`: **"Tìm kiếm theo ngữ nghĩa đang tạm
        thời không khả dụng. Kết quả dưới đây chỉ khớp theo từ khoá và có
        thể bỏ sót nội dung liên quan."**
      - `degradedMode = semantic_only`: **"Tìm kiếm theo từ khoá đang tạm
        thời không khả dụng. Kết quả dưới đây chỉ dựa trên tương đồng ngữ
        nghĩa và có thể bỏ sót kết quả khớp chính xác mã số/tên riêng."**
      - Banner có nút **"Thử lại"**: gửi lại đúng câu hỏi + bộ lọc hiện tại;
        nút bị vô hiệu trong lúc request đang chạy (không gửi trùng).

### AC-6 — Giới hạn phân trang

```
Given tôi đang xem danh sách kết quả FR-24 cho một câu hỏi
When tôi chuyển sang các trang tiếp theo
Then hệ thống chỉ hỗ trợ xem tối đa 2 trang kết quả đầu tiên
And nếu tôi muốn xem thêm, UI hướng dẫn tôi thu hẹp bộ lọc/tinh chỉnh câu
     hỏi thay vì cho phép phân trang sâu (deep paging)
```

- [ ] AC-6.1: UI không cung cấp điều hướng tới trang thứ 3 trở đi cho kết quả
      FR-24; nếu số kết quả vượt quá 2 trang, hiển thị gợi ý thu hẹp phạm
      vi tìm kiếm.
- [ ] AC-6.2: Tổng số kết quả hiển thị (nếu có) được ghi chú là số gần đúng,
      không cam kết chính xác tuyệt đối (do double-check phạm vi có thể làm
      giảm số lượng thực tế hiển thị so với số tìm được ban đầu).

### AC-7 — Ghi nhật ký kiểm toán mọi truy vấn FR-24

```
Given tôi thực hiện một truy vấn FR-24 (thành công, rỗng, suy giảm hoặc lỗi)
When hệ thống xử lý xong truy vấn
Then một bản ghi `search.executed` được ghi vào nhật ký kiểm toán (FR-09)
And bản ghi KHÔNG chứa nội dung đoạn trích/tài liệu trả về
```

- [ ] AC-7.1: Bản ghi gồm: người thực hiện, thời điểm, bộ lọc, câu hỏi
      nguyên văn **cắt ≤ 200 ký tự**, `mode` (`hybrid|semantic|lexical`),
      `degraded_mode`, `min_score` đang áp dụng, `result_count` (SA mục 3
      NFR Security (audit), mục 7).
- [ ] AC-7.2: Ghi nhật ký không làm chậm/chặn việc trả kết quả cho người
      dùng; truy vấn bị từ chối 403 vẫn được ghi nhận.

### AC-8 — Ghi chú nội dung chưa tìm được đầy đủ

```
Given một kết quả thuộc tài liệu còn tác vụ số hoá chưa xác nhận
      (đang chờ/đang xử lý/chờ duyệt/đang duyệt/lỗi)
When kết quả hiển thị trong danh sách FR-24
Then ngay dưới tên tài liệu có dòng ghi chú màu xám, nguyên văn: "Nội dung
     đang chờ kiểm duyệt, có thể mất tới 24 giờ để tìm được theo nội dung
     đầy đủ."
```

- [ ] AC-8.1: Dùng đúng quy tắc và câu chữ của
      `search-fulltext-05-frontend.md` Story 4 AC-1 (dựa vào trạng thái số
      hoá của tài liệu, không suy từ nguồn nội dung đoạn trích); đây là
      thông tin, không phải cảnh báo (không dùng màu đỏ/vàng).

## Ngoài phạm vi (Out of scope)

- Sinh câu trả lời tổng hợp bằng LLM (giai đoạn 2, chưa có thời điểm kích
  hoạt — xem `search-semantic.md` mục 7).
- Cho người dùng tự chọn chế độ tìm kiếm (chỉ từ khoá / chỉ ngữ nghĩa) — đã
  chốt là luôn hybrid, chỉ có `degraded_mode` do hệ thống tự quyết định.
- Phân trang sâu (deep paging) quá 2 trang đầu.
- Tô sáng (highlight) từ khoá trong đoạn trích FR-24 — giai đoạn 1 chỉ có
  nhãn "Khớp từ khoá" (AC-2.3).
- Cam kết bắt được mã số/tên riêng trong câu hỏi dài hỗn hợp (AC-1.3) —
  dùng trang toàn văn `/search`.
- Kết quả nhãn sự kiện âm thanh (`content_source = sed`) trong FR-24.
- Truy vấn cross-lingual (Anh ↔ Việt) — ngoài phạm vi giai đoạn 1.
- Cấu hình tham số vận hành (`semantic.top_k`, `semantic.min_score`,
  `search.query_timeout_ms`) qua UI FR-01 — thuộc
  phạm vi story/role R3 (quản trị), không thuộc story R1/R2 này.
- Chi tiết mapping/analyzer/DSL của nhánh toàn văn, và hành vi phân trang
  của tra cứu toàn văn thuần (FR-23) — thuộc `search-fulltext.md`.

## Phụ thuộc / Ghi chú kiến trúc liên quan

- Tham chiếu ADR trong `docs/01-sa/architecture/search-semantic.md`:
  - Quyết định 1 (chunking = 1 vector/segment + cửa sổ ngữ cảnh lân cận):
    nền tảng để dẫn nguồn (AC-2) luôn trỏ đúng segment/vị trí, kể cả khi nội
    dung embed có ghép ngữ cảnh lân cận.
  - Quyết định 2 (hybrid RRF, không rerank — sửa 2026-10-08; ngưỡng căn cứ =
    cosine ≥ `semantic.min_score` hoặc khớp đủ từ ở nhánh lexical): nền tảng
    cho AC-1 và AC-3.
  - Quyết định 3 (pre-filter phạm vi trong truy vấn k-NN OpenSearch (sửa
    2026-10-10, trước là Qdrant) + double-check khi hydrate, fail-closed khi thiếu ngữ cảnh phạm vi): nền tảng cho AC-4.
  - Quyết định 4 (chỉ mục chỉ dùng khoá/ACL/điểm, nội dung dẫn nguồn lấy từ
    PostgreSQL; "không đủ căn cứ" trả rỗng, cấm hạ ngưỡng ngầm): nền tảng
    cho AC-2 và AC-3. Story này chốt thêm (2026-10-08): không highlight ở
    giai đoạn 1 (AC-2.3); `content_source` của kết quả FR-24 gồm
    `ocr|stt|note|title|summary`, loại `sed` (AC-2.5).
  - Quyết định 5 (ngân sách thời gian, suy giảm từng phần với cờ
    `degraded_mode`, không bao giờ bỏ filter phạm vi khi suy giảm): nền
    tảng cho AC-5.
  - Quyết định 6 (bổ sung 2026-10-10: vector trong OpenSearch k-NN,
    embedding Qwen3-Embedding-0.6B qua endpoint REST nội bộ; không còn
    Qdrant/TEI): chỉ đổi hạ tầng, **không** đổi AC nào. "Dịch vụ ngữ
    nghĩa/embedding lỗi" ở AC-5 là endpoint embedding hoặc truy vấn k-NN.
- Giao diện dùng lại thành phần của `search-fulltext-05-frontend.md`: panel
  bộ lọc FR-23, nhảy tới vị trí/bbox, badge "tự động", ghi chú chờ kiểm
  duyệt, kiểu banner đỏ (lỗi)/vàng (suy giảm).
- Hợp đồng `lexicalSearch()` mà Quyết định 2/5 dựa vào nằm ở
  `docs/01-sa/architecture/search-fulltext.md` §5 (**Approved**), chữ ký
  không đổi.
- **Điều kiện go-live (không chặn DEV):** `semantic.min_score` = 0,50 (ngưỡng
  cosine; sửa 2026-10-11 từ 0,55 do đổi model sang Qwen3) và `semantic.top_k` = 50 là giá trị tạm `[ĐX]` (SA mục 7 đã chấp
  nhận); phải hiệu chỉnh bằng tập truy vấn vàng tiếng Việt do nghiệp vụ/QA
  cung cấp trước go-live. Tham số đọc từ `system-config`, chỉnh không cần
  deploy lại.

## Câu hỏi mở cho DEV

- [x] **Phụ thuộc `search-fulltext.md` còn Draft (chặn `/dev-spec`)** →
      **Chốt:** đã giải quyết — `search-fulltext.md` Approved, §5 giữ nguyên
      chữ ký `lexicalSearch`; SA `search-semantic.md` §7 chốt 2026-10-08; xoá
      cảnh báo (cập nhật: đầu file, Phụ thuộc).
- [x] **Nội dung thông báo "không đủ căn cứ" và nhãn UI (ngôn ngữ, văn
      phong)** → **Chốt:** chỉ tiếng Việt, nguyên văn; tiêu đề "Các đoạn nội
      dung liên quan"; thông điệp rỗng chốt ở AC-3.3 (cập nhật: AC-2.2, AC-3.3).
- [x] **Ngưỡng `semantic.min_score`/`top_k` là giá trị tạm** → **Chốt:**
      không phải câu hỏi cho DEV; là điều kiện go-live, trách nhiệm tập
      truy vấn vàng thuộc nghiệp vụ/QA (cập nhật: Phụ thuộc).
- [x] **Cách hiển thị cảnh báo `degraded_mode`** → **Chốt:** banner vàng
      phía trên danh sách, copy nguyên văn theo từng chế độ, có nút "Thử
      lại" (cập nhật: AC-5.4).

Chốt 2026-10-08 sau review story:

- [x] **AC-1.3 hứa bắt mã số trong câu hỏi dài, nhưng nhánh từ khoá dùng
      AND** → **Chốt:** thu hẹp cam kết về truy vấn ngắn khớp đủ từ, gợi ý
      dùng `/search` (cập nhật: AC-1.3, Ngoài phạm vi).
- [x] **Highlight cần vị trí khớp mà `lexicalSearch` không trả** → **Chốt:**
      bỏ highlight ở giai đoạn 1, chỉ nhãn "Khớp từ khoá" (cập nhật: AC-2.3,
      Ngoài phạm vi).
- [x] **Màn hình FR-24 đặt ở đâu** → **Chốt:** trang riêng
      `/search/semantic`, menu "Tra cứu ngữ nghĩa" (cập nhật: AC-1, AC-1.4).
- [x] **Câu chữ lỗi 403/503/429** → **Chốt:** nguyên văn ở AC-4.2, AC-5.2,
      AC-5.3; 429 áp dụng mọi chế độ.
- [x] **Yêu cầu SA chưa có AC (audit, trễ chỉ mục)** → **Chốt:** thêm AC-7,
      AC-8.
- [x] **`sed`/tóm tắt video trong kết quả** → **Chốt:** có tóm tắt video,
      không có `sed` (cập nhật: AC-2, AC-2.5, Ngoài phạm vi).
