/**
 * Reads claims off an already-verified JWT (NextAuth's server-side token
 * exchange already validated the signature) without pulling in a decode
 * library for one call site.
 */
export function decodeJwtPayload<T = Record<string, unknown>>(token: string): T | null {
  const payload = token.split('.')[1]
  if (!payload) return null
  try {
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/')
    const json = Buffer.from(base64, 'base64').toString('utf-8')
    return JSON.parse(json) as T
  } catch {
    return null
  }
}
