import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PermissionsBanner } from '@/components/PermissionsBanner'
import { usePermissions } from '@/lib/permissions'

vi.mock('@/lib/permissions', () => ({ usePermissions: vi.fn() }))

const mocked = vi.mocked(usePermissions)

function mockState(status: 'loading' | 'ready' | 'error', refetch = vi.fn()) {
  mocked.mockReturnValue({
    status,
    roles: [],
    permissions: [],
    can: () => false,
    refetch,
  })
  return refetch
}

describe('PermissionsBanner', () => {
  afterEach(() => cleanup())

  it('is hidden while loading or ready', () => {
    mockState('loading')
    const { container, rerender } = render(<PermissionsBanner />)
    expect(container.firstChild).toBeNull()
    mockState('ready')
    rerender(<PermissionsBanner />)
    expect(container.firstChild).toBeNull()
  })

  it('shows the banner on error and the retry button calls refetch (AC-8)', () => {
    const refetch = mockState('error')
    render(<PermissionsBanner />)
    expect(screen.getByRole('alert')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }))
    expect(refetch).toHaveBeenCalledTimes(1)
  })
})
