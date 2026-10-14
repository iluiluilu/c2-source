import { describe, expect, it } from 'vitest'
import { allowedExtensionSet, extensionOf, partitionByExtension } from '../allowedFormats'

const item = (name: string) => ({ file: new File(['x'], name), relativePath: 'Root' })

describe('allowedFormats', () => {
  it('builds a lower-case extension set from the allowlist', () => {
    const set = allowedExtensionSet([
      { mimeType: 'application/pdf', extensions: ['PDF'] },
      { mimeType: 'image/jpeg', extensions: ['jpg', 'jpeg'] },
    ])
    expect([...set].sort()).toEqual(['jpeg', 'jpg', 'pdf'])
  })

  it('extracts the last extension, case-insensitively', () => {
    expect(extensionOf('a.tar.PDF')).toBe('pdf')
    expect(extensionOf('noext')).toBe('')
    expect(extensionOf('.env')).toBe('')
  })

  it('skips files whose extension is not allowed (D-03: .exe inside a dropped folder)', () => {
    const { accepted, skipped } = partitionByExtension(
      [item('a.pdf'), item('setup.exe'), item('README'), item('B.PDF')],
      new Set(['pdf']),
    )
    expect(accepted.map((i) => i.file.name)).toEqual(['a.pdf', 'B.PDF'])
    expect(skipped.map((i) => i.file.name)).toEqual(['setup.exe', 'README'])
  })

  it('keeps everything when the allowlist could not be loaded', () => {
    const items = [item('setup.exe')]
    expect(partitionByExtension(items, null)).toEqual({ accepted: items, skipped: [] })
  })
})
