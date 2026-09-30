# AGENTS.md — Quy tắc chung cho Claude Code

Đây là nguồn chỉ dẫn pipeline cho Claude Code (đọc qua `CLAUDE.md` bằng cú pháp
import `@AGENTS.md`). Project này không còn dùng Kilo Code.

## 1. Vai trò (roles)

| Role | Nhiệm vụ | Output |
|---|---|---|
| **SA** (Solution Architect) | Phân tích yêu cầu ở mức hệ thống, ra quyết định kỹ thuật (ADR), define NFR, rủi ro, tech stack | `docs/01-sa/architecture/*.md` |
| **BA** (Business Analyst) | Chuyển yêu cầu nghiệp vụ + kiến trúc thành user story, acceptance criteria | `docs/02-ba/stories/*.md` |
| **DEV** | Viết tech spec chi tiết (data model, API, test plan) rồi implement code | `docs/03-dev/specs/*.md` + code |
| **QA** (Reviewer) | Nghiệm thu code so với Acceptance Criteria, rà soát chất lượng/bảo mật/rủi ro | `docs/04-qa/reports/*.md` |

Mỗi role là **một agent riêng**, không dùng một agent "biết tuốt" cho mọi việc.
Agent tương ứng:

- Claude Code: `.claude/agents/sa.md`, `.claude/agents/ba.md`, `.claude/agents/dev.md`, `.claude/agents/qa.md`

Ngoài 4 role trên còn có **Orchestrator** (`.claude/agents/orchestrator.md`) — không tự làm việc chuyên môn mà điều phối
4 role qua tool `task`/subagent. Xem mục 6.

## 2. Pipeline chuẩn

```
Ý tưởng/yêu cầu
   │
   ▼  /sa-spec <mô tả>
docs/01-sa/architecture/<slug>.md          (SA)
   │  /resolve-questions docs/01-sa/architecture/<slug>.md  → người dùng đổi Approved
   │
   ▼  /ba-story docs/01-sa/architecture/<slug>.md
docs/02-ba/stories/<slug>.md               (BA)
   │  /resolve-questions docs/02-ba/stories/<slug>.md       → người dùng đổi Approved
   │
   ▼  /dev-spec docs/02-ba/stories/<slug>.md
docs/03-dev/specs/<slug>.md                (DEV - spec)
   │  /resolve-questions docs/03-dev/specs/<slug>.md        → người dùng đổi Approved
   │
   ▼  /dev-implement docs/03-dev/specs/<slug>.md
Code + tests                                (DEV - implement)
   │
   ▼  /qa-review docs/02-ba/stories/<slug>.md docs/03-dev/specs/<slug>.md
docs/04-qa/reports/<slug>.md                (QA - nghiệm thu)
   │
   ▼  /handoff
docs/**/handoff.md (tóm tắt ngắn cho bước kế tiếp)
```

Xem chi tiết ở `docs/00-overview/README.md`.

> **Chốt câu hỏi mở:** sau mỗi bước tạo artifact có `Status: Draft` (SA, BA,
> DEV-spec), chạy `/resolve-questions <file>` để review "Câu hỏi mở", nhận đề
> xuất câu trả lời có trích nguồn, xác nhận từng câu, rồi ghi quyết định vào
> file. Command chạy ở session chính (không qua subagent) vì phải hỏi người
> dùng; không tự đổi `Status`; chỉ sửa SPEC/file tầng trên khi được đồng ý.

> **Brownfield:** với dự án đã có code, chạy `/analyze-codebase` trước để SA
> ghi `docs/01-sa/architecture/_codebase-overview.md` làm nền, rồi mới vào
> pipeline feature như trên.
>
> **Theo dõi tiến độ:** cập nhật `docs/00-overview/features.md` (feature
> registry) mỗi khi một `<slug>` chuyển bước.

## 3. Nguyên tắc tối ưu context/token (BẮT BUỘC)

1. **Mỗi bước = 1 session/agent mới.** Không mang toàn bộ lịch sử chat qua bước
   sau; bước sau chỉ nhận **đường dẫn file** artifact của bước trước.
2. **Output luôn là file markdown** trong `docs/`, không giữ kết quả chỉ trong
   context chat — chat có thể bị compact/mất, file thì không.
3. **Dùng template** trong `docs/templates/` để output ngắn, có cấu trúc cố
   định, dễ diff/review, tránh văn xuôi dài dòng.
4. **Chạy `/handoff` trước khi đổi bước/đổi agent** để nén context hiện tại
   thành một bản tóm tắt ≤ 30 dòng (quyết định đã chốt, câu hỏi mở, file cần
   đọc tiếp) rồi mới mở agent/session kế tiếp.
5. **Ưu tiên `grep`/`glob`** để định vị code liên quan thay vì đọc toàn bộ file
   hoặc toàn bộ thư mục lớn.
6. **Archive tài liệu cũ** vào `docs/_archive/` để tránh agent tự động đọc
   nhầm tài liệu đã lỗi thời.
7. **Không** thêm toàn bộ `docs/**` vào cấu hình auto-load instructions của
   `CLAUDE.md` — chỉ agent nào cần bước nào thì agent đó tự đọc
   đúng file đó (qua `@path` hoặc tool `read`).
