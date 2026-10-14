/**
 * Tiny pub/sub with no dependencies: api.ts emits when the backend answers
 * 403, permissions.ts listens and refetches /me/permissions (the user's
 * permissions may have been changed by R3 since the last poll).
 */
type Listener = () => void

const listeners = new Set<Listener>()

export const forbiddenBus = {
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
