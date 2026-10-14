'use client'

import { useCallback, useEffect, useSyncExternalStore } from 'react'
import { useSession } from 'next-auth/react'
import { API_BASE, fetchWithToken } from './api'
import { forbiddenBus } from './forbiddenBus'

/**
 * Client-side mirror of the caller's effective backend authorities
 * (GET /me/permissions). UI gating only - the backend still enforces every
 * permission. Module singleton store: any number of usePermissions() callers
 * share ONE fetch/poll cycle (no Provider needed).
 */
export type PermissionsStatus = 'loading' | 'ready' | 'error'

export interface PermissionsState {
  status: PermissionsStatus
  roles: readonly string[]
  permissions: readonly string[]
}

/** Internal dedupe window: refetch triggers closer than this collapse into one call. */
export const REFETCH_DEDUPE_MS = 2000
/**
 * Background poll. It must never refresh the access token: a token refresh
 * counts as activity for Keycloak's SSO idle timeout, so polling with
 * refresh would keep an abandoned tab logged in forever.
 */
export const REFETCH_INTERVAL_MS = 4 * 60 * 1000

const INITIAL: PermissionsState = { status: 'loading', roles: [], permissions: [] }

let state: PermissionsState = INITIAL
let token: string | null = null
let inFlight = false
let lastFetchAt = 0
/** The background poll hit an expired token; refetch once the token changes. */
let authStale = false
const subscribers = new Set<() => void>()

function setState(next: PermissionsState) {
  state = next
  for (const s of [...subscribers]) s()
}

async function doFetch(background = false): Promise<void> {
  if (!token || inFlight) return
  inFlight = true
  lastFetchAt = Date.now()
  const usedToken = token
  try {
    const url = `${API_BASE}/me/permissions`
    const res = background
      ? await fetch(url, { headers: { Authorization: `Bearer ${usedToken}` } })
      : await fetchWithToken(url, usedToken)
    if (background && res.status === 401) {
      // Token expired while idle: keep the last known permissions; the next
      // user-driven call refreshes the token and setPermissionsToken refetches.
      authStale = true
      return
    }
    if (!res.ok) throw new Error(String(res.status))
    authStale = false
    const body = (await res.json()) as { roles?: string[]; permissions?: string[] }
    if (token === null) return // logged out meanwhile
    setState({
      status: 'ready',
      roles: body.roles ?? [],
      permissions: body.permissions ?? [],
    })
  } catch {
    if (token === null) return
    // AC-8: keep the last known permissions while flagging the error; can()
    // is fail-closed on 'error' regardless.
    setState({ ...state, status: 'error' })
  } finally {
    inFlight = false
  }
}

/**
 * Debounced refetch (AC-7); `force` (retry button) bypasses the window.
 * `background` (interval poll) never refreshes an expired token.
 */
export function refetchPermissions(force = false, background = false): void {
  if (!token) return
  if (!force && Date.now() - lastFetchAt < REFETCH_DEDUPE_MS) return
  void doFetch(background)
}

/** Called with the session access token, or undefined/null on logout. */
export function setPermissionsToken(next: string | null | undefined): void {
  const t = next || null
  if (t === token) return
  const wasNone = token === null
  token = t
  if (!t) {
    lastFetchAt = 0
    setState(INITIAL)
    return
  }
  if (wasNone || authStale) {
    lastFetchAt = 0
    void doFetch()
  }
}

/** Test helper: back to pristine module state. */
export function resetPermissionsStore(): void {
  token = null
  inFlight = false
  lastFetchAt = 0
  authStale = false
  state = INITIAL
  subscribers.clear()
}

export function getPermissionsState(): PermissionsState {
  return state
}

export function canWith(s: PermissionsState, code: string): boolean {
  return s.status === 'ready' && s.permissions.includes(code)
}

// ---- single global listeners, installed while >=1 hook consumer is mounted ----
let timer: ReturnType<typeof setInterval> | null = null
let unsubBus: (() => void) | null = null

const onFocus = () => refetchPermissions()
const onVisibility = () => {
  if (document.visibilityState === 'visible') refetchPermissions()
}

function attach() {
  if (timer) return
  timer = setInterval(() => refetchPermissions(true, true), REFETCH_INTERVAL_MS)
  window.addEventListener('focus', onFocus)
  document.addEventListener('visibilitychange', onVisibility)
  unsubBus = forbiddenBus.subscribe(() => refetchPermissions())
}

function detach() {
  if (!timer) return
  clearInterval(timer)
  timer = null
  window.removeEventListener('focus', onFocus)
  document.removeEventListener('visibilitychange', onVisibility)
  unsubBus?.()
  unsubBus = null
}

function subscribe(cb: () => void): () => void {
  subscribers.add(cb)
  attach()
  return () => {
    subscribers.delete(cb)
    if (subscribers.size === 0) detach()
  }
}

export interface UsePermissions extends PermissionsState {
  /** Fail-closed: false while loading or on error. */
  can: (code: string) => boolean
  refetch: () => void
}

export function usePermissions(): UsePermissions {
  const { data: session, status: sessionStatus } = useSession()
  const snapshot = useSyncExternalStore(subscribe, getPermissionsState, () => INITIAL)
  const accessToken = sessionStatus === 'authenticated' ? session?.accessToken : null

  useEffect(() => {
    setPermissionsToken(accessToken)
  }, [accessToken])

  const can = useCallback((code: string) => canWith(snapshot, code), [snapshot])
  const refetch = useCallback(() => refetchPermissions(true), [])
  return { ...snapshot, can, refetch }
}
