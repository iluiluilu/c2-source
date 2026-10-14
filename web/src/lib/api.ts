import type { Session } from 'next-auth'
import { getSession } from 'next-auth/react'
import { forbiddenBus } from './forbiddenBus'
import { sessionExpiredBus } from './sessionExpiredBus'

/**
 * Shared client for the Spring Boot backend. Every call carries the Keycloak
 * bearer token from the NextAuth session; the backend enforces roles (RBAC)
 * - UI gating is a courtesy only.
 */
export const API_BASE =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:3001'

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    /** Parsed JSON error body, when the response had one (e.g. a 409 with a confirmationToken). */
    public readonly body?: unknown,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

const SESSION_EXPIRED_MESSAGE = 'Phiên đăng nhập đã hết hạn.'

async function toApiError(res: Response): Promise<ApiError> {
  let message = `${res.status} ${res.statusText}`
  let parsed: unknown
  try {
    parsed = (await res.json()) as { message?: string | string[] }
    const body = parsed as { message?: string | string[] }
    if (body.message) {
      message = Array.isArray(body.message)
        ? body.message.join('; ')
        : body.message
    }
  } catch {
    if (res.status === 401) message = SESSION_EXPIRED_MESSAGE
  }
  if (res.status === 403) forbiddenBus.emit()
  return new ApiError(res.status, message, parsed)
}

// ---- access-token refresh on 401 ----
//
// The refresh token lives server-side in the NextAuth cookie, so "refresh"
// here means asking NextAuth for the session again: its jwt callback
// exchanges the refresh token with Keycloak once the access token expired.
// There is no background poll (Providers.tsx) - a refresh only happens when a
// backend call is actually rejected, so Keycloak's SSO idle timeout still
// logs out a user who stopped working.

type SessionRefresher = () => Promise<Session | null | undefined>

let refresher: SessionRefresher = () => getSession()
let pendingRefresh: Promise<string | null> | null = null
/** Callers may still hold the session object with the token that just died. */
let replacedToken: { dead: string; fresh: string } | null = null

/**
 * SessionErrorGuard registers useSession().update here, so a refresh also
 * pushes the new session into SessionProvider (and every useSession()).
 */
export function setSessionRefresher(fn: SessionRefresher | null): void {
  refresher = fn ?? (() => getSession())
}

function currentToken(token: string | undefined): string {
  if (token && replacedToken?.dead === token) return replacedToken.fresh
  return token ?? ''
}

/** Single-flight: concurrent 401s share one refresh. null = session is gone. */
function refreshToken(dead: string): Promise<string | null> {
  if (!pendingRefresh) {
    pendingRefresh = (async () => {
      try {
        const s = (await refresher()) ?? (await getSession())
        return s?.accessToken && !s.error ? s.accessToken : null
      } catch {
        return null
      }
    })().finally(() => {
      pendingRefresh = null
    })
  }
  return pendingRefresh.then((fresh) => {
    if (fresh && fresh !== dead) replacedToken = { dead, fresh }
    return fresh
  })
}

/**
 * A 401 with no JSON body is Spring Security rejecting the bearer token
 * itself (expired/invalid) - distinct from a controller-level 401 business
 * error, which always carries a {message} body.
 */
async function isTokenRejected(res: Response): Promise<boolean> {
  if (res.status !== 401) return false
  try {
    const body = (await res.clone().json()) as { message?: unknown }
    return !body?.message
  } catch {
    return true
  }
}

/**
 * fetch with the bearer token; on a rejected token refresh once and retry.
 * If the session can't be refreshed (or the retry is rejected too) the
 * session-expired popup is raised and the 401 response is returned.
 */
export interface ApiInit extends RequestInit {
  /**
   * Background polls (e.g. the notification bell) set this: a rejected
   * token is NOT refreshed, since a refresh counts as activity for
   * Keycloak's SSO idle timeout. The caller just gets the 401.
   */
  noRefresh?: boolean
}

export async function fetchWithToken(
  url: string,
  token: string | undefined,
  init?: ApiInit,
): Promise<Response> {
  const { noRefresh, ...rest } = init ?? {}
  const send = (t: string) =>
    fetch(url, {
      ...rest,
      headers: { Authorization: `Bearer ${t}`, ...rest.headers },
    })
  const used = currentToken(token)
  const res = await send(used)
  if (noRefresh || !used || !(await isTokenRejected(res))) return res

  const fresh = await refreshToken(used)
  if (!fresh) {
    sessionExpiredBus.emit()
    return res
  }
  const retried = await send(fresh)
  if (await isTokenRejected(retried)) sessionExpiredBus.emit()
  return retried
}

