# Tech Spec: search-fulltext-03-dinh-vi-ket-qua

> File: `docs/03-dev/specs/search-fulltext-03-dinh-vi-ket-qua.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/search-fulltext-03-dinh-vi-ket-qua.md`
> Status: Approved

Kế thừa nền tảng ở `-01` (mapping, hydrator, `POST /search`) và `-02`
(filters). File này chốt chi tiết `HighlightMapper`, hình dạng `position`
trong response, và thứ tự sắp xếp nhiều đoạn trích trong cùng tài liệu.

## 1. Tóm tắt kỹ thuật

- `HighlightMapper`: nhận `inner_hits` (mỗi hit = 1 segment) từ OpenSearch +
  dữ liệu hydrate từ `content_segment` (Postgres), sinh `SegmentHit.position`
  đúng 1 trong 3 dạng: `page` (văn bản đã OCR), `time` (audio/video), `none`
  (`note`/`title`).
- `bbox_polygon` **không** đi qua OpenSearch — lấy thẳng từ
  `content_segment.bbox_polygon` (JSONB) ở bước hydrate đã có sẵn (không
  round-trip DB thêm, đúng QĐ 1/4 SA).
- Badge `confirm_source="auto"` được trả kèm mọi `SearchHit` cấp tài liệu
  (không phải cấp segment — `confirm_source` denormalize theo tài liệu/run,
  xem mục 2) để UI cảnh báo AC-6.

## 2. Data model

Không có bảng/index mới so với `-01`/`-02`. Chốt cụ thể **hình dạng** trường
`position` (đã có placeholder ở `-01` §3.2) và nguồn dữ liệu từng trường:

```java
package com.aismartstore.backend.search.dto;

public sealed interface PositionRef {
    record Page(int pageNo, int lineFrom, int lineTo, List<List<Double>> bboxPolygon,
                int highlightOffsetStart, int highlightOffsetEnd) implements PositionRef {}
    record Time(int timeStartMs, int timeEndMs) implements PositionRef {}
    record None() implements PositionRef {}
}
```

- `Page.bboxPolygon`: danh sách điểm đa giác `[x, y]` theo toạ độ **tỉ lệ
  0–1** trên trang (không phải pixel tuyệt đối) — **đã chốt chính thức
  2026-09-07** ở `digitization-pipeline.md` QĐ 3 và
  `digitization-pipeline-02-ocr-stt-processing.md` §3.3 (worker OCR tự chia
  cho `page_width`/`page_height` trước khi ghi). `SearchResultHydrator`
  không cần quy đổi — dùng thẳng giá trị lưu ở `content_segment.bbox_polygon`.
- `highlightOffsetStart/End`: offset ký tự trong `current_text` của segment,
  lấy từ vị trí `<em>...</em>` do `unified` highlighter trả về (parse offset
  bằng cách bật `highlight.type=unified` + `no_match_size=0`, dùng
  `fragment_offset` nếu OpenSearch hỗ trợ, hoặc tính lại bằng cách tìm vị trí
  thẻ `<em>` trong fragment và cộng lại offset của fragment trong toàn văn —
  chốt cách tính cụ thể khi implement, không phát sinh field DB mới).
- `content_source`/`position_type` quyết định biến thể nào được dựng:
  `content_source ∈ {ocr}` → `Page`; `{stt}` → `Time`; `{note, title}` →
  `None`. `{sed, summary}` nay đã là giá trị chính thức của enum (đồng bộ
  vào `ai-search-infra.md` 2026-09-07, cùng lúc `digitization-results-
  store.md` được Approved với quyết định SED/tóm tắt video **bắt buộc qua
  FR-18**) nhưng **chưa được map vào `PositionRef` ở đây** — `sed` có
  `time_start_ms`/`time_end_ms` (nên hợp lý là `Time`, giống `stt`),
  `summary` không có toạ độ (nên là `None`, giống `note`/`title`); cần một
  bản cập nhật nhỏ ở `HighlightMapper` (mục 4 bước 1c) khi 2 loại nội dung
  này thực sự có dữ liệu (đang chờ story/spec riêng cho màn hình duyệt SED/
  tóm tắt video trước khi có nội dung `confirmed` để tìm kiếm).
- `confirm_source` cấp tài liệu: lấy từ `digitization_run.confirm_source`
  của run đang active tạo ra segment có điểm cao nhất (không phải "mọi run
  của tài liệu" — một tài liệu có thể có nhiều run task khác nhau, ví dụ OCR
  đã `manual`, STT còn `auto`; hiển thị theo **run của segment đang khớp**,
  không phải một cờ chung cấp tài liệu). Field trả về đổi từ cấp tài liệu
  (như viết ở `-01` §3.2, để đơn giản hoá ban đầu) sang **cấp segment**:
  `SegmentHit.confirmSource` — **sửa lại quyết định ở `-01`**: badge nằm
  trong từng `segments[]`, không phải ở object tài liệu cấp cao. `-01`/`-02`
  cần cập nhật response mẫu theo đúng field này khi implement (không tạo
  mâu thuẫn dữ liệu, chỉ là vị trí field trong JSON — DEV lưu ý khi code).

