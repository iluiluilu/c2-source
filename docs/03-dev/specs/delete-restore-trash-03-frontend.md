# Tech Spec: delete-restore-trash-03-frontend

> File: `docs/03-dev/specs/delete-restore-trash-03-frontend.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/delete-restore-trash-03-frontend.md`
> Status: Approved

**Đã kiểm tra trước khi viết:** toàn bộ backend của cả 4 story nghiệp vụ đã
implement + test xong (`backend/src/main/java/com/aismartstore/backend/trash/**`,
`TrashController`, `TrashService`, DTO ở `trash/dto/*.java`, 2 endpoint mutation
`POST /documents/trash`/`trash-preview` ở `DocumentsController` +
`documents/dto/TrashDto.java`/`TrashPreviewDto.java`, `trashBytes` ở
`WarehouseCapacityService.Snapshot`) — spec này **không sửa backend**, chỉ
đối chiếu đúng field/shape thật vào phần frontend. Đây là **frontend thuần**
(`web/`), tái dùng 100% quy ước hiện có: Next.js App Router, Client Component
(`'use client'`), Tailwind CSS thuần (không thư viện UI), `apiJson`/`apiFetch`/
`ApiError` (`@/lib/api`), `AppShell`/`AdminTabs`, `useSession()`/`hasRole()`/
`canSeeFeature()`, `useConfirm()`/`ConfirmDialog`, `FolderTree` (đã có, dùng
lại cho bước chọn thư mục đích khi phục hồi thay vì viết cây thư mục mới).
Không thêm thư viện JS mới.

## 1. Tóm tắt kỹ thuật

- 2 route mới: `/trash` (Story 1, R1) và `/admin/trash-stats` (Story 4, R3,
  `hidden: true`, vào qua tab `AdminTabs`).
- 2 component mới dùng chung: `RestoreDialog` (Story 3 — xử lý đủ 8 case xung
  đột phục hồi, dùng cho cả phục hồi đơn lẻ từ `/trash` và xử lý từng item
  lỗi sau `restore-batch`) và `TrashDetailPanel` (Story 1 AC-6 — xem chi tiết,
  không bắt buộc để phục hồi).
- Sửa 2 file có sẵn cho Story 2 (nút "Xóa" + dialog xác nhận, dùng
  `useConfirm()` sẵn có — **không cần component mới**, vì nội dung dialog chỉ
  là text nhiều dòng, `ConfirmDialogView` đã hỗ trợ `whitespace-pre-line`):
  `web/src/app/documents/page.tsx`, `web/src/app/documents/[id]/page.tsx`.
- Sửa 1 file có sẵn cho Story 5 AC-1/AC-2 (dòng "Trong đó, thùng rác" +
  cảnh báo tỷ trọng): `web/src/app/admin/folders/page.tsx`. AC-3 (banner ở
  `/documents` cho R1) **đã bỏ khỏi phạm vi slug này** — xem Quyết định 3.
- Không có thay đổi backend, không có bảng/migration mới.

### Các quyết định DEV tự chốt (thay cho 4 câu hỏi mở của BA)

1. **N = 0 nhóm bị ảnh hưởng (Story 2 AC-3):** chốt theo đúng đề xuất của BA —
   **ẩn hẳn** dòng cảnh báo về nhóm khi tổng `activeGroupShareCount` của các
   tài liệu đã chọn bằng 0. Lý do: giữ dialog gọn, N=0 không có thông tin gì
   đáng cảnh báo thêm so với việc chỉ liệt kê tên tài liệu sắp xóa; không đổi
   sang phương án "luôn có 1 dòng" vì không có ích lợi UX rõ ràng để đánh đổi
   thêm 1 nhánh hiển thị.
2. **Response thật của `GET /trash/stats`** (đọc trực tiếp
   `TrashService.TrashStats`/`DocumentRepository.countByExpiryBucket`, mục
   2.3): backend đã trả **buckets có sẵn**, không phải danh sách thô — 5
   bucket cố định `expired`/`within_7_days`/`within_30_days`/`within_90_days`/
   `later` (biên đã chốt sẵn ở tầng backend, xem Javadoc
   `DocumentRepository#countByExpiryBucket`). Chốt: FE hiển thị **nguyên 5
   bucket này** (chỉ đổi nhãn sang tiếng Việt), **không** tự nhóm lại thành 3
   khoảng như BA đề xuất tạm — biên backend đã chọn (7/30/90 ngày) chi tiết
   và hợp lý hơn, tự nhóm lại ở FE chỉ tốn công và có thể lệch với ý backend.
3. **Story 5 AC-3 (banner dung lượng kho ở `/documents` cho R1):** **đã xác
   nhận với người dùng — bỏ hẳn khỏi slug này** (không phải Task 12
   nice-to-have như đề xuất ban đầu). Lý do người dùng chọn: để
   `warehouse-structure` (FR-13) tự quyết định UI dung lượng kho cho
   `/documents` khi slug đó được implement, tránh 2 slug cùng chỉnh sửa cùng
   1 trang. `web/src/app/documents/page.tsx` trong spec này **chỉ sửa cho
   Story 2** (nút Xóa/dialog xác nhận), không đụng gì tới hiển thị dung
   lượng kho.
