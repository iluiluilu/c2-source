# Tech Spec: digitization-review-06-queue-pending-status

> File: `docs/03-dev/specs/digitization-review-06-queue-pending-status.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/digitization-review-06-queue-pending-status.md`
> Status: Approved

## 1. Tóm tắt kỹ thuật

Đưa `digitization_run.processing_status` (đã có sẵn trên entity/DB, không
migration) ra thành field mới `processingStatus` trong response `GET
/digitization-review/queue`, rồi ở frontend `/digitization`, khi
`row.effectiveStatus === 'pending'`, tra nhãn/màu badge Trạng thái theo
`row.processingStatus` thay vì luôn hiện "Đang chờ" cố định. Không đổi ý
nghĩa/giá trị `review_state`/`effectiveStatus` hiện có (AC-5), không thêm
badge phụ (AC-1), không đổi hành vi các `effectiveStatus` khác `pending`
(AC-4).

Phạm vi 2 lớp:
- Backend: thêm 1 cột vào SELECT + 1 field record + 1 dòng ROW_MAPPER.
- Frontend: thêm field vào type response, sửa logic chọn badge cho đúng 1
  nhánh `effectiveStatus === 'pending'` (giữ nguyên toàn bộ nhánh khác).

## 2. Data model

Không có migration mới. Dữ liệu nguồn: cột `digitization_run.processing_status`
(đã tồn tại), map với hằng số trên entity `DigitizationRun`:

```
PROCESSING_QUEUED           = "queued"
PROCESSING_RUNNING          = "running"
PROCESSING_SUCCEEDED        = "succeeded"
PROCESSING_FAILED_RETRYABLE = "failed_retryable"
PROCESSING_FAILED_PERMANENT = "failed_permanent"
PROCESSING_CANCELLED        = "cancelled"
```

`RunSummaryView` (record, thêm 1 field mới ở cuối để không đổi thứ tự
positional constructor call hiện có trong `ROW_MAPPER`):

```java
public record RunSummaryView(
        UUID runId,
        UUID documentId,
        String documentName,
        String task,
        String reviewState,
        String effectiveStatus,
        String outputQualityFlag,
        int lowConfidenceCount,
        int segmentCount,
        OffsetDateTime reviewDeadlineAt,
        OffsetDateTime firstEditAt,
        boolean isReplacingConfirmedRun,
        String processingStatus) {   // MỚI — AC-5
}
```

`ReviewQueueRepository.search()` — CTE `q`: thêm `r.processing_status` vào
danh sách cột SELECT (cạnh `r.review_state`, xem §4), và `ROW_MAPPER` map
thêm `rs.getString("processing_status")` làm tham số cuối của
`RunSummaryView`.

Giá trị `processingStatus` trong response **luôn có mặt** cho mọi
`effectiveStatus` (kể cả `in_review`, `stale`, `upcoming_deadline`) — spec
không cần CASE điều kiện, chỉ SELECT thẳng cột; frontend tự quyết định chỉ
dùng field này khi `effectiveStatus === 'pending'` (AC-4).

## 3. API contract

| Method | Endpoint | Request | Response |
|---|---|---|---|
| GET | `/digitization-review/queue` | (không đổi — query params `task`, `status`, `qualityFlag`, `page`, `size` giữ nguyên) | `RunSummaryPageView.content[]` — mỗi phần tử thêm field mới `processingStatus: string` (một trong 6 giá trị `PROCESSING_*` ở §2), cộng thêm vào cuối shape hiện có. Không field nào bị đổi tên/kiểu/xoá — tương thích ngược cho mọi client hiện tại chưa đọc field mới (AC-5). |

`RunDetail` (`GET /digitization-review/runs/{runId}` hoặc endpoint tương
đương dùng chung `RunSummaryView` làm base shape — xem Javadoc
`RunSummaryView`) tự động nhận field mới này vì nó kế thừa cùng record;
**không cần sửa gì thêm** ở code side đó, chỉ cần xác nhận không có test nào
assert cứng số lượng field/JSON snapshot của response mà spec này sẽ phá vỡ
(xem §5).

## 4. Luồng xử lý chính

**Backend:**

