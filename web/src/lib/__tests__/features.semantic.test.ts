import { describe, expect, it } from 'vitest'
import { FEATURES, canSeeFeature } from '@/lib/features'
import { canBoSession, lanhDaoSession, quanTriSession } from '@/test-utils/session'

/** search-semantic AC-1.4: menu "Tra cứu ngữ nghĩa" for R1/R2 only, own route, separate from /search. */
describe('semantic-search feature', () => {
  it('is declared at /search/semantic with the verbatim menu label', () => {
    const feature = FEATURES.find((f) => f.key === 'semantic-search')
    expect(feature).toMatchObject({ label: 'Tra cứu ngữ nghĩa', href: '/search/semantic', enabled: true })
  })

  it('is visible to R1 and R2 but not R3', () => {
    expect(canSeeFeature(canBoSession(), 'semantic-search')).toBe(true)
    expect(canSeeFeature(lanhDaoSession(), 'semantic-search')).toBe(true)
    expect(canSeeFeature(quanTriSession(), 'semantic-search')).toBe(false)
  })

  it('keeps /search from also highlighting on /search/semantic', () => {
    const search = FEATURES.find((f) => f.key === 'search')
    expect(search?.activeExcludePrefixes).toContain('/search/semantic')
  })
})
