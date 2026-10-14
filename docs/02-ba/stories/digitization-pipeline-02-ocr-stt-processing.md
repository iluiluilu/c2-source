# User Story: digitization-pipeline-02-ocr-stt-processing

> File: `docs/02-ba/stories/digitization-pipeline-02-ocr-stt-processing.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/digitization-pipeline.md` (mục 1
> "Triển khai theo pha", Quyết định 1/2/3/4/5/6)
> Status: Draft

Pha 2 của số hoá: tiếp theo `digitization-pipeline-01-source-dispatch`
(Approved) — sau khi backend đã đẩy yêu cầu xử lý vào `q.ocr`/`q.stt` với 5
trường message đã chốt (`fileId`, `sourceId`, `filePath`, `originalFileType`,
`processFileType`), AI worker tiêu thụ hàng đợi, chạy OCR (ảnh/PDF) hoặc STT
(audio), phân đoạn nội dung, ghi nhận kết quả, và bàn giao cho
`digitization-review` để cán bộ kiểm duyệt. **Không** bao gồm bước intake/
dispatch (Pha 1; từ 2026-09-23 video không còn tách nguồn và **không** đi
`q.stt` — `digitization-pipeline.md` Quyết định 10), và **không** bao gồm bản thân màn hình/luồng kiểm
duyệt (thuộc `digitization-review`).

---

## Story 1 — Worker OCR tiêu thụ hàng đợi, nhận dạng ký tự có định vị vị trí

**As a** hệ thống (worker OCR)
**I want** tiêu thụ yêu cầu xử lý từ hàng đợi OCR, đọc tệp ảnh/PDF theo đường
dẫn đã nhận, chạy nhận dạng ký tự trên toàn bộ nội dung và sinh kết quả theo
từng đoạn có gắn vị trí trên bản gốc
**So that** nội dung ảnh/PDF trong kho chung được chuyển thành văn bản có thể
tra cứu và định vị lại đúng vị trí (trang, vùng) trên bản gốc

### Acceptance Criteria

```
Given một yêu cầu xử lý OCR nằm trong hàng đợi (5 trường đã chốt ở Story 3 của
     digitization-pipeline-01-source-dispatch)
When worker OCR nhận job
Then worker chạy nhận dạng ký tự trên toàn bộ tệp, sinh ra các đoạn nội dung
     có gắn trang/vị trí/độ tin cậy, không sửa/ghi đè tệp gốc
```

- [ ] AC-1: Worker chạy **OCR đầy đủ trên mọi trang**, kể cả khi tệp PDF đã có
      sẵn lớp text nhúng — lớp text sẵn có (nếu có) chỉ dùng để **đối chiếu
      QA** (không dùng để bỏ qua OCR, không sinh đoạn nội dung riêng cho nó).
- [ ] AC-2: Mỗi đoạn nội dung (segment) sinh ra gắn: số trang, vị trí trong
      trang, thứ tự đọc, và độ tin cậy — sắp xếp theo đúng thứ tự đọc tự
      nhiên của tài liệu.
- [ ] AC-3: Việc dựng lại cấu trúc bảng (table) trong tài liệu **không** được
      cam kết ở phạm vi story này.
- [ ] AC-4: Worker chỉ **đọc** tệp gốc (không sửa/ghi đè); mọi kết quả sinh ra
      (kể cả dữ liệu thô đầy đủ) là bản phái sinh, lưu tách biệt khỏi tệp
      gốc.
- [ ] AC-5: Mỗi lần chạy (run) ghi nhận rõ **model** và **phiên bản model** đã
      dùng để sinh kết quả, phục vụ truy vết/chạy lại khi cần đổi model.

---

## Story 2 — Worker STT tiêu thụ hàng đợi, chuyển giọng nói thành văn bản có mốc thời gian

**As a** hệ thống (worker STT)
**I want** tiêu thụ yêu cầu xử lý từ hàng đợi STT, đọc tệp audio theo đường
dẫn đã nhận, chạy nhận dạng giọng nói và sinh transcript theo từng câu có gắn
mốc thời gian
**So that** nội dung audio (tệp audio nhập kho; audio tách từ video chỉ còn ở
dữ liệu trước 2026-09-23 — Quyết định 10) được chuyển thành văn
bản có thể tra cứu và tua đúng vị trí để nghe lại

### Acceptance Criteria

