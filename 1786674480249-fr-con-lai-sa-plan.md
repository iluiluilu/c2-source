# Kế hoạch xử lý các FR còn lại (bước SA)

> Nguồn yêu cầu: `../docs/dac_ta_yeu_cau_chuc_nang_phi_chuc_nang.adoc` (SRS
> v2.1) — đường dẫn tương đối so với project root của phiên `/sa-spec`
> (`source_v2/`); file thật nằm ở `c2/docs/`, một cấp trên `source_v2/`, không
> phải `source_v2/docs/`.
> Đích lưu mong muốn của người dùng: `source_v2/ke-hoach-xu-ly-fr-con-lai.md`
> (Plan Mode chỉ ghi vào thư mục plans; **đã chốt:** agent triển khai sẽ sao
> chép nội dung này sang `source_v2/` sau khi thoát Plan Mode.)
> Phạm vi: lập kế hoạch tạo nốt tài liệu kiến trúc SA
> (`source_v2/docs/01-sa/architecture/*.md`) cho những FR chưa có tài liệu.

## 1. Mục tiêu

Đối chiếu 35 FR trong SRS với 7 tài liệu SA đã có, xác định FR còn thiếu tài
liệu kiến trúc, và đề xuất thứ tự + gom nhóm để tạo nốt phần còn lại theo
pipeline `/sa-spec → /ba-story → /dev-spec → /dev-implement` (xem `AGENTS.md`).
Kế hoạch này KHÔNG tự viết tài liệu SA — nó là bản đồ công việc cho các phiên
`/sa-spec` kế tiếp.

## 2. Hiện trạng: FR đã có tài liệu SA

| Tài liệu SA (slug) | FR bao phủ | Status |
|---|---|---|
| `auth-session` | FR-02, FR-03, FR-05, FR-06 | Approved |
| `account-lifecycle` | FR-04 (+ override hạn mức FR-14) | Approved |
| `rbac-group-scope` | FR-07, FR-08 (+ nền tảng data model nhóm cho FR-28/29) | Approved |
| `warehouse-structure` | FR-13 | Approved |
| `personal-storage-quota` | FR-14 | Approved |
| `data-ingestion` | FR-11, FR-12 | Approved |
| `system-config` | FR-01 | Approved |

**Lưu ý Status (AGENTS.md 3.9):** `data-ingestion` + `system-config` đã
`Approved` (xác nhận lại 2026-08-14 — trước đó có lỗi gõ `Approveda` ở
`system-config.md`, đã sửa). Giai đoạn A coi như hoàn tất, không còn chặn các
slug dẫn chiếu tham số ở `system-config`.

## 3. Các FR còn lại + slug đề xuất (đã tinh chỉnh)

FR-21 (tóm tắt tự động ảnh/video) đã **tạm hoãn** — không lập tài liệu, chỉ giữ
chỗ mã số.

17 FR còn lại → **12 slug** (đã áp các quyết định tinh chỉnh ở mục 4):

