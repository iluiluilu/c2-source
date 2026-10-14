'use client';

import { useSession } from 'next-auth/react';
import { useEffect, useState } from 'react';
import { ApiError, apiJson } from '@/lib/api';
import type { FolderNode, RestoreResult } from '@/lib/api';
import { FolderTree } from '@/components/FolderTree';

type Step =
  | 'loading'
  | 'folder-picker'
  | 'name-conflict'
  | 'gone'
  | 'purging'
  | 'error'
  | 'success';

export interface RestoreDialogInitialError {
  status: number;
  body: {
    code?: string;
    message?: string;
    suggestedFolderId?: string | null;
    suggestedName?: string;
  };
}

function messageFromBody(body: RestoreDialogInitialError['body'] | undefined): string {
  return body?.message ?? 'Không thể phục hồi tài liệu này.';
}

interface ErrorState {
  step: Step;
  selectedFolderId: string | null;
  suggestedName: string | null;
  errorMessage: string | null;
}

/** Pure mapping of mục 3's 8-case conflict table to UI state - no setState here (used both for the lazy initial-state computation and from the restore catch handler, see react-hooks/set-state-in-effect). */
function deriveErrorState(
  status: number,
  body: RestoreDialogInitialError['body'] | undefined,
): ErrorState {
  const code = body?.code;
  if (status === 409 && code === 'FOLDER_MISSING') {
    return { step: 'folder-picker', selectedFolderId: body?.suggestedFolderId ?? null, suggestedName: null, errorMessage: null };
  }
  if (status === 409 && code === 'NAME_CONFLICT') {
    return { step: 'name-conflict', selectedFolderId: null, suggestedName: body?.suggestedName ?? null, errorMessage: null };
  }
  if (status === 409 && code === 'PURGING') {
    return { step: 'purging', selectedFolderId: null, suggestedName: null, errorMessage: null };
  }
  if (status === 410 && code === 'GONE') {
    return { step: 'gone', selectedFolderId: null, suggestedName: null, errorMessage: null };
  }
  return { step: 'error', selectedFolderId: null, suggestedName: null, errorMessage: messageFromBody(body) };
}

/**
 * delete-restore-trash-02-xoa-cung-xung-dot.md §3: xử lý đủ 8 case xung đột
 * khi phục hồi 1 tài liệu, dùng chung cho nút "Phục hồi" đơn lẻ ở /trash
 * (gọi restore ngay khi mount) và để xử lý từng item CONFLICT/ERROR sau
 * POST /trash/restore-batch (initialError - không gọi lại restore để lấy
 * lỗi lần 2).
 */
