# -*- coding: utf-8 -*-
"""Sinh mock-seed.sql cho 4 tài liệu số hoá trên stg (tài khoản canbo1)."""
import hashlib, json, os

CANBO1 = "c09d17da-4b1a-4c34-ae5c-7427af318e57"
CANBO1_U = "canbo1"
LANHDAO1 = "52881bc2-d3d0-4b62-962d-b2232bd66724"
LANHDAO1_U = "canbo_lanhdao1"
P = "00000000-0000-4000-8000-"

G1, G2 = P+"000000000001", P+"000000000002"
F0, F1, F2, F3, F4 = (P+"0000000000f0", P+"0000000000f1", P+"0000000000f2",
                      P+"0000000000f3", P+"0000000000f4")
DOC = {k: P+"0000000000a"+n for k, n in (("A","1"),("B","2"),("C","3"),("D","4"))}
KEY = {k: P+"0000000000b"+n for k, n in (("A","1"),("B","2"),("C","3"),("D","4"))}
RUN = {k: P+"0000000000c"+n for k, n in (("A","1"),("B","2"),("C","3"),("D","4"))}
BT1, BT2 = P+"0000000000d1", P+"0000000000d2"
def seg_id(doc, i): return P+"0000000e%s%03d" % ({"A":"1","B":"2","C":"3","D":"4"}[doc], i)

def q(s): return "'" + s.replace("'", "''") + "'"
def fmeta(path):
    b = open(path, "rb").read()
    return len(b), hashlib.sha256(b).hexdigest()

SIZE, SHA = {}, {}
for k, f in (("A","doc-a.pdf"),("B","doc-b.m4a"),("C","doc-c.pdf"),("D","doc-d.m4a")):
    SIZE[k], SHA[k] = fmeta(f)

TL = json.load(open("audio-timeline.json"))