4. **Ngưỡng "≥20%" cảnh báo tỷ trọng thùng rác (Story 5 AC-2):** giữ nguyên
   theo đề xuất BA — không có dữ liệu vận hành thật để tính ngưỡng chính xác
   hơn, 20% là mức đủ cao để không gây nhiễu (noise) với biến động thùng rác
   thông thường nhưng đủ thấp để cảnh báo sớm trước khi `usedPercent` chạm
   85%/95%.

## 2. Data model

Không có bảng/migration Postgres mới. Tham chiếu nguyên trạng các DTO/record
thật đã tồn tại ở backend (không lặp lại logic, chỉ dẫn field cho FE dùng
đúng tên/kiểu):

### 2.1 `trash/TrashService` (nội bộ, lộ ra qua `TrashController`)

- `TrashItem(documentId, originalName, deletedAt, purgeAfter, size,
  deletedBySub, deletedByUsername, uploaderSub, uploaderUsername)` — 1 dòng
  của `GET /trash`.
- `TrashList(items: TrashItem[], total: long)` — response `GET /trash`.
- `TrashDetail(documentId, originalName, size, deletedAt, purgeAfter,
  deletedBySub, uploaderSub, folderId, folderMissing: boolean,
  suggestedFolderId, activeGroupShareCount: long, purgeState: string)` —
  response `GET /trash/{id}`.
- `RestoreResult(documentId, restoredFolderId, restoredName,
  groupsLostEffect: UUID[])` — response `POST /trash/{id}/restore` (200).
- `RestoreBatchItem(documentId, status: "RESTORED"|"CONFLICT"|"ERROR", code,
  restoredFolderId, groupsLostEffect)` — 1 phần tử `results[]` của
  `POST /trash/restore-batch`.
- `PreviewItem(documentId, activeGroupShareCount: long)` — 1 phần tử response
  `POST /documents/trash-preview`.
- `ExpiryBucket(bucket: string, count: long, bytes: long)` — `bucket` ∈
  `{"expired","within_7_days","within_30_days","within_90_days","later"}`
  (cố định, xem Quyết định 2).
- `UnrecoverableAccounts(count: long, nearestPurgeAfter: OffsetDateTime|null)`.
- `TrashStats(totalItems: long, totalBytes: long, byExpiryBucket:
  ExpiryBucket[], unrecoverableAccounts: UnrecoverableAccounts)` — response
  `GET /trash/stats`.

### 2.2 `documents/dto` (mutation, gắn ở `DocumentsController`)

- `TrashDto(documentIds: UUID[], reason: string|null)` — body
  `POST /documents/trash`.
- `TrashPreviewDto(documentIds: UUID[])` — body `POST /documents/trash-preview`.

### 2.3 `trash/dto` (phục hồi)

- `RestoreDto(targetFolderId: UUID|null, onNameConflict:
  "AUTO_RENAME"|"CANCEL"|null)` — body `POST /trash/{id}/restore` (cả 3 field
  optional; body cả object cũng optional — không gửi gì = phục hồi không đổi
  gì thêm).
- `RestoreBatchDto(documentIds: UUID[] [1..500], targetFolderId: UUID|null,
  onNameConflict: "AUTO_RENAME"|"CANCEL"|null)` — body
  `POST /trash/restore-batch`.

### 2.4 `warehouse/WarehouseCapacityService.Snapshot` (đã có, đã có sẵn field `trashBytes`)

`Snapshot(usedBytes, maxCapacityBytes, usedPercent, warningThresholdPercent,
blockThresholdPercent, status, trashBytes: long)` — response `GET/PATCH
/warehouse/capacity`. **`trashBytes` đã tồn tại ở backend nhưng CHƯA có trong
type FE `WarehouseCapacity`** (`web/src/lib/api.ts` dòng ~138-145, đã đọc,
đối chiếu) — đây là việc cần làm ở Task 1, không phải phát hiện mới cần sửa
backend.

### 2.5 Type mới thêm vào `web/src/lib/api.ts` (bổ sung vào file hiện có, đúng convention — không tạo file type riêng)

