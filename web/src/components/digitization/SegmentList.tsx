'use client';

import type { Session } from 'next-auth';
import { useEffect, useState } from 'react';
import { ApiError, apiJson } from '@/lib/api';
import type { ReviewSegment, ReviewSegmentConflict, ReviewSourceReference } from '@/lib/api';
import { useConfirm } from '@/components/ConfirmDialog';
import { reviewSourceHref } from '@/components/digitization/highlightSegment';

export type SegmentListVariant = 'ocr' | 'stt' | 'sed' | 'video_summary' | 'video';

/** Story 3 AC-4: mm:ss (or plain seconds) <-> ms, 2-way. */
function msToClock(ms: number | null): string {
  if (ms == null) return '';
  const totalSeconds = Math.floor(ms / 1000);
  const mm = Math.floor(totalSeconds / 60);
  const ss = totalSeconds % 60;
  return `${mm}:${String(ss).padStart(2, '0')}`;
}

function clockToMs(value: string): number | null {
  const trimmed = value.trim();
  if (!trimmed) return null;
  const parts = trimmed.split(':');
  if (parts.length === 1) {
    const seconds = Number(parts[0]);
    return Number.isFinite(seconds) ? Math.round(seconds * 1000) : null;
  }
  const mm = Number(parts[0]);
  const ss = Number(parts[1]);
  if (!Number.isFinite(mm) || !Number.isFinite(ss)) return null;
  return Math.round((mm * 60 + ss) * 1000);
}

/**
 * msToClock truncates to whole seconds, so comparing clockToMs(editStart)
 * against the raw segment.timeStartMs (often not a multiple of 1000, e.g.
 * 28170) falsely looked "changed" on every save - even a text-only edit -
 * and got sent to the server, tripping validateSttTimeBounds when
 * durationMs was unknown. Round the original to the same precision before
 * comparing so an untouched time field is correctly detected as unchanged.
 */
function roundToSecond(ms: number | null): number | null {
  return ms == null ? null : Math.floor(ms / 1000) * 1000;
}

/** digitization-review-04-sed-summary.md §5: cảnh báo mềm khi tóm tắt vượt 4000 ký tự. */
const SUMMARY_SOFT_LIMIT = 4000;
/** digitization-review-02-stt.md §4 bước 5: cảnh báo mềm 1 đoạn STT/OCR quá dài. */
const SEGMENT_SOFT_LIMIT = 500;

const inputClass = 'border border-gray-300 rounded-md px-2 py-1 text-sm text-gray-800';

export interface SegmentListProps {
  session: Session | null;
  runId: string;
  variant: SegmentListVariant;
  segments: ReviewSegment[];
  selectedSegmentId: string | null;
  onSelectSegment: (segmentId: string) => void;
  /** digitization-review-02-stt.md §3.1: clamp [0, sourceDurationMs] tier 1 (UX only). */
  sourceDurationMs: number | null;
  onSegmentUpdated: (segment: ReviewSegment) => void;
  onSegmentDeleted: (segment: ReviewSegment) => void;
  onSegmentAdded: (segment: ReviewSegment) => void;
  /**
   * digitization-review-07-confirmed-content-view.md §4: xem lại nội dung đã
   * confirmed (ngoài luồng review của R1 chính chủ) - ẩn Sửa/Xoá/Thêm đoạn
   * thủ công, giữ nguyên "Xem bản gốc AI" và link nguồn tham chiếu (chỉ đọc).
   */
  readOnly?: boolean;
  /**
   * digitization-review-04-sed-summary.md §4 bước 5: link deep-link của 1
   * nguồn tham chiếu (`video_summary`); `null` → hiện chữ, không bấm được.
   * Mặc định trỏ màn duyệt.
   */
  sourceRunHref?: (ref: ReviewSourceReference) => string | null;
}

