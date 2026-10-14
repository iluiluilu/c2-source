import { describe as suite, expect, it } from 'vitest'
import { describe, targetHref } from '@/components/NotificationBell'
import type { NotificationRow } from '@/lib/api'

const row = (type: NotificationRow['type'], payload: Record<string, unknown>): NotificationRow => ({
  id: 'n1',
  type,
  payload,
  createdAt: '2026-10-13T10:00:00Z',
  readAt: null,
})

suite('NotificationBell describe/targetHref', () => {
  it('names the group and who added the user', () => {
    const n = row('group.member_added', { groupId: 'g1', groupName: 'Phòng A', actorUsername: 'canbo1' })
    expect(describe(n)).toBe('Bạn đã được thêm vào nhóm "Phòng A" bởi canbo1.')
    expect(targetHref(n)).toBe('/groups/g1')
  })

  it('counts shared documents and links to the group', () => {
    const n = row('document.shared_to_group', {
      groupId: 'g1',
      groupName: 'Phòng A',
      actorUsername: 'canbo1',
      documentIds: ['d1', 'd2'],
    })
    expect(describe(n)).toBe('canbo1 đã chia sẻ 2 tài liệu vào nhóm "Phòng A".')
    expect(targetHref(n)).toBe('/groups/g1')
  })

  it('keeps generic wording for rows written before groupName existed', () => {
    expect(describe(row('group.member_added', { groupId: 'g1' }))).toBe('Bạn đã được thêm vào nhóm mới.')
  })

  it('does not link to a group the user was removed from', () => {
    const n = row('group.member_removed', { groupId: 'g1', groupName: 'Phòng A' })
    expect(describe(n)).toBe('Bạn đã bị gỡ khỏi nhóm "Phòng A".')
    expect(targetHref(n)).toBeNull()
  })
})
