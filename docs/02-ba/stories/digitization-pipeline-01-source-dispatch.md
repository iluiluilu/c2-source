# User Story: digitization-pipeline-01-source-dispatch

> File: `docs/02-ba/stories/digitization-pipeline-01-source-dispatch.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/digitization-pipeline.md` (mục 1
> "Triển khai theo pha", Quyết định 7, **Quyết định 10**)
> Status: Approved
> Ghi chú 2026-09-23 (người dùng duyệt cùng ngày): cập nhật theo Quyết định 10 — **huỷ Story 1** (tách
> nguồn video), video gộp vào Story 2 và đẩy vào hàng đợi xử lý video
> (`q.video`). Bản trước đó đã Approved 2026-08-30.

Pha 1 của số hoá: ngay sau khi một tệp được nhập vào **kho chung** (không áp
dụng cho kho cá nhân — theo đúng phạm vi `document.ingested` đã chốt ở
`ai-search-infra.md`), hệ thống chuẩn bị dữ liệu đầu vào cho AI worker và đẩy
yêu cầu xử lý vào hàng đợi. **Không** bao gồm việc AI worker tiêu thụ hàng
đợi, chạy OCR/STT, hay ghi nhận kết quả — phần đó thuộc pha sau.

---

## Story 1 — ~~Tách nguồn video thành video/audio riêng và đẩy audio vào hàng đợi~~ (ĐÃ HUỶ 2026-09-23)

Huỷ theo `digitization-pipeline.md` Quyết định 10: hệ thống **không còn tách**
tệp video thành video-only/audio-only bằng FFmpeg. Tệp video được xử lý như
audio/ảnh/PDF — xem Story 2. Dữ liệu đã tách trước 2026-09-23 (2 thành phần
video/audio, job tách nguồn) được giữ nguyên, không xử lý lại.

---

## Story 2 — Lưu thông tin và đẩy video/audio/ảnh/PDF vào hàng đợi xử lý (không cần tách)

**As a** hệ thống
**I want** với tệp video, audio, ảnh, hoặc PDF vừa nhập kho — lưu lại thông tin cần
cho AI worker và đẩy ngay vào hàng đợi xử lý tương ứng, không qua bước tách
**So that** AI worker nhận được yêu cầu xử lý sớm nhất có thể, không phải chờ
một bước biến đổi không cần thiết

### Acceptance Criteria

```
Given một tệp video, audio, ảnh, hoặc PDF vừa qua kiểm tra toàn vẹn/mã độc
     và vào kho chung
When hệ thống kích hoạt xử lý sau nhập kho
Then hệ thống lưu thông tin tệp và đẩy ngay yêu cầu xử lý vào hàng đợi phù
     hợp với loại tệp, không tạo job tách nguồn nào
```

- [ ] AC-1: Không tạo job xử lý FFmpeg/tách nguồn cho bất kỳ loại tệp nào,
      **kể cả video** (từ 2026-09-23). Mỗi tệp chỉ sinh **đúng 1** yêu cầu xử
      lý trong hàng đợi.
- [ ] AC-2: Thông tin thành phần cần cho AI worker (id thành phần, id tệp gốc,
      đường dẫn lưu trữ, loại tệp gốc, loại tệp gửi xử lý) được lưu lại,
      trong đó **"id thành phần" trùng với "id tệp gốc"** (không sinh id mới)
      — vì không có biến đổi nào tạo ra một thực thể tách biệt.
- [ ] AC-3: Yêu cầu xử lý được đẩy vào **đúng hàng đợi theo loại tệp**: video
      → hàng đợi xử lý video (`q.video`); audio → hàng đợi xử lý giọng nói
      (`q.stt`); ảnh/PDF → hàng đợi xử lý nhận dạng ký tự (`q.ocr`). Tệp video
      **không** sinh yêu cầu nào vào hàng đợi xử lý giọng nói.
- [ ] AC-5: Với tệp video, đường dẫn gửi xử lý là **tệp video gốc** (có cả
      hình và tiếng), không phải tệp phái sinh; tệp gốc chỉ được đọc, không
      bị sửa/ghi đè (NFR-09).
- [ ] AC-4: Một tệp lỗi/không đẩy được vào hàng đợi (ví dụ hàng đợi tạm thời
      không kết nối được) không được chặn việc xử lý các tệp khác đang/đã
      nhập kho.

---

## Story 3 — Nội dung thông tin đẩy vào hàng đợi xử lý

**As a** AI worker (bên tiêu thụ hàng đợi — ngoài phạm vi pha này)
**I want** mỗi yêu cầu xử lý trong hàng đợi mang đủ thông tin để định vị và
truy xuất đúng tệp cần xử lý
**So that** AI worker không phải tra cứu thêm hệ thống khác trước khi bắt đầu
xử lý

### Acceptance Criteria

```
Given một yêu cầu xử lý được đẩy vào hàng đợi (từ Story 2)
When AI worker đọc message
Then message có đủ 5 trường: id tệp gốc, id thành phần, đường dẫn tệp, loại
     tệp gốc, loại tệp gửi xử lý
```

- [ ] AC-1: Message mang đúng 5 trường: **`fileId`** (id tệp gốc trong kho),
      **`sourceId`** (id thành phần — luôn bằng `fileId` cho tài liệu nhập từ
      2026-09-23, kể cả video),
      **`filePath`** (đường dẫn để AI worker stream tệp từ kho lưu trữ),
      **`originalFileType`** (mimetype tệp gốc), **`processFileType`**
      (mimetype tệp thực sự gửi xử lý — nay luôn bằng `originalFileType` vì
      không còn tách nguồn video). *(Pha 2 bổ sung trường thứ 6 `runId` —
      xem `digitization-pipeline-02-ocr-stt-processing`.)*
