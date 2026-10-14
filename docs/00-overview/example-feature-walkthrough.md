# Ví dụ: thực hiện một feature theo pipeline SA → BA → DEV

> Xem quy tắc đầy đủ ở [`AGENTS.md`](../../AGENTS.md) và tổng quan pipeline ở
> [`README.md`](./README.md). File này là một ví dụ minh hoạ cụ thể, từng
> bước, để dùng làm mẫu khi bắt tay vào một feature mới.

Ví dụ minh hoạ: feature "thêm đăng nhập bằng OTP qua SMS" → `<slug> = otp-login`.

## Bước 0 — Chọn `<slug>`

Đặt tên ngắn gọn, kebab-case, dùng **xuyên suốt** cả 3 tầng tài liệu. Không
cần tạo tay, các bước sau agent sẽ tự đặt nếu bạn không chỉ định, nhưng đặt
trước giúp bạn dễ theo dõi.

## Bước 1 — SA: phân tích kiến trúc

Trong session mới (đầu tiên của feature), gõ:

```
/sa-spec Thêm chức năng đăng nhập bằng OTP qua SMS, tích hợp với hệ thống auth hiện tại
```

- Lệnh này tự động dùng subagent `@sa` (model `opus` — suy luận kiến trúc sâu).
- Kết quả ghi ra `docs/01-sa/architecture/otp-login.md` (ADR, NFR, rủi ro,
  tech stack). Bạn chỉ nhận lại tóm tắt 3-5 dòng trong chat, không phải toàn
  bộ file.

**Review:** mở file đó, đọc thật kỹ mục `4. Phương án & Quyết định (ADR)` và
`7. Câu hỏi mở`. Nếu ổn, sửa dòng đầu file:

```
> Status: Draft
```

thành:

```
> Status: Approved
```

Đây là cổng duyệt bắt buộc — bước BA sẽ hỏi lại bạn nếu thấy vẫn `Draft`.

## Bước 2 — Handoff sang BA

Vẫn trong session SA, gõ:

```
/handoff
```

Bản tóm tắt (đã chốt / câu hỏi mở / bước kế tiếp) sẽ vừa in ra chat, vừa được
ghi vào `docs/_handoff/otp-login.md`.

**Mở session/agent mới** (tab mới, hoặc `/clear`) — đây là điểm quan trọng để
không mang theo lịch sử chat cũ, tiết kiệm token. Nếu tiện, dán đoạn handoff
vừa in vào tin nhắn đầu; nếu quên, agent BA có thể tự đọc
`docs/_handoff/otp-login.md`.

## Bước 3 — BA: viết user story + AC

Trong session mới:

```
/ba-story docs/01-sa/architecture/otp-login.md
```

- Dùng `@ba` (model `sonnet`).
- Tự kiểm tra `Status` của file SA — nếu bạn quên approve ở bước 1, nó sẽ hỏi
  lại trước khi làm tiếp.
- Ghi ra `docs/02-ba/stories/otp-login.md`.

**Review AC**, đổi `Status: Draft → Approved` nếu ổn. Nếu 1 feature cần nhiều
story độc lập, để agent tách thành `otp-login-01-*.md`,
`otp-login-02-*.md`...

## Bước 4 — Handoff sang DEV (spec)

```
/handoff
```

→ mở session mới.

## Bước 5 — DEV: viết tech spec (chưa code)

```
/dev-spec docs/02-ba/stories/otp-login.md
```

- Dùng `@dev` nhưng model bị override lên `opus` ở bước này (cần thiết kế
  data model/API kỹ).
- Kiểm tra Status file BA trước khi viết.
- Ghi ra `docs/03-dev/specs/otp-login.md` (data model, API contract, test
  plan, task breakdown). **Chưa có code** ở bước này.

**Review kỹ task breakdown + test plan** — đây là bản kế hoạch code sẽ bám
theo, càng rõ càng ít phải sửa lại sau. Đổi `Status: Draft → Approved`.

## Bước 6 — Handoff sang DEV (implement)

