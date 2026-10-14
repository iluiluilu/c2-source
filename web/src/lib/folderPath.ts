import type { FolderNode } from './api'

/** `Kho A / Hợp đồng / 2026` for `id` inside `nodes`, or null when the folder is not in the tree (any more). */
export function folderPathLabel(nodes: FolderNode[], id: string | null): string | null {
  if (!id) return null
  const walk = (level: FolderNode[], trail: string[]): string[] | null => {
    for (const node of level) {
      const path = [...trail, node.name]
      if (node.id === id) return path
      const found = walk(node.children, path)
      if (found) return found
    }
    return null
  }
  return walk(nodes, [])?.join(' / ') ?? null
}
