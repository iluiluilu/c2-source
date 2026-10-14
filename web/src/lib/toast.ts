/**
 * App-wide success/info toasts (UX review 2026-10-13, UX-09): one floating
 * stack instead of per-page banners that pushed the content down. Module
 * store with no React dependency, same shape as forbiddenBus - any code can
 * call toast.success(); <Toaster/> (mounted by AppShell) renders it.
 * Errors stay inline on each page: they must persist until the user acts.
 */
export type ToastTone = 'success' | 'info';

export interface ToastAction {
  label: string;
  href: string;
}

export interface Toast {
  id: number;
  tone: ToastTone;
  message: string;
  action?: ToastAction;
}

/** How long a toast stays up; one with an action stays longer to be clickable. */
export const TOAST_MS = 4000;
export const TOAST_WITH_ACTION_MS = 8000;

type Listener = (toasts: readonly Toast[]) => void;

let toasts: readonly Toast[] = [];
let nextId = 1;
const listeners = new Set<Listener>();
const timers = new Map<number, ReturnType<typeof setTimeout>>();

function emit() {
  for (const l of [...listeners]) l(toasts);
}

export function dismissToast(id: number): void {
  const t = timers.get(id);
  if (t) clearTimeout(t);
  timers.delete(id);
  toasts = toasts.filter((x) => x.id !== id);
  emit();
}

function push(tone: ToastTone, message: string, action?: ToastAction): number {
  const id = nextId++;
  // Keep the stack short: the newest 3 are enough context.
  toasts = [...toasts, { id, tone, message, action }].slice(-3);
  timers.set(
    id,
    setTimeout(() => dismissToast(id), action ? TOAST_WITH_ACTION_MS : TOAST_MS),
  );
  emit();
  return id;
}

export const toast = {
  success: (message: string, action?: ToastAction) => push('success', message, action),
  info: (message: string, action?: ToastAction) => push('info', message, action),
};

export function subscribeToasts(listener: Listener): () => void {
  listeners.add(listener);
  listener(toasts);
  return () => {
    listeners.delete(listener);
  };
}

export function getToasts(): readonly Toast[] {
  return toasts;
}

/** Test helper. */
export function resetToasts(): void {
  for (const t of timers.values()) clearTimeout(t);
  timers.clear();
  toasts = [];
  emit();
}