| # | Slug | FR | Domain | Module backend | Hạ tầng mới cần |
|---|---|---|---|---|---|
| 1 | `audit-logging` | FR-09, FR-10 | Nhật ký & giám sát | `audit/` (đã có) | — |
| 2 | `classification-notes` | FR-15, FR-16 | Nhãn + ghi chú | `tags/`, `notes/` (đã có) | — |
| 3 | `group-management-sharing` | FR-28, FR-29 | Quản lý nhóm & chia sẻ | `groups/` (nền tảng đã có) | — |
| 4 | `ai-search-infra` | *(nền, không FR riêng)* | Hạ tầng AI/search dùng chung | *(mới)* | OpenSearch, Qdrant, RabbitMQ, GPU serving |
| 5 | `digitization-pipeline` | FR-17, FR-19, FR-20 | OCR/STT/mốc thời gian (AI tự động) | *(mới)* | dùng lại #4 |
| 6 | `digitization-review` | FR-18 | Kiểm duyệt + tự xác nhận 24h | *(mới)*; liên quan #5 | — |
| 7 | `search-fulltext` | FR-22, FR-23, FR-25 | Toàn văn/nâng cao/định vị | *(mới)* | OpenSearch (từ #4) |
| 8 | `search-semantic` | FR-24 | Tra cứu ngữ nghĩa | *(mới)* | Qdrant + embedding (từ #4) |
| 9 | `delete-restore-trash` | FR-26, FR-27 | Xóa mềm/thùng rác + phục hồi | *(mới)*; `documents/`, `groups/` | — |
| 10 | `reporting-export` | FR-30, FR-31 | Báo cáo + xuất PDF/Excel/Word | *(mới)* | — |
| 11 | `external-api` | FR-32, FR-33 | API ngoài + khóa/rate limit | *(mới)* | (tái dùng Redis) |
| 12 | `backup-restore` | FR-34, FR-35 | Sao lưu + khôi phục | *(mới)* | (trỏ `kien_truc_he_thong.adoc`) |

## 4. Quyết định tinh chỉnh đã chốt (phiên planning này)

- **Tách search làm 2 slug** — `search-fulltext` (FR-22/23/25, OpenSearch) vs
  `search-semantic` (FR-24, Qdrant + embedding). Cô lập rủi ro AI/embedding,
  cho phép làm FR-22/23/25 trước, không chờ Qdrant.
- **Tách số hóa làm 2 slug** — `digitization-pipeline` (FR-17/19/20, AI tự
  động, hàng đợi/GPU) vs `digitization-review` (FR-18, luồng kiểm duyệt thủ
  công + tự xác nhận 24h + UI sửa). Tách hạ tầng AI khỏi nghiệp vụ review.
- **Lập slug hạ tầng nền `ai-search-infra` TRƯỚC Giai đoạn C** — chốt một chỗ:
  chọn OpenSearch/Qdrant/RabbitMQ/GPU serving/embedding model, và cách nhúng
  filter phạm vi FR-08 vào search engine (row-level filter đã chốt cho DB
  nhưng search engine cần cơ chế riêng). Đồng bộ với `kien_truc_he_thong.adoc`.
  Các slug 5/6/7/8 chỉ dẫn chiếu, không lặp lại quyết định hạ tầng.
- **File kế hoạch:** giữ ở thư mục plans; agent triển khai sao chép sang
  `source_v2/ke-hoach-xu-ly-fr-con-lai.md` sau khi thoát Plan Mode.

## 5. Thứ tự thực thi (theo phụ thuộc)

**Giai đoạn A — hoàn tất nền đang dở:**
1. ~~Duyệt `system-config` + `data-ingestion` → `Approved`.~~ **Đã xong**
   (cả hai đã `Approved`) — không còn việc cần làm ở giai đoạn này.

**Giai đoạn B — module đã có, ít phụ thuộc hạ tầng mới:**
2. `audit-logging` (FR-09, FR-10) — nền cho mọi FR (ghi log).
3. `classification-notes` (FR-15, FR-16).
4. `group-management-sharing` (FR-28, FR-29).

**Giai đoạn C — cần hạ tầng AI/search (rủi ro & effort cao nhất):**
5. `ai-search-infra` (nền — phải làm trước 6–9).
6. `digitization-pipeline` (FR-17, FR-19, FR-20).
7. `digitization-review` (FR-18) — phụ thuộc #6 (kết quả OCR/STT để duyệt).
8. `search-fulltext` (FR-22, FR-23, FR-25) — phụ thuộc dữ liệu đã xác nhận
   (FR-18) + filter FR-08.
9. `search-semantic` (FR-24) — phụ thuộc #5 (Qdrant/embedding) + #8.
10. `delete-restore-trash` (FR-26, FR-27) — **giữ ở Giai đoạn C, không chờ #13
    (đã chốt).** FR-26 (xóa mềm) + nhánh FR-27 phục hồi-từ-thùng-rác (R1) độc
    lập với backup; chỉ nhánh FR-27 phục hồi-từ-sao-lưu (R3) **dẫn chiếu** sang
    `backup-restore` (#13), không chặn đặc tả slug này. Xóa mềm còn gỡ chỉ mục
    (phụ thuộc search) + gỡ chia sẻ nhóm (FR-29).

**Giai đoạn D — tích hợp ngoài & vận hành:**
11. `reporting-export` (FR-30, FR-31) — phụ thuộc dữ liệu nhóm + filter FR-08.
12. `external-api` (FR-32, FR-33) — tái dùng filter FR-08; khóa API là xác
    thực riêng cho R4 (ngoài Keycloak); rate limit tái dùng Redis.
13. `backup-restore` (FR-34, FR-35) — cơ chế kỹ thuật trỏ
    `kien_truc_he_thong.adoc`; SRS chỉ đặc tả nghiệp vụ.

## 6. Điểm cần quyết định (mở, cho từng slug — cho phiên /sa-spec)

- **audit-logging:** `AuditLog` **đã có sẵn** hash-chain + append-only
  (`AuditLog.java`: `hash = SHA256(fields || prevHash)`, DB trigger reject
  UPDATE/DELETE, `AuditService.verifyChain()`). ⇒ FR-09 chủ yếu là **rà
  soát/verify**, KHÔNG phải xây mới. Việc còn lại tập trung ở **FR-10**: API
  tra cứu/lọc (theo người dùng/thời gian/hành động/đối tượng) + xuất tệp +
  enforce R2 chỉ đọc + ghi log chính hành động tra cứu. Câu hỏi mở: định dạng
  xuất (CSV/PDF?); R2 chỉ đọc enforce ở tầng nào (route `@Roles`).
- **classification-notes:** danh mục nhãn tĩnh hay tạo được? Ghi chú có lập chỉ
  mục tìm kiếm (FR-22/23) — đồng bộ với search-fulltext.
- **group-management-sharing:** cơ chế thông báo thành viên (in-app?). Vai trò
  owner/member map quyền chia sẻ ra sao.
- **ai-search-infra:** chốt OpenSearch + Qdrant + RabbitMQ + GPU serving +
  embedding model cục bộ (tiếng Việt); cách nhúng filter phạm vi FR-08 vào
  truy vấn search engine; ranh giới với `kien_truc_he_thong.adoc`.
- **digitization-pipeline:** engine OCR/STT cục bộ nào? cách phân đoạn + mốc
  thời gian (FR-20); đánh dấu độ tin cậy thấp.
- **digitization-review:** cơ chế tự xác nhận sau 24h (tham số
  `ocr_stt.auto_confirm_hours` ở `system-config`) + đánh dấu nguồn "tự động";
  chặn mốc thời gian vượt độ dài tệp gốc.
- **search-fulltext:** mapping OpenSearch (trang/dòng cho FR-25); highlight
  đoạn liên quan; nhúng filter FR-08.
- **search-semantic:** embedding model + chiến lược chunking; bắt buộc dẫn
  nguồn; xử lý "không đủ căn cứ".
- **delete-restore-trash:** thùng rác lưu ở đâu (retention 365 ngày ở
  `system-config`)? xóa cứng theo lịch? gỡ chỉ mục/gỡ chia sẻ đồng bộ hay bất
  đồng bộ.
- **reporting-export:** thư viện xuất (JasperReports/Apache POI...)? mẫu báo
  cáo cấu hình tới đâu.
- **external-api:** cơ chế khóa API + rate limit (Redis?); định nghĩa scope
  cho R4.
- **backup-restore:** ranh giới SRS vs `kien_truc_he_thong.adoc`; RTO/RPO
  (NFR-06 để mở).

## 7. Khoảng trống hạ tầng (rủi ro chung)

Đã có: **Keycloak, PostgreSQL, SeaweedFS, Redis, ClamAV**.
Chưa có (bổ sung ở slug `ai-search-infra` / `backup-restore`):

| Thành phần | Phục vụ FR | Slug |
|---|---|---|
| OpenSearch | FR-22/23/25 | `ai-search-infra` → `search-fulltext` |
| Qdrant + embedding | FR-24 | `ai-search-infra` → `search-semantic` |
| RabbitMQ + AI/GPU cục bộ | FR-17–20 | `ai-search-infra` → `digitization-*` |
| Cơ chế sao lưu (ZFS + backup store) | FR-34/35 | `backup-restore` |

## 7b. Cách gọi `/sa-spec` cho mỗi slug (input chuẩn)

`.kilo/command/sa-spec.md` nhận `$ARGUMENTS` dạng văn bản tự do và **tự suy ra
`<slug>`**. Để slug khớp đúng bảng ở mục 3 (tránh agent tự đặt tên khác nhau),
mỗi lần gọi phải ghim rõ: (1) slug mong muốn, (2) danh sách FR ID, (3) trỏ tới
đúng đề mục SRS, (4) nhắc các quyết định/điểm mở tương ứng ở mục 6.

Mẫu đối số (thay `<slug>`/`<FR...>`/`<mục 6>` theo từng dòng checklist):

```
/sa-spec slug=<slug>; phân tích kiến trúc cho <FR...> trong
../docs/dac_ta_yeu_cau_chuc_nang_phi_chuc_nang.adoc mục 2.13; bám các điểm cần
quyết định ở ke-hoach-xu-ly-fr-con-lai.md mục 6 (<slug>); ghi ra
docs/01-sa/architecture/<slug>.md theo architecture-template.md.
```

(Đường dẫn ở trên tính từ project root của phiên `/sa-spec`, tức
`source_v2/`: SRS lùi một cấp `../docs/...`, còn file kế hoạch — sau khi đã
copy theo checklist mục 8 — nằm ngay tại root nên không có tiền tố
`source_v2/`.)

Ràng buộc khi chạy (đồng bộ AGENTS.md):
- Trước mỗi phiên, đọc các file SA đã `Approved` mà slug này dẫn chiếu
  (vd. search-* đọc `ai-search-infra`; digitization-review đọc
  `digitization-pipeline` + `system-config`), không đọc toàn bộ `docs/**`.
- Sau mỗi phiên: người dùng review + đổi `Status: Approved`, rồi `/handoff`
  trước khi mở slug kế tiếp.
- Tôn trọng thứ tự phụ thuộc ở mục 5 (đặc biệt `ai-search-infra` phải
  `Approved` trước 4 slug Giai đoạn C phụ thuộc nó).

## 8. Checklist thực thi

- [ ] Agent triển khai sao chép plan này sang
      `source_v2/ke-hoach-xu-ly-fr-con-lai.md`.
- [x] ~~Người dùng duyệt `system-config.md` + `data-ingestion.md` →
      `Approved`.~~ Đã xong (xác nhận 2026-08-14, xem mục 2).
- [ ] `/sa-spec audit-logging` (FR-09, FR-10) — FR-09 chủ yếu rà soát/verify
      (hash-chain đã có); trọng tâm là FR-10 (tra cứu/lọc/xuất).
- [ ] `/sa-spec classification-notes` (FR-15, FR-16).
- [ ] `/sa-spec group-management-sharing` (FR-28, FR-29).
- [ ] `/sa-spec ai-search-infra` (nền — trước Giai đoạn C).
- [ ] `/sa-spec digitization-pipeline` (FR-17, FR-19, FR-20).
- [ ] `/sa-spec digitization-review` (FR-18).
- [ ] `/sa-spec search-fulltext` (FR-22, FR-23, FR-25).
- [ ] `/sa-spec search-semantic` (FR-24).
- [ ] `/sa-spec delete-restore-trash` (FR-26, FR-27).
- [ ] `/sa-spec reporting-export` (FR-30, FR-31).
- [ ] `/sa-spec external-api` (FR-32, FR-33).
- [ ] `/sa-spec backup-restore` (FR-34, FR-35).
- [ ] Sau mỗi phiên: review + `Approved`, rồi `/handoff` trước slug kế tiếp.

## 9. Quyết định đã chốt (tổng hợp các phiên planning)

- Tách search (fulltext/semantic) và số hóa (pipeline/review); thêm slug nền
  `ai-search-infra`.
- Thứ tự `delete-restore-trash` giữ trước `backup-restore` (cross-ref, không
  chặn).
- `audit-logging`: hash-chain FR-09 đã có sẵn → trọng tâm FR-10.
- Cách gọi `/sa-spec` ghim slug/FR/đề mục SRS (mục 7b).

Các câu hỏi kỹ thuật chi tiết (mục 6) sẽ chốt trong từng phiên `/sa-spec`
tương ứng, không chặn việc khởi động kế hoạch.

## 10. Cách kiểm chứng (validation)

Đây là kế hoạch cấp SA (không có code), nên "validation" là kiểm tra tính đầy
đủ/nhất quán của tài liệu, không phải chạy test.

**Định nghĩa hoàn thành cho từng slug SA:**
- File `docs/01-sa/architecture/<slug>.md` tạo đúng theo
  `architecture-template.md` (đủ 7 mục: bối cảnh, ràng buộc, NFR, ADR, tech
  stack, rủi ro, câu hỏi mở).
- Mọi FR ID gán cho slug (bảng mục 3) đều xuất hiện trong phần bối cảnh/ADR.
- Mọi "điểm cần quyết định" ở mục 6 cho slug đó đã được trả lời hoặc ghi rõ
  out-of-scope kèm lý do.
- Các dẫn chiếu chéo (FR-08 filter, tham số `system-config`, slug nền
  `ai-search-infra`...) trỏ tới file đã `Approved`, không tới `Draft`.
- Status ở đầu file để `Draft` chờ người dùng duyệt (không tự đặt `Approved`).

**Kiểm chứng bao phủ toàn bộ (chạy 1 lần sau khi đủ 12 slug):**
- Đối chiếu 35 FR của SRS với union các FR trong 7 file cũ + 12 file mới:
  đúng 34 FR có tài liệu, FR-21 để trạng thái "tạm hoãn" (không file), không
  FR nào bị bỏ sót hoặc gán trùng cho 2 slug.
- Không có slug nào không map tới FR (trừ `ai-search-infra` là slug nền, ghi
  rõ "không FR riêng").
- Grep `docs/01-sa/architecture/*.md` tìm `FR-\d+` để lập ma trận FR→slug và
  rà chồng chéo/bỏ sót.

## 11. Câu hỏi mở còn lại

Không còn. Kế hoạch sẵn sàng cho agent triển khai thực thi checklist mục 8.