```ts
export interface TrashItem {
  documentId: string
  originalName: string
  deletedAt: string
  purgeAfter: string
  size: number
  deletedBySub: string
  deletedByUsername: string
  uploaderSub: string
  uploaderUsername: string
}

export interface TrashList {
  items: TrashItem[]
  total: number
}

export interface TrashDetail {
  documentId: string
  originalName: string
  size: number
  deletedAt: string
  purgeAfter: string
  deletedBySub: string
  uploaderSub: string
  folderId: string | null
  folderMissing: boolean
  suggestedFolderId: string | null
  activeGroupShareCount: number
  purgeState: string
}

export interface RestoreResult {
  documentId: string
  restoredFolderId: string
  restoredName: string
  groupsLostEffect: string[]
}

export interface RestoreBatchItem {
  documentId: string
  status: 'RESTORED' | 'CONFLICT' | 'ERROR'
  code: string | null
  restoredFolderId: string | null
  groupsLostEffect: string[] | null
}

export interface TrashPreviewItem {
  documentId: string
  activeGroupShareCount: number
}

export interface TrashExpiryBucket {
  bucket: 'expired' | 'within_7_days' | 'within_30_days' | 'within_90_days' | 'later'
  count: number
  bytes: number
}

export interface TrashStats {
  totalItems: number
  totalBytes: number
  byExpiryBucket: TrashExpiryBucket[]
  unrecoverableAccounts: { count: number; nearestPurgeAfter: string | null }
}

// WarehouseCapacity — thêm 1 field vào interface đã có (không đổi field khác)
export interface WarehouseCapacity {
  usedBytes: number
  maxCapacityBytes: number
  usedPercent: number
  warningThresholdPercent: number
  blockThresholdPercent: number
  status: WarehouseCapacityStatus
  trashBytes: number // MỚI
}
```

## 3. API contract

Toàn bộ endpoint đã tồn tại ở backend, không đổi request/response nào — bảng
dưới chỉ ánh xạ màn hình nào gọi endpoint nào (chi tiết field xem mục 2):

| Method | Endpoint | Dùng ở màn hình |
|---|---|---|
| GET | `/trash?view=own\|uploaded&limit=&offset=` | `/trash` — Story 1 |
| GET | `/trash/{id}` | `/trash` — Story 1 AC-6 (`TrashDetailPanel`) |
| GET | `/trash/stats` | `/admin/trash-stats` — Story 4 |
| POST | `/trash/{id}/restore` | `RestoreDialog` (đơn lẻ + xử lý item lỗi sau batch) — Story 3 |
| POST | `/trash/restore-batch` | `/trash` — Story 3 AC-9 (phục hồi hàng loạt) |
| POST | `/documents/trash` | `/documents`, `/documents/[id]` — Story 2 AC-5 |
| POST | `/documents/trash-preview` | `/documents`, `/documents/[id]` — Story 2 AC-2/AC-3 |
| GET | `/warehouse/capacity` | `/admin/folders` (đã gọi) — Story 5 AC-1/AC-2 |
| GET | `/folders/tree` | `RestoreDialog` (Story 3 AC-1 — chọn thư mục đích), đã dùng ở `/upload` |

Mã lỗi 409/410 dùng trong `RestoreDialog` (nguồn:
`delete-restore-trash-02-xoa-cung-xung-dot.md` §3, đối chiếu đúng với
`TrashService` mục "error bodies", không đổi):

| HTTP | `code` | Field kèm theo | Case (BA Story 3) |
|---|---|---|---|
| 409 | `FOLDER_MISSING` | `suggestedFolderId` (nullable) | AC-1 |
| 409 | `NAME_CONFLICT` | `suggestedName` | AC-2 |
| 409 | `PURGING` | — | AC-8 |
| 410 | `GONE` | — | AC-7 |
| 400 | `TARGET_FOLDER_NOT_FOUND` | — | (chọn thư mục đã bị xóa giữa lúc mở dialog — hiếm, hiển thị lỗi chung, quay lại bước chọn thư mục) |

## 4. Luồng xử lý chính

### 4.1 Cấu trúc file/route (mới + sửa)

```
web/src/app/trash/page.tsx                         # MỚI — Story 1
web/src/app/admin/trash-stats/page.tsx              # MỚI — Story 4

web/src/components/trash/RestoreDialog.tsx          # MỚI — Story 3
web/src/components/trash/TrashDetailPanel.tsx       # MỚI — Story 1 AC-6

web/src/lib/api.ts                                  # SỬA — thêm type (mục 2.5)
web/src/lib/features.ts                             # SỬA — thêm 'trash', 'admin-trash-stats'
web/src/components/AdminTabs.tsx                    # SỬA — thêm tab 'trash-stats'

web/src/app/documents/page.tsx                      # SỬA — Story 2 (nút Xóa) — KHÔNG đụng Story 5
web/src/app/documents/[id]/page.tsx                  # SỬA — Story 2 (nút Xóa)
web/src/app/admin/folders/page.tsx                   # SỬA — Story 5 AC-1/AC-2
```

### 4.2 `features.ts` — 2 mục mới

```ts
{
  key: 'trash',
  label: 'Thùng rác',
  href: '/trash',
  icon: 'trash',
  roles: [Role.CAN_BO],
  enabled: true,
  deniedHint: 'Thùng rác dành cho Cán bộ (R1).',
},
{
  key: 'admin-trash-stats',
  label: 'Thùng rác',
  href: '/admin/trash-stats',
  icon: 'admin',
  roles: [Role.QUAN_TRI],
  enabled: true,
  hidden: true, // vào qua tab AdminTabs, giống 'admin-document-ownership'
  deniedHint: 'Thống kê thùng rác dành cho Quản trị hệ thống (R3).',
},
```