1. `ReviewQueueRepository.search()` build CTE `q`: thêm `r.processing_status`
   vào mệnh đề SELECT ngay cạnh `r.review_state` (dòng SELECT hiện có trong
   `ReviewQueueRepository.java`, đoạn xây `cte`).
2. `ROW_MAPPER` thêm `rs.getString("processing_status")` làm tham số cuối khi
   dựng `RunSummaryView`.
3. `RunSummaryView` thêm field `processingStatus` (record component cuối).
4. `ReviewController` không cần sửa — nó chỉ serialize `RunSummaryView`/
   `RunSummaryPageView` sẵn có qua Jackson, field mới tự động xuất hiện
   trong JSON response.

**Frontend:**

5. `web/src/lib/api.ts`: thêm `processingStatus: string` vào interface
   `ReviewRunSummary` (dùng kiểu `string` thô, không union hẹp, vì AC-6 gộp
   `failed_retryable`/`failed_permanent` chung 1 xử lý UI — không cần TS
   phân biệt chi tiết từng giá trị `PROCESSING_*`; nếu muốn chặt chẽ hơn có
   thể khai `ReviewProcessingStatus = 'queued' | 'running' | 'succeeded' |
   'failed_retryable' | 'failed_permanent' | 'cancelled'`, để tuỳ DEV lúc
   code).
6. `web/src/app/digitization/page.tsx`: thêm hàm/logic chọn badge cho hàng
   `pending`, tách khỏi 2 map tĩnh `STATUS_LABELS`/`STATUS_BADGE_CLASS`
   hiện dùng chung cho mọi `effectiveStatus`. Đề xuất cụ thể (không bắt buộc
   đúng tên biến, chỉ cần đúng hành vi AC-1/AC-2/AC-4/AC-6):

   ```ts
   function pendingBadge(processingStatus: string): { label: string; className: string } {
     switch (processingStatus) {
       case 'queued':
       case 'running':
         return { label: 'Đang xử lý', className: 'bg-gray-100 text-gray-700' }; // AC-1, giữ tinh thần xám hiện tại (== màu 'pending' cũ)
       case 'succeeded':
         return { label: 'Chờ duyệt', className: 'bg-yellow-50 text-yellow-800' }; // AC-2, đồng bộ document-ownership pending_review
       case 'failed_retryable':
       case 'failed_permanent':
         return { label: 'Lỗi xử lý', className: 'bg-red-50 text-red-700' }; // AC-6, đồng bộ class stale/failed
       default:
         // cancelled hoặc giá trị lạ chưa lường trước: fallback về nhãn "Đang chờ" cũ,
         // không throw, không chặn render bảng.
         return { label: 'Đang chờ', className: 'bg-gray-100 text-gray-700' };
     }
   }
   ```

   Tại chỗ render badge (`STATUS_BADGE_CLASS[row.effectiveStatus]` /
   `STATUS_LABELS[row.effectiveStatus]`, dòng ~249-251):

   ```tsx
   const badge = row.effectiveStatus === 'pending'
     ? pendingBadge(row.processingStatus)
     : { label: STATUS_LABELS[row.effectiveStatus], className: STATUS_BADGE_CLASS[row.effectiveStatus] };
   // <span className={`text-xs font-semibold px-2 py-1 rounded-full ${badge.className}`}>{badge.label}</span>
   ```

   `effectiveStatus` khác `pending` (`in_review`, `stale`,
   `upcoming_deadline`, `confirmed`) đi thẳng qua nhánh cũ, không đổi
   (AC-4) — kể cả khi `confirmed` không thực sự xuất hiện ở hàng chờ (đã lọc
   ở backend), giữ nguyên map để không phá vỡ chỗ khác tái dùng 2 map này
   nếu có.

7. Không đổi `STATUS_FILTER_OPTIONS` (bộ lọc theo `effectiveStatus`) — story
   xác nhận ngoài phạm vi (mục "Ngoài phạm vi" của story, gạch đầu dòng cuối
   cùng về filter).

## 5. Test plan

**Backend**

- `ReviewQueueRepository` — grep test hiện có (nếu có file unit/slice test
  riêng cho repository; nếu chưa có, thêm case vào test tích hợp gần nhất
  đang cover `search()`): thêm case seed run với `processing_status =
  'succeeded'` và `review_state = 'pending'`, assert
  `content[0].processingStatus == "succeeded"` trong response JSON — xác
  nhận field mới có mặt và đúng giá trị, không phá field cũ.