# ---- text các đoạn -------------------------------------------------------
OCR_A = [
 # (page, line_from, line_to, ai_text, current_text, conf)
 (1, 1, 9,
  "CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM. Độc lập - Tự do - Hạnh phúc. "
  "HỢP ĐỒNG THUÊ NHÀ. Số: 07/2026/HĐTN. Hôm nay, ngày 10 tháng 9 năm 2026, "
  "tại Phòng Hành chính - Tổng hợp, chúng tôi gồm có:", None, 0.972),
 (1, 10, 18,
  "BÊN CHO THUÊ (Bên A): Ông Nguyễn Văn An, sinh năm 1978, CCCD số 001078004512 "
  "do Cục Cảnh sát quản lý hành chính về trật tự xã hội cấp ngày 12 tháng 3 năm 2021, "
  "địa chỉ thường trú tại số 145 đường Trần Hưng Đao, phường Cửa Nam, quận Hoàn Kiếm, "
  "thành phố Hà Nội. BÊN THUÊ (Bên B): Bà Phạm Lan Hương, sinh năm 1985, "
  "CCCD số 001185OO7733, địa chỉ thường trú tại số 28 ngõ 91 đường Nguyễn Chí Thanh, "
  "phường Láng Hạ, quận Đống Đa, thành phố Hà Nội.",
  "BÊN CHO THUÊ (Bên A): Ông Nguyễn Văn An, sinh năm 1978, CCCD số 001078004512 "
  "do Cục Cảnh sát quản lý hành chính về trật tự xã hội cấp ngày 12 tháng 3 năm 2021, "
  "địa chỉ thường trú tại số 145 đường Trần Hưng Đạo, phường Cửa Nam, quận Hoàn Kiếm, "
  "thành phố Hà Nội. BÊN THUÊ (Bên B): Bà Phạm Lan Hương, sinh năm 1985, "
  "CCCD số 001185007733, địa chỉ thường trú tại số 28 ngõ 91 đường Nguyễn Chí Thanh, "
  "phường Láng Hạ, quận Đống Đa, thành phố Hà Nội.", 0.814),
 (1, 19, 26,
  "Điều 1. Đối tượng của hợp đồng. Bên A đồng ý cho Bên B thuê toàn bộ diện tích "
  "tầng một của căn nhà số 145 đường Trần Hưng Đạo, tổng diện tích sử dụng là 82 mét vuông, "
  "bao gồm một phòng khách, hai phòng làm việc và khu phụ khép kín. "
  "Điều 2. Thời hạn thuê. Thời hạn thuê là 24 tháng, tính từ ngày 01 tháng 10 năm 2026 "
  "đến hết ngày 30 tháng 9 năm 2028. Khi hết thời hạn, nếu Bên B có nhu cầu tiếp tục thuê "
  "thì phải thông báo bằng văn bản cho Bên A trước ít nhất 30 ngày.", None, 0.961),
 (2, 1, 12,
  "Điều 3. Giá thuê và phương thức thanh toán. Giá thuê là 18.500.000 đồng một tháng, "
  "chưa bao gồm tiền điện, tiền nước và phí dịch vụ. Bên B thanh toán theo quý, "
  "chậm nhất vào ngày 05 của tháng đầu mỗi quý, bằng hình thức chuyển khoản vào tài khoản "
  "của Bên A mở tại Ngân hàng Thương mại cổ phần Ngoại thương Việt Nam, chi nhánh Hoàn Kiếm. "
  "Điều 4. Tiền đặt cọc. Bên B đặt cọc cho Bên A số tiền 37.000.000 đồng, "
  "tương đương hai tháng tiền thuê, ngay tại thời điểm ký hợp đồng. "
  "Khoản tiền đặt cọc được hoàn trả đầy đủ cho Bên B khi kết thúc hợp đồng, "
  "sau khi trừ đi các khoản bồi thường thiệt hại (nếu có).", None, 0.958),
 (2, 13, 24,
  "Điều 5. Quyền và nghĩa vụ của các bên. Bên A có trách nhiệm bàn giao nhà đúng hiện trạng "
  "đã thỏa thuận và bảo đảm quyền sử dụng ổn định cho Bên B trong suốt thời hạn thuê. "
  "Bên B có trách nhiệm sử dụng nhà đúng mục đích làm văn phòng, giữ gìn tài sản, "
  "không tự ý cải tạo kết cấu khi chưa có sự đồng ý bằng văn bản của Bên A. "
  "Điều 6. Điều khoản thi hành. Hợp đồng này có hiệu lực kể từ ngày ký, "
  "được lập thành 04 bản có giá trị pháp lý như nhau, mỗi bên giữ 02 bản. "
  "Mọi tranh chấp phát sinh sẽ được hai bên thương lượng giải quyết; nếu không thương lượng được "
  "thì đưa ra Tòa án nhân dân có thẩm quyền. BÊN CHO THUÊ (Ký, ghi rõ họ tên) Nguyễn Văn An. "
  "BÊN THUÊ (Ký, ghi rõ họ tên) Phạm Lan Hương.", None, 0.934),
]

