# BUG-005 — `TrashPurgeService.purgeOne()` không xoá object SeaweedFS nếu còn document khác cùng `sha256` → object mồ côi vĩnh viễn (mỗi document có `storage_key` riêng)

> Phát hiện 30/09/2026 khi khảo sát feature `folder-upload-display-name`
> (SA R12, BA Ngoài phạm vi). Mới đọc code + SA, **chưa tái hiện trên
> stg/DB**. Feature trên bỏ dedup theo tên nên bản cùng `sha256` sẽ phổ biến
> hơn nhiều → bug này sẽ lộ rõ hơn sau khi feature go live.

- **Trạng thái:** 🔴 Chưa fix, chưa tái hiện.
- **Mức độ nghiêm trọng:** Thấp–Trung bình. Không mất dữ liệu người dùng;
  chỉ rò rỉ dung lượng lưu trữ (object không còn document nào trỏ tới) và
  object của tài liệu đã purge vẫn còn đọc được nếu biết key (trái kỳ vọng
  xoá cứng ở NFR-09).

## 1. Hiện tượng (suy ra từ code)

`backend/src/main/java/com/aismartstore/backend/trash/purge/TrashPurgeService.java`
(~dòng 205-212):

```java
String storageKey = document.getStorageKey();
String sha256 = document.getSha256();
if (storageKey != null
        && sha256 != null
        && !documentRepository.existsBySha256AndIdNotAndStatusNot(sha256, documentId, DocumentStatus.PURGED)) {
    objectStorageService.purgeWarehouseObject(storageKey);
}
```

Object chỉ bị xoá khi **không còn document nào khác (chưa PURGED) cùng
`sha256`**. Kịch bản:

1. Tải `scan.pdf` hai lần (hoặc hai người tải cùng nội dung) → 2 document A, B
   cùng `sha256`, `storage_key` khác nhau (`documents/<UUID>` mỗi lần).
2. Xoá A vào thùng rác, đến hạn purge: B còn sống nên điều kiện sai →
   **object của A không bị xoá**, nhưng A được rỗng hoá (`storage_key = null`,
   status `PURGED`).
3. Không còn hàng nào trỏ tới key của A → object mồ côi, không job nào dọn.

## 2. Nguyên nhân gốc

Comment trong code ghi "dùng chung sha256 (dedup)", tức giả định nhiều
document dùng chung **một object**. Thực tế ingest luôn `putObject` với key
mới `documents/<UUID.randomUUID()>` (kho cá nhân `<sub>/<UUID>`) →
**1 document – 1 object**, kể cả cùng `sha256`; `findBySha256AndStatus` chỉ
phục vụ cảnh báo `duplicatesBySha`, không tái sử dụng object (SA
`folder-upload-display-name.md` mục 1). Điều kiện kiểm theo `sha256` vì vậy
sai đối tượng: cần kiểm theo **`storage_key`**.

## 3. Hướng sửa đề xuất

- Đổi điều kiện: xoá object trừ khi còn document khác (chưa PURGED) có cùng
  **`storage_key`**. Nếu xác nhận không bao giờ dùng chung key thì bỏ hẳn
  điều kiện và luôn xoá.
- Kiểm tra mọi luồng có thể tái sử dụng key (from-personal → kho chung, nhân
  bản, khôi phục) trước khi bỏ điều kiện.
- Test: 2 document cùng `sha256` khác `storage_key` → purge A xoá object A,
  giữ object B; IT với SeaweedFS/mock verify `purgeWarehouseObject` được gọi.
- Cân nhắc job dọn object mồ côi cho dữ liệu đã purge trước đó (đối chiếu
  key trong bucket `documents` với `document.storage_key`).

## 4. Việc cần xác minh trước khi sửa

- [ ] Tái hiện trên stg: 2 document cùng `sha256`, purge 1 → object còn trong
  SeaweedFS (nhớ path-style URL, xem memory SeaweedFS S3 path).
- [ ] Có luồng nào gán cùng `storage_key` cho >1 document không.
- [ ] Ước lượng số object mồ côi hiện có trên stg/prod.
