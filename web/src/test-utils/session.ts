import type { Session } from 'next-auth'
import { Role } from '@/lib/roles'

/**
 * Shared session fixture for page/component tests. Every field a real
 * next-auth `Session` carries that app code actually reads (AppShell,
 * apiFetch, features.ts) gets a sane default so tests only override what
 * the scenario cares about.
 */
export function makeSession(overrides: Partial<Session> = {}): Session {
  return {
    accessToken: 'test-access-token',
    idToken: 'test-id-token',
    username: 'canbo1',
    displayName: 'canbo1',
    roles: [Role.CAN_BO],
    expires: '2999-01-01T00:00:00.000Z',
    ...overrides,
  } as Session
}

export const canBoSession = () =>
  makeSession({ username: 'canbo1', displayName: 'canbo1', roles: [Role.CAN_BO] })
export const lanhDaoSession = () =>
  makeSession({ username: 'lanhdao1', displayName: 'lanhdao1', roles: [Role.LANH_DAO] })
export const quanTriSession = () =>
  makeSession({ username: 'admin1', displayName: 'admin1', roles: [Role.QUAN_TRI] })