`AdminTabs.tsx`: thêm `{ key: 'trash-stats', label: 'Thùng rác', href:
'/admin/trash-stats' }` vào mảng `ADMIN_TABS` (sau `document-ownership`).

**Icon `trash` — đã xác nhận với người dùng: thêm icon thùng rác thật**
(không dùng tạm icon `documents`, tránh nhầm lẫn 2 mục sidebar). Chưa có key
`trash` trong `NavIcon`'s `paths` map (`web/src/components/AppShell.tsx`,
~dòng 13-23) — nếu không thêm, `paths['trash']` là `undefined` và component
tự fallback về `paths.dashboard` (icon 4 ô vuông), **không** phải icon
`documents` như từng nhắc tạm ở bản nháp trước. Thêm 1 dòng vào `paths`:

```ts
trash: 'M3 6h18M8 6V4a2 2 0 012-2h4a2 2 0 012 2v2m3 0v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6h14zM10 11v6M14 11v6',
```

(path thùng rác chuẩn — nắp + thân + 2 gạch dọc bên trong, cùng phong cách
stroke-outline 24x24 với các icon khác trong map).

### 4.3 Story 1 — `/trash` (danh sách, 2 tab)

- State: `view: 'own'|'uploaded'` (mặc định `'own'`, đổi tab → `useEffect`
  phụ thuộc `view` gọi lại `GET /trash?view=...&limit=&offset=`), `limit`
  (mặc định 50), `offset` (reset về 0 khi đổi `view`), `rows: TrashList |
  null`, `selectedIds: Set<string>` (checkbox chọn nhiều, reset khi đổi
  `view`/trang).
- Cột bảng: tên (`originalName`), ngày xóa (`deletedAt`,
  `toLocaleString('vi-VN')` — đúng pattern `documents/page.tsx`), hạn xóa
  cứng (`purgeAfter` — hàm `formatRelativeDays()` mới, ví dụ "còn 12 ngày";
  `text-red-600 font-semibold` khi số ngày còn lại ≤ 30, tính từ
  `new Date(purgeAfter).getTime() - Date.now()`), dung lượng (`formatSize()`
  — copy chữ ký `formatSize(bytes: number)` từ `admin/folders/page.tsx`
  (KHÔNG phải bản `formatSize(size: string)` ở `documents/page.tsx` —
  `TrashItem.size` là `number`, không phải `string` như `DocumentRow.size`;
  copy nhầm bản sẽ lỗi kiểu TypeScript), không có module dùng chung sẵn cho
  hàm này nên copy trực tiếp, đúng cách đang làm ở `documents/[id]/page.tsx`
  cũng tự định nghĩa lại). `view === 'own'`: ẩn cột
  "Người xóa"; `view === 'uploaded'`: thêm cột "Người xóa"
  (`deletedByUsername`).
- Rỗng: `rows.items.length === 0` → thông báo theo `view` (AC-4): `view ===
  'own' ? 'Thùng rác của bạn đang trống.' : 'Không có tài liệu nào bạn nạp bị
  người khác xóa.'` thay cho bảng trống.
- Nút "Phục hồi" mỗi dòng → mở `RestoreDialog` với `mode: 'single'`,
  `documentId`, gọi ngay `POST /trash/{id}/restore` (body rỗng) khi mount —
  xem 4.5.
- Checkbox + "Phục hồi đã chọn" → gọi trực tiếp `POST /trash/restore-batch`
  với `documentIds: [...selectedIds]` (không mở dialog trước, không truyền
  `targetFolderId`/`onNameConflict`) → nhận `results: RestoreBatchItem[]` →
  hiện **báo cáo tổng hợp** (component nội tuyến trong `trash/page.tsx`, không
  tách file riêng vì chỉ dùng ở đây): số `RESTORED`, danh sách item
  `CONFLICT`/`ERROR` kèm nút "Xử lý" mở `RestoreDialog` với `mode: 'single'`,
  `documentId`, **`initialError: { status, code, body }`** đã biết sẵn (không
  gọi lại `restore` lần nữa để lấy lỗi — dùng thẳng kết quả đã có từ
  `restore-batch`, tiết kiệm 1 round-trip). Sau khi báo cáo xong, gọi lại
  `GET /trash` để danh sách tự cập nhật, loại bỏ item `RESTORED` (AC-9).
- Click dòng (ngoài vùng nút/checkbox) → mở `TrashDetailPanel` với
  `documentId` (AC-6).
- Phân trang: nút "Trang trước/sau" tính từ `offset`/`limit`/`total`, đúng
  pattern nút prev/next đã có ở `admin/audit/page.tsx` (khác ở chỗ dùng
  `limit`/`offset` thay vì `page`/`size` — khớp đúng tham số `GET /trash`
  thật, không tự đổi sang `page`/`size` chỉ vì `admin/audit` dùng vậy).

