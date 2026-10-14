# Tech Spec: data-ingestion-web-drop

> ⚠️ ARCHIVED 2026-09-30 — chưa từng implement; thay bằng slug
> `folder-upload-display-name` (xem `docs/_handoff/folder-upload-display-name.md`).
> Mục 5 (test plan) và rủi ro (b), (d) mục 7 được tái sử dụng ở spec mới.

> File: `docs/03-dev/specs/data-ingestion-web-drop.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/data-ingestion.md`
> Spec cha: `docs/03-dev/specs/data-ingestion.md` (tách phần web theo
> AGENTS.md mục 3.8 — spec cha vượt ~300 dòng)
> Kiến trúc: `docs/01-sa/architecture/data-ingestion.md` — Quyết định 1
> (bỏ `<input webkitdirectory>`, tải thư mục chỉ qua kéo-thả)
> Status: Draft

## 1. Tóm tắt kỹ thuật

- Thay cơ chế chọn thư mục qua `<input webkitdirectory>` bằng kéo-thả
  thư mục + duyệt cây `webkitGetAsEntry()`, để loại popup xác nhận gốc
  của trình duyệt ("Upload N files to this site?").
- UI: chọn tệp lẻ qua nút; tải thư mục qua kéo-thả.
- Một helper dùng chung `web/src/lib/dropTraversal.ts` cho cả
  `web/src/app/upload/page.tsx` và `web/src/app/personal/page.tsx`.
- **Không đổi** (ngoài phạm vi spec này): API `/ingest/batches`,
  `/ingest/batches/{id}/files`, chunked upload, retry/backoff 503, dedup
  phía server (`SKIPPED ALREADY_EXISTS`). Chỉ đổi cách client thu thập
  tệp + `relativePath`.

## 2. Data model

Không đổi schema/migration. Kiểu phía client:

```ts
type PickedFile = { file: File; relativePath: string };

type CollectResult = {
  files: PickedFile[];
  failed: { path: string; error: string }[];
  truncated: false | 'MAX_FILES' | 'MAX_TOTAL_BYTES';
  aborted: boolean;
};

collectDroppedFiles(
  dataTransfer: DataTransfer,
  opts: {
    maxFiles: number;          // ingest limits: maxFilesPerBatch
    maxTotalBytes: number;     // ingest limits: maxBatchSizeBytes
    signal?: AbortSignal;      // nút "Huỷ"
    onProgress?: (fileCount: number) => void;
  },
): Promise<CollectResult>;
```

## 3. API contract

Không đổi. Vẫn dùng `GET /ingest/limits` (precheck) và các endpoint
`/ingest/batches*` như spec cha mục 3.

## 4. Luồng xử lý chính

1. `onDrop` (bỏ qua nếu `isRunning`): gọi `e.preventDefault()`, rồi
   **đồng bộ** lấy toàn bộ `item.webkitGetAsEntry()` của
   `dataTransfer.items` trước mọi `await`. Lý do: sau `await` đầu tiên
   trình duyệt vô hiệu `DataTransferItemList`.
2. Bật trạng thái "Đang đọc thư mục… (N tệp)" + nút "Huỷ".
3. Duyệt cây:
   - `FileSystemFileEntry` → `file()` → cộng vào `files`, tăng đếm tệp và
     tổng byte, gọi `onProgress`.
   - `FileSystemDirectoryEntry` → `createReader()`, gọi `readEntries`
     **lặp đến khi trả mảng rỗng** (mỗi lượt tối đa 100 entry — Chrome).
   - Lỗi một entry (`file()`/`readEntries` error callback) → ghi vào
     `failed`, tiếp tục các entry khác.
   - Trước mỗi lượt `readEntries`/`file()`: kiểm tra `signal.aborted` →
     dừng, `aborted=true`; kiểm tra đếm > `maxFiles` hoặc tổng byte >
     `maxTotalBytes` → dừng, `truncated` tương ứng.