export async function apiFetch(
  path: string,
  session: Session | null,
  init?: ApiInit,
): Promise<Response> {
  const res = await fetchWithToken(`${API_BASE}${path}`, session?.accessToken, init)
  if (!res.ok) {
    throw await toApiError(res)
  }
  return res
}

export async function apiJson<T>(
  path: string,
  session: Session | null,
  init?: ApiInit,
): Promise<T> {
  const res = await apiFetch(path, session, init)
  return (await res.json()) as T
}

/**
 * Same contract as apiJson, but for a multipart file upload that wants
 * live progress (bytes sent so far). fetch() has no cross-browser way to
 * observe request-body upload progress, so this uses XMLHttpRequest instead
 * - only for this one case, everything else keeps using fetch via apiFetch.
 */
export function apiUploadJson<T>(
  path: string,
  session: Session | null,
  form: FormData,
  onProgress?: (loadedBytes: number) => void,
): Promise<T> {
  const used = currentToken(session?.accessToken)
  return sendUpload<T>(path, used, form, onProgress).catch(async (err) => {
    // Same refresh-once-and-retry as fetchWithToken (the form is re-sent).
    if (!(err instanceof ApiError) || !isRejectedUpload(err) || !used) throw err
    const fresh = await refreshToken(used)
    if (!fresh) {
      sessionExpiredBus.emit()
      throw err
    }
    return sendUpload<T>(path, fresh, form, onProgress).catch((retryErr) => {
      if (retryErr instanceof ApiError && isRejectedUpload(retryErr)) sessionExpiredBus.emit()
      throw retryErr
    })
  })
}

function isRejectedUpload(err: ApiError): boolean {
  const body = err.body as { message?: unknown } | undefined
  return err.status === 401 && !body?.message
}

function sendUpload<T>(
  path: string,
  token: string,
  form: FormData,
  onProgress?: (loadedBytes: number) => void,
): Promise<T> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', `${API_BASE}${path}`)
    xhr.setRequestHeader('Authorization', `Bearer ${token}`)
    xhr.upload.onprogress = (e) => {
      if (onProgress) onProgress(e.loaded)
    }
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          resolve(xhr.responseText ? (JSON.parse(xhr.responseText) as T) : (undefined as T))
        } catch {
          resolve(undefined as T)
        }
        return
      }
      let message = `${xhr.status} ${xhr.statusText}`
      let parsed: unknown
      try {
        parsed = JSON.parse(xhr.responseText) as { message?: string | string[] }
        const body = parsed as { message?: string | string[] }
        if (body.message) {
          message = Array.isArray(body.message) ? body.message.join('; ') : body.message
        }
      } catch {
        if (xhr.status === 401) message = SESSION_EXPIRED_MESSAGE
      }
      if (xhr.status === 403) forbiddenBus.emit()
      reject(new ApiError(xhr.status, message, parsed))
    }
    xhr.onerror = () => reject(new Error('Lỗi mạng khi tải lên.'))
    xhr.send(form)
  })
}

// ---- Shared backend response shapes ----

export interface FolderNode {
  id: string
  name: string
  /** 1 = root. warehouse-structure.md: additive field, max depth 5. */
  depth: number
  children: FolderNode[]
}

// ---- FR-13 warehouse capacity (warehouse-structure.md Story 5) ----

export type WarehouseCapacityStatus = 'OK' | 'WARNING' | 'BLOCKED'

/** GET/PATCH /warehouse/capacity. */
export interface WarehouseCapacity {
  usedBytes: number
  maxCapacityBytes: number
  usedPercent: number
  warningThresholdPercent: number
  blockThresholdPercent: number
  status: WarehouseCapacityStatus
  /** delete-restore-trash-01: bytes currently sitting in the trash (subset of usedBytes). */
  trashBytes: number
}

// ---- FR-11/FR-12 batch ingest (data-ingestion.md) ----

export type UploadBatchStatus = 'OPEN' | 'CLOSED' | 'LIMIT_EXCEEDED' | 'EXPIRED'