- `ReviewControllerIT` (`backend/src/test/java/.../review/ReviewControllerIT.java`,
  test đang gọi `GET /digitization-review/queue` ở dòng ~304): cập nhật
  assertion JSON (nếu test đang assert cứng danh sách field/`jsonPath`) để
  không fail vì field mới `processingStatus`; thêm ít nhất 1 case mới assert
  `processingStatus` xuất hiện đúng giá trị cho 1 run `queued`/`running` và 1
  run `succeeded` (cùng `review_state = pending`), tái dùng helper seed data
  sẵn có trong file thay vì viết lại.
- Case biên cần cover: `processing_status = failed_retryable` và
  `failed_permanent` đều trả về đúng giá trị thô (không bị CASE nào ở SQL
  biến đổi — spec này chỉ SELECT thẳng cột, không map).

**Frontend**

- `web/src/app/digitization/__tests__/page.test.tsx`: thêm test case cho
  từng nhánh AC-1/AC-2/AC-6 — render bảng với `effectiveStatus: 'pending'` và
  lần lượt `processingStatus` = `'queued'`, `'running'`, `'succeeded'`,
  `'failed_retryable'`, `'failed_permanent'`; assert đúng text badge ("Đang
  xử lý" / "Chờ duyệt" / "Lỗi xử lý") xuất hiện, và **không** còn text "Đang
  chờ" cố định cho các case này. Thêm 1 case AC-4: `effectiveStatus:
  'in_review'` (hoặc `stale`/`upcoming_deadline`) với `processingStatus`
  bất kỳ — assert badge/label giữ nguyên như trước khi có thay đổi này
  (không phụ thuộc `processingStatus`).
- `web/src/app/digitization/__tests__/buildQueueQuery.test.ts`: không cần
  sửa — file này test query string cho filter/pagination, không liên quan
  badge; chỉ chạy lại để xác nhận không có regression ngoài ý muốn.
- Case biên: `processingStatus` là giá trị không nằm trong 6 giá trị đã biết
  (dữ liệu cũ/lạ) — assert fallback không throw, hiển thị nhãn mặc định
  (theo `default` ở §4 bước 6).

## 6. Task breakdown

- [x] Task 1 (backend): Thêm `r.processing_status` vào SELECT của CTE `q`
      trong `ReviewQueueRepository.search()`.
- [x] Task 2 (backend): Thêm field `processingStatus` vào `RunSummaryView`
      (cuối record) và cập nhật `ROW_MAPPER` map thêm cột. (Cũng cập nhật
      `ReviewController.toSummary()` — 1 call site positional thứ 2 không
      được spec §7 nêu tên nhưng đã grep ra, cần sửa đồng bộ để không vỡ
      build.)
- [x] Task 3 (backend test): Cập nhật/thêm case trong `ReviewControllerIT`
      (và test repository nếu có) cho `processingStatus` — cả 2 trạng thái
      "chưa xong" (`queued`/`running`), "sẵn sàng" (`succeeded`), và "lỗi"
      (`failed_retryable`/`failed_permanent`). (5 test case mới thêm; compile
      OK qua `mvn -o test-compile`, nhưng KHÔNG chạy được trong sandbox này —
      xem ghi chú Task 7.)
- [x] Task 4 (frontend types): Thêm `processingStatus: string` (hoặc union
      `ReviewProcessingStatus`) vào `ReviewRunSummary` trong
      `web/src/lib/api.ts`.
- [x] Task 5 (frontend UI): Thêm logic `pendingBadge()` (hoặc tương đương)
      trong `web/src/app/digitization/page.tsx`, áp dụng đúng khi
      `row.effectiveStatus === 'pending'`; các `effectiveStatus` khác giữ
      nguyên map `STATUS_LABELS`/`STATUS_BADGE_CLASS` hiện có.
- [x] Task 6 (frontend test): Thêm test case AC-1/AC-2/AC-4/AC-6 vào
      `web/src/app/digitization/__tests__/page.test.tsx`.
      **Phát hiện + chốt cùng người dùng 2026-09-21:** `ReviewRunDetail
      extends ReviewRunSummary` (trước khi sửa) kéo field `processingStatus`
      mới thành required luôn ở `ReviewRunDetail`, nhưng `RunDetailView`
      phía backend (`GET /digitization-review/runs/{runId}`) là record
      riêng, copy tay field qua `of()`, **không** có `processingStatus` —
      response JSON thật sự thiếu field mà type nói luôn có. Quyết định: chỉ
      sửa type TS, không đụng backend (AC của story chỉ nhắm trang
      `/digitization`, không yêu cầu gì cho trang chi tiết run). Đã sửa
      `web/src/lib/api.ts`: `ReviewRunDetail extends Omit<ReviewRunSummary,
      'processingStatus'>` thay vì `extends ReviewRunSummary` trực tiếp, và
      bỏ giá trị `processingStatus` giả đã thêm tạm vào 2 file test
      `runs/[runId]/__tests__/loadFullRunDetail.test.ts` và
      `runs/[runId]/__tests__/page.test.tsx` (không cần nữa vì field hết bắt
      buộc ở đó). `tsc --noEmit` + `eslint` sạch, `npx vitest run` vẫn
      270/270 pass sau khi sửa.
- [x] Task 7: Chạy toàn bộ test bị ảnh hưởng (backend module `digitization.review`,
      frontend `digitization/__tests__`) và xác nhận pass trước khi coi
      story hoàn tất. Frontend: `npx vitest run` 270/270 pass +
      `tsc --noEmit` sạch + eslint sạch. Backend: `mvn -o compile` và
      `mvn -o test-compile` sạch (0 lỗi), nhưng KHÔNG chạy được
      `ReviewControllerIT` trong sandbox này — mọi `@SpringBootTest` IT
      (kể cả các class không liên quan đến story này, ví dụ
      `RolePermissionSeedTest`, `DocumentSharingIT`, `GroupLifecycleIT`,
      `DigitizationRunLifecycleServiceIT`) đều fail giống nhau khi load
      ApplicationContext do `NullPointerException: Host name` trong
      `OpenSearchClientConfig.openSearchTransport` — môi trường/hạ tầng cục
      bộ, không liên quan đến thay đổi của story này. Cần chạy lại
      `ReviewControllerIT` ở môi trường CI/dev chuẩn của team để xác nhận.

## 7. Rủi ro kỹ thuật

- Nếu có nơi khác ngoài `/digitization` đang deserialize response
  `GET /digitization-review/queue` với schema validation nghiêm ngặt (ví dụ
  test contract cứng số field), field mới `processingStatus` có thể làm
  fail — cần grep thêm `RunSummaryPageView`/`RunSummaryView` trong test
  trước khi merge để chắc chắn không bỏ sót chỗ assert cứng shape JSON.
- `RunSummaryView` cũng là base shape cho `RunDetail` (theo Javadoc của
  record) — cần xác nhận constructor `RunSummaryView` không bị gọi
  positional ở nơi nào khác ngoài `ROW_MAPPER` (ví dụ code build `RunDetail`
  từ `RunSummaryView` bằng cách gọi lại constructor thủ công) để tránh lỗi
  biên dịch do thêm tham số; nếu có, cần cập nhật đồng bộ.
- Giá trị `processingStatus = 'cancelled'` không được story đề cập tới cho
  nhánh `pending` (không rõ nghiệp vụ có bao giờ để run `cancelled` ở lại
  `review_state = pending` hay không). **Chốt cùng người dùng 2026-09-21:**
  giữ nguyên fallback về nhãn "Đang chờ" cũ (không throw, không cần badge
  riêng cho `cancelled`) — case hiếm/edge, không đáng làm phức tạp thêm
  story. Không còn là điểm mở.
- Đổi field cuối của record `RunSummaryView` là thay đổi có tính breaking
  cho bất kỳ code Java nào gọi constructor positional (không dùng builder) —
  rủi ro thấp vì record này chỉ có 1 nơi khởi tạo (`ROW_MAPPER`) theo kiểm
  tra hiện tại, nhưng nên `grep -rn "new RunSummaryView("` trước khi implement
  để chắc chắn.
