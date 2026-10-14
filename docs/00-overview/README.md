# Pipeline SA -> BA -> DEV

Xem quy tắc đầy đủ ở [`AGENTS.md`](../../AGENTS.md) tại root repo. File này chỉ
tóm tắt cách chạy pipeline cho người mới.

## Sơ đồ

```
Ý tưởng/yêu cầu
   │
   ▼  /sa-spec <mô tả>
docs/01-sa/architecture/<slug>.md          (SA)
   │
   ▼  /ba-story docs/01-sa/architecture/<slug>.md
docs/02-ba/stories/<slug>.md               (BA)
   │
   ▼  /dev-spec docs/02-ba/stories/<slug>.md
docs/03-dev/specs/<slug>.md                (DEV - spec)
   │
   ▼  /dev-implement docs/03-dev/specs/<slug>.md
Code + tests                                (DEV - implement)
   │
   ▼  /qa-review docs/02-ba/stories/<slug>.md docs/03-dev/specs/<slug>.md
docs/04-qa/reports/<slug>.md                (QA - nghiệm thu)
```

> **Brownfield (dự án đã có code):** chạy `/analyze-codebase` trước tiên để SA
> ghi `docs/01-sa/architecture/_codebase-overview.md` làm nền, rồi mới vào
> pipeline feature.
>
> **Feature registry:** cập nhật `docs/00-overview/features.md` mỗi khi một
> `<slug>` chuyển bước để nắm tiến độ tổng.

## Cách chạy (Claude Code)

1. Mở session mới, gõ `/sa-spec <mô tả yêu cầu>`.
2. Chạy `/resolve-questions docs/01-sa/architecture/<slug>.md` để chốt các câu
   hỏi mở (agent đề xuất câu trả lời, bạn xác nhận, agent ghi vào file). Áp
   dụng tương tự sau `/ba-story` và `/dev-spec`.
   Đọc file SA tạo ra ở `docs/01-sa/architecture/<slug>.md`. Nếu ổn, đổi dòng
   `> Status: Draft` đầu file thành `> Status: Approved`, chạy `/handoff` rồi
   mở session mới cho bước BA (dán đoạn handoff vừa in ra vào tin nhắn đầu
   tiên của session mới; nếu quên, agent bước sau có thể đọc lại
   `docs/_handoff/<slug>.md`).
3. Ở session mới: `/ba-story docs/01-sa/architecture/<slug>.md`.
4. Lặp lại: duyệt (đổi Status) -> `/handoff` -> session mới ->
   `/dev-spec docs/02-ba/stories/<slug>.md`.
5. Duyệt spec -> `/handoff` -> session mới ->
   `/dev-implement docs/03-dev/specs/<slug>.md`.
6. Sau khi implement xong -> `/handoff` -> session mới ->
   `/qa-review docs/02-ba/stories/<slug>.md docs/03-dev/specs/<slug>.md`. QA
   ghi báo cáo nghiệm thu ra `docs/04-qa/reports/<slug>.md` với kết luận
   PASS / PASS WITH ISSUES / FAIL. Nếu có vấn đề chặn, quay lại
   `/dev-implement` để DEV xử lý rồi review lại.

## Cách chạy (Claude Code)

Tương tự, dùng `.claude/commands/*.md` (`/sa-spec`, `/ba-story`, `/dev-spec`,
`/dev-implement`, `/qa-review`, `/resolve-questions`, `/handoff`,
`/analyze-codebase`) và subagent
tương ứng ở `.claude/agents/*.md` (mỗi command đã trỏ thẳng tới đúng subagent
qua `@sa`/`@ba`/`@dev`/`@qa`, kèm `model:` phù hợp cho từng bước — xem
`AGENTS.md` mục 3.10).

## Vì sao tách session ở mỗi bước?

- Mỗi role chỉ cần đọc **một file input** (từ bước trước) + template, không
  cần toàn bộ lịch sử chat của các bước trước — giảm token đáng kể.
- `/handoff` giúp nén quyết định quan trọng thành bản tóm tắt ngắn, tránh mất
  thông tin khi đổi session mà không cần mang theo toàn bộ context.
- Nếu một bước cần sửa lại (ví dụ BA cần SA bổ sung ADR), chỉ mở lại session
  cho đúng role đó với file liên quan, không cần chạy lại toàn bộ pipeline.

## Quy ước

- `<slug>`: kebab-case, dùng xuyên suốt 4 tầng (`01-sa`, `02-ba`, `03-dev`,
  `04-qa`).
- **Traceability bằng ID:** `ARCH-<slug>`/`ADR-n` (SA) → `STORY-<slug>`/`AC-n`
  (BA) → `SPEC-<slug>` + task map tới `AC-n` (DEV) → QA đối chiếu từng `AC-n`.
- File quá ~300 dòng: tách nhỏ theo domain/feature (`<slug>-01-*.md`,
  `<slug>-02-*.md`...).
- Tài liệu lỗi thời: chuyển vào `docs/_archive/` để tránh agent đọc nhầm.
- Mỗi file SA/BA/DEV có dòng `> Status: Draft` ở đầu (theo template). Người
  dùng tự đổi thành `Approved` sau khi review; agent bước sau sẽ hỏi lại nếu
  thấy vẫn `Draft` trước khi tiếp tục.
- File `/handoff` được ghi ra `docs/_handoff/<slug>.md` (ngoài việc in trong
  chat) để không bị mất khi mở session/agent mới.
- Thêm mã audit mới ở backend (action/resource_type) → cập nhật
  `web/src/lib/auditCatalog.ts` (danh mục tĩnh cho `/admin/audit`, xem
  `docs/03-dev/specs/audit-ui-readability.md`), nếu không mã mới vẫn hiển thị
  được (fallback) nhưng thiếu trong dropdown lọc.