/** GET /ingest/limits - server-side source of truth, used for a client-side pre-check (Story 2 AC-3a). */
export interface IngestLimits {
  maxFilesPerBatch: number
  maxBatchSizeBytes: number
  maxFileSizeBytes: number
  /**
   * chunked-file-upload-01-ingest-chunked-upload.md Quyết định 4: a file
   * bigger than this goes through the chunked-upload session protocol
   * (`uploadOneChunked`) instead of the whole-file `POST .../files` call.
   */
  chunkThresholdBytes: number
}

// ---- chunked-file-upload-01-ingest-chunked-upload.md §3: upload-session protocol ----

/** POST /ingest/batches/{batchId}/upload-sessions response. */
export interface OpenUploadSessionResponse {
  sessionId: string
  chunkSizeBytes: number
  receivedBytes: number
  expiresAt: string
}

/** PUT /upload-sessions/{sessionId}/chunks response. */
export interface ChunkAckResponse {
  sessionId: string
  receivedBytes: number
  declaredSizeBytes: number
}

/** GET /upload-sessions/{sessionId} response - used to resync `receivedBytes` after a transient error (AC-4). */
export interface UploadSessionStatusResponse {
  sessionId: string
  status: 'OPEN' | 'COMPLETED' | 'ABORTED' | 'EXPIRED'
  receivedBytes: number
  declaredSizeBytes: number
  chunkSizeBytes: number
  expiresAt: string
}

/** GET /ingest/allowed-formats - drives the file picker's `accept` attribute. */
export interface AllowedFormat {
  mimeType: string
  extensions: string[]
}

/** POST /ingest/batches response. */
export interface OpenBatchResponse {
  batchId: string
  status: UploadBatchStatus
  expiresAt: string
}

/** GET /ingest/batches/{id} response. */
export interface BatchStatusResponse {
  batchId: string
  status: UploadBatchStatus
  declaredFileCount: number
  declaredTotalBytes: number
  actualFileCount: number
  actualTotalBytes: number
  expiresAt: string
}

export interface CloseBatchSummary {
  filesAccepted: number
  filesSkipped: number
  filesRejected: number
  actualTotalBytes: number
}

/** POST /ingest/batches/{id}/close response. */
export interface CloseBatchResponse {
  batchId: string
  status: UploadBatchStatus
  summary: CloseBatchSummary
}

/** POST /ingest/batches/{id}/files - 201 body. */
export interface IngestedFileResult {
  status: 'INGESTED'
  document: DocumentRow
  duplicateOf: string[]
}

/**
 * folder-upload-display-name: the old `SKIPPED ALREADY_EXISTS` outcome is gone - re-uploading a name
 * creates a new copy under a suffixed `displayName`; a clash answers 409 `DISPLAY_NAME_CONFLICT`.
 */
export type IngestFileResult = IngestedFileResult

/** 409 body / 422 body of the display-name rules (folder-upload-display-name). */
export interface DisplayNameErrorBody {
  code: 'DISPLAY_NAME_CONFLICT' | 'INVALID_DISPLAY_NAME'
  reason?: string
  message?: string
}

/** POST /ingest/name-check | /personal/name-check. */
export interface NameCheckRequest {
  folderId: string | null
  files: { relativePath: string; originalName: string }[]
}

export interface NameCheckResult {
  taken: boolean
}

// ---- Story 5 (data-ingestion.md): format allowlist admin ----

export interface AllowedFormatDetail {
  mimeType: string
  extensions: string[]
  category: string
  updatedAt: string
}

export interface DocumentRow {
  id: string
  /** The file's real name (download name, search, main label in lists). */
  originalName: string
  /** Unique name inside (folder, relativePath); equals originalName unless a clash got a suffix. */
  displayName: string
  mimeType: string
  size: string
  /** data-ingestion.md §2.3: null for a 'rejected' row (format check runs before hashing). */
  sha256: string | null
  relativePath: string
  folderId: string
  uploaderUsername: string
  status: 'pending' | 'clean' | 'infected' | 'rejected'
  createdAt: string
  /** FR-15: present on GET /documents rows (absent elsewhere). */
  tags?: TagRow[]
  /** GET /documents: the caller's groups this document reaches them through. */
  sharedViaGroups?: string[]
}

// ---- FR-15 tags ----

/** Mirrors backend TagKinds: `doc_type` (auto-assigned from the format) and admin-managed `topic`. */
export type TagKind = 'topic' | 'doc_type'

export const TAG_KIND_LABELS: Record<TagKind, string> = {
  doc_type: 'Loại tài liệu',
  topic: 'Chủ đề',
}

export interface TagRow {
  id: string
  kind: TagKind
  label: string
  createdByUsername: string
  createdAt: string
}

