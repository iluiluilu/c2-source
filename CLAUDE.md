# CLAUDE.md

@AGENTS.md

## Ghi chú riêng cho Claude Code

- Subagent theo role nằm ở `.claude/agents/sa.md`, `.claude/agents/ba.md`,
  `.claude/agents/dev.md` — gọi bằng `@sa`, `@ba`, `@dev` hoặc để Claude tự
  route theo mô tả (`description`) của từng subagent.
- Slash command pipeline nằm ở `.claude/commands/*.md`: `/sa-spec`,
  `/ba-story`, `/dev-spec`, `/resolve-questions`, `/dev-implement`, `/handoff`.
- Luôn tuân theo nguyên tắc tối ưu context/token ở mục 3 của `AGENTS.md`:
  mỗi role/bước nên chạy trong một cuộc hội thoại riêng, chỉ truyền đường dẫn
  file, không truyền lại toàn bộ lịch sử.