export function RestoreDialog({
  documentId,
  initialError,
  otherConflictCount = 0,
  onApplyAll,
  onClose,
  onSuccess,
}: {
  documentId: string;
  initialError?: RestoreDialogInitialError;
  /**
   * folder-upload-display-name AC-23: how many OTHER files of the same restore are waiting on a name
   * conflict. > 0 shows the "Áp dụng cho tất cả" checkbox.
   */
  otherConflictCount?: number;
  /** Ticked "Áp dụng cho tất cả": the page sends ONE restore-batch with this choice for every conflicting file. */
  onApplyAll?: (action: 'AUTO_RENAME' | 'CANCEL') => void;
  onClose: () => void;
  onSuccess: (result: RestoreResult) => void;
}) {
  const [applyToAll, setApplyToAll] = useState(false);
  const { data: session } = useSession();
  // An item that already failed once during restore-batch (initialError)
  // starts straight at its step - computed synchronously from props instead
  // of via a mount effect (react-hooks/set-state-in-effect: no direct
  // setState calls in an effect body). Otherwise (single-item "Phục hồi"
  // button) mounting starts at 'loading' and the effect below calls restore.
  const initialDerived = initialError ? deriveErrorState(initialError.status, initialError.body) : null;
  const [step, setStep] = useState<Step>(initialDerived?.step ?? 'loading');
  const [selectedFolderId, setSelectedFolderId] = useState<string | null>(
    initialDerived?.selectedFolderId ?? null,
  );
  const [suggestedName, setSuggestedName] = useState<string | null>(initialDerived?.suggestedName ?? null);
  const [folders, setFolders] = useState<FolderNode[] | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(initialDerived?.errorMessage ?? null);
  const [result, setResult] = useState<RestoreResult | null>(null);

  // .then()/.catch() (not async/await) so every setState call sits inside a
  // callback closure, not "directly" in doRestore's own synchronous body -
  // react-hooks/set-state-in-effect flags a setState reachable from an
  // effect's own function body even past an `await`, but not one deferred
  // into a passed callback; matches the apiJson(...).then(setter) convention
  // used everywhere else in this codebase for the same reason.
  const doRestore = (targetFolderId: string | null, onNameConflict: string | null) => {
    const body: Record<string, string> = {};
    if (targetFolderId) body.targetFolderId = targetFolderId;
    if (onNameConflict) body.onNameConflict = onNameConflict;
    apiJson<RestoreResult>(`/trash/${documentId}/restore`, session, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
      .then((r) => {
        setResult(r);
        setStep('success');
        onSuccess(r);
      })
      .catch((e: unknown) => {
        if (e instanceof ApiError) {
          const derived = deriveErrorState(e.status, {
            ...(e.body as RestoreDialogInitialError['body']),
            message: e.message,
          });
          setStep(derived.step);
          setSelectedFolderId(derived.selectedFolderId);
          setSuggestedName(derived.suggestedName);
          setErrorMessage(derived.errorMessage);
        } else {
          setErrorMessage((e as Error).message);
          setStep('error');
        }
      });
  };

  const attemptRestore = (targetFolderId: string | null, onNameConflict: string | null) => {
    setStep('loading');
    doRestore(targetFolderId, onNameConflict);
  };

  // Mount: no initialError means this is the single-item "Phục hồi" button -
  // call restore right away with no target folder/conflict override. Step
  // is already 'loading' from the initial state above, so this calls the
  // fetch part directly (no synchronous setState in the effect body).
  useEffect(() => {
    if (!initialError) {
      doRestore(null, null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Lazily load the folder tree the first time we enter folder-picker.
  useEffect(() => {
    if (step === 'folder-picker' && folders === null) {
      apiJson<FolderNode[]>('/folders/tree', session)
        .then(setFolders)
        .catch(() => setFolders([]));
    }
  }, [step, folders, session]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
      onClick={step === 'gone' || step === 'purging' || step === 'success' ? onClose : undefined}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Phục hồi tài liệu"
        className="w-full max-w-md rounded-lg bg-white p-6 shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="text-lg font-bold text-[#0D2546] mb-4">Phục hồi tài liệu</h2>

        {step === 'loading' && (
          <p className="text-sm text-gray-500">Đang xử lý…</p>
        )}

        {step === 'folder-picker' && (
          <div>
            <p className="text-sm text-gray-600 mb-3">
              Thư mục gốc của tài liệu đã bị xóa, vui lòng chọn thư mục đích để
              phục hồi.
            </p>
            <div className="border border-gray-200 rounded-md p-3 max-h-64 overflow-y-auto">
              {folders === null ? (
                <p className="text-sm text-gray-400">Đang tải…</p>
              ) : (
                <FolderTree
                  nodes={folders}
                  selectedId={selectedFolderId}
                  onSelect={(id) => setSelectedFolderId(id)}
                />
              )}
            </div>
            <div className="flex justify-end gap-3 mt-4">
              <button
                type="button"
                onClick={onClose}
                className="rounded-md border border-gray-300 px-4 py-2 text-sm font-semibold text-gray-700 hover:bg-gray-50 cursor-pointer"
              >
                Hủy
              </button>
              <button
                type="button"
                disabled={selectedFolderId === null}
                onClick={() => void attemptRestore(selectedFolderId, null)}
                className="rounded-md bg-[#0D2546] px-4 py-2 text-sm font-semibold text-white hover:bg-[#163a6b] disabled:bg-gray-300 cursor-pointer disabled:cursor-not-allowed"
              >
                Phục hồi
              </button>
            </div>
          </div>
        )}

        {step === 'name-conflict' && (
          <div>
            <p className="text-sm text-gray-600 mb-4">
              Đã tồn tại tài liệu cùng tên tại thư mục đích.
            </p>
            {otherConflictCount > 0 && onApplyAll && (
              <label className="flex items-center gap-2 text-sm text-gray-700 mb-3">
                <input
                  type="checkbox"
                  checked={applyToAll}
                  onChange={(e) => setApplyToAll(e.target.checked)}
                />
                Áp dụng cho tất cả ({otherConflictCount + 1} tệp trùng tên)
              </label>
            )}
            <div className="space-y-2">
              <button
                type="button"
                onClick={() =>
                  applyToAll && onApplyAll
                    ? onApplyAll('AUTO_RENAME')
                    : void attemptRestore(selectedFolderId, 'AUTO_RENAME')
                }
                className="w-full text-left rounded-md border border-gray-200 px-4 py-2.5 text-sm hover:bg-gray-50 cursor-pointer"
              >
                <span className="font-semibold text-[#0D2546]">Đổi tên</span>
                {suggestedName && (
                  <span className="block text-xs text-gray-500 mt-0.5">
                    Tên mới: {suggestedName}
                  </span>
                )}
              </button>
              <button
                type="button"
                onClick={() => (applyToAll && onApplyAll ? onApplyAll('CANCEL') : onClose())}
                className="w-full text-left rounded-md border border-gray-200 px-4 py-2.5 text-sm font-semibold text-gray-700 hover:bg-gray-50 cursor-pointer"
              >
                Dừng khôi phục
              </button>
            </div>
          </div>
        )}

        {step === 'gone' && (
          <div>
            <p className="text-sm text-gray-600 mb-4">
              Tài liệu đã quá hạn lưu hoặc đã bị xóa cứng, không thể phục hồi
              qua thùng rác. Liên hệ quản trị để được hỗ trợ khôi phục từ bản
              sao lưu (nếu có).
            </p>
            <div className="flex justify-end">
              <button
                type="button"
                onClick={onClose}
                className="rounded-md bg-[#0D2546] px-4 py-2 text-sm font-semibold text-white hover:bg-[#163a6b] cursor-pointer"
              >
                Đóng
              </button>
            </div>
          </div>
        )}

        {step === 'purging' && (
          <div>
            <p className="text-sm text-gray-600 mb-4">
              Tài liệu đang được xóa cứng, chưa thể phục hồi.
            </p>
            <div className="flex justify-end">
              <button
                type="button"
                onClick={onClose}
                className="rounded-md bg-[#0D2546] px-4 py-2 text-sm font-semibold text-white hover:bg-[#163a6b] cursor-pointer"
              >
                Đóng
              </button>
            </div>
          </div>
        )}

        {step === 'success' && result && (
          <div>
            {result.groupsLostEffect.length > 0 ? (
              <div className="bg-yellow-50 border border-yellow-200 rounded-md p-3 text-sm text-yellow-800 mb-4">
                Phục hồi thành công. {result.groupsLostEffect.length} nhóm từng
                chia sẻ tài liệu này đã bị xóa nên không còn hiệu lực chia sẻ.
              </div>
            ) : (
              <div className="bg-green-50 border border-green-200 rounded-md p-3 text-sm text-green-800 mb-4">
                Phục hồi thành công.
              </div>
            )}
            <div className="flex justify-end">
              <button
                type="button"
                onClick={onClose}
                className="rounded-md bg-[#0D2546] px-4 py-2 text-sm font-semibold text-white hover:bg-[#163a6b] cursor-pointer"
              >
                Đóng
              </button>
            </div>
          </div>
        )}

        {step === 'error' && (
          <div>
            <p className="text-sm text-red-700 mb-4">
              {errorMessage ?? 'Đã xảy ra lỗi khi phục hồi tài liệu.'}
            </p>
            <div className="flex justify-end gap-3">
              <button
                type="button"
                onClick={onClose}
                className="rounded-md border border-gray-300 px-4 py-2 text-sm font-semibold text-gray-700 hover:bg-gray-50 cursor-pointer"
              >
                Đóng
              </button>
              <button
                type="button"
                onClick={() => setStep('folder-picker')}
                className="rounded-md bg-[#0D2546] px-4 py-2 text-sm font-semibold text-white hover:bg-[#163a6b] cursor-pointer"
              >
                Quay lại chọn thư mục
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