// ---- FR-16 notes ----

export interface DocumentNote {
  id: string
  documentId: string
  body: string
  createdByUsername: string
  updatedByUsername: string | null
  createdAt: string
  updatedAt: string
}

// ---- FR-14 personal storage (personal-storage-quota.md) ----

export interface PersonalFileRow {
  id: string
  folderId: string | null
  originalName: string
  displayName: string
  relativePath: string
  mimeType: string
  size: string
  sha256: string
  /** personal-storage-quota.md §2: additive, the batch that created this row. */
  batchId: string | null
  createdAt: string
  /** personal-to-warehouse.md AC-21: a live (non-trashed) warehouse copy exists. */
  addedToWarehouse?: boolean
}

// ---- personal-to-warehouse.md §3: POST/GET /ingest/from-personal ----

export interface FromPersonalRequest {
  targetFolderId: string
  note?: string
  fileIds: string[]
  folderIds: string[]
  /** folder-upload-display-name: the display name chosen for every expanded personal file. */
  displayNames?: { personalFileId: string; displayName: string }[]
}

/** POST /ingest/from-personal/name-check element (same order as the expanded selection). */
export interface FromPersonalNameCheckItem {
  personalFileId: string
  relativePath: string
  originalName: string
  taken: boolean
}

export interface FromPersonalIneligible {
  personalFileId: string
  name: string
  code: string
}

export interface FromPersonalStartResponse {
  batchId: string
  total: number
  ineligible: FromPersonalIneligible[]
  expiresAt: string
}

export type FromPersonalItemStatus = 'PENDING' | 'INGESTED' | 'SKIPPED' | 'FAILED'

export interface FromPersonalItem {
  personalFileId: string
  /** personal_ingest_item id - needed for PUT .../items/{itemId}/display-name. */
  itemId?: string
  displayName?: string | null
  /** Times the client already renamed this item after DISPLAY_NAME_CONFLICT (server allows 3). */
  nameRetryCount?: number
  name: string
  status: FromPersonalItemStatus
  code: string | null
  documentId: string | null
  duplicateOf: string[]
}

export interface FromPersonalResult {
  batchId: string
  status: 'OPEN' | 'CLOSED'
  done: number
  total: number
  items: FromPersonalItem[]
}

export interface QuotaInfo {
  quotaBytes: number
  usedBytes: number
  remainingBytes: number
  /**
   * chunked-file-upload-02-personal-chunked-upload.md §2.3: same
   * `system_config` value as ingest's `chunkThresholdBytes` (AC-1) - a file
   * bigger than this goes through the chunked upload-session protocol
   * instead of the whole-file `POST .../files` call.
   */
  chunkThresholdBytes: number
}

/** POST /personal/batches response. */
export interface OpenPersonalBatchResponse {
  batchId: string
  status: UploadBatchStatus
  expiresAt: string
}

/** GET /personal/batches/{id} response. */
export interface PersonalBatchStatusResponse {
  batchId: string
  status: UploadBatchStatus
  declaredFileCount: number
  declaredTotalBytes: number
  actualFileCount: number
  actualTotalBytes: number
  expiresAt: string
}

export interface PersonalCloseBatchSummary {
  filesStored: number
  actualTotalBytes: number
}

/** POST /personal/batches/{id}/close response. */
export interface PersonalCloseBatchResponse {
  batchId: string
  status: UploadBatchStatus
  summary: PersonalCloseBatchSummary
}

/** POST /personal/batches/{id}/files - 201 body. */
export interface PersonalUploadedFileResponse {
  status: 'STORED'
  file: PersonalFileRow
}

/** 409 QUOTA_EXCEEDED error body (personal-storage-quota.md §3). */
export interface PersonalQuotaExceededError {
  code: 'QUOTA_EXCEEDED'
  message: string
  usedBytes: number
  quotaBytes: number
  remainingBytes: number
}

// ---- group-management-sharing-01-group-lifecycle.md: groups & sharing ----

export type GroupMemberRole = 'owner' | 'member'

/** GET/POST/PATCH /groups row. Mirrors backend Group. */
export interface GroupRow {
  id: string
  name: string
  description: string | null
  ownerSub: string
  createdAt: string
  updatedAt: string
  /** GET /groups only (GroupsService.GroupListView). */
  memberCount?: number
  documentCount?: number
  myRole?: GroupMemberRole | null
}