```
Given một yêu cầu xử lý STT nằm trong hàng đợi
When worker STT nhận job
Then worker chạy nhận dạng giọng nói, cắt khoảng lặng trước khi nhận dạng,
     sinh các đoạn văn bản có mốc thời gian bắt đầu/kết thúc hợp lệ so với độ
     dài tệp gốc
```

- [ ] AC-1: Worker phát hiện và cắt khoảng lặng/nhiễu (VAD) **trước khi** đưa
      vào mô hình nhận dạng giọng nói, nhằm hạn chế hiện tượng model "bịa"
      câu trên đoạn im lặng.
- [ ] AC-2: Kết quả được gộp thành từng đoạn theo câu (dựa vào dấu câu +
      khoảng lặng), độ dài mỗi đoạn nằm trong khoảng cấu hình (khởi điểm
      3–30 giây; đoạn quá dài bị cắt cưỡng bức).
- [ ] AC-3: Mỗi đoạn có mốc thời gian **bắt đầu** và **kết thúc** (tính bằng
      mili-giây, tương đối từ đầu tệp media) cùng độ tin cậy.
- [ ] AC-4: Mốc thời gian của mọi đoạn phải thoả: `0 ≤ bắt đầu < kết thúc ≤
      độ dài tệp gốc` — hệ thống **chặn** việc ghi nhận mốc thời gian vượt
      quá độ dài thật của tệp gốc, không phải chỉ kiểm tra ở tầng ứng dụng.
- [ ] AC-5: Phiên âm phải **trung thực** — không lọc, không che từ tục tĩu
      hay phương ngữ vùng miền (đây là chứng cứ nghiệp vụ); chỉ được phép
      tăng cường từ vựng để nhận dạng đúng hơn, không được sửa nghĩa.
- [ ] AC-6: Mỗi lần chạy ghi nhận rõ model và phiên bản model đã dùng.

---

## Story 3 — Máy trạng thái job, retry, và phát hiện job treo

**As a** hệ thống
**I want** theo dõi trạng thái từng job OCR/STT theo một máy trạng thái rõ
ràng, tự động thử lại khi gặp lỗi tạm thời, chuyển vào hàng đợi lỗi khi hết
lần thử hoặc gặp lỗi vĩnh viễn, và tự phát hiện job bị treo do worker/GPU gặp
sự cố
**So that** không tệp nào bị mất hoặc kẹt vĩnh viễn trong hàng đợi xử lý, và
người vận hành biết được job nào cần can thiệp

### Acceptance Criteria

```
Given một job OCR/STT đang được xử lý
When job gặp lỗi, hoặc worker xử lý job bị treo/chết giữa chừng
Then hệ thống tự động thử lại (nếu lỗi tạm thời), tự động đưa lại vào hàng
     đợi (nếu job bị treo), hoặc dừng thử và cảnh báo (nếu hết số lần thử
     hoặc lỗi không thể khắc phục bằng cách thử lại)
```

- [ ] AC-1: Trạng thái một job đi theo đúng trình tự: `queued → running →
      (succeeded | failed_retryable | failed_permanent | cancelled)`.
- [ ] AC-2: Lỗi **tạm thời** (hết bộ nhớ GPU, timeout, lỗi đọc/ghi) → tự động
      thử lại tối đa **3 lần**, có thời gian chờ tăng dần giữa các lần thử;
      hết 3 lần vẫn lỗi → chuyển sang hàng đợi lỗi (DLQ) và **sinh cảnh
      báo**.
- [ ] AC-3: Lỗi **vĩnh viễn** (tệp hỏng, định dạng/codec không hỗ trợ) →
      chuyển thẳng trạng thái lỗi không thể thử lại, **không** tốn lượt thử
      lại nào.
- [ ] AC-4: Một job đang chạy phải định kỳ "báo còn sống"; nếu quá một
      khoảng thời gian không báo (worker/GPU chết giữa chừng), hệ thống coi
      job đó là **treo** và **tự động đưa lại vào hàng đợi** để worker khác
      xử lý tiếp — không cần người vận hành can thiệp thủ công để job không
      bị mất.
- [ ] AC-5: Khi hàng đợi lỗi (DLQ) khác rỗng, hệ thống phải sinh cảnh báo cho
      người vận hành.
- [ ] AC-6: Job của tệp đơn lẻ do cán bộ nạp (interactive) được ưu tiên xử lý
      trước job của lô lớn (bulk), nhưng lô lớn vẫn được đảm bảo một phần
      tài nguyên xử lý tối thiểu để không bị "đói" vô thời hạn khi có nhiều
      job interactive dồn dập.