- [ ] AC-2: `filePath` phải là đường dẫn AI worker **đọc/stream được** trực
      tiếp từ kho lưu trữ đối tượng đang dùng (không phải đường dẫn nội bộ
      chỉ backend truy cập được).

---

## Story 4 — Tên hàng đợi cấu hình được qua biến môi trường backend

**As a** người vận hành hệ thống
**I want** tên các hàng đợi đích (OCR, STT, video) là biến môi trường của backend,
không hard-code trong code
**So that** đổi tên/hàng đợi giữa các môi trường (dev/staging/production)
không cần sửa code

### Acceptance Criteria

```
Given backend cần đẩy một yêu cầu xử lý vào hàng đợi
When backend khởi động và đọc cấu hình
Then tên hàng đợi đích được lấy từ biến môi trường, không hard-code
```

- [ ] AC-1: Tên hàng đợi OCR, STT và video là các biến môi trường riêng, đọc qua cấu
      hình backend hiện có (`application.yml` + biến môi trường) — cùng cách
      các tham số hạ tầng khác (host/port DB, Redis...) đã làm trong repo.
- [ ] AC-2: Tham số kết nối tới hệ thống hàng đợi (host/port/user/pass) dùng
      **đúng** những gì đã có sẵn ở hạ tầng staging (`stg/`), không định
      nghĩa lại một bộ tham số kết nối khác.

---

## Ngoài phạm vi (Out of scope)

- AI worker tiêu thụ hàng đợi, chạy OCR/STT/SED — thuộc pha sau (Pha 2,
  `digitization-pipeline.md` Quyết định 1/2/3, chưa đặt tên slug BA/DEV).
- Ghi nhận/lưu kết quả xử lý AI, phân đoạn, mốc thời gian, kiểm duyệt (FR-18,
  FR-20) — thuộc `digitization-review.md` và Pha 2.
- Tách nguồn video (video-only/audio-only) — đã huỷ 2026-09-23 (Quyết định
  10).
- Chuyển văn bản lời nói (STT) cho phần tiếng của video — không còn được
  kích hoạt qua hàng đợi giọng nói; có còn cần hay không là câu hỏi mở ở
  `digitization-pipeline.md` §7 (Quyết định 10).
- Xử lý SED (Sound Event Detection), speaker diarization — vẫn tắt theo
  `digitization-pipeline.md` Quyết định 6, không liên quan pha này.
- Video/ảnh/audio/PDF thuộc **kho cá nhân** — không kích hoạt số hoá, đúng
  phạm vi `document.ingested` (chỉ kho chung) đã chốt ở `ai-search-infra.md`.

## Phụ thuộc / Ghi chú kiến trúc liên quan

- `digitization-pipeline.md` Quyết định 7: toàn bộ quyết định kỹ thuật của
  pha này (hợp đồng message, định tuyến hàng đợi theo `processFileType`,
  idempotency dispatch); **Quyết định 10** huỷ nhánh tách nguồn video, định
  tuyến video gốc vào `q.video` (Quyết định 8).
- `digitization-pipeline.md` Quyết định 2: exchange `ai.jobs`, quy ước
  `<queue>.retry`/`<queue>.dlq` — Story 4 kế thừa, không định nghĩa lại.
- `ai-search-infra.md` mục 8: loại sự kiện `ocr.requested`/`stt.requested`
  (khái niệm) — Story 3 chốt trường dữ liệu cụ thể cho 2 loại sự kiện này.
- `data-ingestion.md`: nguồn của sự kiện kích hoạt pha này — tệp phải qua
  trọn vẹn kiểm tra toàn vẹn/mã độc/định dạng và **vào kho chung** trước khi
  Story 1/2 được kích hoạt. Cần xác nhận cơ chế phát `document.ingested` đã
  có trong code `data-ingestion` hay chưa (xem Câu hỏi mở cho DEV).
- Hạ tầng RabbitMQ đã có trong `docker-compose.yml` (dev) và
  `stg/docker-compose.yml` (staging), **chưa** có dependency
  `spring-boot-starter-amqp` trong `backend/pom.xml` — cần bổ sung (giống
  cách Redis được bổ sung ở `data-ingestion.md`).

## Câu hỏi mở cho DEV

- [ ] **Cơ chế kích hoạt Pha 1 khi chưa có outbox `document.ingested` trong
      code hiện tại:** rà soát `backend/src/main/java/.../documents/
      DocumentsService.java` cho thấy chưa có bước phát sự kiện
      `document.ingested` (bảng `outbox_event` cũng chưa tồn tại trong
      migration hiện có, `V1`–`V13`, `V15`–`V17`). DEV cần chốt: (a) thêm
      outbox + relay tối thiểu theo đúng thiết kế `ai-search-infra.md` Quyết
      định 7 trước khi làm Pha 1, hay (b) với riêng Pha 1, gọi trực tiếp
      trong cùng transaction ghi `document` (đơn giản hơn, chấp nhận nợ kỹ
      thuật tạm thời, outbox đầy đủ làm khi có nhu cầu thật từ nhiều consumer
      hơn)? Ảnh hưởng trực tiếp Task breakdown của tech spec.
- [x] ~~**Vị trí cài đặt binary `ffmpeg` cho backend**~~ — không còn cần
      (Quyết định 10, 2026-09-23): backend không chạy FFmpeg nữa.