OCR_C = [
 (1, 1, 8,
  "ỦY BAN NHÂN DÂN THÀNH PHỐ HÀ NỘI. SỞ THÔNG TIN VÀ TRUYỀN THÔNG. "
  "BÁO CÁO TỔNG KẾT CÔNG TÁC QUÝ III NĂM 2026. "
  "Số: 214/BC-STTTT ngày 16 tháng 9 năm 2026.", None, 0.949),
 (1, 9, 16,
  "I. TÌNH HÌNH CHUNG. Trong quý III năm 2026, Sở đã bám sát chương trình công tác năm, "
  "tập trung triển khai các nhiệm vụ trọng tâm về chuyển đổi số, số hóa tài liệu lưu trữ "
  "và bảo đảm an toàn thông tin mạng cho hệ thống dùng chung của thành phố.", None, 0.931),
 (1, 17, 30,
  "II. KẾT QUẢ THỰC HIỆN NHIỆM VỤ. Tổng số văn bản đến trong quý là l.284 văn bản, "
  "đã xử lý đúng hạn 1.25l văn bản, đạt tỷ lệ 97,4 phần trăm. Công tác số hóa tài liệu "
  "đạt 18.64O trang, vượt l2 phần trăm so với chỉ tiêu quý. Hệ thống nhận dạng ký tự quang học "
  "được đưa vào vận hành thử nghiệm từ tháng 8, bước đầu cho kết quả khả quan. "
  "Chỉ tiêu | Kế hoạch | Thực hiện | Tỷ lệ. Số hóa tài liệu (trang) | l6.600 | 18.640 | ll2,3%. "
  "Xử lý văn bản đến | 1.284 | 1.251 | 97,4%. Hồ sơ dịch vụ công trực tuyến | 7.5OO | 8.112 | 108,2%.",
  None, 0.427),
 (2, 1, 10,
  "III. TỒN TẠI, HẠN CHẾ. Chất lượng ảnh quét của một số tài liệu cũ trước năm 1995 còn thấp, "
  "dẫn tới độ chính xác nhận dạng chưa đạt yêu cầu, phải bố trí cán bộ kiểm tra và hiệu đính thủ công. "
  "Nhân lực chuyên trách về an toàn thông tin còn mỏng, mới đáp ứng khoảng 60 phần trăm "
  "khối lượng công việc thực tế.", None, 0.918),
 (2, 11, 22,
  "IV. PHƯƠNG HƯỚNG QUÝ IV NĂM 2026. Hoàn thành số hóa toàn bộ khối tài liệu lưu trữ "
  "giai đoạn 1990 đến 2000; nâng cấp hạ tầng lưu trữ đối tượng và cụm tìm kiếm toàn văn; "
  "tổ chức hai lớp tập huấn nghiệp vụ kiểm duyệt kết quả số hóa cho cán bộ các phòng, ban; "
  "rà soát và ban hành quy chế quản lý dữ liệu số của Sở trước ngày 15 tháng 12 năm 2026. "
  "GIÁM ĐỐC (Đã ký) Trần Minh Tuấn.", None, 0.905),
]

STT_CONF = {"B": [0.946, 0.921, 0.938], "D": [0.912, 0.887, 0.933]}
SPEAKER = {"B": ["SPEAKER_00", "SPEAKER_00", "SPEAKER_00"],
           "D": ["SPEAKER_00", "SPEAKER_01", "SPEAKER_00"]}

out = []
w = out.append
w("-- ============================================================================")
w("-- Mock dữ liệu số hoá cho stg - tài khoản canbo1")
w("--   Kịch bản 1 (đã duyệt, chia sẻ nhóm, tìm kiếm được): tài liệu A (OCR) + B (audio/STT)")
w("--   Kịch bản 2 (số hoá xong, chờ user duyệt):           tài liệu C (OCR) + D (audio/STT)")
w("-- Sinh tự động bởi gen_seed.py - chạy 1 lần, idempotent qua ON CONFLICT DO NOTHING.")
w("-- ============================================================================")
w("")
w("BEGIN;")
w("")
w("-- 1. Nhóm --------------------------------------------------------------------")
w("INSERT INTO \"group\" (id, name, description, owner_sub, created_at, updated_at) VALUES")
w("    (%s, 'Phòng Hành chính - Tổng hợp', 'Nhóm mock cho dữ liệu số hoá', %s, now(), now())," % (q(G1), q(CANBO1)))
w("    (%s, 'Ban Giám đốc', 'Nhóm mock cho dữ liệu số hoá', %s, now(), now())" % (q(G2), q(LANHDAO1)))
w("ON CONFLICT (id) DO NOTHING;")
w("")
w("INSERT INTO group_member (group_id, user_sub, role_in_group, joined_at) VALUES")
w("    (%s, %s, 'owner',  now())," % (q(G1), q(CANBO1)))
w("    (%s, %s, 'member', now())," % (q(G1), q(LANHDAO1)))
w("    (%s, %s, 'owner',  now())," % (q(G2), q(LANHDAO1)))
w("    (%s, %s, 'member', now())" % (q(G2), q(CANBO1)))
w("ON CONFLICT (group_id, user_sub) DO NOTHING;")
w("")
w("-- 2. Thư mục kho -------------------------------------------------------------")
w("INSERT INTO warehouse_folder (id, name, parent_id, path_ids, depth, created_by_sub, created_by_username, created_at, updated_at) VALUES")
# CK ck_warehouse_folder_name_format: chỉ [A-Za-z0-9 _-], nên tên thư mục phải ASCII.
rows = [(F0, "kho_so_hoa_mock",  None, "{}", 1),
        (F1, "hop_dong",         F0,   "{%s}" % F0, 2),
        (F2, "ghi_am_cuoc_hop",  F0,   "{%s}" % F0, 2),
        (F3, "bao_cao",          F0,   "{%s}" % F0, 2),
        (F4, "phong_van",        F0,   "{%s}" % F0, 2)]
