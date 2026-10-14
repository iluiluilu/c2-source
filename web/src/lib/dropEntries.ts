/**
 * SPEC-folder-upload-display-name FE-1: drag-and-drop of files AND folders through
 * `DataTransferItem.webkitGetAsEntry()` (recursive, paginated `readEntries`). Replaces the old
 * `<input webkitdirectory>` picker, whose browser confirmation popup we want to avoid (AC-2/AC-3).
 */

export interface DroppedFile {
  file: File;
  /** Folder path of the file relative to the drop, INCLUDING the dropped folder's own name; '' for loose files. */
  relativePath: string;
}

interface FsEntry {
  isFile: boolean;
  isDirectory: boolean;
  name: string;
  fullPath: string;
  file?(success: (f: File) => void, error?: (e: unknown) => void): void;
  createReader?(): { readEntries(success: (entries: FsEntry[]) => void, error?: (e: unknown) => void): void };
}

const tick = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

/** `readEntries` returns at most ~100 entries per call - loop until it returns an empty page (AC-4). */
async function readAllEntries(entry: FsEntry): Promise<FsEntry[]> {
  const reader = entry.createReader!();
  const all: FsEntry[] = [];
  for (;;) {
    const page = await new Promise<FsEntry[]>((resolve, reject) => reader.readEntries(resolve, reject));
    if (page.length === 0) return all;
    all.push(...page);
    await tick(); // yield to the event loop between pages so the UI stays responsive
  }
}

function fileOf(entry: FsEntry): Promise<File> {
  return new Promise((resolve, reject) => entry.file!(resolve, reject));
}

/** `/Root/sub/x.pdf` -> `Root/sub`; `/x.pdf` -> ''. */
export function relativePathFromFullPath(fullPath: string): string {
  const parts = fullPath.split('/').filter((p) => p !== '');
  return parts.slice(0, -1).join('/');
}

async function walk(entry: FsEntry, out: DroppedFile[]): Promise<void> {
  if (entry.isFile) {
    out.push({ file: await fileOf(entry), relativePath: relativePathFromFullPath(entry.fullPath) });
    return;
  }
  if (entry.isDirectory) {
    for (const child of await readAllEntries(entry)) {
      await walk(child, out);
    }
  }
}

/**
 * MUST be called synchronously inside the `drop` handler: `webkitGetAsEntry()` is only valid until the
 * event handler returns, so every entry is grabbed before the first `await`.
 */
export function collectDroppedFiles(dataTransfer: DataTransfer): Promise<DroppedFile[]> {
  const entries: FsEntry[] = [];
  let supportsEntries = false;
  const items = dataTransfer.items ? Array.from(dataTransfer.items) : [];
  for (const item of items) {
    if (item.kind !== 'file') continue;
    const getter = (item as unknown as { webkitGetAsEntry?: () => FsEntry | null }).webkitGetAsEntry;
    if (typeof getter === 'function') {
      supportsEntries = true;
      const entry = getter.call(item);
      if (entry) entries.push(entry);
    }
  }
  const looseFiles = Array.from(dataTransfer.files ?? []);

  if (!supportsEntries) {
    // Old browser / no entries API: only individual files (no folder structure available).
    return Promise.resolve(looseFiles.map((file) => ({ file, relativePath: '' })));
  }
  return (async () => {
    const out: DroppedFile[] = [];
    for (const entry of entries) {
      await walk(entry, out);
    }
    return out;
  })();
}