4. `relativePath` theo **quy tắc cũ** của `relativePathOf`: từ
   `entry.fullPath` bỏ tên thư mục gốc được thả và tên tệp, giữ các cấp
   giữa (tệp lẻ / tệp ngay dưới gốc → `""`). ⚠️ Xem rủi ro (c) mục 7.
5. Kết quả:
   - `aborted` → không thêm gì vào hàng đợi.
   - `truncated` → hiển thị lỗi precheck AC3a, không thêm vào hàng đợi.
   - Còn lại → `selectFiles(files)`; `failed` hiển thị như tệp lỗi riêng,
     không chặn các tệp khác.
6. `selectFiles` nhận `PickedFile[]` thay vì `FileList`; input tệp lẻ map
   `relativePath=""`. **Giữ nguyên** precheck AC3a (đếm tệp/tổng dung
   lượng/`maxFileSizeBytes` từ `GET /ingest/limits`).

## 5. Test plan

- **Unit — `web/src/lib/__tests__/dropTraversal.test.ts`** (mock
  `webkitGetAsEntry`/`createReader`/`file()`):
  - Thư mục > 100 entry → `readEntries` mock trả nhiều lượt (100, 100, k,
    rồi `[]`) → nhận đủ mọi tệp, không mất lượt cuối.
  - Items lấy đồng bộ: mock `DataTransferItemList` bị vô hiệu sau `await`
    đầu tiên → helper vẫn trả đủ entry.
  - Chỉ thả tệp lẻ → `relativePath=""` cho mọi tệp.
  - Thả lẫn tệp lẻ + thư mục lồng nhiều cấp → `relativePath` đúng quy tắc
    cũ cho từng tệp.
  - Một entry lỗi (`file()`/`readEntries` error callback) → entry đó vào
    `failed`, các tệp còn lại vẫn có trong `files`.
  - Vượt `maxFiles` → `truncated='MAX_FILES'`, `readEntries` không bị gọi
    thêm sau khi vượt.
  - Vượt `maxTotalBytes` → `truncated='MAX_TOTAL_BYTES'`, dừng tương tự.
  - `signal.abort()` giữa chừng → `aborted=true`, không gọi thêm
    `readEntries`/`file()`.
  - `onProgress` được gọi với số tệp tăng dần.
- **`web/src/app/upload/__tests__/page.test.tsx`:**
  - Đổi selector `input[type="file"]:not([multiple][webkitdirectory])`
    thành selector của input tệp lẻ duy nhất còn lại.
  - Case `onDrop` thư mục lồng nhau (mock `webkitGetAsEntry`/
    `createReader`, `readEntries` nhiều lượt) → hàng đợi hiển thị đúng
    `relativePath`.
  - Precheck AC3a vẫn chặn khi vượt ngưỡng (qua cả input lẫn drop).
  - Không còn nút "Chọn thư mục"; có chữ hướng dẫn kéo-thả.
  - Hiển thị "Đang đọc thư mục…" + nút "Huỷ"; bấm "Huỷ" → hàng đợi không
    đổi.
- **`web/src/app/personal/__tests__/page.test.tsx`:**
  - Vùng kéo-thả nhận thư mục lồng nhau → đúng `relativePath`.
  - Khi `isRunning`, thả vào **không** thêm tệp vào hàng đợi.
  - Hiển thị "Đang đọc thư mục…"; không còn nút "Chọn thư mục".
- **Test tay** (jsdom không có `FileSystemEntry`, xem rủi ro (e)): thả
  thư mục > 100 tệp, lồng ≥ 3 cấp trên Chrome, Edge, Firefox, Safari.

## 6. Task breakdown

- [ ] Task 1: Helper `web/src/lib/dropTraversal.ts` theo mục 2 + mục 4
      bước 1, 3, 4. Đây là **nơi duy nhất** gọi `webkitGetAsEntry`/
      `readEntries` (xem rủi ro (b)).
- [ ] Task 2: `selectFiles` ở `upload/page.tsx` và `personal/page.tsx`
      nhận `PickedFile[]`; bỏ `relativePathOf` dựa trên
      `webkitRelativePath`; giữ nguyên precheck AC3a.