## 3. API contract

Mở rộng response `-01` §3.2 — field `segments[].position` và
`segments[].confirmSource` chốt như sau:

```json
{
  "results": [
    {
      "documentId": "b6f1...",
      "documentName": "Hop_dong_thue_nha_2024.pdf",
      "docType": "hop_dong",
      "score": 12.34,
      "segments": [
        {
          "segmentId": "s1",
          "contentSource": "ocr",
          "confirmSource": "manual",
          "highlight": "...<em>thuê nhà</em>...",
          "position": { "type": "page", "pageNo": 3, "lineFrom": 10, "lineTo": 12,
                         "bboxPolygon": [[0.12,0.30],[0.62,0.30],[0.62,0.35],[0.12,0.35]],
                         "highlightOffsetStart": 42, "highlightOffsetEnd": 51 }
        },
        {
          "segmentId": "s2",
          "contentSource": "title",
          "confirmSource": "manual",
          "highlight": "Hop_dong_<em>thue</em>_nha_2024.pdf",
          "position": { "type": "none" }
        }
      ]
    }
  ]
}
```

- Tài liệu âm thanh/video: `position.type = "time"`,
  `{"timeStartMs": 12000, "timeEndMs": 18500}`.
- `position.type = "none"` **luôn** đi kèm `contentSource ∈ {note, title}` —
  frontend dựa vào `type`, không dựa vào `contentSource`, để tương lai thêm
  loại segment mới không có vị trí (ví dụ `summary`) không cần đổi logic UI.

## 4. Luồng xử lý chính

1. `HighlightMapper.map(innerHit, hydratedSegment)` (gọi trong
   `SearchResultHydrator.hydrate` ở `-01` §4.1.6, sau khi đã có
   `content_segment` tương ứng):
   1. Đọc `highlight.text` từ `inner_hit` (unified highlighter,
      `fragment_size=200`, `number_of_fragments=1`,
      `boundary_scanner=sentence` — cấu hình ở `SearchQueryBuilder`, đã nêu ở
      `-01`/QĐ4 SA).
   2. Tính `highlightOffsetStart/End` bằng cách định vị `<em>` trong fragment
      trả về, cộng offset bắt đầu của fragment trong `current_text` (OpenSearch
      không trả offset tuyệt đối trực tiếp cho `unified` — cần tự tính bằng
      cách so khớp fragment con trong text gốc, hoặc dùng
      `highlight.fragment_offset` nếu client hỗ trợ ở phiên bản đang dùng —
      xác nhận API cụ thể khi implement).
   3. Rẽ nhánh theo `contentSource`:
      - `ocr` → `PositionRef.Page(pageNo, lineFrom, lineTo, bboxPolygon,
        offsetStart, offsetEnd)` từ `content_segment` (Postgres).
      - `stt` → `PositionRef.Time(timeStartMs, timeEndMs)` — ưu tiên
        `current_time_start_ms/current_time_end_ms` (bản đã sửa nếu có),
        không phải `ai_time_*` (khớp nguyên tắc "hiển thị bản hiện hành" của
        `digitization-results-store.md`).
      - `note`, `title` → `PositionRef.None()`, kèm text cảnh báo cố định
        (copy UI do BA/DEV chốt ở câu hỏi mở story `-03`) — response chỉ trả
        `type="none"`, **không** trả text cảnh báo từ backend (tránh cứng
        hoá copy UI trong API, để frontend tự hiển thị theo `type`).
2. **Sắp xếp nhiều đoạn trích trong 1 tài liệu** (AC-5 story `-03`): cấu hình
   `inner_hits.sort` = `[{"_score": "desc"}, {"page_no": "asc"},
   {"time_start_ms": "asc"}]` — OpenSearch bỏ qua field không có trong
   segment (an toàn khi trộn `page_no`/`time_start_ms` null lẫn nhau vì
   `note`/`title` không có cả hai, luôn xếp sau theo `_score`).
3. **Nhảy tới vị trí (deep-link)** — chỉ trả dữ liệu, không tự dựng URL:
   frontend ghép `?page=<pageNo>&highlight=<offsetStart>-<offsetEnd>` (văn
   bản) hoặc `?t=<timeStartMs>` (audio/video) — quyết định UI, ngoài phạm vi
   backend.

## 5. Test plan

- Unit test `HighlightMapper`: mỗi `content_source` (`ocr`, `stt`, `note`,
  `title`) sinh đúng biến thể `PositionRef`; segment `ocr` thiếu `bbox_polygon`
  (dữ liệu cũ/lỗi) → không throw, trả `bboxPolygon=null` kèm `pageNo`/
  `lineFrom/to` vẫn có (degrade một phần, không chặn cả kết quả).
- Unit test tính `highlightOffsetStart/End`: fragment nằm giữa văn bản dài,
  nhiều khớp trong cùng segment — chỉ lấy khớp đầu tiên
  (`number_of_fragments=1`), offset tính đúng so với `current_text` gốc
  (test bằng chuỗi cố định, so sánh thủ công).
