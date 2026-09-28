#!/usr/bin/env node
/**
 * EONA — the way each fixed radar controls (28/09, Arthur). Writes config.radarDirectionsFile,
 * which the radar store reads: { generatedAt, radars: { <id>: { text, road, fetchedAt, course } },
 * towns: { <query@cell>: { lat, lon } | null } }.
 *
 * For every radar of the data.gouv list also on the official radar site:
 * 1. its page on the site (config.radarSiteUrl, one a config.radarSiteGapMs, identified as EONA),
 *    only when never read or read more than config.radarSiteRefetchDays ago: "MACON vers MOULINS";
 * 2. the towns it names, placed by the BAN (communes, the nearest to the radar), kept;
 * 3. the road it stands on (signs.road within config.radarRoadMaxM): its way toward what the text
 *    says is the radar's `course` (src/radars/direction.js). Nothing found: `course` null, the
 *    radar rings both ways.
 * A refusal from the site (403, 429) stops the reading there: what was read is kept.
 *
 * Usage (on the VPS, from /opt/eona-backend, as eona):
 *   node bin/eona-radar-directions.js [--max-pages N]
 */

import { readFile, rename, writeFile } from 'node:fs/promises';
import { config } from '../src/config.js';
import { db } from '../src/db.js';
import { downloadRadars, fetchLatestCsvResource } from '../src/radars/dataset.js';
import { controlledCourse, readDirection } from '../src/radars/direction.js';
import { haversine } from '../src/radars/geo.js';

const DAY_MS = 24 * 60 * 60 * 1000;
const BAN_GAP_MS = 150;
const SAVE_EVERY = 100;
const ROAD_KINDS_SKIPPED = ['service', 'footway', 'cycleway', 'path', 'track', 'pedestrian', 'steps', 'bridleway'];

const maxPagesArg = process.argv.indexOf('--max-pages');
const maxPages = maxPagesArg > 0 ? Number(process.argv[maxPagesArg + 1]) : Infinity;
const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function load() {
  try {
    const saved = JSON.parse(await readFile(config.radarDirectionsFile, 'utf8'));
    return { radars: saved.radars ?? {}, towns: saved.towns ?? {} };
  } catch {
    return { radars: {}, towns: {} };
  }
}

async function save(state) {
  const tmp = `${config.radarDirectionsFile}.tmp`;
  await writeFile(tmp, JSON.stringify({ generatedAt: new Date().toISOString(), ...state }), 'utf8');
  await rename(tmp, config.radarDirectionsFile);
}

async function site(path) {
  const res = await fetch(`${config.radarSiteUrl.replace(/\/$/, '')}${path}`, {
    headers: { 'user-agent': config.radarSiteUserAgent, accept: 'application/json' },
    signal: AbortSignal.timeout(20_000),
  });
  return res;
}

/** The commune named [query] nearest to the radar, or null (none, or too far to be it). */
async function town(query, radar) {
  const url = new URL(`${config.banUrl.replace(/\/$/, '')}/search/`);
  url.search = new URLSearchParams({ q: query, type: 'municipality', limit: '1', lat: String(radar.lat), lon: String(radar.lon) });
  const res = await fetch(url, { signal: AbortSignal.timeout(10_000) });
  if (!res.ok) throw new Error(`BAN ${res.status}`);
  const feature = (await res.json()).features?.[0];
  const [lon, lat] = feature?.geometry?.coordinates ?? [];
  if (!Number.isFinite(lat) || !Number.isFinite(lon) || !(feature.properties?.score >= 0.5)) return null;
  return haversine(radar.lat, radar.lon, lat, lon) <= config.radarTownMaxM ? { lat, lon } : null;
}

/** Each radar's road: its course at the radar (either way) and its one-way flag, when one is near. */
async function roads(radars) {
  const { rows } = await db.query(
    `WITH r AS (
       SELECT id, ST_Transform(ST_SetSRID(ST_MakePoint(lon, lat), 4326), 2154) AS g
       FROM unnest($1::text[], $2::float8[], $3::float8[]) AS t(id, lon, lat)
     )
     SELECT r.id, q.oneway,
       degrees(ST_Azimuth(
         ST_LineInterpolatePoint(q.geom_m, greatest(0, q.f - q.step)),
         ST_LineInterpolatePoint(q.geom_m, least(1, q.f + q.step)))) AS course
     FROM r CROSS JOIN LATERAL (
       SELECT geom_m, oneway, ST_LineLocatePoint(geom_m, r.g) AS f,
         least(0.5, 10 / greatest(ST_Length(geom_m), 1)) AS step
       FROM signs.road
       WHERE ST_DWithin(geom_m, r.g, $4) AND highway <> ALL($5::text[])
       ORDER BY geom_m <-> r.g
       LIMIT 1
     ) q`,
    [radars.map((r) => r.id), radars.map((r) => r.lon), radars.map((r) => r.lat), config.radarRoadMaxM, ROAD_KINDS_SKIPPED],
  );
  return new Map(rows.map((row) => [row.id, { course: Number(row.course), oneway: row.oneway }]));
}