/**
 * GET /groups/{id}/members row. Mirrors backend GroupsService.GroupMemberView.
 * `username`/`displayName` are `null` only if the Keycloak join failed (down,
 * or the account was deleted there) - fall back to `userSub` in that case.
 */
export interface GroupMemberRow {
  userSub: string
  username: string | null
  displayName: string | null
  roleInGroup: GroupMemberRole
  joinedAt: string
}

/** 422 GROUP_LIMIT_EXCEEDED error body (Story 6). */
export interface GroupLimitExceededError {
  code: 'GROUP_LIMIT_EXCEEDED'
  message: string
}

/** GET /users/directory row - a minimal user picker for "add member" (Story 5). */
export interface UserDirectoryEntry {
  sub: string
  username: string
  displayName: string
  role: string | null
}

// ---- group-management-sharing-02-document-sharing.md: bulk share, group->documents, notifications ----

/** GET /documents/{id}/shares row. Mirrors backend GroupsService.ShareView. */
export interface DocumentShareRow {
  groupId: string
  groupName: string | null
  sharedBySub: string
  sharedAt: string
}

export type BulkShareItemStatus = 'shared' | 'already_shared' | 'rejected'

/** One item of the POST /documents/shares response. */
export interface BulkShareResultItem {
  documentId: string
  status: BulkShareItemStatus
  reason: string | null
}

/** POST /documents/shares response. */
export interface BulkShareResponse {
  results: BulkShareResultItem[]
}

/** GET /groups/{id}/documents row - the reverse of DocumentShareRow. */
export interface GroupDocumentRow {
  documentId: string
  title: string
  uploaderSub: string
  uploaderUsername: string | null
  uploaderDisplayName: string | null
  sharedBySub: string
  sharedByUsername: string | null
  sharedByDisplayName: string | null
  sharedAt: string
}

/** GET /groups/{id}/documents response (GroupsController.GroupDocumentsPage). */
export interface GroupDocumentsPageResponse {
  content: GroupDocumentRow[]
  totalElements: number
  page: number
  size: number
  totalPages: number
}

export type NotificationType =
  | 'group.member_added'
  | 'group.member_removed'
  | 'document.shared_to_group'

/** GET /notifications row. */
export interface NotificationRow {
  id: string
  type: NotificationType
  payload: Record<string, unknown>
  createdAt: string
  readAt: string | null
}

// ---- FR-04/05/06 accounts ----

/** account-lifecycle.md: account.status values. */
export type AccountStatus = 'active' | 'locked' | 'disabled'

/** GET /accounts row (R3 only). Mirrors backend AccountsService.AccountRow. */
export interface AccountRow {
  id: string
  username: string
  firstName: string | null
  lastName: string | null
  email: string | null
  enabled: boolean
  roles: string[]
  status: AccountStatus
  statusReason: string | null
  storageQuotaBytes: number
}

// ---- FR-01 system configuration ----

export type ConfigValueType = 'STRING' | 'NUMBER' | 'BOOLEAN' | 'JSON'

/** GET /admin/system-config row (R3 only). Mirrors backend ConfigView. */
export interface SystemConfigRow {
  key: string
  description: string
  valueType: ConfigValueType
  unit: string | null
  value: string | number | boolean | object
  defaultValue: string | number | boolean | object
  usingDefault: boolean
  sensitive: boolean
}

/** GET /admin/system-config/{key}/history entry (Story 5). */
export interface SystemConfigHistoryEntry {
  oldValue: string | number | boolean | object | null
  newValue: string | number | boolean | object | null
  actor: string
  createdAt: string
}

/** Response of a successful PATCH/confirm/rollback. */
export interface SystemConfigUpdateResult {
  key: string
  value: string | number | boolean | object
  updatedAt: string
}

// ---- rbac-permission-editing.md: RBAC role x permission matrix (R3 only) ----

/** GET /admin/rbac/roles row. Mirrors backend RbacService.RoleView. */
export interface RbacRoleRow {
  code: string
  displayName: string
  permissionCodes: string[]
}

/** GET /admin/rbac/permissions row. Mirrors backend RbacService.PermissionView. */
export interface RbacPermissionRow {
  code: string
  description: string
  /**
   * export-reporting AC-5: false = permission that can never be granted to QUAN_TRI (e.g. report.*);
   * the RBAC screen hides that checkbox. Optional for older backends (treated as assignable).
   */
  adminAssignable?: boolean
}

