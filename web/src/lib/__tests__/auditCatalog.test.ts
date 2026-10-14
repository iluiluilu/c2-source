import { describe, expect, it } from 'vitest'
import {
  AUDIT_ACTIONS,
  AUDIT_ACTION_GROUPS,
  AUDIT_RESOURCE_TYPES,
  actionLabel,
  metadataLabel,
  outcomeLabel,
  resourceTypeInfo,
  shortResourceId,
} from '@/lib/auditCatalog'

/**
 * audit-ui-readability.md §5: unit coverage for the static catalog +
 * lookup functions, independent of page.tsx rendering.
 */
describe('actionLabel', () => {
  it('returns the catalog label for a known code', () => {
    expect(actionLabel('document.create')).toBe('Tạo tài liệu')
  })

  it('falls back to "Yêu cầu bị từ chối (<label>)" for an unknown <x>.request_rejected, when x matches a resource type', () => {
    expect(actionLabel('personal_file.request_rejected')).toBe('Yêu cầu bị từ chối (Tệp cá nhân)')
  })

  it('falls back to "Yêu cầu bị từ chối (<x>)" for an unknown <x>.request_rejected, when x matches nothing', () => {
    expect(actionLabel('foo.request_rejected')).toBe('Yêu cầu bị từ chối (foo)')
  })

  it('falls back to the raw code for a fully unknown code', () => {
    expect(actionLabel('some.unknown.action')).toBe('some.unknown.action')
  })
})

describe('resourceTypeInfo', () => {
  it('returns the catalog label/icon for a known code', () => {
    expect(resourceTypeInfo('document')).toEqual({ label: 'Tài liệu', icon: '📄' })
  })

  it('falls back to the raw code + a generic icon for an unknown code', () => {
    expect(resourceTypeInfo('some_unknown_type')).toEqual({ label: 'some_unknown_type', icon: '•' })
  })

  it('returns null for null', () => {
    expect(resourceTypeInfo(null)).toBeNull()
  })

  it('returns null for an empty string', () => {
    expect(resourceTypeInfo('')).toBeNull()
  })
})

describe('outcomeLabel', () => {
  it('success (any statusCode) -> Thành công', () => {
    expect(outcomeLabel('success', 200)).toBe('Thành công')
    expect(outcomeLabel('success', null)).toBe('Thành công')
  })

  it('failure + 401 -> Chưa đăng nhập', () => {
    expect(outcomeLabel('failure', 401)).toBe('Chưa đăng nhập')
  })

  it('failure + 403 -> Không có quyền', () => {
    expect(outcomeLabel('failure', 403)).toBe('Không có quyền')
  })

  it('failure + 404 -> Không tìm thấy', () => {
    expect(outcomeLabel('failure', 404)).toBe('Không tìm thấy')
  })

  it('failure + 409 -> Xung đột dữ liệu', () => {
    expect(outcomeLabel('failure', 409)).toBe('Xung đột dữ liệu')
  })

  it('failure + other 4xx -> Yêu cầu không hợp lệ', () => {
    expect(outcomeLabel('failure', 422)).toBe('Yêu cầu không hợp lệ')
  })

  it('failure + 5xx -> Lỗi hệ thống', () => {
    expect(outcomeLabel('failure', 500)).toBe('Lỗi hệ thống')
    expect(outcomeLabel('failure', 503)).toBe('Lỗi hệ thống')
  })

  it('failure + null statusCode -> Thất bại', () => {
    expect(outcomeLabel('failure', null)).toBe('Thất bại')
  })

  it('skipped (any statusCode) -> Bỏ qua', () => {
    expect(outcomeLabel('skipped', null)).toBe('Bỏ qua')
  })

  it('an unknown outcome falls back to the raw value', () => {
    expect(outcomeLabel('weird_outcome', null)).toBe('weird_outcome')
  })
})

describe('shortResourceId', () => {
  it('shortens a standard UUID to its first 8 chars + ellipsis', () => {
    expect(shortResourceId('3fa85f64-5717-4562-b3fc-2c963f66afa6')).toBe('3fa85f64…')
  })

  it('leaves a short non-UUID string unchanged', () => {
    expect(shortResourceId('doc-1')).toBe('doc-1')
  })

  it('leaves a numeric string unchanged', () => {
    expect(shortResourceId('12345')).toBe('12345')
  })
})

describe('metadataLabel', () => {
  it('returns the Vietnamese label for a known key', () => {
    expect(metadataLabel('documentId')).toBe('ID tài liệu')
  })

  it('returns the raw key when unknown', () => {
    expect(metadataLabel('someUnknownKey')).toBe('someUnknownKey')
  })
})

describe('catalog integrity', () => {
  it('has no duplicate action codes', () => {
    const codes = AUDIT_ACTIONS.map((a) => a.code)
    expect(new Set(codes).size).toBe(codes.length)
  })

  it('has no duplicate resource type codes', () => {
    const codes = AUDIT_RESOURCE_TYPES.map((r) => r.code)
    expect(new Set(codes).size).toBe(codes.length)
  })

  it('every action belongs to a declared group', () => {
    for (const action of AUDIT_ACTIONS) {
      expect(AUDIT_ACTION_GROUPS).toContain(action.group)
    }
  })

  it('covers every action code from spec §8.1 (snapshot)', () => {
    expect(AUDIT_ACTIONS.map((a) => a.code).sort()).toMatchSnapshot()
  })

  it('covers every resource type code from spec §8.2 (snapshot)', () => {
    expect(AUDIT_RESOURCE_TYPES.map((r) => r.code).sort()).toMatchSnapshot()
  })
})