### 4.4 Story 2 — Dialog xác nhận xóa (sửa `documents/page.tsx`, `documents/[id]/page.tsx`)

Cả 2 nơi dùng chung 1 hàm cục bộ `confirmAndTrash(documentIds: string[],
names: string[])` (định nghĩa riêng ở mỗi file — không tách thành hook dùng
chung vì logic ngắn (~25 dòng) và state cục bộ 2 nơi khác nhau, đúng mức độ
hiện có của repo, ví dụ `download()` cũng được định nghĩa lặp lại ở nhiều nơi
thay vì factor ra):

```ts
async function confirmAndTrash(documentIds: string[], names: string[]) {
  let totalGroups: number | null = null;
  try {
    const preview = await apiJson<TrashPreviewItem[]>('/documents/trash-preview', session, {
      method: 'POST',
      body: JSON.stringify({ documentIds }),
    });
    totalGroups = preview.reduce((s, p) => s + p.activeGroupShareCount, 0);
  } catch {
    totalGroups = null; // AC-7: preview lỗi/chậm không chặn xóa
  }

  const nameLines = names.length > 10
    ? [...names.slice(0, 10), `và ${names.length - 10} tài liệu khác`]
    : names;
  let message = nameLines.join('\n');
  if (totalGroups === null) {
    message += '\n\nKhông tải được số nhóm bị ảnh hưởng.';
  } else if (totalGroups > 0) {
    message += `\n\n${totalGroups} nhóm sẽ mất quyền xem (các) tài liệu này.`;
  }
  // totalGroups === 0: không thêm dòng nào (Quyết định 1)

  const ok = await confirm({
    title: 'Xóa tài liệu',
    message,
    tone: 'danger',
    confirmLabel: 'Xóa',
  });
  if (!ok) return;

  try {
    await apiFetch('/documents/trash', session, {
      method: 'POST',
      body: JSON.stringify({ documentIds }),
    });
    setSuccessMessage(`Đã chuyển ${documentIds.length} tài liệu vào thùng rác.`);
    // AC-5: loại các id đã xóa khỏi state rows ngay, không reload trang
    setRows((prev) => prev?.filter((r) => !documentIds.includes(r.id)) ?? prev);
  } catch (e) {
    setError((e as Error).message); // AC-6: hiện lỗi rõ ràng, dialog đã đóng nhưng thao tác không thành công
  }
}
```

- `documents/page.tsx`: thêm cột checkbox (chỉ hiện khi `hasRole(session,
  Role.CAN_BO)` — R2 không có), nút "Xóa" mỗi dòng (chỉ R1) và nút "Xóa đã
  chọn" phía trên bảng khi `selectedIds.size > 0` (chỉ R1). `successMessage`
  hiển thị dạng banner xanh ngay dưới tiêu đề trang (đúng pattern
  `capacityMessage` ở `admin/folders/page.tsx`), kèm `<Link href="/trash">Xem
  thùng rác</Link>` (AC-5).
- `documents/[id]/page.tsx`: thêm nút "Xóa" (chỉ hiện khi `canWrite` —
  biến `hasRole(session, Role.CAN_BO)` đã có sẵn ở file này) cạnh các nút
  hành động khác; xóa thành công → `router.push('/documents')` sau khi hiện
  `successMessage` ngắn (giống pattern `digitization/runs/[runId]/page.tsx`
  dùng `setTimeout(() => router.push(...), 1500)`).
- AC-6 (lỗi khi xóa hàng loạt — tất cả-hoặc-không): backend đã tự đảm bảo
  atomic ở `TrashService.trash()` (toàn bộ nằm trong 1 `@Transactional`, ném
  lỗi ngay khi gặp `alreadyDeletedIds`/`forbiddenIds` đầu tiên, kèm
  `documentIds` gây lỗi trong body) — FE chỉ cần hiển thị `e.message` từ
  `ApiError`, backend đã trả đúng danh sách id gây lỗi trong
  `body.documentIds`, không cần tự suy luận lại.

### 4.5 Story 3 — `RestoreDialog` (8 case xung đột, dùng chung 1 component)

Props:

```ts
{
  documentId: string
  initialError?: { status: number; body: { code?: string; suggestedFolderId?: string | null; suggestedName?: string } }
  onClose: () => void
  onSuccess: (result: RestoreResult) => void
}
```

- Internal `step`: `'loading' | 'folder-picker' | 'name-conflict' | 'gone' |
  'purging' | 'error' | 'success'`.
- Mount: nếu có `initialError` → nhảy thẳng vào bước tương ứng (không gọi lại
  API — dùng đúng dữ liệu đã có từ `restore-batch`, xem 4.3). Nếu không có
  (mở từ nút "Phục hồi" 1 dòng trong `/trash`) → `step='loading'`, gọi ngay
  `POST /trash/{id}/restore` (body rỗng); `catch (e: ApiError)` → map
  `e.status`/`(e.body as {code}).code` sang `step` theo bảng mã lỗi ở mục 3;
  thành công (200) → `step='success'`, gọi `onSuccess(result)`.
