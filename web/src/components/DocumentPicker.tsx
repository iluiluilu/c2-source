'use client';

import type { Session } from 'next-auth';
import { useEffect, useRef, useState } from 'react';
import { apiJson } from '@/lib/api';
import type { DocumentRow } from '@/lib/api';

/**
 * group-management-sharing-02-document-sharing.md §4.6 ("Thêm tài liệu" on
 * the group detail page): search-as-you-type over `GET /documents?q=`, same
 * shape/debounce as {@link "./UserPicker"} (`GET /users/directory?q=`).
 */
export function DocumentPicker({
  session,
  onSelect,
  /** Documents already shown in the group's document list - filtered out client-side. */
  excludeIds = [],
  placeholder = 'Tìm theo tên tài liệu…',
}: {
  session: Session | null;
  onSelect: (doc: DocumentRow) => void;
  excludeIds?: string[];
  placeholder?: string;
}) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<DocumentRow[]>([]);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const boxRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const keyword = query.trim();
    const timer = setTimeout(() => {
      if (keyword.length < 2) {
        setResults([]);
        return;
      }
      setLoading(true);
      apiJson<DocumentRow[]>(
        `/documents?q=${encodeURIComponent(keyword)}&limit=10`,
        session,
      )
        .then((rows) => {
          setResults(rows);
          setOpen(true);
        })
        .catch(() => setResults([]))
        .finally(() => setLoading(false));
    }, 300);
    return () => clearTimeout(timer);
  }, [query, session]);

  useEffect(() => {
    const onClick = (e: MouseEvent) => {
      if (boxRef.current && !boxRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', onClick);
    return () => document.removeEventListener('mousedown', onClick);
  }, []);

  const excluded = new Set(excludeIds);
  const visible = results.filter((r) => !excluded.has(r.id));

  return (
    <div className="relative" ref={boxRef}>
      <input
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        onFocus={() => results.length > 0 && setOpen(true)}
        placeholder={placeholder}
        className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm text-gray-800"
      />
      {open && query.trim().length >= 2 && (
        <div className="absolute z-30 mt-1 w-full bg-white border border-gray-200 rounded-md shadow-lg max-h-64 overflow-y-auto text-sm">
          {loading ? (
            <div className="px-3 py-2 text-gray-400">Đang tìm…</div>
          ) : visible.length === 0 ? (
            <div className="px-3 py-2 text-gray-400">Không tìm thấy tài liệu phù hợp.</div>
          ) : (
            visible.map((doc) => (
              <button
                key={doc.id}
                type="button"
                onClick={() => {
                  onSelect(doc);
                  setQuery('');
                  setResults([]);
                  setOpen(false);
                }}
                className="w-full text-left px-3 py-2 hover:bg-gray-50 flex items-center gap-2 cursor-pointer"
              >
                <span className="font-semibold text-[#0D2546] truncate">{doc.originalName}</span>
              </button>
            ))
          )}
        </div>
      )}
    </div>
  );
}
