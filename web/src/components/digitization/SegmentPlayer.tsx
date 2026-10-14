'use client';

import type { Session } from 'next-auth';
import { useEffect, useRef, useState } from 'react';
import { apiFetch, apiJson } from '@/lib/api';
import type { DocumentRow, ReviewSegment } from '@/lib/api';

/** An explicit request to seek: `nonce` changes every click, even re-clicking the same segment. */
export interface SegmentSeekRequest {
  segmentId: string;
  nonce: number;
  /**
   * digitization-review-04-sed-summary.md §4 bước 5 (chỉnh 2026-09-23):
   * `false` = chỉ tua tới đoạn, không `.play()` - dùng cho deep-link
   * `?highlightSegmentIndex=` (trình duyệt chặn tự phát ở tab mới).
   */
  play?: boolean;
}

export interface SegmentPlayerProps {
  session: Session | null;
  documentId: string;
  /** STT/SED/video segments (real timeStartMs/timeEndMs) - digitization-review-05-frontend.md §4.3, never video_summary. */
  segments: ReviewSegment[];
  seekRequest: SegmentSeekRequest | null;
  /** Fired on free playback (not a click) so the list can highlight the segment currently playing (AC-3). */
  onActiveSegmentChange: (segmentId: string | null) => void;
}

/**
 * digitization-review-05-frontend.md §4.3 (Story 3/4 - STT/SED, never
 * video_summary, see §4.1): a single <video>/<audio> wrapper shared by both
 * tasks - seek-to-segment + auto-pause at the segment's timeEndMs + 2-way
 * highlight sync via timeupdate. Media source is loaded once as a Blob (same
 * bearer-token constraint as OcrPageViewer) and kept for the page's lifetime.
 */
export function SegmentPlayer({ session, documentId, segments, seekRequest, onActiveSegmentChange }: SegmentPlayerProps) {
  const [mimeType, setMimeType] = useState<string | null>(null);
  const [mediaUrl, setMediaUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const mediaRef = useRef<HTMLVideoElement | HTMLAudioElement | null>(null);
  // AC-2: the boundary to auto-pause at for the segment last explicitly seeked to.
  const boundaryMsRef = useRef<number | null>(null);
  const activeIdRef = useRef<string | null>(null);
  // A seek requested before the media element exists (deep-link: segments
  // load before the media blob) - applied once the element mounts.
  const pendingSeekRef = useRef<SegmentSeekRequest | null>(null);

  useEffect(() => {
    let cancelled = false;
    apiJson<DocumentRow>(`/documents/${documentId}`, session)
      .then((doc) => {
        if (!cancelled) setMimeType(doc.mimeType);
      })
      .catch((e: Error) => {
        if (!cancelled) setError(e.message);
      });
    return () => {
      cancelled = true;
    };
  }, [session, documentId]);

  useEffect(() => {
    let cancelled = false;
    let objectUrl: string | null = null;
    apiFetch(`/documents/${documentId}/content`, session)
      .then((res) => res.blob())
      .then((blob) => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(blob);
        setMediaUrl(objectUrl);
      })
      .catch((e: Error) => {
        if (!cancelled) setError(e.message);
      });
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
    // Loaded once for the page's lifetime (§4.3) - deliberately not re-run on segment change.
  }, [session, documentId]);

  const applySeek = (request: SegmentSeekRequest, media: HTMLVideoElement | HTMLAudioElement) => {
    pendingSeekRef.current = null;
    const segment = segments.find((s) => s.segmentId === request.segmentId);
    if (!segment || segment.timeStartMs == null) return;
    media.currentTime = segment.timeStartMs / 1000;
    boundaryMsRef.current = segment.timeEndMs ?? null;
    if (request.play !== false) void media.play();
  };

  const attachMedia = (el: HTMLVideoElement | HTMLAudioElement | null) => {
    mediaRef.current = el;
    if (el && pendingSeekRef.current) applySeek(pendingSeekRef.current, el);
  };

  useEffect(() => {
    if (!seekRequest) return;
    if (mediaRef.current) applySeek(seekRequest, mediaRef.current);
    else pendingSeekRef.current = seekRequest;
    // Keyed only on seekRequest (nonce) - a re-click of the same segment must re-seek.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [seekRequest]);

  const handleTimeUpdate = () => {
    const media = mediaRef.current;
    if (!media) return;
    const currentMs = media.currentTime * 1000;
    if (boundaryMsRef.current != null && currentMs >= boundaryMsRef.current) {
      media.pause();
      // AC-2: only stop once at the boundary - "trừ khi người dùng chủ động
      // bấm play tiếp" (a manual replay afterwards must not immediately
      // re-pause at the same point).
      boundaryMsRef.current = null;
    }
    const active = segments.find(
      (s) => s.timeStartMs != null && s.timeEndMs != null && currentMs >= s.timeStartMs && currentMs < s.timeEndMs,
    );
    const activeId = active?.segmentId ?? null;
    if (activeId !== activeIdRef.current) {
      activeIdRef.current = activeId;
      onActiveSegmentChange(activeId);
    }
  };

  const isVideo = mimeType?.startsWith('video/') ?? false;

  return (
    <div className="bg-white rounded-lg border border-gray-200 p-3">
      {error && (
        <p className="text-sm text-red-700 mb-2">Không tải được tệp gốc để phát: {error}</p>
      )}
      {mediaUrl && mimeType ? (
        isVideo ? (
          <video
            ref={attachMedia}
            src={mediaUrl}
            controls
            onTimeUpdate={handleTimeUpdate}
            className="w-full max-h-96 lg:max-h-[40vh] rounded-md bg-black"
          />
        ) : (
          <audio
            ref={attachMedia}
            src={mediaUrl}
            controls
            onTimeUpdate={handleTimeUpdate}
            className="w-full"
          />
        )
      ) : (
        !error && <p className="text-sm text-gray-400">Đang tải tệp gốc…</p>
      )}
    </div>
  );
}
