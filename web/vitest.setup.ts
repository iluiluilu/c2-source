import '@testing-library/jest-dom/vitest'
import { afterEach } from 'vitest'
import { resetToasts } from '@/lib/toast'

// lib/toast.ts is a module singleton: a toast raised by one test must not
// leak into the next test's DOM.
afterEach(() => {
  resetToasts()
})
