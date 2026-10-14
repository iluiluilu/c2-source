type Category = 'TEXT_DOC' | 'IMAGE' | 'AUDIO' | 'VIDEO'

/** Common extensions -> MIME type + allowlist group, so admins type ".docx", not a MIME string. */
const KNOWN: Record<string, { mime: string; category: Category }> = {
  pdf: { mime: 'application/pdf', category: 'TEXT_DOC' },
  doc: { mime: 'application/msword', category: 'TEXT_DOC' },
  docx: { mime: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', category: 'TEXT_DOC' },
  xls: { mime: 'application/vnd.ms-excel', category: 'TEXT_DOC' },
  xlsx: { mime: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', category: 'TEXT_DOC' },
  ppt: { mime: 'application/vnd.ms-powerpoint', category: 'TEXT_DOC' },
  pptx: { mime: 'application/vnd.openxmlformats-officedocument.presentationml.presentation', category: 'TEXT_DOC' },
  odt: { mime: 'application/vnd.oasis.opendocument.text', category: 'TEXT_DOC' },
  ods: { mime: 'application/vnd.oasis.opendocument.spreadsheet', category: 'TEXT_DOC' },
  rtf: { mime: 'application/rtf', category: 'TEXT_DOC' },
  txt: { mime: 'text/plain', category: 'TEXT_DOC' },
  md: { mime: 'text/markdown', category: 'TEXT_DOC' },
  csv: { mime: 'text/csv', category: 'TEXT_DOC' },
  jpg: { mime: 'image/jpeg', category: 'IMAGE' },
  jpeg: { mime: 'image/jpeg', category: 'IMAGE' },
  png: { mime: 'image/png', category: 'IMAGE' },
  gif: { mime: 'image/gif', category: 'IMAGE' },
  webp: { mime: 'image/webp', category: 'IMAGE' },
  bmp: { mime: 'image/bmp', category: 'IMAGE' },
  tif: { mime: 'image/tiff', category: 'IMAGE' },
  tiff: { mime: 'image/tiff', category: 'IMAGE' },
  mp3: { mime: 'audio/mpeg', category: 'AUDIO' },
  wav: { mime: 'audio/wav', category: 'AUDIO' },
  flac: { mime: 'audio/flac', category: 'AUDIO' },
  m4a: { mime: 'audio/mp4', category: 'AUDIO' },
  ogg: { mime: 'audio/ogg', category: 'AUDIO' },
  mp4: { mime: 'video/mp4', category: 'VIDEO' },
  mov: { mime: 'video/quicktime', category: 'VIDEO' },
  avi: { mime: 'video/x-msvideo', category: 'VIDEO' },
  mkv: { mime: 'video/x-matroska', category: 'VIDEO' },
  webm: { mime: 'video/webm', category: 'VIDEO' },
}

/** Suggestion for the first extension in a "docx, .doc" style list, or null if unknown. */
export function suggestFormat(extensions: string): { mime: string; category: Category } | null {
  const first = extensions.split(',')[0]?.trim().replace(/^\./, '').toLowerCase()
  return (first && KNOWN[first]) || null
}
