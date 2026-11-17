// Minimal strict CBOR, the subset docs/PROTOCOL.md uses: unsigned and negative
// integers, byte strings, text strings, arrays and maps. Shortest-form
// integer encoding is enforced on read so that ids are stable across
// implementations. Indefinite lengths, floats, tags and simple values are
// rejected.

/** Byte arrays backed by a plain ArrayBuffer, which is what WebCrypto and fetch accept. */
export type Bytes = Uint8Array<ArrayBuffer>;

export class CborError extends Error {}

const MAX_SAFE = Number.MAX_SAFE_INTEGER;

export class CborWriter {
  private parts: number[] = [];

  private head(major: number, value: number): void {
    if (!Number.isInteger(value) || value < 0 || value > MAX_SAFE) throw new CborError("value out of range");
    const m = major << 5;
    if (value < 24) this.parts.push(m | value);
    else if (value < 0x100) this.parts.push(m | 24, value);
    else if (value < 0x10000) this.parts.push(m | 25, value >> 8, value & 0xff);
    else if (value < 0x100000000) this.parts.push(m | 26, (value >>> 24) & 0xff, (value >>> 16) & 0xff, (value >>> 8) & 0xff, value & 0xff);
    else {
      const hi = Math.floor(value / 0x100000000);
      const lo = value % 0x100000000;
      this.parts.push(m | 27, (hi >>> 24) & 0xff, (hi >>> 16) & 0xff, (hi >>> 8) & 0xff, hi & 0xff,
        (lo >>> 24) & 0xff, (lo >>> 16) & 0xff, (lo >>> 8) & 0xff, lo & 0xff);
    }
  }

  uint(v: number): this { this.head(0, v); return this; }

  int(v: number): this {
    if (v >= 0) this.head(0, v); else this.head(1, -1 - v);
    return this;
  }

  bytes(b: Bytes): this {
    this.head(2, b.length);
    for (const x of b) this.parts.push(x);
    return this;
  }

  text(s: string): this {
    return this.textBytes(new TextEncoder().encode(s));
  }

  private textBytes(b: Bytes): this {
    this.head(3, b.length);
    for (const x of b) this.parts.push(x);
    return this;
  }

  array(n: number): this { this.head(4, n); return this; }
  map(n: number): this { this.head(5, n); return this; }

  finish(): Bytes { return Uint8Array.from(this.parts); }
}

export class CborReader {
  private pos = 0;
  constructor(private readonly buf: Bytes) {}

  private byte(): number {
    const b = this.buf[this.pos];
    if (b === undefined) throw new CborError("unexpected end");
    this.pos++;
    return b;
  }

  private head(expectMajor: number, what: string): number {
    const ib = this.byte();
    const major = ib >> 5;
    if (major !== expectMajor) throw new CborError(`expected ${what}, got major type ${major}`);
    const ai = ib & 0x1f;
    if (ai < 24) return ai;
    let value: number;
    let min: number;
    switch (ai) {
      case 24: value = this.byte(); min = 24; break;
      case 25: value = (this.byte() << 8) | this.byte(); min = 0x100; break;
      case 26: value = ((this.byte() << 24) >>> 0) + (this.byte() << 16) + (this.byte() << 8) + this.byte(); min = 0x10000; break;
      case 27: {
        const hi = ((this.byte() << 24) >>> 0) + (this.byte() << 16) + (this.byte() << 8) + this.byte();
        const lo = ((this.byte() << 24) >>> 0) + (this.byte() << 16) + (this.byte() << 8) + this.byte();
        if (hi > 0x1fffff) throw new CborError("integer exceeds 2^53");
        value = hi * 0x100000000 + lo; min = 0x100000000; break;
      }
      default: throw new CborError("indefinite or reserved length");
    }
    if (value < min) throw new CborError("non-minimal integer encoding");
    return value;
  }

  uint(): number { return this.head(0, "uint"); }

  int(): number {
    const ib = this.buf[this.pos];
    if (ib === undefined) throw new CborError("unexpected end");
    const major = ib >> 5;
    if (major === 0) return this.head(0, "int");
    if (major === 1) return -1 - this.head(1, "int");
    throw new CborError(`expected int, got major type ${major}`);
  }

  private raw(major: number, what: string): Bytes {
    const n = this.head(major, what);
    if (this.pos + n > this.buf.length) throw new CborError("unexpected end");
    const out = this.buf.subarray(this.pos, this.pos + n);
    this.pos += n;
    return out;
  }

  bytes(): Bytes { return this.raw(2, "bytes"); }

  text(): string {
    const b = this.raw(3, "text");
    try {
      return new TextDecoder("utf-8", { fatal: true }).decode(b);
    } catch {
      throw new CborError("invalid utf-8");
    }
  }

  array(): number { return this.head(4, "array"); }
  map(): number { return this.head(5, "map"); }

  end(): void {
    if (this.pos !== this.buf.length) throw new CborError(`${this.buf.length - this.pos} trailing bytes`);
  }
}
