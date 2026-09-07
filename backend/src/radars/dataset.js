import { config } from '../config.js';

/**
 * Resolves the latest CSV resource published on data.gouv for the fixed-radar
 * dataset, so we never hard-code a dated file URL.
 */
export async function fetchLatestCsvResource() {
  const res = await fetch(config.radarDatasetApiUrl, {
    headers: { accept: 'application/json' },
  });
  if (!res.ok) throw new Error(`data.gouv API ${res.status}`);
  const json = await res.json();

  const csv = (json.resources || [])
    .filter((r) => (r.format || '').toLowerCase() === 'csv' || (r.url || '').endsWith('.csv'))
    .sort((a, b) => date(b) - date(a));

  if (csv.length === 0) throw new Error('No CSV resource in dataset');
  const chosen = csv[0];
  return { url: chosen.url, lastModified: chosen.last_modified || chosen.created_at || null };
}

function date(resource) {
  return new Date(resource.last_modified || resource.created_at || 0).getTime();
}

/**
 * Downloads the CSV (Latin-1) and parses it into normalized radar records.
 * Columns: "Numéro de radar;Type de radar;Date de mise en service;VMA;Latitude;Longitude".
 */
export async function downloadRadars(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`CSV download ${res.status}`);
  const buffer = await res.arrayBuffer();
  const text = new TextDecoder('latin1').decode(buffer);
  return parseRadarCsv(text);
}

export function parseRadarCsv(text) {
  const lines = text.split(/\r?\n/).filter((l) => l.trim().length > 0);
  if (lines.length < 2) return [];

  const header = splitLine(lines[0]).map(normalizeHeader);
  const idx = {
    id: header.findIndex((h) => h.includes('numero')),
    type: header.findIndex((h) => h.includes('type')),
    date: header.findIndex((h) => h.includes('date')),
    vma: header.findIndex((h) => h.includes('vma')),
    lat: header.findIndex((h) => h.includes('latitude')),
    lon: header.findIndex((h) => h.includes('longitude')),
  };

  const radars = [];
  for (let i = 1; i < lines.length; i++) {
    const cols = splitLine(lines[i]);
    const lat = num(cols[idx.lat]);
    const lon = num(cols[idx.lon]);
    if (lat === null || lon === null || !inFrance(lat, lon)) continue;

    radars.push({
      id: cell(cols[idx.id]) || `radar-${i}`,
      type: cell(cols[idx.type]) || null,
      vma: int(cols[idx.vma]),
      serviceDate: cell(cols[idx.date]) || null,
      lat,
      lon,
    });
  }
  return radars;
}

function splitLine(line) {
  return line.split(';');
}

function normalizeHeader(h) {
  return (h || '')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .trim();
}

function cell(v) {
  return (v || '').trim();
}

function num(v) {
  if (v == null) return null;
  const n = parseFloat(String(v).trim().replace(',', '.'));
  return Number.isFinite(n) ? n : null;
}

function int(v) {
  const n = num(v);
  return n === null ? null : Math.round(n);
}

// Rough metropolitan + overseas bounding sanity check.
function inFrance(lat, lon) {
  return lat >= -25 && lat <= 52 && lon >= -65 && lon <= 56;
}