- [ ] AC-7: Với lô lớn (nạp thư mục/hàng loạt), tiến độ (tổng số/đã xong/lỗi/
      đang chờ) phải xem được, và việc xử lý lô được checkpoint theo từng
      tệp — dừng giữa chừng (ví dụ do bảo trì) rồi tiếp tục không phải chạy
      lại từ đầu.

---

## Story 4 — Gắn cờ chất lượng đầu vào và đầu ra

**As a** hệ thống
**I want** đánh giá chất lượng tệp đầu vào trước khi xử lý AI, và đánh giá độ
tin cậy của kết quả sau khi xử lý xong, rồi gắn cờ tương ứng theo ngưỡng cấu
hình được
**So that** cán bộ kiểm duyệt biết ngay tài liệu/audio nào cần chú ý kỹ hơn,
không phải tự đọc lại toàn bộ để đánh giá chất lượng

### Acceptance Criteria

```
Given một tệp được xử lý OCR/STT
When hệ thống đánh giá chất lượng đầu vào (trước khi chạy AI) và đầu ra (sau
     khi có kết quả)
Then hệ thống gắn cờ chất lượng tương ứng, theo ngưỡng lấy từ cấu hình hệ
     thống (không hard-code)
```

- [ ] AC-1: Trước khi đưa vào xử lý AI, hệ thống đánh giá nhanh chất lượng
      đầu vào (ví dụ: độ nét/độ phân giải ảnh, mức nhiễu ước lượng của audio,
      tỷ lệ khoảng lặng) và gắn cờ tương ứng. Tệp bị gắn cờ chất lượng kém
      **vẫn được xử lý bình thường**, chỉ bị **hạ ưu tiên** trong hàng đợi.
- [ ] AC-2: Sau khi có kết quả, hệ thống tính độ tin cậy trung bình/tỷ lệ
      đoạn có độ tin cậy thấp trên toàn tài liệu, gắn cờ chất lượng đầu ra
      tương ứng theo ngưỡng cấu hình.
- [ ] AC-3: Đoạn nội dung nghi ngờ "ảo giác" (model sinh ra nội dung không có
      thật, ví dụ với audio gần như im lặng) **vẫn được giữ lại** và gắn cờ,
      **không** bị tự động xoá.
- [ ] AC-4: Khi tỷ lệ đoạn có độ tin cậy thấp trên một tài liệu vượt ngưỡng
      cấu hình, toàn bộ tài liệu (run) được gắn cờ chất lượng kém ở cấp tài
      liệu.
- [ ] AC-5: Tài liệu bị gắn cờ chất lượng kém ở cấp tài liệu được đánh dấu
      **"chặn tự-xác-nhận"** trong dữ liệu bàn giao cho kiểm duyệt (Story 5)
      — bản thân việc chặn tự-xác-nhận-24h do `digitization-review` thực
      thi, pipeline chỉ có trách nhiệm **đặt đúng cờ**.
- [ ] AC-6: Mọi ngưỡng dùng để gắn cờ (độ nét, độ tin cậy OCR/STT, tỷ lệ đoạn
      thấp...) đều là **tham số cấu hình**, không hard-code trong code; đổi
      ngưỡng chỉ làm tính lại cờ, **không** làm chạy lại model.

---

## Story 5 — Bàn giao kết quả cho kiểm duyệt (`digitization-review`)

**As a** hệ thống
**I want** khi một lượt xử lý (run) OCR/STT hoàn tất, đặt mọi đoạn nội dung
sinh ra vào trạng thái chờ duyệt với hạn tự-xác-nhận cụ thể, và phát một sự
kiện mang đủ thông tin cần thiết cho khâu kiểm duyệt
**So that** `digitization-review` có thể tiêu thụ độc lập để hiển thị hàng
chờ duyệt, và bản chỉnh sửa của cán bộ không bao giờ bị pipeline ghi đè hay
mất khi chạy lại model

### Acceptance Criteria

```
Given một lượt xử lý OCR hoặc STT vừa hoàn tất thành công
When hệ thống ghi nhận kết quả cuối cùng của lượt xử lý đó
Then mọi đoạn nội dung được đặt ở trạng thái chờ duyệt kèm hạn tự-xác-nhận, và
     một sự kiện thông báo hoàn tất được phát ra mang đủ thông tin cho khâu
     kiểm duyệt
```