- [ ] Task 3: `upload/page.tsx`:
  - Bỏ nút "Chọn thư mục", input `webkitdirectory`, `useEffect` gán
    thuộc tính.
  - `onDrop` gọi `collectDroppedFiles` với `maxFiles`/`maxTotalBytes` từ
    limits.
  - Chữ hướng dẫn "Kéo-thả thư mục vào đây để tải cả thư mục".
  - Trạng thái "Đang đọc thư mục… (N tệp)" + nút "Huỷ" (`AbortController`).
- [ ] Task 4: `personal/page.tsx`: như Task 3, **thêm mới** vùng kéo-thả
      (hiện chưa có); không nhận thả khi `isRunning`.
- [ ] Task 5: Test theo mục 5 (unit + page test).
- [ ] Task 6: Test tay 4 trình duyệt theo mục 5, ghi kết quả vào mục này.
- [ ] Task 7: DoD — `grep -rnE "webkitdirectory|webkitRelativePath|webkitGetAsEntry|readEntries" web/src`
      ngoài các file `__tests__` (mock) chỉ còn kết quả trong
      `dropTraversal.ts`.

## 7. Rủi ro kỹ thuật

- **(a) Không còn nút chọn thư mục dự phòng** — trade-off **SA đã chấp
  nhận** (Quyết định 1): thiết bị cảm ứng / người dùng không kéo-thả được
  chỉ chọn được tệp lẻ. Phương án thay thế (`showDirectoryPicker()`, giữ
  `webkitdirectory`) đã bị SA loại. Không xử lý thêm ở spec này.
- **(b) Mất tệp âm thầm** (lấy entry sau `await`, hoặc không lặp
  `readEntries` → chỉ nhận 100 entry đầu) → **đã khắc phục triệt để bằng
  thiết kế + test**. Đây là lỗi code thuần, không phụ thuộc môi trường:
  - Quy tắc lấy entry đồng bộ + lặp `readEntries` nằm ở **một chỗ duy
    nhất** (helper, Task 1); hai trang không tự duyệt cây (DoD Task 7).
  - Hai unit test riêng chốt đúng hai hành vi (mục 5: "> 100 entry",
    "items lấy đồng bộ"), bắt buộc pass trong CI.
- **(d) Thư mục rất lớn** → **đã khắc phục, rủi ro còn lại không đáng
  kể**. Đính chính bản trước: `readEntries`/`file()` là API bất đồng bộ
  nên **không chặn main thread**, UI vẫn phản hồi; vấn đề thật chỉ là
  thời gian chờ. Xử lý:
  - Dừng sớm theo cả `maxFiles` **và** `maxTotalBytes`, nên không bao giờ
    duyệt quá ngưỡng một batch được phép.
  - Hiển thị số tệp đã đọc (`onProgress`) + nút "Huỷ" (`AbortSignal`).
  - Rủi ro còn lại: thư mục rất nhiều thư mục con rỗng (không đếm vào
    `maxFiles`) vẫn tốn thời gian duyệt — người dùng thấy tiến trình và
    tự huỷ được.
- ⚠️ **(c) Trùng `relative_path` → `SKIPPED` sai** — **CHƯA CHỐT, đang
  trao đổi.** Quy tắc cũ bỏ tên thư mục gốc; `webkitdirectory` chỉ cho
  chọn 1 gốc nên an toàn, kéo-thả thì không. Hai trường hợp va chạm cùng
  `(folder_id, relative_path, originalName)`:
  - Thả nhiều thư mục gốc có tệp cùng tên ở cùng cấp.
  - Thả tệp lẻ `a.pdf` cùng lúc với thư mục `X/` chứa `a.pdf` (cả hai ra
    `relativePath=""`).
  Mục 4 bước 4 và Task 1 phải cập nhật theo phương án được chốt.
- **(e) Test chỉ dựa vào mock** — jsdom không có `FileSystemEntry`;
  giảm thiểu bằng test tay 4 trình duyệt (Task 6), không tự động hoá
  được hoàn toàn.
