import { angleBetween, bearingDeg, haversine } from './geo.js';

/**
 * The way a fixed radar controls, from the official radar site's text ("MACON vers MOULINS",
 * "Sud vers Nord") and the road it stands on. Pure: bin/eona-radar-directions.js fetches the
 * texts, places the towns (BAN) and reads the road; this only reads and decides.
 */

/** The eight winds as the site writes them, and their course. */
const WINDS = {
  NORD: 0, 'NORD EST': 45, EST: 90, 'SUD EST': 135, SUD: 180, 'SUD OUEST': 225, OUEST: 270, 'NORD OUEST': 315,
};

/** Upper case, no accents, one space between words, no punctuation but the words' own. */
function folded(text) {
  return String(text ?? '')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toUpperCase()
    .replace(/[^A-Z0-9()]+/g, ' ')
    .trim();
}

/** "Sud-Est", "l'Est", "vers le nord" → its course, else null. */
function windOf(part) {
  const words = folded(part).replace(/^(L|LE|LA|LES|DU|DE LA|DE L)\s+/, '');
  return Object.hasOwn(WINDS, words) ? WINDS[words] : null;
}

/**
 * A town as the site writes it, as the BAN finds it: "ROCHE SUR YON (LA)" → "La Roche sur Yon",
 * "ST ETIENNE" → "Saint Etienne". Null when it is no town: empty, a dash, a road, a street.
 */
export function townQuery(part) {
  let text = folded(part);
  const article = /^(.*)\s*\((LA|LE|LES|L)\)$/.exec(text);
  if (article) text = `${article[2]} ${article[1]}`.trim();
  text = text.replace(/[()]/g, ' ').replace(/\s+/g, ' ').trim();
  if (!text || text.length < 2 || /\d/.test(text)) return null;
  // A street, a road, a place in a town: no town to look for.
  if (/^(?:(?:LA|LE|LES|L) )?(AV|AVENUE|BD|BLD|BOULEVARD|RUE|ROUTE|RTE|CHEMIN|PLACE|QUAI|ROCADE|PR|SORTIE|ECHANGEUR|AIRE|PEAGE|PONT|TUNNEL|CENTRE)\b/.test(text)) {
    return null;
  }
  return text
    .replace(/\bSTE\b/g, 'SAINTE')
    .replace(/\bST\b/g, 'SAINT')
    .toLowerCase()
    .replace(/(^|[\s-])([a-z])/g, (m, sep, c) => sep + c.toUpperCase());
}

/**
 * The site's text read: { wind } when it names two winds or one ("Sud vers Nord": 0,
 * "vers l'Est": 90), { from, to } town queries when it names places (either may be null),
 * null when it says nothing usable ("-", "PR croissant", both ways).
 */
export function readDirection(text) {
  const raw = String(text ?? '').trim();
  if (!raw || raw === '-') return null;
  const parts = raw.split(/\s+vers\s+/i);
  if (parts.length !== 2) return null;
  const [fromPart, toPart] = parts;
  const fromWind = windOf(fromPart);
  const toWind = windOf(toPart);
  if (toWind !== null && (fromWind !== null || !fromPart.trim())) {
    if (fromWind === null) return { wind: toWind };
    // From one wind to the other: the way between them ("Nord Ouest vers Sud Est": 135).
    const [x, y] = [Math.sin(rad(toWind)) - Math.sin(rad(fromWind)), Math.cos(rad(toWind)) - Math.cos(rad(fromWind))];
    if (Math.hypot(x, y) < 1e-6) return null;
    return { wind: ((Math.atan2(x, y) * 180) / Math.PI + 360) % 360 };
  }
  if (toWind !== null) return { wind: toWind };
  const from = townQuery(fromPart);
  const to = townQuery(toPart);
  return from || to ? { from, to } : null;
}

/** Farther than this from the radar, a town gives the way; nearer, it is the radar's own. */
const TOWN_MIN_M = 1500;

/**
 * The course the radar controls on its road: the road's way ([roadCourse], either direction)
 * that goes toward what the text says — the wind, or from the first town to the second (the
 * radar to the second, the first to the radar when only one is found). Null when the text gives
 * no way, or when the road runs across it (more than [maxOffDeg] off both ways).
 * [from], [to]: { lat, lon } or null; [radar]: { lat, lon }.
 */
export function controlledCourse({ reading, from = null, to = null, radar, roadCourse, maxOffDeg = 60 }) {
  if (!reading || !Number.isFinite(roadCourse)) return null;
  let wanted = null;
  if (Number.isFinite(reading.wind)) {
    wanted = reading.wind;
  } else {
    const far = (p) => p && haversine(radar.lat, radar.lon, p.lat, p.lon) >= TOWN_MIN_M;
    if (far(from) && far(to)) wanted = bearingDeg(from.lat, from.lon, to.lat, to.lon);
    else if (far(to)) wanted = bearingDeg(radar.lat, radar.lon, to.lat, to.lon);
    else if (far(from)) wanted = bearingDeg(from.lat, from.lon, radar.lat, radar.lon);
  }
  if (wanted === null) return null;
  const ahead = ((roadCourse % 360) + 360) % 360;
  const back = (ahead + 180) % 360;
  const course = angleBetween(ahead, wanted) <= angleBetween(back, wanted) ? ahead : back;
  return angleBetween(course, wanted) <= maxOffDeg ? Math.round(course) : null;
}

function rad(deg) {
  return (deg * Math.PI) / 180;
}
