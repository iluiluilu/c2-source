import { cleanup, render } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { Highlight, markQueryTerms, parseHighlightParts } from '@/lib/search-highlight'

/**
 * search-fulltext-05-frontend.md §4 bước 2/§5/§7 (Task 5) - the mandatory
 * "an toàn trước stored-XSS" coverage: a malicious-looking string in the
 * non-matched part of `highlight` must render as plain text, never get
 * interpreted as markup (no `dangerouslySetInnerHTML` anywhere in this
 * module).
 */
describe('parseHighlightParts', () => {
  it('splits one <em>...</em> pair into matched/unmatched parts, in order', () => {
    const parts = parseHighlightParts('Hop dong nay quy dinh <em>thue nha</em> giua hai ben.')

    expect(parts).toEqual([
      { text: 'Hop dong nay quy dinh ', matched: false },
      { text: 'thue nha', matched: true },
      { text: ' giua hai ben.', matched: false },
    ])
  })

  it('handles multiple <em>...</em> pairs', () => {
    const parts = parseHighlightParts('<em>mot</em> hai <em>ba</em>')

    expect(parts).toEqual([
      { text: 'mot', matched: true },
      { text: ' hai ', matched: false },
      { text: 'ba', matched: true },
    ])
  })

  it('returns a single unmatched part when there is no <em> at all', () => {
    expect(parseHighlightParts('khong co tu khoa noi bat')).toEqual([
      { text: 'khong co tu khoa noi bat', matched: false },
    ])
  })

  it('returns an empty list for null/undefined/empty input', () => {
    expect(parseHighlightParts(null)).toEqual([])
    expect(parseHighlightParts(undefined)).toEqual([])
    expect(parseHighlightParts('')).toEqual([])
  })

  it('keeps a stray <script> tag in the non-matched text as literal text, not markup', () => {
    const malicious = '<script>alert(1)</script> <em>thue nha</em>'
    const parts = parseHighlightParts(malicious)

    // The <script> text is kept verbatim as a plain-text part - it is up to
    // the caller (Highlight/React) to never interpret it as markup.
    expect(parts[0]).toEqual({ text: '<script>alert(1)</script> ', matched: false })
    expect(parts[1]).toEqual({ text: 'thue nha', matched: true })
  })
})

describe('Highlight component', () => {
  afterEach(cleanup)

  it('renders the matched part inside a <mark> element', () => {
    const { container } = render(<Highlight text="tim <em>thue nha</em> ngay" />)

    const mark = container.querySelector('mark')
    expect(mark).not.toBeNull()
    expect(mark?.textContent).toBe('thue nha')
    expect(container.textContent).toBe('tim thue nha ngay')
  })

  it('renders a malicious-looking string as inert text, never executes/interprets it as HTML', () => {
    const malicious = '<img src=x onerror=alert(1)> <em>ket qua</em>'
    const { container } = render(<Highlight text={malicious} />)

    // No actual <img> element was created from the string - it is text content only.
    expect(container.querySelector('img')).toBeNull()
    expect(container.textContent).toContain('<img src=x onerror=alert(1)>')
    expect(container.querySelector('mark')?.textContent).toBe('ket qua')
  })

  it('renders nothing for a null highlight', () => {
    const { container } = render(<Highlight text={null} />)
    expect(container.textContent).toBe('')
  })
})

describe('markQueryTerms (fallback without server markers)', () => {
  it('marks whole query words case-insensitively', () => {
    expect(markQueryTerms('Thông qua Và lập biên bản', 'và')).toEqual([
      { text: 'Thông qua ', matched: false },
      { text: 'Và', matched: true },
      { text: ' lập biên bản', matched: false },
    ])
  })

  it('does not mark a term inside a longer word', () => {
    expect(markQueryTerms('vàng và bạc', 'và')).toEqual([
      { text: 'vàng ', matched: false },
      { text: 'và', matched: true },
      { text: ' bạc', matched: false },
    ])
  })

  it('returns the text unmarked for an empty query', () => {
    expect(markQueryTerms('abc', '  ')).toEqual([{ text: 'abc', matched: false }])
  })
})