/** GET /admin/rbac/roles/{roleCode}/preview-impact response (Story 2 AC-5). */
export interface RbacPreviewImpact {
  affectedAccountCount: number
  permissionsAdded: string[]
  permissionsRemoved: string[]
}

/** PUT /admin/rbac/roles/{roleCode}/permissions response. */
export interface RbacUpdateResult {
  roleCode: string
  permissionCodes: string[]
  affectedAccountCount: number
}

/** 409 RBAC_LOCKOUT error body (Story 3 AC-2 - Quyết định 3). */
export interface RbacLockoutError {
  code: 'RBAC_LOCKOUT'
  message: string
}

// ---- audit-logging.md: activity log (R2 scoped / R3 unrestricted) ----

/** GET /audit row. Mirrors backend AuditLog. */
export interface AuditLogRow {
  id: number
  createdAt: string
  actor: string
  actorSub: string | null
  action: string
  resourceType: string | null
  resourceId: string | null
  outcome: string
  method: string | null
  path: string | null
  statusCode: number | null
  ip: string | null
  metadata: Record<string, unknown> | null
  sealedAt: string | null
}

/** GET /audit response (AuditController.AuditPage). */
export interface AuditPageResponse {
  content: AuditLogRow[]
  totalElements: number
  page: number
  size: number
  totalPages: number
}

/** GET /audit/verify response (AuditService.ChainVerification). */
export interface AuditChainVerification {
  ok: boolean
  count: number
  brokenAtId: number | null
  reason: string | null
}

// ---- digitization-review-01/02/03/04/05: review queue + run detail ----

export type ReviewTask = 'ocr' | 'stt' | 'sed' | 'video_summary' | 'video'

export type ReviewState = 'pending' | 'in_review' | 'confirmed' | 'superseded' | 'not_applicable' | 'void'

/** digitization-review-01-ocr.md §3.1: a computed label, not a DB column. */
export type ReviewEffectiveStatus = 'pending' | 'in_review' | 'stale' | 'upcoming_deadline' | 'confirmed'

export type ReviewQualityFlag = 'ok' | 'poor'

/** GET /digitization-review/queue row + the base shape of RunDetail. */
export interface ReviewRunSummary {
  runId: string
  documentId: string
  documentName: string
  task: ReviewTask
  reviewState: ReviewState
  effectiveStatus: ReviewEffectiveStatus
  outputQualityFlag: ReviewQualityFlag
  lowConfidenceCount: number
  segmentCount: number
  reviewDeadlineAt: string | null
  firstEditAt: string | null
  /** Story 8 (-01-ocr.md): another confirmed run for the same (document, task) exists. */
  isReplacingConfirmedRun: boolean
  /**
   * digitization-review-06-queue-pending-status.md §2: raw
   * `digitization_run.processing_status` ('queued' | 'running' | 'succeeded'
   * | 'failed_retryable' | 'failed_permanent' | 'cancelled'), always present
   * regardless of effectiveStatus - only read when effectiveStatus ===
   * 'pending' to pick a finer-grained badge (AC-4).
   */
  processingStatus: string
}

/** GET /digitization-review/queue response (ReviewController.RunSummaryPageView). */
export interface ReviewRunSummaryPageResponse {
  content: ReviewRunSummary[]
  totalElements: number
  page: number
  size: number
  totalPages: number
}

/**
 * digitization-review-04-sed-summary.md §3.2: resolved element of a
 * video_summary segment's source_segment_ids. sourceRunReviewState can be
 * 'superseded' (AC-4 Story 10) - the deep-link still opens that exact run.
 */
export interface ReviewSourceReference {
  sourceSegmentId: string
  sourceRunId: string
  segmentIndex: number
  timeStartMs: number | null
  sourceRunReviewState: ReviewState
}

/** One content_segment, projected for the review UI (ReviewController.SegmentView). */
export interface ReviewSegment {
  segmentId: string
  segmentIndex: number
  origin: 'ai' | 'manual'
  aiText: string
  currentText: string
  currentRevisionNo: number
  isEdited: boolean
  isDeleted: boolean
  confidence: number | null
  pageNo: number | null
  /** V26: ratio 0-1 points, not pixels - digitization-review-05-frontend.md §4.2. */
  bboxPolygon: number[][] | null
  readingOrder: number | null
  timeStartMs: number | null
  timeEndMs: number | null
  /** digitization-review-02-stt.md §3.2: only ever true for task=stt/sed. */
  overlapsAdjacent: boolean
  /** Non-null only for content_source='sed' (AC-1 Story 9). */
  soundEventLabel: string | null
  /** Non-null (possibly empty) only for content_source='summary' (AC-1 Story 10). */
  sourceReferences: ReviewSourceReference[] | null
}