/**
 * digitization-review-05-frontend.md §4.1/§4.3: danh sách đoạn dùng chung cho
 * OCR/STT/SED/tóm tắt video, khác nhau qua `variant` - ô mốc thời gian
 * (sửa được, chỉ `stt`), nhãn AI gốc (chỉ đọc, chỉ `sed`), liên kết nguồn
 * (chỉ `video_summary`), nút xoá (mọi loại trừ `video_summary` - Story 5
 * AC-4), nút thêm thủ công (chỉ `ocr`/`stt` - Story 4 AC-3/Story 5 AC-4).
 */
export function SegmentList({
  session,
  runId,
  variant,
  segments,
  selectedSegmentId,
  onSelectSegment,
  sourceDurationMs,
  onSegmentUpdated,
  onSegmentDeleted,
  onSegmentAdded,
  readOnly = false,
  sourceRunHref = reviewSourceHref,
}: SegmentListProps) {
  const confirm = useConfirm();
  const [error, setError] = useState<string | null>(null);

  const [editingId, setEditingId] = useState<string | null>(null);
  const [editText, setEditText] = useState('');
  const [editStart, setEditStart] = useState('');
  const [editEnd, setEditEnd] = useState('');
  const [showAiFor, setShowAiFor] = useState<string | null>(null);

  const [showAddForm, setShowAddForm] = useState(false);
  const [newText, setNewText] = useState('');

  // digitization-review-05-frontend.md §4.3.1: đưa đoạn được chọn (click bbox,
  // đoạn đang phát) vào vùng nhìn thấy của danh sách cuộn riêng. Bỏ qua khi
  // đang sửa để không làm nhảy form đang nhập.
  useEffect(() => {
    if (!selectedSegmentId || editingId !== null) return;
    document.getElementById(`segment-row-${selectedSegmentId}`)?.scrollIntoView?.({ block: 'nearest' });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- chỉ cuộn khi đổi đoạn được chọn, không cuộn lại khi thoát chế độ sửa
  }, [selectedSegmentId]);

  const showDelete = variant !== 'video_summary';
  // digitization-pipeline-05-video-review.md: `video` duyệt như `stt` (mốc thời gian sửa được).
  const showAddManual = variant === 'ocr' || variant === 'stt' || variant === 'video';
  const showTimeInputs = variant === 'stt' || variant === 'video';
  const showReadOnlyTime = variant === 'stt' || variant === 'sed' || variant === 'video';
  const showSoundEventLabel = variant === 'sed';
  const showSourceReferences = variant === 'video_summary';
  // Mô tả cảnh video dài như tóm tắt - dùng cùng giới hạn mềm/số dòng.
  const longText = variant === 'video_summary' || variant === 'video';
  const textLimit = longText ? SUMMARY_SOFT_LIMIT : SEGMENT_SOFT_LIMIT;

  const startEdit = (segment: ReviewSegment) => {
    setError(null);
    setEditingId(segment.segmentId);
    setEditText(segment.currentText);
    setEditStart(msToClock(segment.timeStartMs));
    setEditEnd(msToClock(segment.timeEndMs));
    setShowAiFor(null);
  };

  const cancelEdit = () => setEditingId(null);

  const applyConflict = (segment: ReviewSegment, conflict: ReviewSegmentConflict) => {
    onSegmentUpdated({
      ...segment,
      currentRevisionNo: conflict.latestRevisionNo,
      currentText: conflict.latestText,
      timeStartMs: conflict.latestTimeStartMs,
      timeEndMs: conflict.latestTimeEndMs,
    });
  };

  const saveEdit = async (segment: ReviewSegment) => {
    setError(null);
    const body: Record<string, unknown> = {
      expectedRevisionNo: segment.currentRevisionNo,
      text: editText,
    };
    if (showTimeInputs) {
      const startMs = clockToMs(editStart);
      const endMs = clockToMs(editEnd);
      // AC-6 (-05 Story 3): sửa chỉ văn bản -> không kèm timeStartMs/timeEndMs.
      // Compare against the same whole-second precision the mm:ss input can
      // actually produce (see roundToSecond) - comparing against the raw,
      // sub-second-precise segment value made an untouched field look
      // "changed" whenever it wasn't already a multiple of 1000ms.
      const startChanged = startMs !== roundToSecond(segment.timeStartMs);
      const endChanged = endMs !== roundToSecond(segment.timeEndMs);
      if (startChanged || endChanged) {
        if (startMs != null) {
          body.timeStartMs = sourceDurationMs != null ? Math.max(0, Math.min(startMs, sourceDurationMs)) : Math.max(0, startMs);
        }
        if (endMs != null) {
          body.timeEndMs = sourceDurationMs != null ? Math.max(0, Math.min(endMs, sourceDurationMs)) : Math.max(0, endMs);
        }
      }
    }
    try {
      const updated = await apiJson<ReviewSegment>(
        `/digitization-review/segments/${segment.segmentId}`,
        session,
        { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) },
      );
      onSegmentUpdated(updated);
      setEditingId(null);
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        applyConflict(segment, e.body as ReviewSegmentConflict);
        setError('Đoạn này vừa được sửa bởi yêu cầu khác — đã tải lại nội dung mới nhất, vui lòng sửa lại.');
        setEditingId(null);
      } else {
        setError((e as Error).message);
      }
    }
  };

  const removeSegment = async (segment: ReviewSegment) => {
    const ok = await confirm({
      title: 'Xoá đoạn',
      message: 'Xác nhận xoá đoạn này? Đoạn sẽ được đánh dấu đã xoá.',
      tone: 'danger',
    });
    if (!ok) return;
    setError(null);
    try {
      const updated = await apiJson<ReviewSegment>(
        `/digitization-review/segments/${segment.segmentId}?expectedRevisionNo=${segment.currentRevisionNo}`,
        session,
        { method: 'DELETE' },
      );
      onSegmentDeleted(updated);
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        applyConflict(segment, e.body as ReviewSegmentConflict);
        setError('Đoạn này vừa được sửa bởi yêu cầu khác — đã tải lại nội dung mới nhất.');
      } else {
        setError((e as Error).message);
      }
    }
  };

  const addManual = async () => {
    const text = newText.trim();
    if (!text) return;
    setError(null);
    try {
      const lastIndex = segments.length > 0 ? segments[segments.length - 1].segmentIndex : undefined;
      const created = await apiJson<ReviewSegment>(
        `/digitization-review/runs/${runId}/segments`,
        session,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ afterSegmentIndex: lastIndex, text }),
        },
      );
      onSegmentAdded(created);
      setNewText('');
      setShowAddForm(false);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  return (
    <div className="bg-white rounded-lg border border-gray-200 flex flex-col min-h-0 max-h-full">
      {error && (
        <div className="m-3 bg-red-50 border border-red-200 rounded-md p-3 text-sm text-red-800">
          {error}
        </div>
      )}

      <ul className="divide-y divide-gray-100 flex-1 min-h-0 overflow-y-auto">
        {segments.length === 0 && (
          <li className="px-4 py-6 text-center text-gray-400 text-sm">Chưa có đoạn nào.</li>
        )}
        {segments.map((segment) => {
          const isSelected = segment.segmentId === selectedSegmentId;
          const isEditing = editingId === segment.segmentId;
          return (
            <li
              key={segment.segmentId}
              id={`segment-row-${segment.segmentId}`}
              onClick={() => !isEditing && onSelectSegment(segment.segmentId)}
              className={`px-4 py-3 cursor-pointer ${isSelected ? 'bg-[#0D2546]/5' : 'hover:bg-gray-50'}`}
            >
              <div className="flex items-start justify-between gap-3">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 text-xs text-gray-400 mb-1">
                    <span>#{segment.segmentIndex}</span>
                    {segment.origin === 'manual' && (
                      <span className="text-[10px] uppercase bg-gray-100 text-gray-600 px-1.5 py-0.5 rounded">
                        Thủ công
                      </span>
                    )}
                    {segment.isEdited && (
                      <span className="text-[10px] uppercase bg-blue-50 text-blue-700 px-1.5 py-0.5 rounded">
                        Đã sửa
                      </span>
                    )}
                    {segment.confidence != null && (
                      <span>độ tin cậy {Math.round(segment.confidence * 100)}%</span>
                    )}
                    {showReadOnlyTime && !isEditing && (segment.timeStartMs != null || segment.timeEndMs != null) && (
                      <span className="font-mono">
                        [{msToClock(segment.timeStartMs)} → {msToClock(segment.timeEndMs)}]
                      </span>
                    )}
                    {variant === 'stt' && segment.overlapsAdjacent && (
                      <span
                        title="Mốc thời gian chồng lấn với đoạn liền kề"
                        className="text-yellow-700 bg-yellow-50 px-1.5 py-0.5 rounded font-semibold"
                      >
                        ⚠ Chồng lấn
                      </span>
                    )}
                    {showSoundEventLabel && segment.soundEventLabel && (
                      <span className="font-mono text-gray-500">AI: {segment.soundEventLabel}</span>
                    )}
                  </div>

                  {isEditing ? (
                    <div onClick={(e) => e.stopPropagation()} className="space-y-2">
                      {showTimeInputs && (
                        <div className="flex items-center gap-2">
                          <label className="text-xs text-gray-500">Bắt đầu</label>
                          <input
                            value={editStart}
                            onChange={(e) => setEditStart(e.target.value)}
                            placeholder="mm:ss"
                            className={`${inputClass} w-24`}
                          />
                          <label className="text-xs text-gray-500">Kết thúc</label>
                          <input
                            value={editEnd}
                            onChange={(e) => setEditEnd(e.target.value)}
                            placeholder="mm:ss"
                            className={`${inputClass} w-24`}
                          />
                        </div>
                      )}
                      <textarea
                        value={editText}
                        onChange={(e) => setEditText(e.target.value)}
                        rows={longText ? 8 : 3}
                        className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm text-gray-800"
                      />
                      <p
                        className={`text-xs ${editText.length > textLimit ? 'text-[#D11A2A]' : 'text-gray-400'}`}
                      >
                        {editText.length}/{textLimit} ký tự{editText.length > textLimit ? ' — hơi dài, cân nhắc rút gọn' : ''}
                      </p>
                      <div className="flex gap-2">
                        <button
                          onClick={() => void saveEdit(segment)}
                          className="bg-[#0D2546] hover:bg-[#163a6b] text-white font-semibold px-3 py-1.5 rounded-md text-sm cursor-pointer"
                        >
                          Lưu
                        </button>
                        <button
                          onClick={cancelEdit}
                          className="text-gray-500 hover:text-gray-700 text-sm font-semibold px-2 cursor-pointer"
                        >
                          Hủy
                        </button>
                      </div>
                    </div>
                  ) : (
                    <>
                      <p className="text-sm text-gray-800 whitespace-pre-wrap break-words">
                        {segment.isDeleted ? (
                          <span className="line-through text-gray-400">{segment.currentText}</span>
                        ) : (
                          segment.currentText
                        )}
                      </p>
                      {showAiFor === segment.segmentId && (
                        <p className="mt-1 text-xs text-gray-400 whitespace-pre-wrap break-words border-l-2 border-gray-200 pl-2">
                          Bản gốc AI: {segment.aiText}
                        </p>
                      )}
                      {showSourceReferences && (
                        <div className="mt-2 flex flex-wrap gap-2">
                          {(segment.sourceReferences ?? []).length === 0 ? (
                            <span className="text-xs text-gray-400">Không có nguồn tham chiếu.</span>
                          ) : (
                            segment.sourceReferences!.map((ref) => {
                              const href = sourceRunHref(ref);
                              const label = (
                                <>
                                  Đoạn #{ref.segmentIndex}
                                  {ref.timeStartMs != null ? `, ${msToClock(ref.timeStartMs)}` : ''}
                                  {ref.sourceRunReviewState === 'superseded' ? ' (đã thay thế)' : ''}
                                </>
                              );
                              if (href == null) {
                                return (
                                  <span
                                    key={ref.sourceSegmentId}
                                    title="Run nguồn không còn ở trạng thái đã xác nhận nên không mở được từ màn xem này"
                                    className="text-xs text-gray-500"
                                  >
                                    {label}
                                  </span>
                                );
                              }
                              return (
                                <a
                                  key={ref.sourceSegmentId}
                                  href={href}
                                  target="_blank"
                                  rel="noopener noreferrer"
                                  onClick={(e) => e.stopPropagation()}
                                  title={
                                    ref.sourceRunReviewState === 'superseded'
                                      ? 'Nguồn này đã bị thay thế bởi một run mới hơn - liên kết vẫn mở đúng run gốc'
                                      : undefined
                                  }
                                  className="text-xs text-[#0D2546] underline hover:text-[#D11A2A]"
                                >
                                  {label}
                                </a>
                              );
                            })
                          )}
                        </div>
                      )}
                    </>
                  )}
                </div>

                {!isEditing && !segment.isDeleted && (
                  <div className="flex flex-col items-end gap-1 shrink-0" onClick={(e) => e.stopPropagation()}>
                    {!readOnly && (
                      <button
                        onClick={() => startEdit(segment)}
                        className="text-[#0D2546] hover:underline text-xs font-semibold cursor-pointer"
                      >
                        Sửa
                      </button>
                    )}
                    {variant === 'ocr' && segment.aiText && (
                      <button
                        onClick={() =>
                          setShowAiFor((prev) => (prev === segment.segmentId ? null : segment.segmentId))
                        }
                        className="text-gray-500 hover:underline text-xs cursor-pointer"
                      >
                        {showAiFor === segment.segmentId ? 'Ẩn bản gốc' : 'Xem bản gốc AI'}
                      </button>
                    )}
                    {!readOnly && showDelete && (
                      <button
                        onClick={() => void removeSegment(segment)}
                        className="text-[#D11A2A] hover:underline text-xs font-semibold cursor-pointer"
                      >
                        Xoá
                      </button>
                    )}
                  </div>
                )}
              </div>
            </li>
          );
        })}
      </ul>

      {!readOnly && showAddManual && (
        <div className="p-3 border-t border-gray-100">
          {showAddForm ? (
            <div className="space-y-2">
              <textarea
                value={newText}
                onChange={(e) => setNewText(e.target.value)}
                rows={2}
                placeholder="Nội dung đoạn thêm thủ công…"
                className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm text-gray-800"
              />
              <div className="flex gap-2">
                <button
                  onClick={() => void addManual()}
                  disabled={!newText.trim()}
                  className="bg-[#0D2546] hover:bg-[#163a6b] disabled:bg-gray-300 text-white font-semibold px-3 py-1.5 rounded-md text-sm cursor-pointer disabled:cursor-not-allowed"
                >
                  Thêm đoạn
                </button>
                <button
                  onClick={() => {
                    setShowAddForm(false);
                    setNewText('');
                  }}
                  className="text-gray-500 hover:text-gray-700 text-sm font-semibold px-2 cursor-pointer"
                >
                  Hủy
                </button>
              </div>
            </div>
          ) : (
            <button
              onClick={() => setShowAddForm(true)}
              className="text-[#0D2546] hover:underline text-sm font-semibold cursor-pointer"
            >
              + Thêm đoạn thủ công
            </button>
          )}
        </div>
      )}
    </div>
  );
}