- `step === 'folder-picker'` (AC-1): render `<FolderTree>` (đã có, dùng lại
  nguyên trạng, tự `apiJson<FolderNode[]>('/folders/tree', session)` khi vào
  bước này) — nếu `suggestedFolderId` khác `null`, set làm `selectedId` mặc
  định (đã chọn sẵn, người dùng đổi được); `suggestedFolderId === null` →
  không chọn sẵn, nút "Phục hồi" `disabled` tới khi `selectedId` khác
  `null`. Bấm "Phục hồi" → gọi lại `POST /trash/{id}/restore` với
  `targetFolderId: selectedId` → có thể tiếp tục nhận `NAME_CONFLICT` (chuyển
  sang `step='name-conflict'`) hoặc thành công.
- `step === 'name-conflict'` (AC-2): 3 lựa chọn dạng radio/button — (a) "Đổi
  tên tự động" hiện `suggestedName`, gọi lại restore với `onNameConflict:
  'AUTO_RENAME'` (giữ nguyên `targetFolderId` hiện tại nếu có); (b) "Chọn thư
  mục khác" → `step='folder-picker'` (quay lại, không mất `suggestedFolderId`
  ban đầu vẫn hiển thị nếu còn phù hợp); (c) "Hủy" → `onClose()`. Không có
  lựa chọn ghi đè.
- `step === 'gone'` (AC-7): thông báo cố định (khớp nguyên văn
  `TrashService.gone()` message hoặc rút gọn tương đương), chỉ có nút "Đóng".
- `step === 'purging'` (AC-8): thông báo cố định (khớp `TrashService.purging()`
  message), chỉ nút "Đóng", **không tự động retry**.
- `step === 'success'`: nếu `result.groupsLostEffect.length > 0` (AC-6) —
  hiện banner cảnh báo (không phải lỗi) "Phục hồi thành công.
  {N} nhóm từng chia sẻ tài liệu này đã bị xóa nên không còn hiệu lực chia
  sẻ."; ngược lại chỉ "Phục hồi thành công." — cả 2 trường hợp đều tự đóng
  dialog sau khi người dùng bấm "Đóng" (không tự động đóng, để người dùng kịp
  đọc cảnh báo AC-6, khác với toast tự ẩn của Story 2).
- `step === 'error'`: case `TARGET_FOLDER_NOT_FOUND`/lỗi khác — thông báo
  chung + nút quay lại `folder-picker`.

### 4.6 `TrashDetailPanel` (Story 1 AC-6)

Panel/dialog đơn giản (không dùng `useConfirm`, chỉ 1 nút "Đóng" + nút "Phục
hồi" mở `RestoreDialog` luôn tiện — không bắt buộc theo AC-6 nhưng hợp lý để
không phải đóng panel rồi tìm lại nút "Phục hồi" ở bảng): fetch `GET
/trash/{id}` khi mount, hiển thị toàn bộ field `TrashDetail`; nếu
`folderMissing === true` hiện ghi chú "Thư mục gốc đã bị xóa" +
(`suggestedFolderId` khác null → tên thư mục gợi ý, cần gọi thêm
`GET /folders/tree` để tra tên từ id, hoặc bỏ qua tên chỉ hiện id rút gọn nếu
không muốn gọi thêm API — chọn cách sau cho đơn giản, không bắt buộc UX đẹp ở
panel xem-thêm này).

### 4.7 Story 4 — `/admin/trash-stats`

- `AppShell` + `<AdminTabs active="trash-stats" />` (đúng khuôn
  `admin/document-ownership/page.tsx`), route guard qua `canSeeFeature(session,
  'admin-trash-stats')`.
- Gọi `GET /trash/stats` khi mount, hiển thị:
  - Tổng số item (`totalItems`) + tổng dung lượng (`formatSize(totalBytes)`).
  - Bảng 5 dòng từ `byExpiryBucket` (Quyết định 2) — nhãn tiếng Việt cố định:
    `expired` → "Đã quá hạn", `within_7_days` → "Trong 7 ngày tới",
    `within_30_days` → "Trong 30 ngày tới", `within_90_days` → "Trong 90 ngày
    tới", `later` → "Sau 90 ngày" — mỗi dòng: `count` + `formatSize(bytes)`.
    Bucket không có trong response (mảng có thể thiếu bucket rỗng, tùy
    `GROUP BY` thật) → hiển thị `0`.
  - Khối riêng cho `unrecoverableAccounts` (AC-3): `count` + chú thích ngắn
    "Cả người xóa lẫn người nạp gốc đều đã bị khóa/vô hiệu hóa" +
    `nearestPurgeAfter` (nếu khác `null`, định dạng ngày tuyệt đối, đây KHÔNG
    phải tương đối như `/trash` vì không có tên tài liệu cụ thể để gắn cảnh
    báo màu vào — chỉ là 1 con số thống kê).
- **Không** có link/nút nào dẫn tới `/trash/{id}` hay bất kỳ tên
  tệp/đường dẫn nào (AC-4/AC-5) — không tự gọi thêm API nào khác ngoài `GET
  /trash/stats`.
- Không vẽ chart (Out of scope Story 4) — chỉ bảng số liệu Tailwind thuần.

### 4.8 Story 5 — `trashBytes` ở màn hình kho

- `admin/folders/page.tsx` (AC-1/AC-2): ngay dưới thanh tiến trình hiện có
  (đã đọc, dòng ~205-225), thêm 1 dòng `text-sm text-gray-600`: "Trong đó,
  thùng rác: {formatSize(capacity.trashBytes)}". AC-2: nếu
  `capacity.trashBytes / capacity.usedBytes >= 0.2` (Quyết định 4, guard
  `usedBytes > 0` để tránh chia 0) → thêm 1 dòng gợi ý màu vàng nhạt ngay
  dưới, tách biệt với khối `CAPACITY_STATUS_STYLE` hiện có (không đổi logic
  85%/95% đã có): "Phần lớn dung lượng đang dùng là thùng rác, sẽ tự động
  giải phóng theo lịch xóa cứng." — không có nút "xóa cứng ngay".
- AC-3 (banner ở `/documents` cho R1) — **đã bỏ khỏi phạm vi slug này**
  (xác nhận người dùng, xem Quyết định 3 ở mục 1). Không có task nào cho
  `documents/page.tsx` liên quan tới `trashBytes`/dung lượng kho.

## 5. Test plan

Test hiện tại của `web/` dùng Vitest + Testing Library (`documents/__tests__`,
`upload/__tests__` là ví dụ) — viết test tương tự, không thêm công cụ mới:

- **`/trash` (`app/trash/__tests__/page.test.tsx`):**
  - Đổi tab `own`→`uploaded` gọi lại `GET /trash?view=uploaded...`, ẩn/hiện
    đúng cột "Người xóa" theo tab.
  - Danh sách rỗng hiện đúng thông báo theo từng tab (AC-4).
  - `purgeAfter` còn ≤ 30 ngày render class cảnh báo đỏ; > 30 ngày thì không.
  - Chọn nhiều dòng → "Phục hồi đã chọn" gọi đúng `POST /trash/restore-batch`
    với đúng `documentIds`; kết quả có item `CONFLICT` → hiện nút "Xử lý" mở
    `RestoreDialog` với `initialError` đúng, **không** gọi lại
    `POST /trash/{id}/restore` để lấy lỗi lần 2.
  - Sau báo cáo batch, danh sách loại bỏ đúng các `documentId` có
    `status === 'RESTORED'` (AC-9).
- **`RestoreDialog` (`components/trash/__tests__/RestoreDialog.test.tsx`):**
  - `FOLDER_MISSING` với `suggestedFolderId` khác null → chọn sẵn đúng node
    trong `FolderTree`, nút "Phục hồi" không bị `disabled`.
  - `FOLDER_MISSING` với `suggestedFolderId = null` → không chọn sẵn, nút
    "Phục hồi" `disabled` tới khi chọn 1 node.
  - `NAME_CONFLICT` → 3 lựa chọn hiện đúng, "Đổi tên tự động" gọi lại restore
    với `onNameConflict: 'AUTO_RENAME'`, "Chọn thư mục khác" quay về
    `folder-picker`, "Hủy" gọi `onClose`.
  - `GONE`/`PURGING` → chỉ có nút "Đóng", không có hành động khác.
  - Thành công với `groupsLostEffect` rỗng vs không rỗng → 2 nội dung banner
    khác nhau đúng theo AC-6.
- **Dialog xác nhận xóa (`documents/__tests__/page.test.tsx`, mở rộng):**
  - `totalGroups === 0` → dialog message **không** chứa dòng cảnh báo nhóm.
  - `totalGroups > 0` → message chứa đúng số N.
  - `POST /documents/trash-preview` reject → dialog vẫn mở được, message có
    dòng "Không tải được số nhóm bị ảnh hưởng", vẫn xác nhận xóa được (AC-7).
  - > 10 tài liệu chọn cùng lúc → message rút gọn đúng dạng "và N tài liệu
    khác" (AC-4).
  - Xóa thành công → `rows` state loại bỏ đúng id đã xóa, không gọi lại
    `GET /documents` (AC-5 — "không cần tải lại trang").
  - Nút "Xóa"/checkbox chỉ hiện khi `hasRole(session, Role.CAN_BO)` — test
    session R2 không thấy nút này dù thấy được `/documents` (AC-1).
- **`/admin/trash-stats` (`app/admin/trash-stats/__tests__/page.test.tsx`):**
  - Render đúng 5 dòng bucket theo đúng nhãn tiếng Việt cố định (mục 4.7),
    kể cả khi response thiếu 1 bucket (test mock thiếu `later`) → hiện `0`.
  - Không render bất kỳ `<a>`/`<Link>` nào trỏ tới `/trash/{id}` (AC-5).
  - `unrecoverableAccounts.count = 0` vẫn hiển thị khối này (không ẩn hẳn —
    khác Story 2 AC-3, vì đây là số liệu thống kê không phải cảnh báo động).
- **`admin/folders/page.tsx` (mở rộng test có sẵn):**
  - Dòng "Trong đó, thùng rác" render đúng `formatSize(trashBytes)`.
  - `trashBytes/usedBytes >= 0.2` → hiện gợi ý; `< 0.2` hoặc `usedBytes = 0`
    → không hiện, không lỗi chia 0.
- Không có E2E/Playwright trong repo hiện tại — không thêm ở spec này (đúng
  phạm vi, theo đúng 2 spec frontend tiền lệ).

## 6. Task breakdown

- [x] Task 1: `web/src/lib/api.ts` — thêm toàn bộ type mục 2.5 (bao gồm sửa
      `WarehouseCapacity` thêm `trashBytes`).
- [x] Task 2: `web/src/lib/features.ts` — thêm feature `trash` và
      `admin-trash-stats` (mục 4.2).
- [x] Task 3: `web/src/components/AdminTabs.tsx` — thêm tab `trash-stats`.
- [x] Task 4: `web/src/components/trash/RestoreDialog.tsx` — toàn bộ 8 case
      (mục 4.5) + unit test.
- [x] Task 5: `web/src/components/trash/TrashDetailPanel.tsx` (mục 4.6).
- [x] Task 6: `web/src/app/trash/page.tsx` — Story 1 đầy đủ (2 tab, bảng,
      phân trang, chọn nhiều + phục hồi hàng loạt, tích hợp `RestoreDialog`/
      `TrashDetailPanel`) + test.
- [x] Task 7: Sửa `web/src/app/documents/page.tsx` — Story 2 (checkbox, nút
      "Xóa"/"Xóa đã chọn", `confirmAndTrash`, banner thành công) + test.
- [x] Task 8: Sửa `web/src/app/documents/[id]/page.tsx` — Story 2 (nút
      "Xóa", `confirmAndTrash`, điều hướng sau khi xóa) + test.
- [x] Task 9: `web/src/app/admin/trash-stats/page.tsx` — Story 4 đầy đủ (mục
      4.7) + test.
- [x] Task 10: Sửa `web/src/app/admin/folders/page.tsx` — Story 5 AC-1/AC-2
      (dòng `trashBytes` + gợi ý ≥20%) + test.
- [x] Task 11: Toàn bộ test plan mục 5 chạy xanh (`npm run lint` +
      `npm run build` qua được typecheck, đúng cách đã làm ở 2 spec tiền lệ).
- [x] Task 12: `web/src/components/AppShell.tsx` — thêm icon `trash` vào
      `paths` map của `NavIcon` (path SVG mục 4.2), để mục "Thùng rác" trong
      sidebar không fallback về icon dashboard.

*(Không có task nào cho banner dung lượng kho ở `/documents` — AC-3 Story 5
đã bỏ khỏi phạm vi slug này, xác nhận người dùng, xem Quyết định 3.)*

## 7. Rủi ro kỹ thuật

- **`RestoreDialog` là component phức tạp nhất của slug này** (5+ bước trạng
  thái, 2 điểm gọi API lồng nhau folder→name-conflict) — rủi ro bug logic
  chuyển bước nếu không test đủ các nhánh mục 5; giảm thiểu bằng cách viết
  test cho từng `step` độc lập trước khi ghép vào `/trash`.
- **Không có toast/snackbar dùng chung trong `web/`** — Story 2 AC-5 dùng
  banner tĩnh trong trang (giống `capacityMessage` ở `admin/folders`) thay vì
  toast tự ẩn; nếu người dùng thực hiện nhiều lượt xóa liên tiếp nhanh,
  banner cũ bị ghi đè bởi banner mới ngay lập tức (chấp nhận được, không mất
  thông tin quan trọng — số liệu ở lượt trước không còn ý nghĩa sau lượt
  sau).
- **`byExpiryBucket` có thể thiếu bucket** nếu không có document nào rơi vào
  1 trong 5 nhóm (do `GROUP BY` chỉ trả nhóm có ít nhất 1 dòng) — FE bắt buộc
  merge với danh sách 5 bucket cố định phía client, mặc định `0` cho bucket
  thiếu (đã ghi rõ ở mục 4.7/5, nhưng dễ bị bỏ sót nếu implement vội).
- **`RestoreDialog` phụ thuộc `FolderTree` hiện có** (viết cho `/upload`,
  chưa từng dùng ở ngữ cảnh khác) — cần xác nhận lại khi implement rằng
  `FolderTree` không có giả định ngầm nào riêng cho luồng upload (ví dụ
  disable node nào đó) có thể gây sai lệch khi tái dùng ở luồng phục hồi.
