const UPPER = 'ABCDEFGHJKLMNPQRSTUVWXYZ'
const LOWER = 'abcdefghijkmnopqrstuvwxyz'
const DIGITS = '23456789'
const SYMBOLS = '!@#$%*?-_'
const ALL = UPPER + LOWER + DIGITS + SYMBOLS

function pick(chars: string, rnd: number): string {
  return chars[rnd % chars.length]
}

/**
 * Random temporary password for an admin to hand over: at least one of each
 * character class (any reasonable Keycloak policy), look-alike characters
 * (0/O, 1/l/I) left out so it can be read aloud. Uses crypto, not Math.random.
 */
export function generatePassword(length = 14): string {
  const rnd = new Uint32Array(length + length)
  crypto.getRandomValues(rnd)
  const chars = [
    pick(UPPER, rnd[0]),
    pick(LOWER, rnd[1]),
    pick(DIGITS, rnd[2]),
    pick(SYMBOLS, rnd[3]),
  ]
  for (let i = chars.length; i < length; i++) chars.push(pick(ALL, rnd[i]))
  // Fisher-Yates with the second half of the random buffer.
  for (let i = chars.length - 1; i > 0; i--) {
    const j = rnd[length + i] % (i + 1)
    ;[chars[i], chars[j]] = [chars[j], chars[i]]
  }
  return chars.join('')
}
