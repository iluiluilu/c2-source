import { describe, expect, it } from 'vitest';
import { collectDroppedFiles, relativePathFromFullPath } from '../dropEntries';

type Entry = {
  isFile: boolean;
  isDirectory: boolean;
  name: string;
  fullPath: string;
  file?: (ok: (f: File) => void) => void;
  createReader?: () => { readEntries: (ok: (e: Entry[]) => void) => void };
};

const file = (dir: string, name: string): Entry => ({
  isFile: true,
  isDirectory: false,
  name,
  fullPath: `${dir}/${name}`,
  file: (ok) => ok(new File(['x'], name)),
});

/** Directory whose readEntries returns `pageSize` children per call, then an empty page. */
const dir = (path: string, children: Entry[], pageSize = 100): Entry => ({
  isFile: false,
  isDirectory: true,
  name: path.split('/').pop() ?? '',
  fullPath: path,
  createReader: () => {
    let cursor = 0;
    return {
      readEntries: (ok) => {
        const page = children.slice(cursor, cursor + pageSize);
        cursor += pageSize;
        ok(page);
      },
    };
  },
});

function dataTransfer(entries: Entry[]): DataTransfer {
  return {
    items: entries.map((e) => ({ kind: 'file', webkitGetAsEntry: () => e })),
    files: [],
  } as unknown as DataTransfer;
}

describe('relativePathFromFullPath', () => {
  it('strips the leading slash and the file name', () => {
    expect(relativePathFromFullPath('/a.pdf')).toBe('');
    expect(relativePathFromFullPath('/Root/sub/a.pdf')).toBe('Root/sub');
  });
});

describe('collectDroppedFiles', () => {
  it('walks several roots plus loose files, keeping the dropped folder name in relativePath', async () => {
    const root1 = dir('/Hồ sơ', [file('/Hồ sơ', 'a.pdf'), dir('/Hồ sơ/2026', [file('/Hồ sơ/2026', 'b.pdf')])]);
    const root2 = dir('/Other', [file('/Other', 'c.pdf')]);
    const result = await collectDroppedFiles(dataTransfer([root1, file('', 'loose.pdf'), root2]));
    expect(result.map((r) => `${r.relativePath}|${r.file.name}`)).toEqual([
      'Hồ sơ|a.pdf',
      'Hồ sơ/2026|b.pdf',
      '|loose.pdf',
      'Other|c.pdf',
    ]);
  });

  it('reads every page when a folder has more than 100 entries (AC-4)', async () => {
    const children = Array.from({ length: 250 }, (_, i) => file('/Big', `f${i}.pdf`));
    const result = await collectDroppedFiles(dataTransfer([dir('/Big', children)]));
    expect(result).toHaveLength(250);
    expect(new Set(result.map((r) => r.file.name)).size).toBe(250);
  });

  it('an empty folder yields no files', async () => {
    expect(await collectDroppedFiles(dataTransfer([dir('/Empty', [])]))).toEqual([]);
  });

  it('falls back to dataTransfer.files without the entries API', async () => {
    const dt = { items: [{ kind: 'file' }], files: [new File(['x'], 'z.pdf')] } as unknown as DataTransfer;
    const result = await collectDroppedFiles(dt);
    expect(result.map((r) => r.file.name)).toEqual(['z.pdf']);
    expect(result[0].relativePath).toBe('');
  });
});