```
/handoff
```

→ mở session mới (có thể không bắt buộc tách session ở bước cuối này nếu bạn
muốn theo sát quá trình code, nhưng vẫn nên `/clear` nếu session DEV-spec đã
dài).

## Bước 7 — DEV: implement

```
/dev-implement docs/03-dev/specs/otp-login.md
```

- Dùng `@dev`, model `sonnet` (rẻ hơn, phù hợp việc code theo spec đã chốt).
- Kiểm tra Status spec trước khi code.
- Implement từng task theo đúng thứ tự, chạy test/lint có sẵn trong repo.
- Nếu cần lệch khỏi spec đáng kể, agent sẽ dừng lại báo trước, không tự ý đổi
  hướng.

## Bước 8 — Kiểm tra kết quả

- Đọc tóm tắt cuối: file đã đổi, kết quả test, việc còn lại.
- Tự chạy lại app/test nếu cần xác nhận bằng mắt (đặc biệt nếu có UI).
- Đánh dấu task đã xong trong `docs/03-dev/specs/otp-login.md` (agent có thể
  tự làm việc này).

## Bước 9 — QA: nghiệm thu độc lập

```
/handoff
```

→ mở session mới, rồi:

```
/qa-review docs/02-ba/stories/otp-login.md docs/03-dev/specs/otp-login.md
```

- Dùng `@qa` (model `sonnet`).
- QA đọc AC (story) + task/test plan (spec), định vị code liên quan, chạy
  test/lint để lấy bằng chứng, rồi ghi báo cáo ra
  `docs/04-qa/reports/otp-login.md` với kết luận **PASS / PASS WITH ISSUES /
  FAIL** và bảng đối chiếu từng `AC-n`.
- **QA không sửa code.** Nếu có vấn đề chặn (Blocking), quay lại
  `/dev-implement` để DEV xử lý rồi chạy lại `/qa-review`. Lặp cho tới khi
  PASS.

## Bước 10 — Cập nhật feature registry

Đổi trạng thái của `otp-login` trong `docs/00-overview/features.md` (SA/BA/
Spec/Impl/QA + trạng thái tổng `Done`). Khi feature khép lại hẳn, chuyển tài
liệu cũ vào `docs/_archive/` nếu cần.

## Mẹo khi làm nhiều feature song song hoặc feature nhỏ

- Feature nhỏ, rõ ràng ngay từ đầu: có thể gộp bước SA+BA nếu không có quyết
  định kiến trúc mới — chạy thẳng `/ba-story` với mô tả tay thay vì file SA,
  nhưng **không khuyến khích** vì phá vỡ tính traceability của pipeline; chỉ
  làm khi feature thực sự nhỏ (sửa UI, thêm field).
- Nếu SA cần bổ sung sau khi BA đã phát hiện thiếu ADR: mở lại đúng
  session/role đó (`/sa-spec` bổ sung), không cần chạy lại toàn bộ pipeline
  từ đầu.

## Chế độ bán tự động (agentic) — `/run-pipeline`

Nếu không muốn gõ từng lệnh và tự mở session mỗi bước, dùng:

```
/run-pipeline Thêm chức năng đăng nhập bằng OTP qua SMS, tích hợp với hệ thống auth hiện tại
```

- Route tới agent **orchestrator**, tự điều phối SA→BA→DEV→QA bằng subagent.
- **Vẫn DỪNG ở mỗi cổng** sau SA / BA / DEV-spec để bạn review và tự đổi
  `Status: Draft → Approved` — orchestrator không tự đổi hộ.
- Tự chạy vòng lặp QA→DEV nếu QA `FAIL`/có blocking, **tối đa 2 vòng**; vẫn
  FAIL thì dừng và báo cáo.
- Hỏi lại khi mô tả mơ hồ, không tự phát minh yêu cầu.

Đánh đổi: nhanh và đỡ thao tác hơn, nhưng tốn token hơn chế độ thủ công. Chi
tiết ở `AGENTS.md` mục 6.
