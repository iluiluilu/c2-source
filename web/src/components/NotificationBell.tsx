'use client';

import type { Session } from 'next-auth';
import { useRouter } from 'next/navigation';
import { useEffect, useRef, useState } from 'react';
import { apiFetch, apiJson } from '@/lib/api';
import type { NotificationRow } from '@/lib/api';

const POLL_INTERVAL_MS = 60_000;

function str(v: unknown): string | null {
  return typeof v === 'string' && v ? v : null;
}

/**
 * groupName/actorUsername are snapshots the backend adds to the payload;
 * rows written before that keep the generic wording.
 */
export function describe(n: NotificationRow): string {
  const group = str(n.payload.groupName);
  const actor = str(n.payload.actorUsername);
  const inGroup = group ? ` "${group}"` : '';
  switch (n.type) {
    case 'group.member_added':
      return `Bạn đã được thêm vào nhóm${inGroup || ' mới'}${actor ? ` bởi ${actor}` : ''}.`;
    case 'group.member_removed':
      return `Bạn đã bị gỡ khỏi nhóm${inGroup || ' một nhóm'}.`;
    case 'document.shared_to_group': {
      const count = Array.isArray(n.payload.documentIds) ? n.payload.documentIds.length : 0;
      const what = count > 1 ? `${count} tài liệu` : 'một tài liệu';
      return `${actor ?? 'Có người'} đã chia sẻ ${what} vào nhóm${inGroup || ' của bạn'}.`;
    }
    default:
      return n.type;
  }
}

/** Where clicking a notification goes - nowhere for a group the user just left. */
export function targetHref(n: NotificationRow): string | null {
  const groupId = str(n.payload.groupId);
  if (!groupId || n.type === 'group.member_removed') return null;
  return `/groups/${groupId}`;
}

/**
 * group-management-sharing-02-document-sharing.md §4.6 point 2 (Story 4):
 * polls {@code GET /notifications?unreadOnly=true} every 60s for the unread
 * count; the dropdown lazily loads the full (read + unread) list on open.
 */
export function NotificationBell({ session }: { session: Session | null }) {
  const [unread, setUnread] = useState<NotificationRow[]>([]);
  const [all, setAll] = useState<NotificationRow[] | null>(null);
  const [open, setOpen] = useState(false);
  const boxRef = useRef<HTMLDivElement>(null);
  const router = useRouter();

  useEffect(() => {
    if (!session) return;
    // Interval ticks must not refresh an expired token (lib/api.ts ApiInit):
    // they would keep an idle tab's Keycloak session alive forever.
    const poll = (noRefresh: boolean) => {
      apiJson<NotificationRow[]>('/notifications?unreadOnly=true', session, { noRefresh })
        .then(setUnread)
        .catch(() => {
          // best-effort - a transient failure just keeps the last known count.
        });
    };
    poll(false);
    const timer = setInterval(() => poll(true), POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [session]);

  useEffect(() => {
    const onClick = (e: MouseEvent) => {
      if (boxRef.current && !boxRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', onClick);
    return () => document.removeEventListener('mousedown', onClick);
  }, []);

  const toggle = () => {
    const next = !open;
    setOpen(next);
    if (next) {
      apiJson<NotificationRow[]>('/notifications', session)
        .then(setAll)
        .catch(() => setAll([]));
    }
  };

  const markRead = (n: NotificationRow) => {
    if (n.readAt) return;
    void apiFetch(`/notifications/${n.id}/read`, session, { method: 'POST' })
      .then(() => {
        setUnread((rows) => rows.filter((r) => r.id !== n.id));
        setAll((rows) =>
          rows
            ? rows.map((r) =>
                r.id === n.id ? { ...r, readAt: new Date().toISOString() } : r,
              )
            : rows,
        );
      })
      .catch(() => {
        // best-effort - leave state unchanged, user can retry.
      });
  };

  return (
    <div className="relative" ref={boxRef}>
      <button
        onClick={toggle}
        className="relative flex items-center justify-center w-9 h-9 rounded-full hover:bg-gray-100 cursor-pointer"
        title="Thông báo"
      >
        <svg
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinecap="round"
          strokeLinejoin="round"
          className="w-5 h-5 text-gray-500"
        >
          <path d="M6 8a6 6 0 1112 0c0 7 3 9 3 9H3s3-2 3-9zM10 21a2 2 0 004 0" />
        </svg>
        {unread.length > 0 && (
          <span className="absolute -top-0.5 -right-0.5 min-w-[16px] h-4 px-1 rounded-full bg-[#D11A2A] text-white text-[10px] font-bold flex items-center justify-center">
            {unread.length > 99 ? '99+' : unread.length}
          </span>
        )}
      </button>

      {open && (
        <div className="absolute right-0 mt-2 w-80 bg-white border border-gray-200 rounded-md shadow-lg py-1 text-sm max-h-96 overflow-y-auto">
          <div className="px-4 py-2 text-xs uppercase tracking-wide text-gray-400 border-b border-gray-100">
            Thông báo
          </div>
          {all === null ? (
            <div className="px-4 py-3 text-gray-400">Đang tải…</div>
          ) : all.length === 0 ? (
            <div className="px-4 py-3 text-gray-400">Không có thông báo nào.</div>
          ) : (
            all.map((n) => (
              <button
                key={n.id}
                onClick={() => {
                  markRead(n);
                  const href = targetHref(n);
                  if (href) {
                    setOpen(false);
                    router.push(href);
                  }
                }}
                className={`w-full text-left px-4 py-2.5 border-b border-gray-50 last:border-0 hover:bg-gray-50 cursor-pointer ${
                  n.readAt ? 'text-gray-400' : 'text-[#0D2546] font-semibold'
                }`}
              >
                <p>{describe(n)}</p>
                <p className="text-[11px] text-gray-400 mt-0.5">
                  {new Date(n.createdAt).toLocaleString('vi-VN')}
                </p>
              </button>
            ))
          )}
        </div>
      )}
    </div>
  );
}