export interface ReviewSegmentPageResponse {
  content: ReviewSegment[]
  totalElements: number
  page: number
  size: number
  totalPages: number
}

/**
 * GET /digitization-review/runs/{runId} response (ReviewController.RunDetailView).
 *
 * Not `extends ReviewRunSummary`: backend `RunDetailView` is a separate flat
 * record that copies fields by hand from `RunSummaryView` (see its javadoc)
 * and does not include `processingStatus` - that field was added to
 * `RunSummaryView` for the /digitization queue badge only
 * (digitization-review-06-queue-pending-status.md), never to RunDetail.
 * `Omit` keeps this type honest instead of claiming a field the API doesn't
 * send.
 */
export interface ReviewRunDetail extends Omit<ReviewRunSummary, 'processingStatus'> {
  sourceDurationMs: number | null
  /** Story 8 (-01-ocr.md): the other confirmed run this one is replacing, if any. */
  previousConfirmedRunId: string | null
  segments: ReviewSegmentPageResponse
  /** Only ever true for task=video_summary (digitization-review-04-sed-summary.md §2.3). */
  staleWarning: boolean
}

/** 409 body of PATCH/DELETE .../segments/{id} (optimistic-lock conflict). */
export interface ReviewSegmentConflict {
  latestRevisionNo: number
  latestText: string
  latestTimeStartMs: number | null
  latestTimeEndMs: number | null
}

// ---- digitization-review-03-ownership-transfer.md: R3 transfer screen ----

/** V26 document_digitization_status - all 4 columns (chốt 2026-09-08). */
export interface DocumentDigitizationStatus {
  ocrStatus: string
  sttStatus: string
  sedStatus: string
  videoSummaryStatus: string
}

/** GET /admin/document-ownership row - metadata-only, no digitized content (AC-5 -03). */
export interface DocumentOwnershipRow {
  documentId: string
  originalName: string
  uploaderSub: string
  digitizationStatus: DocumentDigitizationStatus
}

export interface DocumentOwnershipPageResponse {
  content: DocumentOwnershipRow[]
  totalElements: number
  page: number
  size: number
  totalPages: number
}

/** POST /admin/document-ownership/{documentId}/transfer response. */
export interface DocumentOwnershipTransferResult {
  documentId: string
  fromUploaderSub: string
  toUploaderSub: string
  transferredAt: string
}

// ---- search-fulltext-05-frontend.md §2/§3: POST /search ----

export type SearchMediaType = 'document' | 'audio' | 'video'

export type SearchConfirmSource = 'manual' | 'auto'

export type SearchTopicTagsMode = 'AND' | 'OR'

/** search-fulltext-02-tim-kiem-nang-cao.md §2 (SearchFilters) - every field optional. */
export interface SearchFiltersBody {
  docType?: string | null
  topicTags?: string[] | null
  topicTagsMode?: SearchTopicTagsMode | null
  uploadedFrom?: string | null
  uploadedTo?: string | null
  groupIds?: string[] | null
  folderId?: string | null
  mediaType?: SearchMediaType[] | null
  confirmSource?: SearchConfirmSource | null
}

/** POST /search request body (SearchRequest). `filters` omitted entirely when every group is empty (§3.2). */
export interface SearchRequestBody {
  query?: string | null
  filters?: SearchFiltersBody | null
  sort?: string | null
  page?: number | null
  size?: number | null
}

/** search-fulltext-03-dinh-vi-ket-qua.md §2/§3 (PositionInfo). */
export interface SearchPosition {
  type: 'page' | 'time' | 'none'
  pageNo: number | null
  lineFrom: number | null
  lineTo: number | null
  bboxPolygon: number[][] | null
  highlightOffsetStart: number | null
  highlightOffsetEnd: number | null
  timeStartMs: number | null
  timeEndMs: number | null
}

/**
 * search-fulltext-01-tim-kiem-co-ban.md §3.2 (SegmentHit) +
 * search-fulltext-05-frontend.md §2 (sourceReferences, additive 2026-09-09).
 */
export interface SearchResultSegment {
  segmentId: string
  contentSource: string
  confirmSource: string
  highlight: string | null
  position: SearchPosition
  /** Non-null (possibly empty) only for contentSource='summary'. Reuses ReviewSourceReference (same shape). */
  sourceReferences: ReviewSourceReference[] | null
}