async function main() {
  const state = await load();
  const { url } = await fetchLatestCsvResource();
  const radars = await downloadRadars(url);
  const listRes = await site('/radars/all?_format=json');
  if (!listRes.ok) throw new Error(`radar site list ${listRes.status}`);
  const listed = new Set((await listRes.json()).map((r) => String(r.id)));

  // 1. The pages not read yet, or read too long ago.
  const now = Date.now();
  const due = radars.filter((r) => listed.has(r.id)
    && !(now - Date.parse(state.radars[r.id]?.fetchedAt ?? 0) < config.radarSiteRefetchDays * DAY_MS));
  let pages = 0;
  let refused = null;
  for (const radar of due) {
    if (pages >= maxPages) break;
    const res = await site(`/radars/${encodeURIComponent(radar.id)}?_format=json`).catch((e) => ({ ok: false, status: String(e.message) }));
    pages += 1;
    if (res.status === 403 || res.status === 429) {
      refused = res.status;
      break;
    }
    if (res.ok) {
      const page = await res.json().catch(() => ({}));
      state.radars[radar.id] = {
        text: typeof page.radardirection === 'string' ? page.radardirection.trim() : null,
        road: typeof page.radarroad === 'string' ? page.radarroad.trim() : null,
        fetchedAt: new Date().toISOString(),
        course: state.radars[radar.id]?.course ?? null,
      };
    } else if (res.status === 404) {
      state.radars[radar.id] = { text: null, road: null, fetchedAt: new Date().toISOString(), course: null };
    }
    if (pages % SAVE_EVERY === 0) {
      await save(state);
      console.log(`[radar-directions] ${pages}/${due.length} pages`);
    }
    await pause(config.radarSiteGapMs);
  }

  // 2 and 3. The towns, the road, the course of every radar with a text.
  const road = await roads(radars);
  const counts = { radars: radars.length, texts: 0, courses: 0, noReading: 0, noTown: 0, noRoad: 0, across: 0 };
  const check = { same: 0, opposite: 0 };
  for (const radar of radars) {
    const entry = state.radars[radar.id];
    if (!entry?.text) continue;
    counts.texts += 1;
    const reading = readDirection(entry.text);
    if (!reading) {
      counts.noReading += 1;
      entry.course = null;
      continue;
    }
    const place = async (query) => {
      if (!query) return null;
      const key = `${query}@${Math.round(radar.lat)},${Math.round(radar.lon)}`;
      if (!Object.hasOwn(state.towns, key)) {
        state.towns[key] = await town(query, radar).catch(() => undefined);
        await pause(BAN_GAP_MS);
        if (state.towns[key] === undefined) delete state.towns[key];
      }
      return state.towns[key] ?? null;
    };
    const from = await place(reading.from);
    const to = await place(reading.to);
    if (reading.wind === undefined && !from && !to) counts.noTown += 1;
    const onRoad = road.get(radar.id);
    if (!onRoad) counts.noRoad += 1;
    entry.course = onRoad ? controlledCourse({ reading, from, to, radar, roadCourse: onRoad.course }) : null;
    if (entry.course === null) {
      if (onRoad && (reading.wind !== undefined || from || to)) counts.across += 1;
      continue;
    }
    counts.courses += 1;
    // On a one-way road, the way the road goes says the controlled way: a check of the reading.
    if (onRoad.oneway) {
      const roadWay = onRoad.oneway === -1 ? (onRoad.course + 180) % 360 : onRoad.course;
      const off = Math.abs(((entry.course - roadWay) % 360 + 540) % 360 - 180);
      if (off <= 45) check.same += 1;
      else if (off >= 135) check.opposite += 1;
    }
  }
  await save(state);
  console.log(`[radar-directions] ${pages} pages read${refused ? `, stopped: site answered ${refused}` : ''}`);
  console.log(`[radar-directions] ${JSON.stringify(counts)}`);
  console.log(`[radar-directions] one-way roads: ${check.same} same way, ${check.opposite} opposite`);
}

main()
  .catch((e) => {
    console.error('[radar-directions] failed —', String(e.message || e));
    process.exitCode = 1;
  })
  .finally(() => db.end());