- [ ] AC-1: Khi một lượt xử lý hoàn tất, **mọi** đoạn nội dung thuộc lượt đó
      được ghi ở trạng thái **chờ duyệt**, kèm **hạn tự-xác-nhận** = thời
      điểm hoàn tất + số giờ tự-xác-nhận lấy từ cấu hình hệ thống. Áp dụng
      **giống nhau** cho cả kết quả OCR lẫn kết quả STT (không có ngưỡng
      riêng cho STT).
- [ ] AC-2: Hệ thống phát một sự kiện thông báo hoàn tất, mang đủ thông tin
      để `digitization-review` xử lý mà **không cần tra cứu lại** hệ thống
      khác: định danh tài liệu, định danh lượt xử lý, loại tác vụ (OCR/STT),
      số lượng đoạn nội dung, số đoạn có độ tin cậy thấp, cờ chất lượng, có
      bị chặn tự-xác-nhận hay không, và hạn tự-xác-nhận.
- [ ] AC-3: Pipeline **không bao giờ ghi đè** đoạn nội dung mà cán bộ đã sửa —
      bản do AI sinh ra được giữ nguyên vẹn (dùng để đối chiếu/cải tiến
      model sau này), bản sửa của cán bộ được lưu ở một nơi tách biệt.
- [ ] AC-4: Pipeline **không** tự kích hoạt việc lập chỉ mục tìm kiếm cho kết
      quả vừa xử lý — chỉ khi cán bộ (hoặc cơ chế tự-xác-nhận) xác nhận xong
      ở `digitization-review`, việc lập chỉ mục mới được kích hoạt (bởi phía
      `digitization-review`, không phải pipeline này).
- [ ] AC-5: Nếu một tài liệu **đã được xác nhận** trước đó được xử lý lại
      (chạy lại model), kết quả mới tạo thành một lượt xử lý **mới**, ở
      trạng thái chờ duyệt — pipeline **không tự động** thay thế bản đã xác
      nhận trước đó; việc có thay thế hay không thuộc quyết định của
      `digitization-review`.
- [ ] AC-6: Trong khoảng thời gian từ lúc hoàn tất đến khi được xác nhận (tối
      đa bằng số giờ tự-xác-nhận cấu hình), kết quả **chưa** tìm kiếm được —
      đây là hệ quả nghiệp vụ cần được truyền đạt rõ khi thiết kế trải
      nghiệm người dùng (không thuộc phạm vi story này).

---

## Story 6 — Không sinh kết quả trùng lặp khi job được xử lý lại

**As a** hệ thống
**I want** đảm bảo một job được giao lại (do hàng đợi redeliver, hoặc do job
bị coi là treo và đưa lại vào hàng đợi) không tạo ra kết quả trùng lặp
**So that** dữ liệu kết quả luôn nhất quán, không phát sinh đoạn nội dung rác
khi hạ tầng hàng đợi chỉ đảm bảo "gửi tối thiểu một lần" chứ không đảm bảo
"gửi đúng một lần"

### Acceptance Criteria

```
Given cùng một yêu cầu xử lý (cùng tài liệu, cùng loại tác vụ, cùng phiên bản
     model) được giao đến worker nhiều hơn một lần
When worker xử lý lại yêu cầu đó
Then hệ thống không tạo thêm một lượt xử lý (run) mới hay đoạn nội dung mới
     trùng lặp cho cùng kết quả đó
```

- [ ] AC-1: Một yêu cầu xử lý được nhận diện duy nhất theo bộ ba (tài liệu,
      loại tác vụ, phiên bản model) — nếu cùng bộ ba này đến worker nhiều
      lần (redelivery), kết quả cuối cùng chỉ tương ứng với **đúng một** lượt
      xử lý, không nhân bản đoạn nội dung.
- [ ] AC-2: Trường hợp một job bị coi là treo (Story 3, AC-4) và một worker
      khác nhận lại xử lý tiếp, kết quả hoàn tất chỉ được ghi nhận đúng một
      lần cho lượt xử lý đó — không có tình huống hai worker cùng ghi nhận
      "hoàn tất" cho cùng một lượt xử lý.
- [ ] AC-3: Việc chạy lại **có chủ đích** (ví dụ đổi sang phiên bản model
      mới) luôn tạo ra một lượt xử lý **mới**, không ghi đè lượt xử lý cũ —
      lượt cũ được giữ lại làm lịch sử/đối chiếu.
