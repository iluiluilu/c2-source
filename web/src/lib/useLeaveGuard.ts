'use client';

import { useEffect } from 'react';

export const LEAVE_WHILE_UPLOADING_MESSAGE =
  'Đang tải tệp lên. Nếu rời trang, bạn sẽ không theo dõi được tiến trình và các tệp chưa tải xong có thể bị hủy. Vẫn rời trang?';

/**
 * feedbacks D-11 (short-term): while `active`, warn before the upload queue is lost - on reload/close
 * (`beforeunload`, browser-native prompt) and on any in-app link (sidebar, breadcrumbs...) that would unmount the
 * page. The click listener runs in the capture phase on `document`, i.e. before Next's `<Link>` handler, so a
 * cancelled confirm stops the client-side navigation too.
 */
export function useLeaveGuard(active: boolean, message: string = LEAVE_WHILE_UPLOADING_MESSAGE): void {
  useEffect(() => {
    if (!active) return;

    const onBeforeUnload = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      e.returnValue = '';
    };

    const onClick = (e: MouseEvent) => {
      if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
      const anchor = (e.target as Element | null)?.closest?.('a[href]') as HTMLAnchorElement | null;
      if (!anchor || anchor.target === '_blank' || anchor.hasAttribute('download')) return;
      const url = new URL(anchor.href, window.location.href);
      if (url.origin !== window.location.origin || url.pathname === window.location.pathname) return;
      if (!window.confirm(message)) {
        e.preventDefault();
        e.stopPropagation();
      }
    };

    window.addEventListener('beforeunload', onBeforeUnload);
    document.addEventListener('click', onClick, true);
    return () => {
      window.removeEventListener('beforeunload', onBeforeUnload);
      document.removeEventListener('click', onClick, true);
    };
  }, [active, message]);
}
