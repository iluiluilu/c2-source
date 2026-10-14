import { describe, expect, it, vi } from 'vitest';
import vectors from './display-name-vectors.json';
import {
  assignDisplayNames,
  checkDisplayName,
  cpLength,
  generateSuffixName,
  random6,
  splitExtension,
  timestamp17,
  withSuffix,
} from '../displayName';

type Val = string | { rep: string; n: number; tail: string };
const expand = (v: Val): string => (typeof v === 'string' ? v : v.rep.repeat(v.n) + v.tail);

describe('displayName - shared vectors with the backend', () => {
  it('splitExtension', () => {
    for (const v of vectors.split) {
      expect(splitExtension(v.name), v.name).toEqual({ base: v.base, ext: v.ext });
    }
  });

  it('withSuffix (incl. cut to 512 code points, surrogate pairs)', () => {
    for (const v of vectors.suffix) {
      const out = withSuffix(expand(v.original as Val), v.ts, v.rand);
      expect(out).toBe(expand(v.expected as Val));
      expect(cpLength(out)).toBeLessThanOrEqual(512);
    }
  });

  it('checkDisplayName', () => {
    for (const v of vectors.validate) {
      expect(checkDisplayName(expand(v.original as Val), expand(v.display as Val)), JSON.stringify(v)).toBe(
        v.reason,
      );
    }
  });
});

describe('displayName - generation', () => {
  it('timestamp17 uses Asia/Ho_Chi_Minh (UTC+7) with milliseconds', () => {
    // 2026-09-30T17:30:15.042Z -> 2026-10-01 00:30:15.042 in UTC+7
    expect(timestamp17(new Date(Date.UTC(2026, 8, 30, 17, 30, 15, 42)))).toBe('20261001003015042');
  });

  it('random6 comes from crypto.getRandomValues', () => {
    const spy = vi.spyOn(crypto, 'getRandomValues').mockImplementation(((arr: Uint8Array) => {
      arr.fill(0);
      return arr;
    }) as typeof crypto.getRandomValues);
    expect(random6()).toBe('aaaaaa');
    spy.mockRestore();
    expect(random6()).toMatch(/^[a-z0-9]{6}$/);
  });

  it('a generated name is valid for the backend rules', () => {
    const name = generateSuffixName('Báo cáo.docx', new Date(Date.UTC(2026, 8, 30, 3, 0, 0, 1)));
    expect(name).toMatch(/^Báo cáo_\d{17}-[a-z0-9]{6}\.docx$/);
    expect(checkDisplayName('Báo cáo.docx', name)).toBeNull();
  });
});

describe('assignDisplayNames', () => {
  const now = new Date(Date.UTC(2026, 8, 30, 3, 0, 0, 0));

  it('keeps names that are free', () => {
    expect(
      assignDisplayNames(
        [
          { relativePath: 'd', originalName: 'a.pdf' },
          { relativePath: 'd', originalName: 'b.pdf' },
        ],
        [false, false],
        now,
      ),
    ).toEqual(['a.pdf', 'b.pdf']);
  });

  it('suffixes a name the server says is taken', () => {
    const [name] = assignDisplayNames([{ relativePath: '', originalName: 'a.pdf' }], [true], now);
    expect(name).not.toBe('a.pdf');
    expect(checkDisplayName('a.pdf', name)).toBeNull();
  });

  it('suffixes a clash inside the same batch, case-insensitively and NFC vs NFD', () => {
    const names = assignDisplayNames(
      [
        { relativePath: 'd', originalName: 'Báo.pdf' },
        { relativePath: 'd', originalName: 'BÁO.PDF' },
        { relativePath: 'd', originalName: 'Báo.pdf' },
        { relativePath: 'other', originalName: 'Báo.pdf' },
      ],
      [false, false, false, false],
      now,
    );
    expect(names[0]).toBe('Báo.pdf');
    expect(names[1]).not.toBe('BÁO.PDF');
    expect(names[2]).not.toBe('Báo.pdf');
    expect(names[3]).toBe('Báo.pdf'); // different relativePath
    expect(new Set(names.map((n, i) => `${i < 3 ? 'd' : 'o'}/${n.toLowerCase()}`)).size).toBe(4);
  });
});