- [ ] AC-4: Tại một thời điểm, với mỗi (tài liệu, loại tác vụ), chỉ có **tối
      đa một** lượt xử lý đang ở trạng thái "đang hoạt động" (chưa bị thay
      thế/huỷ) — tránh tình trạng hai lượt xử lý cùng "đang hoạt động" gây
      nhầm lẫn cho khâu kiểm duyệt.

---

## Ngoài phạm vi (Out of scope)

- **Thiết kế bảng/cột lưu trữ cụ thể** (Postgres DDL cho lượt xử lý, đoạn nội
  dung, sự kiện âm thanh...) — thuộc `digitization-results-store.md`, hiện
  **Status: Draft**, chưa duyệt. Xem "Phụ thuộc" bên dưới.
- **Kiểm duyệt thủ công, sửa nội dung, UI hàng chờ duyệt, cơ chế tự-xác-nhận
  chi tiết** — thuộc `digitization-review.md`, pipeline chỉ bàn giao đúng hợp
  đồng ở Story 5.
- **SED (nhận diện âm thanh), speaker diarization, tóm tắt nội dung video** —
  hạ tầng có thể đã dựng sẵn nhưng **feature flag mặc định TẮT**
  (`digitization-pipeline.md` Quyết định 6), không nằm trong phạm vi cam kết
  của các story trên.
- **Lập chỉ mục và truy vấn tìm kiếm** (full-text/semantic) — thuộc
  `search-fulltext.md`/`search-semantic.md`.
- **Pha 1** (ghi `digitization_source`, đẩy hàng đợi; tách nguồn video đã huỷ
  2026-09-23) — đã
  Approved và triển khai riêng ở
  `digitization-pipeline-01-source-dispatch.md`, không lặp lại ở đây.
- **Benchmark giai đoạn 0** (đo CER/WER trên dữ liệu thật/tổng hợp để chốt
  ngưỡng nghiệm thu) — là công việc kỹ thuật của SA/DEV trước khi go-live,
  không phải acceptance criteria nghiệp vụ của story này.
- **Giám sát/vận hành hạ tầng** (dashboard GPU, alerting chi tiết) — chỉ cần
  đảm bảo *có* cảnh báo khi DLQ khác rỗng (Story 3, AC-5) ở mức nghiệp vụ,
  công cụ giám sát cụ thể không thuộc phạm vi BA.

## Phụ thuộc / Ghi chú kiến trúc liên quan

- `digitization-pipeline.md` Quyết định 1: engine OCR (PaddleOCR+VietOCR) và
  STT (PhoWhisper/faster-whisper), luôn OCR toàn bộ PDF kể cả có text layer —
  Story 1 kế thừa nguyên vẹn.
- `digitization-pipeline.md` Quyết định 2: máy trạng thái job, ưu tiên
  interactive/bulk, retry/DLQ, lease+heartbeat, idempotency theo `(document_id,
  task, model_version)` — Story 3 và Story 6 kế thừa nguyên vẹn, không đổi số
  liệu (3 lần retry, backoff 1m/5m/30m, heartbeat 30s).
- `digitization-pipeline.md` Quyết định 3: mô hình phân đoạn + mốc thời gian,
  ràng buộc bất biến `time_start_ms < time_end_ms ≤ duration_ms` — Story 1/2
  kế thừa nguyên vẹn.
- `digitization-pipeline.md` Quyết định 4: hợp đồng bàn giao cho
  `digitization-review` — Story 5 diễn giải lại ở mức nghiệp vụ, không đổi
  nội dung cam kết (5 điểm gốc).
- `digitization-pipeline.md` Quyết định 5: gắn cờ chất lượng 2 lớp, ngưỡng
  khởi điểm (OCR < 0.60, STT < 0.55, `no_speech_prob` > 0.8, > 30% segment
  thấp ⇒ `poor`) — Story 4 kế thừa; các con số này là `[ĐX]` của SA, có thể
  điều chỉnh qua cấu hình mà không cần sửa story.
- `digitization-pipeline.md` Quyết định 6: SED/diarization dựng hạ tầng
  nhưng feature flag tắt — lý do các story trên **không** đưa SED/diarization
  vào acceptance criteria.
