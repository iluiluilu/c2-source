import { describe, expect, it } from 'vitest'
import type { FolderNode } from '../api'
import { folderPathLabel } from '../folderPath'

const node = (id: string, name: string, depth: number, children: FolderNode[] = []): FolderNode => ({
  id,
  name,
  depth,
  children,
})

const tree = [node('a', 'Kho A', 1, [node('a1', 'Hợp đồng', 2, [node('a11', '2026', 3)])]), node('b', 'Kho B', 1)]

describe('folderPathLabel', () => {
  it('joins every ancestor name down to the selected folder', () => {
    expect(folderPathLabel(tree, 'a11')).toBe('Kho A / Hợp đồng / 2026')
    expect(folderPathLabel(tree, 'b')).toBe('Kho B')
  })

  it('is null when nothing is selected or the folder is not in the tree', () => {
    expect(folderPathLabel(tree, null)).toBeNull()
    expect(folderPathLabel(tree, 'gone')).toBeNull()
  })
})
