import type { ReviewTask } from './api'

/** User-facing names of the digitization tasks - one place for every screen. */
export const TASK_LABELS: Record<ReviewTask, string> = {
  ocr: 'Văn bản & ảnh',
  stt: 'Lời nói (ghi âm)',
  sed: 'Âm thanh sự kiện',
  video_summary: 'Tóm tắt video',
  video: 'Video',
}
