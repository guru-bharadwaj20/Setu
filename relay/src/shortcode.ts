import type { Bytes } from "./cbor";
// Family-facing short code: first 6 Crockford base32 characters of
// SHA-256(origin), shown as ABC-DEF. Same rule as protocol/ShortCode.kt.

const ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
export const CODE_LENGTH = 6;

export async function shortCodeOf(origin: Bytes): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", origin));
  let out = "";
  let acc = 0;
  let bits = 0;
  for (const b of digest) {
    acc = ((acc << 8) | b) >>> 0;
    bits += 8;
    while (bits >= 5 && out.length < CODE_LENGTH) {
      bits -= 5;
      out += ALPHABET[(acc >>> bits) & 0x1f];
    }
    if (out.length === CODE_LENGTH) break;
    acc &= (1 << bits) - 1;
  }
  return out;
}

export function display(code: string): string {
  return code.substring(0, 3) + "-" + code.substring(3);
}

/** Accepts what a person types: lowercase, dashes, spaces, O for 0, I/L for 1, U for V. */
export function normalise(input: string): string | null {
  const cleaned = input.toUpperCase().replace(/[-\s]/g, "").replace(/O/g, "0").replace(/[IL]/g, "1").replace(/U/g, "V");
  if (cleaned.length !== CODE_LENGTH) return null;
  for (const c of cleaned) if (!ALPHABET.includes(c)) return null;
  return cleaned;
}
