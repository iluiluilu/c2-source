'use client';

import Link from 'next/link';
import { useEffect, type ReactNode } from 'react';

/**
 * Viewer modal shared by the two search pages (page image / media segment).
 * Capped to the viewport: the header (title, "Mở tài liệu", "Đóng") stays
 * visible and a tall page image scrolls inside the body. Esc and a click on
 * the backdrop close it.
 */
export function SearchModal({
  title,
  documentHref,
  onClose,
  children,
}: {
  title: string;
  /** Link to the document detail page, shown next to "Đóng". */
  documentHref?: string;
  onClose: () => void;
  children: ReactNode;
}) {
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4" onClick={onClose}>
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className="flex max-h-[90vh] w-full max-w-2xl flex-col rounded-lg bg-white shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex shrink-0 items-center justify-between gap-3 border-b border-gray-100 px-4 py-3">
          <h3 className="truncate text-sm font-semibold text-[#0D2546]">{title}</h3>
          <div className="flex shrink-0 items-center gap-4 text-sm">
            {documentHref && (
              <Link href={documentHref} className="font-semibold text-[#0D2546] hover:underline">
                Mở tài liệu
              </Link>
            )}
            <button onClick={onClose} className="cursor-pointer text-gray-500 hover:text-gray-700">
              Đóng ✕
            </button>
          </div>
        </div>
        <div className="overflow-y-auto p-4">{children}</div>
      </div>
    </div>
  );
}
