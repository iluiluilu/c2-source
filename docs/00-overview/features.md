# Feature Registry

> Bảng theo dõi feature chạy qua pipeline. Cập nhật mỗi khi một `<slug>` chuyển bước.
> Dựng lần đầu ngày 2026-09-30 từ dòng `> Status:` của các file trong `docs/01-sa|02-ba|03-dev`.
> Cột Impl/QA chưa được khảo sát (feature cũ chưa có `docs/04-qa/reports/`): điền khi chạy `/qa-review`.

| Slug | SA | BA | Spec | Impl | QA |
|---|---|---|---|---|---|
| `account-lifecycle` | Approved | Approved | Approved | ? | — |
| `ai-search-infra` | Approved | — | — | ? | — |
| `audit-logging` | Approved | Approved | Approved | ? | — |
| `audit-ui-readability` | — | Draft | Approved | ? | — |
| `auth-session` | Approved | Approved | Approved | ? | — |
| `backend-multi-instance` | Approved (2026-10-12, 8/8 câu hỏi đã chốt) | Approved (2026-10-12, 28 AC, 6/6 câu hỏi đã chốt) | Approved (2026-10-12, 19 task, 5/5 câu hỏi đã chốt, đã review) | — | — |
| `backup-restore` | Approved | — | — | ? | — |
| `branding` | Draft | — | — | ? | — |
| `chunked-file-upload` | Approved | — | — | ? | — |
| `chunked-file-upload-01-ingest-chunked-upload` | — | Approved | Approved | ? | — |
| `chunked-file-upload-02-personal-chunked-upload` | — | Approved | Approved | ? | — |
| `chunked-file-upload-03-session-cleanup` | — | Approved | Approved | ? | — |
| `classification-notes` | Approved | — | — | ? | — |
| `classification-notes-01-tags` | — | Approved | Approved | ? | — |
| `classification-notes-02-notes` | — | Approved | Approved | ? | — |
| `data-ingestion` | Approved | Approved | Draft | ? | — |
| `delete-restore-trash` | Approved | — | — | ? | — |
| `delete-restore-trash-01-xoa-mem-thung-rac` | — | Approved | Approved | ? | — |
| `delete-restore-trash-02-xoa-cung-xung-dot` | — | Approved | Approved | ? | — |
| `delete-restore-trash-03-frontend` | — | Approved | Approved | ? | — |
| `digitization-pipeline` | Approved | — | — | ? | — |
| `digitization-pipeline-01-source-dispatch` | — | Approved | Approved | ? | — |
| `digitization-pipeline-02-ocr-stt-processing` | — | Draft | Approved | ? | — |
| `digitization-pipeline-03-video-queue` | — | — | Approved | ? | — |
| `digitization-pipeline-04-video-no-separation` | — | — | Approved | ? | — |
| `digitization-pipeline-05-video-review` | — | — | Draft | ? | — |
| `digitization-results-store` | Approved | Draft | Approved | ? | — |
| `digitization-review` | Approved | — | — | ? | — |
| `digitization-review-01-ocr` | — | Approved | Approved | ? | — |
| `digitization-review-02-stt` | — | Approved | Approved | ? | — |
| `digitization-review-03-ownership-transfer` | — | Approved | Approved | ? | — |
| `digitization-review-04-sed-summary` | — | Approved | Approved | ? | — |
| `digitization-review-05-frontend` | — | Approved | Approved | ? | — |
| `digitization-review-06-queue-pending-status` | — | Approved | Approved | ? | — |
| `digitization-review-07-confirmed-content-view` | — | — | Approved | ? | — |
| `external-api` | Draft | — | — | ? | — |
| `folder-upload-display-name` | Approved | Draft | — | ? | — |
| `group-management-sharing` | Approved | — | — | ? | — |
| `group-management-sharing-01-group-lifecycle` | — | Approved | Approved | ? | — |
| `group-management-sharing-02-document-sharing` | — | Approved | Approved | ? | — |
| `personal-storage-quota` | Approved | Approved | Approved | ? | — |
| `personal-to-warehouse` | Approved | Approved | Approved | ? | — |
| `r2-business-parity` | Approved | — | — | — | — |
| `r2-business-parity-01-permission-grant` | — | Approved | Approved | Done | PASS |
| `r2-business-parity-02-ownership-transfer` | — | Approved | Approved | Done | PASS WITH ISSUES |
| `r2-business-parity-03-web-permission-gating` | — | Approved | Approved | Done | PASS (2026-10-01) |
| `rbac-group-scope` | Approved | Approved | Approved | ? | — |
| `rbac-permission-editing` | Approved | Approved | Approved | ? | — |
| `export-reporting` | Approved | Approved | Approved | Done (chờ QA) | — |
| `search-fulltext` | Approved | — | — | ? | — |
| `search-fulltext-01-tim-kiem-co-ban` | — | Approved | Approved | ? | — |
| `search-fulltext-02-tim-kiem-nang-cao` | — | Approved | Approved | ? | — |
| `search-fulltext-03-dinh-vi-ket-qua` | — | Approved | Approved | ? | — |
| `search-fulltext-04-dong-bo-chi-muc` | — | Approved | Approved | ? | — |
| `search-fulltext-05-frontend` | — | Approved | Approved | ? | — |
| `search-semantic` | Approved (sửa 2026-10-10/11, đã review) | Approved (sửa 2026-10-11) | Approved (2026-10-11) | Implemented 2026-10-11 (sửa sau QA: L2 norm, lexical loại `sed`; flag `SEMANTIC_ENABLED=false` mặc định) | PASS WITH ISSUES 2026-10-11 (còn N4/N6, T23, rollout) |
| `system-config` | Approved | Approved | Approved | ? | — |
| `trash-search-index-cleanup` | — | — | Approved | ? | — |
| `warehouse-structure` | Approved | Approved | Approved | ? | — |

Ghi chú: `—` = không có file ở tầng đó; `?` = chưa xác định. Nền brownfield: `docs/01-sa/architecture/_codebase-overview.md` (bỏ qua ở bảng).
