-- backend-multi-instance Task 8 (AC-9): thoi diem gan nhat embed.requested duoc publish cho tai lieu.
-- Reconciliation semantic bo qua tai lieu vua duoc yeu cau (cua so an han) thay vi yeu cau lai khi message van trong q.embed.
-- Additive, nullable: instance cu van chay duoc. Khong can index (loc trong trang da doc).
ALTER TABLE document ADD COLUMN embed_requested_at timestamptz NULL;