- `digitization-pipeline-01-source-dispatch.md` Story 3: **kế thừa nguyên
  vẹn 5 trường message** (`fileId`, `sourceId`, `filePath`, `originalFileType`,
  `processFileType`) làm đầu vào cho Story 1/2 của file này — không định
  nghĩa lại hợp đồng message đầu vào.
- **`digitization-results-store.md` (Status: Draft)** — thiết kế lưu trữ chi
  tiết (Postgres DDL cho `digitization_run`/`content_segment`/`sound_event`/
  `document_digitization_status`, mở rộng trường chỉ mục OpenSearch — Qdrant đã gỡ 2026-10-10) cho toàn bộ dữ liệu
  mà Story 1–6 mô tả ở mức nghiệp vụ. File này **cần được duyệt (đổi sang
  Approved) trước khi DEV viết tech spec** cho story hiện tại, vì DEV cần DDL
  cụ thể để hiện thực. 4 điểm cần xác nhận ở mục 7 của file đó (SA/DEV cùng
  người dùng chốt):
  1. Hợp nhất `digitization_job` + `digitization_run` thành một bảng.
  2. SED và tóm tắt video có cần đi qua luồng duyệt FR-18 (24h tự-xác-nhận)
     như OCR/STT hay không — không ảnh hưởng Story 1–6 (chỉ OCR/STT), nhưng
     ảnh hưởng thiết kế bảng dùng chung.
  3. Đồng bộ lại `content_source` ở `ai-search-infra.md` mục 8 (bỏ giá trị
     `native_text` đã lỗi thời, thêm `sed`/`summary`).
  4. Ngưỡng "thăng hạng" `sound_event` → `content_segment` (không ảnh hưởng
     Story 1–6 vì SED đang tắt).
- `system-config.md` (FR-01): tham số vận hành cho các story trên —
  `ocr_stt.auto_confirm_hours`, `ocr_stt.min_confidence_ocr`,
  `ocr_stt.min_confidence_stt`, `ocr_stt.poor_ratio_threshold`,
  `ocr_stt.max_retry`, `ocr_stt.segment_max_seconds`, `ai_queue.concurrency`,
  `ai_queue.batch_size`.

## Câu hỏi mở cho DEV

- [ ] **Chờ `digitization-results-store.md` chuyển Approved** (hoặc ít nhất
      4 điểm mục 7 được người dùng quyết định) trước khi chốt DDL
      `digitization_run`/`content_segment` trong tech spec — nếu deadline
      gấp, có thể viết tech spec song song nhưng đánh dấu rõ phần DDL là
      "chờ xác nhận", không code trước phần ghi Postgres.
- [ ] **Hợp đồng message cho `q.result`** (kết quả AI worker gửi về cho
      `digitization-service` ghi Postgres + phát `digitization.completed`) —
      khác với `q.ocr`/`q.stt` (là *yêu cầu* xử lý, đã chốt ở Pha 1), đây là
      *kết quả* xử lý. Story 1/2/5 mô tả ở mức nghiệp vụ (cần có gì trong kết
      quả) nhưng chưa chốt tên trường JSON cụ thể — DEV cần cụ thể hoá ở tech
      spec, theo đúng tinh thần Story 3 của `digitization-pipeline-01-source-dispatch.md`
      đã làm cho `q.ocr`/`q.stt`.
- [ ] **Cơ chế outbox cho `digitization.completed`**: `digitization-pipeline-
      01-source-dispatch.md` đã nêu outbox/`outbox_event` chưa tồn tại trong
      migration hiện tại của backend. DEV cần xác nhận outbox đã được bổ
      sung ở Pha 1 hay chưa, và nếu chưa, quyết định cách triển khai cho Pha
      2 (đủ outbox đầy đủ, hay tạm thời publish trực tiếp trong transaction).
- [ ] **Ngưỡng chất lượng khởi điểm** (0.60 OCR, 0.55 STT, 0.8 `no_speech_prob`,
      30% tỷ lệ đoạn thấp) là đề xuất `[ĐX]` của SA — xác nhận dùng làm giá
      trị mặc định go-live hay cần điều chỉnh sau benchmark giai đoạn 0
      trước khi lên production.
- [ ] **Ai chịu trách nhiệm tính `input_quality_flag`** (Story 4, AC-1) —
      chạy ở AI worker (Python, trước khi đưa vào GPU) hay ở
      `digitization-service` (backend) ngay khi nhận job — ảnh hưởng vị trí
      code, không ảnh hưởng acceptance criteria nghiệp vụ.