- Integration test: tài liệu có 5 segment khớp cùng điểm số bằng nhau (giả
  lập) → thứ tự trả về theo `page_no` tăng dần đúng AC-5; tài liệu có cả
  segment `ocr` điểm cao và `title` điểm thấp hơn → `title` xếp sau dù cùng
  tài liệu.
- Case biên (AC-4 story `-03`): tài liệu chỉ khớp ở `content_source=note`
  hoặc `title` (không khớp `ocr`/`stt` nào) → toàn bộ `segments[]` trả
  `position.type="none"`, không có segment nào giả định `page_no`/`time_*`.
- Case biên: `current_time_start_ms`/`current_time_end_ms` null (segment
  `stt` chưa từng bị sửa) → dùng lại `ai_time_start_ms`/`ai_time_end_ms` làm
  giá trị hiển thị (fallback, vì cột `current_*` được set = `ai_*` ngay khi
  ghi theo `digitization-results-store.md` §4 bước 3 — nên về lý thuyết
  không bao giờ null, nhưng test vẫn cover để phát hiện sớm nếu invariant đó
  bị vi phạm).

## 6. Task breakdown

- [x] Task 1: `PositionRef` (sealed interface) + `HighlightMapper` — implement
      dùng lại DTO phẳng có sẵn từ `-01`, `PositionInfo` (record với field
      `type` phân biệt, đã đúng hình dạng JSON §3), thay vì tạo thêm một sealed
      interface `PositionRef` song song chỉ khác biệt ở cách biểu diễn nội bộ
      Java — bổ sung 2 field `highlightOffsetStart/End` còn thiếu, tách logic
      map segment/tính offset ra lớp `HighlightMapper` riêng (trước nằm inline
      ở `SearchResultHydrator`). Nhân tiện sửa đúng theo §4 bước 1.3: `stt`/
      `sed` giờ ưu tiên `current_time_start_ms/current_time_end_ms` (fallback
      `ai_time_*`) thay vì cột `time_start_ms/time_end_ms` cũ (không phải bản
      hiện hành, không theo cập nhật của cán bộ khi sửa).
- [x] Task 2: Cấu hình `inner_hits` (`highlight`, `sort`) trong
      `SearchQueryBuilder` (mở rộng phần khung đã có ở `-01` Task 5) — thêm
      `boundaryScanner(Sentence)` + `noMatchSize(0)` vào highlight field, và
      sort 3 tầng `_score desc, page_no asc, time_start_ms asc` cho inner_hits.
- [x] Task 3: ~~Sửa response `SegmentHit` ở `-01`/`-02` để `confirmSource`
      nằm cấp segment~~ — đã đồng bộ trực tiếp vào mẫu JSON `-01` §3.2
      2026-09-07 (không còn `confirmSource` cấp tài liệu ở `-01`); Task 7
      của `-01` code đúng theo mẫu hiện tại là đủ, không cần đối chiếu thêm.
- [x] Task 4: ~~Xác nhận định dạng `bbox_polygon`~~ — đã chốt **tỉ lệ 0–1**
      2026-09-07 (mục 2); không cần quy đổi trong `HighlightMapper`.
- [x] Task 5: Toàn bộ test plan mục 5 — `HighlightMapperTest` (unit, mỗi
      `content_source` + degrade bbox thiếu + offset math + fallback
      `ai_time_*`), 2 test mới trong `SearchQueryBuilderTest` (unit, cấu hình
      `inner_hits.sort`/`highlight`), 2 test mới trong `SearchScopeIT`
      (integration AC-5 - tie theo `page_no`, ocr điểm cao xếp trước title điểm
      thấp); `SearchScopeIT` vẫn không chạy được trong sandbox này (V21
      `setval` trên `audit_log` rỗng, xem note đã có sẵn ở đầu file đó) -
      không phải lỗi phát sinh từ thay đổi ở đây.

## 7. Rủi ro kỹ thuật

- ~~Định dạng `bbox_polygon` chưa xác nhận cuối cùng~~ — **đã chốt tỉ lệ 0–1**
  2026-09-07 (mục 2), không còn rủi ro.
- **Tính `highlightOffsetStart/End` từ `unified` highlighter** không có API
  offset tuyệt đối chuẩn hoá rõ ràng trong mọi phiên bản OpenSearch — rủi ro
  phải tự parse fragment, dễ sai khi văn bản có ký tự đặc biệt/nhiều khớp
  trùng lặp; giảm thiểu bằng test biên kỹ ở mục 5, chấp nhận sai số nhỏ ở
  giai đoạn 1 (không chặn go-live, chỉ ảnh hưởng độ chính xác khoanh vùng
  offset trong khi `bbox_polygon` đã đủ để định vị đúng theo AC-7 story
  `-03`).
- ~~`confirm_source` cấp segment thay vì cấp tài liệu~~ — đã đồng bộ trực
  tiếp vào `-01` §3.2 2026-09-07, không còn 2 mẫu response mâu thuẫn nhau.
