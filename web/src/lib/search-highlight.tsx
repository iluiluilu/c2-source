/**
 * search-fulltext-05-frontend.md §4 bước 2/§7 (Task 5) - safely renders
 * `segments[].highlight` (a string possibly containing literal `<em>...</em>`
 * markers from OpenSearch's highlighter, alongside free-text content that
 * may itself have come from a user-entered note or OCR text - FR-16, stored-
 * XSS risk mục 7). This module deliberately never uses
 * `dangerouslySetInnerHTML` on the raw string: it splits on the literal
 * `<em>`/`</em>` markers with plain string search (not a DOM-parsing regex),
 * and every other character - including any `<script>`/HTML-looking text
 * the source data happens to contain - is handed to React as a plain text
 * node, which React always escapes on render.
 */

export interface HighlightPart {
  text: string
  /** True for the text between one `<em>`/`</em>` pair (the matched keyword). */
  matched: boolean
}

const OPEN_TAG = '<em>'
const CLOSE_TAG = '</em>'

/**
 * Parses a highlight fragment into an ordered list of plain-text parts, each
 * flagged as matched or not. Only literal `<em>`/`</em>` markers are treated
 * as structure - everything else (including any other `<`/`>` characters) is
 * kept as literal text, never interpreted as markup.
 */
export function parseHighlightParts(highlight: string | null | undefined): HighlightPart[] {
  if (!highlight) return []

  const parts: HighlightPart[] = []
  let rest = highlight
  // Defensive upper bound: a highlight fragment is capped server-side well
  // under this, so this only guards against a pathological/malformed input
  // never terminating the loop.
  let guard = 0
  while (rest.length > 0 && guard < 1000) {
    guard += 1
    const openIdx = rest.indexOf(OPEN_TAG)
    if (openIdx === -1) {
      parts.push({ text: rest, matched: false })
      break
    }
    if (openIdx > 0) {
      parts.push({ text: rest.slice(0, openIdx), matched: false })
    }
    const afterOpen = rest.slice(openIdx + OPEN_TAG.length)
    const closeIdx = afterOpen.indexOf(CLOSE_TAG)
    if (closeIdx === -1) {
      // No matching close tag - treat the remainder as literal text rather
      // than silently dropping it.
      parts.push({ text: afterOpen, matched: false })
      break
    }
    if (closeIdx > 0) {
      parts.push({ text: afterOpen.slice(0, closeIdx), matched: true })
    }
    rest = afterOpen.slice(closeIdx + CLOSE_TAG.length)
  }
  return parts
}

/**
 * Fallback when the server sent no `<em>` markers (a hit through a field the
 * highlighter does not cover, e.g. a folded/stop-word match): mark the query's
 * words case-insensitively in the plain text. Same output shape, still plain
 * text parts only.
 */
export function markQueryTerms(text: string, query: string | null | undefined): HighlightPart[] {
  const terms = (query ?? '')
    .toLocaleLowerCase('vi')
    .split(/\s+/)
    .map((t) => t.replace(/^["'(]+|["')]+$/g, ''))
    .filter((t) => t.length > 0)
  if (terms.length === 0) return [{ text, matched: false }]

  const lower = text.toLocaleLowerCase('vi')
  const parts: HighlightPart[] = []
  let i = 0
  let plainStart = 0
  while (i < text.length) {
    // Longest term starting at i, on a word boundary on both sides.
    let hit = 0
    for (const t of terms) {
      if (t.length > hit && lower.startsWith(t, i) && isBoundary(lower, i - 1) && isBoundary(lower, i + t.length)) {
        hit = t.length
      }
    }
    if (hit === 0) {
      i += 1
      continue
    }
    if (i > plainStart) parts.push({ text: text.slice(plainStart, i), matched: false })
    parts.push({ text: text.slice(i, i + hit), matched: true })
    i += hit
    plainStart = i
  }
  if (plainStart < text.length) parts.push({ text: text.slice(plainStart), matched: false })
  return parts
}

function isBoundary(s: string, idx: number): boolean {
  if (idx < 0 || idx >= s.length) return true
  return !/[\p{L}\p{N}]/u.test(s[idx])
}

/**
 * Renders `highlight` as React text nodes, wrapping matched parts in `<mark>`.
 * Never `dangerouslySetInnerHTML`. Without server markers, `query`'s words
 * are marked instead (markQueryTerms).
 */
export function Highlight({ text, query }: { text: string | null | undefined; query?: string | null }) {
  let parts = parseHighlightParts(text)
  if (text && query && !parts.some((p) => p.matched)) parts = markQueryTerms(text, query)
  return (
    <>
      {parts.map((part, i) =>
        part.matched ? (
          <mark key={i} className="bg-yellow-200 rounded px-0.5">
            {part.text}
          </mark>
        ) : (
          <span key={i}>{part.text}</span>
        ),
      )}
    </>
  )
}
