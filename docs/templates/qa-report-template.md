# QA Report: <slug>

> File: `docs/04-qa/reports/<slug>.md` — Owner: QA
> Nguồn story: `docs/02-ba/stories/<slug>.md` (STORY-<slug>)
> Nguồn spec: `docs/03-dev/specs/<slug>.md` (SPEC-<slug>)
> Ngày review: <YYYY-MM-DD>
> Commit/branch reviewed: <hash hoặc branch>

## Kết luận

**PASS / PASS WITH ISSUES / FAIL**

<1-3 dòng tóm tắt lý do.>

## Đối chiếu Acceptance Criteria

| AC | Kết quả | Bằng chứng (file:line / test / log) |
|---|---|---|
| AC-1 | Pass / Fail | |
| AC-2 | Pass / Fail | |
| AC-3 | Pass / Fail | |

## Kết quả test/lint đã chạy

```
<lệnh đã chạy + tóm tắt output: pass/fail, số test, coverage nếu có>
```

## Vấn đề chặn (Blocking)

- [ ] ... (fail AC, lỗi nghiêm trọng, rủi ro bảo mật) — DEV phải xử lý.

## Đề xuất cải thiện (Non-blocking)

- [ ] ...

## Kiểm tra thêm

- [ ] Security checklist trong spec đã thoả?
- [ ] Observability (log/metric) như spec?
- [ ] Migration/rollback an toàn?