for i, (fid, name, parent, path, depth) in enumerate(rows):
    w("    (%s, %s, %s, %s::uuid[], %d, %s, %s, now(), now())%s" % (
        q(fid), q(name), q(parent) if parent else "NULL", q(path), depth,
        q(CANBO1), q(CANBO1_U), "," if i < len(rows)-1 else ""))
w("ON CONFLICT (id) DO NOTHING;")
w("")
w("-- 3. Nhãn chủ đề (doc_type đã seed sẵn từ V20) --------------------------------")
w("INSERT INTO tag (kind, label, created_by_sub, created_by_username, created_at, active) VALUES")
topics = ["Hợp đồng", "Họp giao ban", "Báo cáo định kỳ", "Tuyển dụng"]
for i, t in enumerate(topics):
    w("    ('topic', %s, %s, %s, now(), true)%s" % (q(t), q(CANBO1), q(CANBO1_U), "," if i < len(topics)-1 else ""))
w("ON CONFLICT (kind, lower(label)) DO NOTHING;")
w("")

DOCS = [
  # key, name, mime, folder, doc_type tag, topic tag, ngày nạp
  ("A", "Hợp đồng thuê nhà số 07-2026-HĐTN.pdf", "application/pdf", F1, "văn bản",  "Hợp đồng",        "2026-09-10T08:15:00+07:00"),
  ("B", "Ghi âm cuộc họp giao ban 15-09-2026.m4a", "audio/x-m4a",   F2, "âm thanh", "Họp giao ban",    "2026-09-15T14:40:00+07:00"),
  ("C", "Báo cáo tổng kết quý III năm 2026.pdf",  "application/pdf", F3, "văn bản",  "Báo cáo định kỳ", "2026-09-17T09:05:00+07:00"),
  ("D", "Ghi âm phỏng vấn cán bộ 17-09-2026.m4a", "audio/x-m4a",    F4, "âm thanh", "Tuyển dụng",      "2026-09-17T15:20:00+07:00"),
]

w("-- 4. Tài liệu ----------------------------------------------------------------")
w("INSERT INTO document (id, original_name, mime_type, size, sha256, storage_key, folder_id,")
w("                      uploader_sub, uploader_username, status, scan_result, created_at,")
w("                      relative_path, purge_state) VALUES")
for i, (k, name, mime, folder, dt, tp, created) in enumerate(DOCS):
    scan = ('{"engine":"clamav","result":"clean","scannedAt":"%s"}' % created)
    w("    (%s, %s, %s, %d, %s, %s, %s, %s, %s, 'clean', %s::jsonb, %s, '', 'active')%s" % (
        q(DOC[k]), q(name), q(mime), SIZE[k], q(SHA[k]), q("documents/"+KEY[k]), q(folder),
        q(CANBO1), q(CANBO1_U), q(scan), q(created), "," if i < len(DOCS)-1 else ""))
w("ON CONFLICT (id) DO NOTHING;")
w("")
w("-- 5. Gắn nhãn ----------------------------------------------------------------")
for k, name, mime, folder, dt, tp, created in DOCS:
    for kind, label in (("doc_type", dt), ("topic", tp)):
        w("INSERT INTO document_tag (document_id, tag_id, assigned_by_sub, assigned_by_username, assigned_at, tag_kind)")
        w("SELECT %s, id, %s, %s, now(), kind FROM tag WHERE kind = %s AND lower(label) = lower(%s)" % (
            q(DOC[k]), q(CANBO1), q(CANBO1_U), q(kind), q(label)))
        w("ON CONFLICT DO NOTHING;")
