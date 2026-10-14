'use client';

import type { Session } from 'next-auth';
import { useEffect, useState } from 'react';
import { apiFetch } from '@/lib/api';

/** Above this the browser would hold a big blob in memory - download instead. */
export const PREVIEW_MAX_BYTES = 20 * 1024 * 1024;
const TEXT_PREVIEW_CHARS = 20_000;

type Kind = 'pdf' | 'image' | 'text' | 'audio' | 'video';

export function previewKind(mimeType: string): Kind | null {
  if (mimeType === 'application/pdf') return 'pdf';
  if (mimeType.startsWith('image/') && mimeType !== 'image/tiff') return 'image';
  if (mimeType.startsWith('text/')) return 'text';
  if (mimeType.startsWith('audio/')) return 'audio';
  if (mimeType.startsWith('video/')) return 'video';
  return null;
}

/**
 * On-demand preview of a document's content. Loading is a deliberate click:
 * fetching /content is audited as a download, and files can be large.
 */
export function DocumentPreview({
  session,
  documentId,
  mimeType,
  size,
}: {
  session: Session | null;
  documentId: string;
  mimeType: string;
  size: number;
}) {
  const kind = previewKind(mimeType);
  const [url, setUrl] = useState<string | null>(null);
  const [text, setText] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => () => {
    if (url) URL.revokeObjectURL(url);
  }, [url]);

  if (!kind) {
    return <p className="text-sm text-gray-400">Định dạng này chưa hỗ trợ xem trước — hãy tải xuống để mở.</p>;
  }
  if (size > PREVIEW_MAX_BYTES) {
    return <p className="text-sm text-gray-400">Tệp lớn hơn 20 MB — hãy tải xuống để xem.</p>;
  }

  const load = () => {
    setLoading(true);
    setError(null);
    apiFetch(`/documents/${documentId}/content`, session)
      .then((res) => res.blob())
      .then(async (blob) => {
        if (kind === 'text') {
          const all = await blob.text();
          setText(all.length > TEXT_PREVIEW_CHARS ? `${all.slice(0, TEXT_PREVIEW_CHARS)}\n…` : all);
        } else {
          setUrl(URL.createObjectURL(blob));
        }
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  };

  if (!url && text === null) {
    return (
      <div>
        <button
          type="button"
          onClick={load}
          disabled={loading}
          className="rounded-md border border-gray-300 px-4 py-2 text-sm font-semibold text-[#0D2546] hover:bg-gray-50 cursor-pointer disabled:opacity-60"
        >
          {loading ? 'Đang tải…' : 'Xem trước nội dung'}
        </button>
        {error && <p className="mt-2 text-sm text-red-700">{error}</p>}
      </div>
    );
  }

  if (text !== null) {
    return (
      <pre className="max-h-[32rem] overflow-auto whitespace-pre-wrap rounded-md bg-gray-50 p-4 text-sm text-gray-800">
        {text}
      </pre>
    );
  }
  switch (kind) {
    case 'pdf':
      return <iframe src={url!} title="Xem trước tài liệu" className="h-[40rem] w-full rounded-md border border-gray-200" />;
    case 'image':
      // eslint-disable-next-line @next/next/no-img-element -- blob URL, next/image cannot optimize it
      return <img src={url!} alt="Xem trước tài liệu" className="max-h-[40rem] max-w-full rounded-md border border-gray-200" />;
    case 'audio':
      return <audio src={url!} controls className="w-full" />;
    case 'video':
      return <video src={url!} controls className="max-h-[32rem] w-full rounded-md bg-black" />;
  }
}
