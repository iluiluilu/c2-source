'use client';

import type { FolderNode } from '@/lib/api';

/**
 * Recursive folder tree with single selection - the FR-11 destination picker.
 */
export function FolderTree({
  nodes,
  selectedId,
  onSelect,
  depth = 0,
}: {
  nodes: FolderNode[];
  selectedId: string | null;
  onSelect: (id: string) => void;
  depth?: number;
}) {
  if (nodes.length === 0 && depth === 0) {
    return (
      <p className="text-sm text-gray-400 italic">
        Chưa có thư mục nào.
      </p>
    );
  }
  return (
    <ul className={depth === 0 ? '' : 'ml-5 border-l border-gray-200 pl-2'}>
      {nodes.map((node) => (
        <li key={node.id}>
          <button
            type="button"
            onClick={() => onSelect(node.id)}
            className={`w-full text-left px-2 py-1 rounded text-sm flex items-center gap-2 transition-colors cursor-pointer ${
              selectedId === node.id
                ? 'bg-[#0D2546] text-white'
                : 'hover:bg-gray-100 text-gray-800'
            }`}
          >
            <span aria-hidden>📁</span>
            {node.name}
          </button>
          {node.children.length > 0 && (
            <FolderTree
              nodes={node.children}
              selectedId={selectedId}
              onSelect={onSelect}
              depth={depth + 1}
            />
          )}
        </li>
      ))}
    </ul>
  );
}