8. Khi một file spec/story vượt quá ~300 dòng, tách nhỏ theo domain/feature
   thay vì nhồi thêm vào một file.
9. **Status: Draft/Approved.** Mỗi file SA/BA/DEV luôn có dòng `> Status:
   Draft` ở đầu (từ template). Người dùng review xong thì tự đổi thành
   `Approved` (nên chạy `/resolve-questions <file>` trước để chốt hết câu hỏi
   mở). Agent ở bước sau đọc file input phải kiểm tra dòng này — nếu
   vẫn `Draft`, hỏi lại người dùng đã duyệt chưa trước khi tiếp tục, tránh
   việc lãng phí token vào một artifact chưa chốt.
10. **Model theo vai trò (tiết kiệm chi phí).** SA và giai đoạn DEV viết spec
    dùng model mạnh hơn (opus) vì cần suy luận/kiến trúc sâu; BA, QA và giai
    đoạn DEV implement dùng model rẻ hơn (sonnet) vì phần lớn là chuyển đổi
    format/code/đối chiếu theo spec đã chốt. Xem field `model:` trong
    `.claude/agents/*.md` và `.claude/commands/*.md`.
11. **Traceability bằng ID.** SA gán `ARCH-<slug>` + `ADR-n`; BA gán
    `STORY-<slug>` + `AC-n`; DEV gán `SPEC-<slug>` và map mỗi task tới `AC-n`;
    QA đối chiếu từng `AC-n` trong report. Nhờ vậy truy được từ quyết định
    kiến trúc → AC → task → code → kết quả nghiệm thu.

## 4. Cấu trúc thư mục

```
docs/
  00-overview/        Tổng quan pipeline, quy ước
  01-sa/architecture/ Output của SA (ADR, kiến trúc, NFR)
  02-ba/stories/       Output của BA (user story, AC)
  03-dev/specs/        Output của DEV (tech spec, task breakdown)
  04-qa/reports/       Output của QA (báo cáo nghiệm thu theo AC)
  templates/           Template dùng chung cho các role
  _handoff/            Bản tóm tắt do lệnh /handoff ghi ra, đọc lại khi mở
                       session mới cho bước kế tiếp
  _archive/            Tài liệu cũ, không load vào context nữa

.claude/agents/        Subagent definitions cho Claude Code
.claude/commands/      Slash command cho Claude Code (pipeline steps)
.claude/settings.json  Permission dùng chung cho Claude Code (path được phép
                       ghi mà không cần hỏi lại)
```

## 5. Quy ước đặt tên file

`<slug>` = tên ngắn gọn, kebab-case của feature/task, dùng **giống nhau** ở cả
4 tầng (`01-sa/architecture/<slug>.md`, `02-ba/stories/<slug>.md`,
`03-dev/specs/<slug>.md`, `04-qa/reports/<slug>.md`) để dễ tra cứu và để các
slash command tự suy ra đường dẫn bước trước/sau.

## 6. Hai chế độ chạy pipeline

### 6.1. Thủ công (mặc định) — kiểm soát tối đa

Người dùng tự gõ từng lệnh (`/sa-spec` → `/ba-story` → `/dev-spec` →
`/dev-implement` → `/qa-review`), sau mỗi bước SA/BA/DEV-spec chạy
`/resolve-questions <file>`, tự review và đổi `Status: Draft → Approved`,
tự mở session mới mỗi bước. Token thấp nhất, kiểm soát cao nhất. Đây là chế độ
khuyến nghị mặc định.

### 6.2. Bán tự động (agentic) — `/run-pipeline`

Command `/run-pipeline <mô tả feature>` route tới agent **orchestrator**. Agent
này tự điều phối SA→BA→DEV→QA bằng delegate `@role`
(Claude), nhưng **vẫn tôn trọng cổng duyệt của con người**:

- DỪNG sau mỗi bước tạo artifact (SA, BA, DEV-spec) để người dùng review và tự
  đổi `Status: Approved`. Orchestrator **không** tự đổi Status.
- Ở mỗi cổng, nếu artifact còn câu hỏi mở, orchestrator in sẵn lệnh
  `/resolve-questions <file>` để người dùng chạy trước khi `Approved` (không tự
  chạy vì command này cần hỏi người dùng và ghi vào file của role).
- Tự chạy vòng lặp **QA→DEV**: nếu QA `FAIL`/có blocking → gọi lại DEV sửa rồi
  QA lại, **tối đa 2 vòng**; vẫn FAIL thì dừng và báo cáo (chống đốt token).
- Hỏi người dùng (AskUserQuestion) khi mô tả mơ hồ, không tự phát minh yêu cầu.
- Orchestrator chỉ được ghi `docs/00-overview/features.md` và `docs/_handoff/**`;
  mọi artifact chuyên môn do đúng role tạo ra với quyền hạn riêng.

> Chọn chế độ: cần chắc chắn/ít token → 6.1; cần nhanh, chấp nhận orchestrator
> lo phần cơ học (chuyển bước, vòng QA→DEV) → 6.2. Cả hai dùng chung agent,
> template và cổng `Approved`.