w("")
w("-- 6. Chia sẻ nhóm (chỉ 2 tài liệu ĐÃ DUYỆT) ----------------------------------")
w("INSERT INTO document_group_share (document_id, group_id, shared_by_sub, shared_at) VALUES")
w("    (%s, %s, %s, now())," % (q(DOC["A"]), q(G1), q(CANBO1)))
w("    (%s, %s, %s, now())" % (q(DOC["B"]), q(G1), q(CANBO1)))
w("ON CONFLICT DO NOTHING;")
w("")
w("-- 7. digitization_source ------------------------------------------------------")
w("INSERT INTO digitization_source (id, document_id, component_type, file_path, mime_type,")
w("        dispatchable, dispatch_status, dispatch_attempt_count, dispatched_at, duration_ms, created_at, updated_at) VALUES")
for i, (k, name, mime, folder, dt, tp, created) in enumerate(DOCS):
    dur = TL["doc-b" if k == "B" else "doc-d"]["duration_ms"] if k in ("B", "D") else "NULL"
    w("    (%s, %s, 'original', %s, %s, true, 'published', 1, %s, %s, %s, %s)%s" % (
        q(DOC[k]), q(DOC[k]), q("documents/documents/" + KEY[k]), q(mime),
        q(created), dur, q(created), q(created), "," if i < len(DOCS)-1 else ""))
w("ON CONFLICT (id) DO NOTHING;")
w("")
w("-- 8. Lô số hoá ---------------------------------------------------------------")
w("INSERT INTO digitization_batch (id, label, total_count, succeeded_count, failed_count, created_by_sub, created_at, updated_at) VALUES")
w("    (%s, 'Lô số hoá minh hoạ 10-09-2026', 2, 2, 0, %s, %s, %s)," % (q(BT1), q(CANBO1), q("2026-09-10T08:20:00+07:00"), q("2026-09-15T15:10:00+07:00")))
w("    (%s, 'Lô số hoá minh hoạ 17-09-2026', 2, 2, 0, %s, %s, %s)"  % (q(BT2), q(CANBO1), q("2026-09-17T09:10:00+07:00"), q("2026-09-17T15:55:00+07:00")))
w("ON CONFLICT (id) DO NOTHING;")
w("")

