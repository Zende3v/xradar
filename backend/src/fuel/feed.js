import { inflateRawSync } from 'node:zlib';
import { config } from '../config.js';

/** Official fuel ids of the prix-carburants feed, in the order the app lists them. */
export const FUELS = [
  { id: '1', name: 'Gazole' },
  { id: '2', name: 'SP95' },
  { id: '6', name: 'SP98' },
  { id: '5', name: 'E10' },
  { id: '3', name: 'E85' },
  { id: '4', name: 'GPLc' },
];
const FUEL_ORDER = Object.fromEntries(FUELS.map((f, i) => [f.name, i]));

/**
 * Downloads the official "flux instantané" (prix-carburants.gouv.fr, Licence Ouverte):
 * one ZIP holding one ISO-8859-1 XML file, parsed into stations with their prices.
 */
export async function downloadStations(url = config.fuelFeedUrl) {
  const res = await fetch(url, { headers: { 'User-Agent': config.placeUserAgent } });
  if (!res.ok) throw new Error(`fuel feed ${res.status}`);
  const xml = unzipSingle(Buffer.from(await res.arrayBuffer())).toString('latin1');
  return parseStations(xml);
}

/** The first file of a ZIP archive (stored or deflated) — all this feed ever holds. */
export function unzipSingle(zip) {
  let eocd = -1;
  for (let i = zip.length - 22; i >= Math.max(0, zip.length - 65557); i--) {
    if (zip.readUInt32LE(i) === 0x06054b50) {
      eocd = i;
      break;
    }
  }
  if (eocd < 0) throw new Error('fuel feed: not a zip');
  const central = zip.readUInt32LE(eocd + 16);
  if (zip.readUInt32LE(central) !== 0x02014b50) throw new Error('fuel feed: bad zip directory');
  const method = zip.readUInt16LE(central + 10);
  const size = zip.readUInt32LE(central + 20);
  const local = zip.readUInt32LE(central + 42);
  if (zip.readUInt32LE(local) !== 0x04034b50) throw new Error('fuel feed: bad zip entry');
  const start = local + 30 + zip.readUInt16LE(local + 26) + zip.readUInt16LE(local + 28);
  const data = zip.subarray(start, start + size);
  if (method === 0) return data;
  if (method === 8) return inflateRawSync(data);
  throw new Error(`fuel feed: unsupported zip method ${method}`);
}

/**
 * `<pdv id latitude longitude …>` blocks with `<prix nom id maj valeur/>` and
 * `<rupture id debut fin type/>`. Coordinates are degrees × 100 000, `maj` is French wall
 * clock time, prices are euros (older files wrote thousandths — divided back).
 * Only what the app needs is kept: id, position, prices and their update time, and the
 * declared opening hours.
 */
export function parseStations(xml, now = Date.now()) {
  const stations = [];
  for (const block of xml.match(/<pdv\b[\s\S]*?<\/pdv>/g) || []) {
    const head = /<pdv\b([^>]*)>/.exec(block)?.[1] || '';
    const id = attr(head, 'id');
    const lat = Number(attr(head, 'latitude')) / 100000;
    const lon = Number(attr(head, 'longitude')) / 100000;
    if (!id || !Number.isFinite(lat) || !Number.isFinite(lon) || (lat === 0 && lon === 0)) continue;

    // A shortage with no end yet (or an end still ahead) means the fuel is not on sale now.
    const outOfStock = new Set();
    for (const tag of block.match(/<rupture\b[^>]*>/g) || []) {
      const end = parisToIso(attr(tag, 'fin'));
      if (!end || Date.parse(end) > now) outOfStock.add(attr(tag, 'id'));
    }

    const prices = [];
    for (const tag of block.match(/<prix\b[^>]*>/g) || []) {
      const fuel = FUELS.find((f) => f.id === attr(tag, 'id'));
      let value = Number(attr(tag, 'valeur'));
      if (!fuel || !Number.isFinite(value) || value <= 0) continue;
      if (value >= 100) value /= 1000;
      prices.push({
        fuel: fuel.name,
        price: Math.round(value * 1000) / 1000,
        updatedAt: parisToIso(attr(tag, 'maj')),
        outOfStock: outOfStock.has(fuel.id),
      });
    }
    prices.sort((a, b) => FUEL_ORDER[a.fuel] - FUEL_ORDER[b.fuel]);
    stations.push({ id, lat, lon, prices, hours: parseHours(block) });
  }
  return stations;
}

