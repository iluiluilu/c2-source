'use client';

import type { Session } from 'next-auth';
import { useEffect, useRef, useState } from 'react';
import { apiJson } from '@/lib/api';
import type { UserDirectoryEntry } from '@/lib/api';
import { roleLabel } from '@/lib/roles';

/**
 * group-management-sharing-01-group-lifecycle.md §3.2/§4.7 (Story 5):
 * search-as-you-type over `GET /users/directory`, reused by the "add member"
 * form on the group detail page. Debounced (300ms) and only queries once the
 * keyword is >= 2 characters (matches the backend's own validation).
 */
export function UserPicker({
  session,
  onSelect,
  excludeSubs = [],
  placeholder = 'Tìm theo tên đăng nhập hoặc họ tên…',
}: {
  session: Session | null;
  onSelect: (entry: UserDirectoryEntry) => void;
  /** Suggestions already added elsewhere (e.g. current group members) - filtered out client-side. */
  excludeSubs?: string[];
  placeholder?: string;
}) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<UserDirectoryEntry[]>([]);
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
      apiJson<UserDirectoryEntry[]>(
        `/users/directory?q=${encodeURIComponent(keyword)}`,
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

  const excluded = new Set(excludeSubs);
  const visible = results.filter((r) => !excluded.has(r.sub));

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
            <div className="px-3 py-2 text-gray-400">Không tìm thấy người dùng phù hợp.</div>
          ) : (
            visible.map((entry) => (
              <button
                key={entry.sub}
                type="button"
                onClick={() => {
                  onSelect(entry);
                  setQuery('');
                  setResults([]);
                  setOpen(false);
                }}
                className="w-full text-left px-3 py-2 hover:bg-gray-50 flex items-center justify-between gap-2 cursor-pointer"
              >
                <span>
                  <span className="font-semibold text-[#0D2546]">{entry.displayName}</span>
                  <span className="text-gray-400"> · {entry.username}</span>
                </span>
                {entry.role && (
                  <span className="text-[10px] bg-gray-100 text-gray-500 px-1.5 py-0.5 rounded shrink-0">
                    {roleLabel(entry.role)}
                  </span>
                )}
              </button>
            ))
          )}
        </div>
      )}
    </div>
  );
}