RUNS = [
  # key, task, batch, model, ver, started, completed, in_q, out_q, low_conf, seg_count,
  # review_state, deadline, confirm_source, confirmed_at, edited, first_edit, blocked
  ("A", "ocr", BT1, "vietocr-transformer", "0.3.8",
   "2026-09-10T08:20:11+07:00", "2026-09-10T08:23:47+07:00", "ok", "ok", 1, 5,
   "confirmed", None, "manual_edited", "2026-09-11T10:42:30+07:00", 1, "2026-09-11T10:35:12+07:00", False),
  ("B", "stt", BT1, "whisper-large-v3", "20240930",
   "2026-09-15T14:45:03+07:00", "2026-09-15T14:47:58+07:00", "ok", "ok", 0, 3,
   "confirmed", None, "manual_unchanged", "2026-09-15T16:20:05+07:00", 0, None, False),
  ("C", "ocr", BT2, "vietocr-transformer", "0.3.8",
   "2026-09-17T09:10:22+07:00", "2026-09-17T09:14:05+07:00", "poor_scan", "poor", 1, 5,
   "pending", "+2 days", None, None, 0, None, True),
  ("D", "stt", BT2, "whisper-large-v3", "20240930",
   "2026-09-17T15:25:40+07:00", "2026-09-17T15:27:19+07:00", "ok", "ok", 0, 3,
   "pending", "+3 days", None, None, 0, None, False),
]
w("-- 9. digitization_run --------------------------------------------------------")
w("INSERT INTO digitization_run (id, document_id, source_id, task, batch_id, processing_status,")
w("        attempt_count, worker_id, model_name, model_version, metrics, started_at, completed_at,")
w("        raw_output_path, input_quality_flag, output_quality_flag, low_confidence_count,")
w("        segment_count, auto_confirm_blocked, review_state, review_deadline_at, confirm_source,")
w("        confirmed_by, confirmed_at, first_viewed_at, first_viewed_by, edited_segment_count,")
w("        first_edit_at, created_at, updated_at) VALUES")
for i, r in enumerate(RUNS):
    (k, task, batch, model, ver, started, completed, inq, outq, lowc, segc,
     rstate, deadline, csrc, cat, edited, fedit, blocked) = r
    metrics = json.dumps({"durationMs": 214000 if task == "ocr" else 176000,
                          "avgConfidence": 0.93 if outq == "ok" else 0.72,
                          "pages" if task == "ocr" else "audioDurationMs":
                              2 if task == "ocr" else TL["doc-b" if k=="B" else "doc-d"]["duration_ms"]},
                         ensure_ascii=False)
    dl = "now() + INTERVAL '%s'" % deadline.lstrip("+") if deadline else "NULL"
    viewed = q(fedit or cat) if (fedit or cat) else ("NULL" if rstate == "pending" else "NULL")
    w("    (%s, %s, %s, %s, %s, 'succeeded', 1, 'worker-gpu-01', %s, %s, %s::jsonb, %s, %s," % (
        q(RUN[k]), q(DOC[k]), q(DOC[k]), q(task), q(batch), q(model), q(ver), q(metrics),
        q(started), q(completed)))
    w("     %s, %s, %s, %d, %d, %s, %s, %s, %s, %s, %s, %s, %s, %d, %s, %s, %s)%s" % (
        q("derived/%s/%s.json" % (DOC[k], RUN[k])), q(inq), q(outq), lowc, segc,
        "true" if blocked else "false", q(rstate), dl,
        q(csrc) if csrc else "NULL", q(CANBO1) if cat else "NULL", q(cat) if cat else "NULL",
        viewed, q(CANBO1) if (fedit or cat) else "NULL", edited,
        q(fedit) if fedit else "NULL", q(started), q(cat or completed),
        "," if i < len(RUNS)-1 else ""))
w("ON CONFLICT (id) DO NOTHING;")
w("")

w("-- 10. content_segment --------------------------------------------------------")
w("INSERT INTO content_segment (id, run_id, document_id, segment_index, content_source, origin,")
w("        page_no, bbox_polygon, reading_order, line_from, line_to, time_start_ms, time_end_ms,")
w("        speaker_label, ai_text, ai_time_start_ms, ai_time_end_ms, current_text,")
w("        current_time_start_ms, current_time_end_ms, current_revision_no, is_edited, is_deleted,")
w("        confidence, created_at, updated_at) VALUES")
lines = []
for k, ocr in (("A", OCR_A), ("C", OCR_C)):
    completed = [r for r in RUNS if r[0] == k][0][6]
    for i, (page, lf, lt, ai, cur, conf) in enumerate(ocr):
        bbox = json.dumps([[0.08, round(0.10 + i*0.16, 3)], [0.92, round(0.10 + i*0.16, 3)],
                           [0.92, round(0.24 + i*0.16, 3)], [0.08, round(0.24 + i*0.16, 3)]])
        edited = cur is not None
        lines.append("    (%s, %s, %s, %d, 'ocr', 'ai', %d, %s::jsonb, %d, %d, %d, NULL, NULL, NULL,"
                     " %s, NULL, NULL, %s, NULL, NULL, %d, %s, false, %s, %s, %s)" % (
            q(seg_id(k, i)), q(RUN[k]), q(DOC[k]), i, page, q(bbox), i+1, lf, lt,
            q(ai), q(cur if edited else ai), 1 if edited else 0,
            "true" if edited else "false", conf, q(completed),
            q("2026-09-11T10:35:12+07:00" if edited else completed)))
for k, tlkey in (("B", "doc-b"), ("D", "doc-d")):
    completed = [r for r in RUNS if r[0] == k][0][6]
    total = TL[tlkey]["duration_ms"]
    for i, s in enumerate(TL[tlkey]["segments"]):
        st, en = s["start_ms"], min(s["end_ms"], total)
        lines.append("    (%s, %s, %s, %d, 'stt', 'ai', NULL, NULL, NULL, NULL, NULL, %d, %d, %s,"
                     " %s, %d, %d, %s, %d, %d, 0, false, false, %s, %s, %s)" % (
            q(seg_id(k, i)), q(RUN[k]), q(DOC[k]), i, st, en, q(SPEAKER[k][i]),
            q(s["text"]), st, en, q(s["text"]), st, en, STT_CONF[k][i], q(completed), q(completed)))