const DAY_CODES = ['Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa', 'Su']; // <jour id="1"> is Monday

/**
 * The declared hours as an OpenStreetMap opening_hours value, or null when they say nothing:
 * `automate-24-24="1"` (card pumps around the clock) is "24/7"; otherwise one rule per day,
 * "off" when `ferme="1"`, "unknown" when open without slots. A week without a single real slot (many stations send every day
 * closed from 01.00 to 01.00, or no slot at all) is no information.
 */
export function parseHours(block) {
  const horaires = /<horaires\b([^>]*)>([\s\S]*?)<\/horaires>/.exec(block);
  if (!horaires) return null;
  if (attr(horaires[1], 'automate-24-24') === '1') return '24/7';
  const rules = [];
  let slots = 0;
  for (const day of horaires[2].matchAll(/<jour\b([^>]*?)(?:\/>|>([\s\S]*?)<\/jour>)/g)) {
    const code = DAY_CODES[Number(attr(day[1], 'id')) - 1];
    if (!code) continue;
    if (attr(day[1], 'ferme') === '1') {
      rules.push(`${code} off`);
      continue;
    }
    const ranges = [];
    for (const slot of (day[2] || '').match(/<horaire\b[^>]*>/g) || []) {
      const from = clock(attr(slot, 'ouverture'));
      let to = clock(attr(slot, 'fermeture'));
      if (!from || !to || from === to) continue;
      if (to === '00:00') to = '24:00';
      ranges.push(`${from}-${to}`);
    }
    if (ranges.length > 0) {
      rules.push(`${code} ${ranges.join(',')}`);
      slots += ranges.length;
    } else {
      // Open that day, but at hours nobody declared.
      rules.push(`${code} unknown`);
    }
  }
  return slots > 0 ? rules.join('; ') : null;
}

/** "06.00" / "6.30" -> "06:00" / "06:30"; null if unreadable. */
function clock(value) {
  const m = /^(\d{1,2})[.:h](\d{2})$/.exec(String(value || '').trim());
  if (!m || +m[1] > 24 || +m[2] > 59) return null;
  return `${m[1].padStart(2, '0')}:${m[2]}`;
}

function attr(tag, name) {
  const m = new RegExp(`\\b${name}="([^"]*)"`).exec(tag);
  return m ? m[1] : '';
}

const PARIS = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Europe/Paris',
  hourCycle: 'h23',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
});

/** Minutes Paris is ahead of UTC at [instant] (60 in winter, 120 in summer). */
function parisOffsetMinutes(instant) {
  const p = Object.fromEntries(PARIS.formatToParts(new Date(instant)).map((part) => [part.type, part.value]));
  const wall = Date.UTC(+p.year, +p.month - 1, +p.day, +p.hour, +p.minute, +p.second);
  return Math.round((wall - instant) / 60000);
}

/** "2026-09-12 08:22:50" read on a French clock → "2026-09-12T08:22:50+02:00"; null if unreadable. */
export function parisToIso(local) {
  const m = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})$/.exec(String(local || '').trim());
  if (!m) return null;
  const asUtc = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6]);
  const offset = parisOffsetMinutes(asUtc - parisOffsetMinutes(asUtc) * 60000);
  const sign = offset >= 0 ? '+' : '-';
  const hh = String(Math.floor(Math.abs(offset) / 60)).padStart(2, '0');
  const mm = String(Math.abs(offset) % 60).padStart(2, '0');
  return `${m[1]}-${m[2]}-${m[3]}T${m[4]}:${m[5]}:${m[6]}${sign}${hh}:${mm}`;
}
