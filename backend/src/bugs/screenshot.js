export const SCREENSHOT_MAX_BYTES = 1024 * 1024;
const MAX_BASE64 = Math.ceil(SCREENSHOT_MAX_BYTES / 3) * 4;
const FRAME_MARKERS = new Set([0xc0, 0xc1, 0xc2]);

/** Capture JPEG facultative. Dimensions et poids bornés avant stockage. */
export function screenshotBytes(value) {
  if (value == null) return null;
  if (typeof value !== 'string' || value.length === 0 || value.length > MAX_BASE64
      || value.length % 4 !== 0 || !/^[A-Za-z0-9+/]+={0,2}$/.test(value)) {
    throw new Error('invalid screenshot');
  }
  const bytes = Buffer.from(value, 'base64');
  if (bytes.length > SCREENSHOT_MAX_BYTES || bytes.toString('base64') !== value
      || bytes.length < 16 || bytes.readUInt16BE(0) !== 0xffd8
      || bytes.readUInt16BE(bytes.length - 2) !== 0xffd9) throw new Error('invalid screenshot');
  let offset = 2;
  let frame = false;
  while (offset + 4 <= bytes.length - 2) {
    if (bytes[offset++] !== 0xff) break;
    while (bytes[offset] === 0xff) offset++;
    const marker = bytes[offset++];
    if (marker === 0xda) {
      const length = bytes.readUInt16BE(offset);
      if (frame && length >= 6 && offset + length < bytes.length - 2) return bytes;
      break;
    }
    const length = bytes.readUInt16BE(offset);
    if (length < 2 || offset + length > bytes.length - 2) break;
    if (FRAME_MARKERS.has(marker)) {
      if (length < 8) break;
      const height = bytes.readUInt16BE(offset + 3);
      const width = bytes.readUInt16BE(offset + 5);
      if (!width || !height || width > 4096 || height > 4096 || width * height > 8_000_000) break;
      frame = true;
    }
    offset += length;
  }
  throw new Error('invalid screenshot');
}
