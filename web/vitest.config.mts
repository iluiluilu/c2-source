import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'
import path from 'node:path'

const dirname = import.meta.dirname

/**
 * chunked-file-upload-01-ingest-chunked-upload.md Task F5: first test runner
 * for `web/` (none existed before). jsdom environment because
 * `uploadOneChunked` (upload/page.tsx) is a browser-facing module (uses
 * `Blob`/`File.slice()`); `@/*` alias mirrors tsconfig.json so test files can
 * import the same way app code does.
 *
 * digitization-review-05-frontend.md §5 (Task 9): first component-level
 * (Testing Library) tests in this repo - `@vitejs/plugin-react` (JSX/Fast
 * Refresh transform for .tsx specs) and `@testing-library/jest-dom` matchers
 * were already devDependencies (added ahead of this need) but never wired
 * into this config until now.
 */
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(dirname, './src'),
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['./vitest.setup.ts'],
  },
})