/**
 * search-fulltext-01-tim-kiem-co-ban.md §3.2 (SearchHit) +
 * search-fulltext-05-frontend.md §2 (documentDigitizationStatus, additive
 * 2026-09-09).
 */
export interface SearchResult {
  documentId: string
  documentName: string
  docType: string | null
  score: number
  segments: SearchResultSegment[]
  /** null = document never had a digitization run created - never "chờ kiểm duyệt" (BA Story 4 AC-1). */
  documentDigitizationStatus: string | null
  /** Where/who/when - absent on older backends. */
  folderId?: string | null
  uploaderUsername?: string | null
  createdAt?: string | null
}

/** search-fulltext-02-tim-kiem-nang-cao.md §3.2/§4 step 2 (RefineSuggestion, AC-12). */
export interface RefineSuggestion {
  removedFilter: string
  estimatedCount: number
}

/** POST /search response body (SearchResponse). */
export interface SearchResponseBody {
  results: SearchResult[]
  totalApprox: number
  page: number
  size: number
  degradedMode: 'none' | 'opensearch_unavailable'
  refineSuggestions: RefineSuggestion[]
}

// ---- search-semantic (FR-24): POST /search/semantic ----

/** SPEC-search-semantic §2 (SemanticResult) - one relevant passage; excerpt is read from PostgreSQL, never highlighted. */
export interface SemanticResult {
  segmentId: string
  documentId: string
  documentName: string
  docType: string | null
  excerpt: string
  contentSource: 'ocr' | 'stt' | 'note' | 'title' | 'summary'
  /** null for title/note. */
  confirmSource: 'manual' | 'auto' | null
  position: SearchPosition
  matchedLexical: boolean
  cosineScore: number | null
  rrfScore: number
  documentDigitizationStatus: string | null
  sourceReferences: ReviewSourceReference[] | null
}

export type SemanticDegradedMode = 'none' | 'lexical_only' | 'semantic_only'

export interface SemanticSearchResponseBody {
  results: SemanticResult[]
  degradedMode: SemanticDegradedMode
  insufficientEvidence: boolean
  totalApprox: number
  hasMore: boolean
  maxPages: number
}

export interface SemanticSearchRequestBody {
  query: string
  filters: SearchFiltersBody | null
  page: number
  size: number
}

// ---- delete-restore-trash-01/02/03: thùng rác (trash) ----

/** One row of GET /trash. Mirrors backend TrashService.TrashItem. */
export interface TrashItem {
  documentId: string
  originalName: string
  deletedAt: string
  purgeAfter: string
  size: number
  deletedBySub: string
  deletedByUsername: string
  uploaderSub: string
  uploaderUsername: string
  /** Folder it was deleted from (absent on older backends). */
  folderId?: string | null
}

/** GET /trash response. */
export interface TrashList {
  items: TrashItem[]
  total: number
}

/** GET /trash/{id} response. Mirrors backend TrashService.TrashDetail. */
export interface TrashDetail {
  documentId: string
  originalName: string
  size: number
  deletedAt: string
  purgeAfter: string
  deletedBySub: string
  uploaderSub: string
  folderId: string | null
  folderMissing: boolean
  suggestedFolderId: string | null
  activeGroupShareCount: number
  purgeState: string
}

/** POST /trash/{id}/restore response (200). */
export interface RestoreResult {
  documentId: string
  restoredFolderId: string
  restoredName: string
  groupsLostEffect: string[]
}

/** One element of results[] from POST /trash/restore-batch. */
export interface RestoreBatchItem {
  documentId: string
  status: 'RESTORED' | 'CONFLICT' | 'ERROR'
  code: string | null
  restoredFolderId: string | null
  groupsLostEffect: string[] | null
}

/** One element of the POST /documents/trash-preview response. */
export interface TrashPreviewItem {
  documentId: string
  activeGroupShareCount: number
}

/** delete-restore-trash-01 §4.5: 5 fixed buckets, biên đã chốt ở tầng backend. */
export interface TrashExpiryBucket {
  bucket: 'expired' | 'within_7_days' | 'within_30_days' | 'within_90_days' | 'later'
  count: number
  bytes: number
}

/** GET /trash/stats response (R3 only). */
export interface TrashStats {
  totalItems: number
  totalBytes: number
  byExpiryBucket: TrashExpiryBucket[]
  unrecoverableAccounts: { count: number; nearestPurgeAfter: string | null }
}
