/**
 * SPEC-folder-upload-display-name FE-2: display-name rules shared with the backend
 * (`DisplayNameValidator.java`) through `__tests__/display-name-vectors.json` (R8).
 * Pure TS, no DOM/React. All lengths are counted in Unicode code points.
 */

export const MAX_NAME_CODE_POINTS = 512;
export const MAX_EXTENSION_CODE_POINTS = 16;
export const MAX_SUFFIX_RETRIES = 3;

const TIME_ZONE = 'Asia/Ho_Chi_Minh';
const ALPHABET = 'abcdefghijklmnopqrstuvwxyz0123456789';
const SUFFIX_RE = /_(\d{17})-([a-z0-9]{6})$/;

export type InvalidReason =
  | 'EMPTY'
  | 'NOT_NFC'
  | 'TOO_LONG'
  | 'FORBIDDEN_CHAR'
  | 'BLANK_OR_DOT'
  | 'SUFFIX_MISMATCH'
  | 'ORIGINAL_NAME_TOO_LONG';

export function nfc(s: string): string {
  return s.normalize('NFC');
}

export function cpLength(s: string): number {
  let n = 0;
  for (const _ of s) n++; // eslint-disable-line @typescript-eslint/no-unused-vars
  return n;
}

/** Cuts to at most `max` code points, never inside a surrogate pair. */
export function cutCodePoints(s: string, max: number): string {
  if (max <= 0) return '';
  return Array.from(s).slice(0, max).join('');
}

/**
 * Extension = from the LAST dot when that dot is neither first nor last char and the part after it
 * is at most 16 code points (`.env` -> no extension, `a.tar.gz` -> `.gz`).
 */
export function splitExtension(name: string): { base: string; ext: string } {
  const dot = name.lastIndexOf('.');
  if (dot <= 0 || dot === name.length - 1) return { base: name, ext: '' };
  const ext = name.slice(dot);
  if (cpLength(ext) - 1 > MAX_EXTENSION_CODE_POINTS) return { base: name, ext: '' };
  return { base: name.slice(0, dot), ext };
}

/** `<base>_<timestamp17>-<rand6><ext>`, base cut so the whole name is at most 512 code points. */
export function withSuffix(originalName: string, timestamp17: string, rand6: string): string {
  const { base, ext } = splitExtension(nfc(originalName));
  const suffix = `_${timestamp17}-${rand6}`;
  const room = MAX_NAME_CODE_POINTS - cpLength(suffix) - cpLength(ext);
  return cutCodePoints(base, Math.max(room, 0)) + suffix + ext;
}

/** `yyyyMMddHHmmssSSS` in Asia/Ho_Chi_Minh. */
export function timestamp17(now: Date = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(now);
  const get = (t: string) => parts.find((p) => p.type === t)?.value ?? '00';
  const ms = String(now.getUTCMilliseconds()).padStart(3, '0');
  return `${get('year')}${get('month')}${get('day')}${get('hour')}${get('minute')}${get('second')}${ms}`;
}

/** 6 random chars [a-z0-9] via `crypto.getRandomValues` (rejection sampling, no modulo bias). */
export function random6(): string {
  const out: string[] = [];
  const limit = 256 - (256 % ALPHABET.length);
  while (out.length < 6) {
    const buf = new Uint8Array(12);
    crypto.getRandomValues(buf);
    for (const b of buf) {
      if (b < limit && out.length < 6) out.push(ALPHABET[b % ALPHABET.length]);
    }
  }
  return out.join('');
}

/** A fresh suffix name for `originalName` (used on a name clash and on a 409). */
export function generateSuffixName(originalName: string, now: Date = new Date()): string {
  return withSuffix(originalName, timestamp17(now), random6());
}

function hasForbiddenChar(s: string): boolean {
  for (const ch of s) {
    const cp = ch.codePointAt(0) as number;
    if (
      ch === '/' ||
      ch === '\\' ||
      cp <= 0x1f ||
      (cp >= 0x7f && cp <= 0x9f) ||
      cp === 0x2028 ||
      cp === 0x2029 ||
      (cp >= 0x202a && cp <= 0x202e) ||
      (cp >= 0x2066 && cp <= 0x2069)
    ) {
      return true;
    }
  }
  return false;
}

function timestampValid(ts: string): boolean {
  const month = Number(ts.slice(4, 6));
  const day = Number(ts.slice(6, 8));
  const hour = Number(ts.slice(8, 10));
  const minute = Number(ts.slice(10, 12));
  const second = Number(ts.slice(12, 14));
  return month >= 1 && month <= 12 && day >= 1 && day <= 31 && hour < 24 && minute < 60 && second < 60;
}

function suffixMatches(nfcOriginal: string, display: string): boolean {
  const { base, ext } = splitExtension(nfcOriginal);
  let rest = display;
  if (ext) {
    if (!rest.endsWith(ext)) return false;
    rest = rest.slice(0, rest.length - ext.length);
  }
  const m = SUFFIX_RE.exec(rest);
  if (!m || !timestampValid(m[1])) return false;
  const prefix = rest.slice(0, m.index);
  if (prefix === base) return true;
  return base.startsWith(prefix) && cpLength(display) === MAX_NAME_CODE_POINTS;
}

/** Same checks/order as the backend; `null` when `display` is acceptable for `original`. */
export function checkDisplayName(original: string, display: string): InvalidReason | null {
  if (cpLength(nfc(original)) > MAX_NAME_CODE_POINTS) return 'ORIGINAL_NAME_TOO_LONG';
  const nfcOriginal = nfc(original);
  if (!display) return 'EMPTY';
  if (display !== nfc(display)) return 'NOT_NFC';
  if (cpLength(display) > MAX_NAME_CODE_POINTS) return 'TOO_LONG';
  if (hasForbiddenChar(display)) return 'FORBIDDEN_CHAR';
  if (display.trim() === '' || display === '.' || display === '..') return 'BLANK_OR_DOT';
  if (display === nfcOriginal) return null;
  return suffixMatches(nfcOriginal, display) ? null : 'SUFFIX_MISMATCH';
}

/** Case-insensitive key of a (relativePath, name) pair; same equivalence as the DB unique index. */
export function nameKey(relativePath: string, name: string): string {
  return `${nfc(relativePath)}/${nfc(name).toLowerCase()}`;
}

export interface NameCandidate {
  relativePath: string;
  originalName: string;
}

/**
 * Picks the display name of every file of one batch: keeps the (NFC) original name unless the server
 * said it is `taken` OR an earlier file of the same batch already uses the same case-insensitive
 * (relativePath, name); otherwise generates a suffix name (also avoiding names used in this batch).
 */
export function assignDisplayNames(
  files: NameCandidate[],
  taken: boolean[],
  now: Date = new Date(),
): string[] {
  const used = new Set<string>();
  return files.map((f, i) => {
    const original = nfc(f.originalName);
    let name = original;
    if (taken[i] || used.has(nameKey(f.relativePath, name))) {
      do {
        name = generateSuffixName(original, now);
      } while (used.has(nameKey(f.relativePath, name)));
    }
    used.add(nameKey(f.relativePath, name));
    return name;
  });
}