w(",\n".join(lines))
w("ON CONFLICT (id) DO NOTHING;")
w("")

w("-- 11. segment_revision (đoạn OCR tài liệu A được cán bộ sửa lỗi) --------------")
w("INSERT INTO segment_revision (id, segment_id, revision_no, text, time_start_ms, time_end_ms,")
w("        deleted, edited_by, edited_at, reason) VALUES")
w("    (%s, %s, 1, %s, NULL, NULL, false, %s, %s, %s)" % (
    q(P+"0000000f0001"), q(seg_id("A", 1)), q(OCR_A[1][4]), q(CANBO1),
    q("2026-09-11T10:35:12+07:00"),
    q("Sửa lỗi OCR: 'Trần Hưng Đao' -> 'Trần Hưng Đạo'; '001185OO7733' -> '001185007733'")))
w("ON CONFLICT (id) DO NOTHING;")
w("")

w("-- 12. document_digitization_status -------------------------------------------")
w("INSERT INTO document_digitization_status (document_id, ocr_status, stt_status, sed_status,")
w("        video_summary_status, video_status, overall_status, updated_at) VALUES")
dds = [("A", "confirmed",      "not_applicable", "confirmed"),
       ("B", "not_applicable", "confirmed",      "confirmed"),
       ("C", "pending_review", "not_applicable", "pending_review"),
       ("D", "not_applicable", "pending_review", "pending_review")]
for i, (k, ocr_s, stt_s, overall) in enumerate(dds):
    w("    (%s, %s, %s, 'not_applicable', 'not_applicable', 'not_applicable', %s, now())%s" % (
        q(DOC[k]), q(ocr_s), q(stt_s), q(overall), "," if i < len(dds)-1 else ""))
w("ON CONFLICT (document_id) DO UPDATE SET ocr_status = EXCLUDED.ocr_status,")
w("    stt_status = EXCLUDED.stt_status, overall_status = EXCLUDED.overall_status, updated_at = now();")
w("")

w("-- 13. outbox_event: để backend tự đẩy vào OpenSearch (SearchIndexOutboxPoller)")
w("--     A/B (đã duyệt) -> ingested + classification + review.confirmed + acl")
w("--     C/D (chờ duyệt) -> chỉ ingested + classification; segment CHƯA được index")
w("--     (đúng hợp đồng: chỉ run confirmed mới vào chỉ mục tìm kiếm).")
ev = []
for k, name, mime, folder, dt, tp, created in DOCS:
    ev.append(("document.ingested", {"documentId": DOC[k], "changedAt": created}))
    ev.append(("document.classification.changed",
               {"changedAt": created, "documentId": DOC[k], "changedBySub": CANBO1}))
for k in ("A", "B"):
    r = [x for x in RUNS if x[0] == k][0]
    ev.append(("review.confirmed", {"documentId": DOC[k], "runId": RUN[k], "task": r[1],
                                    "confirmSource": r[14], "confirmedAt": r[15], "segmentCount": r[10]}))
    ev.append(("document.acl.changed", {"documentId": DOC[k], "groupId": G1, "action": "shared",
                                        "changedAt": r[15], "changedBySub": CANBO1}))
w("INSERT INTO outbox_event (event_type, payload, created_at) VALUES")
for i, (t, p) in enumerate(ev):
    w("    (%s, %s::jsonb, now())%s" % (q(t), q(json.dumps(p, ensure_ascii=False)),
                                        "," if i < len(ev)-1 else ";"))
w("")
w("COMMIT;")
w("")
open("mock-seed.sql", "w").write("\n".join(out))
print("mock-seed.sql written:", sum(len(l) for l in out), "bytes")
for k in DOC:
    print(" ", k, DOC[k], SIZE[k], SHA[k][:16])
