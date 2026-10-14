/**
 * api.ts emits when a backend call was rejected for an expired token and the
 * session could not be refreshed; SessionErrorGuard listens and shows the
 * "phiên đăng nhập đã hết hạn" popup.
 */
type Listener = () => void

const listeners = new Set<Listener>()

export const sessionExpiredBus = {
  subscribe(listener: Listener): () => void {
    listeners.add(listener)
    return () => {
      listeners.delete(listener)
    }
  },
  emit(): void {
    for (const l of [...listeners]) l()
  },
}
