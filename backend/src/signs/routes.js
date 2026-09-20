import { Router } from 'express';
import { config } from '../config.js';
import { authAccount, isAdminRequest } from '../accounts/auth.js';
import { signEditStore } from './edits.js';
import * as postgis from './postgis.js';

export const signRouter = Router();

/** Runs a handler; a database failure answers 503 instead of crashing the request. */
const guarded = (handler) => async (req, res) => {
  try {
    await handler(req, res);
  } catch (e) {
    console.error('[signs]', e.message);
    res.status(503).json({ error: 'signs unavailable' });
  }
};

function optionalNumber(value) {
  if (value == null || value === '') return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

/** GET /api/signs/near?lat=..&lon=..&radius=.. — signs around a point, nearest first. */
signRouter.get('/near', guarded(async (req, res) => {
  const lat = Number(req.query.lat);
  const lon = Number(req.query.lon);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
    return res.status(400).json({ error: 'lat and lon are required numbers' });
  }
  const radius = Math.max(1, Math.min(Number(req.query.radius) || config.signDefaultRadiusM, config.signMaxRadiusM));
  const signs = await postgis.near(lat, lon, radius, config.signMaxElements);
  res.json({ count: signs.length, signs, source: 'postgis' });
}));

/**
 * POST /api/signs/route  { coordinates: [[lon,lat],...] }
 * What a driver meets along the whole route, in order: the signs for the traffic going that
 * way, and a "speed" entry wherever the limit changes.
 */
signRouter.post('/route', guarded(async (req, res) => {
  const coords = req.body?.coordinates;
  if (!Array.isArray(coords)) return res.status(400).json({ error: 'coordinates [[lon,lat],...] required' });
  const signs = await postgis.route(coords);
  res.json({ count: signs.length, signs, source: 'postgis' });
}));

/**
 * GET /api/signs/limit?lat=..&lon=..&bearing=..&way=..
 * Speed limit (km/h) where the driver is, for the way they go — a change drivers validated
 * included. [bearing] is their course, [way] the road id the previous answer gave.
 */
signRouter.get('/limit', guarded(async (req, res) => {
  const lat = Number(req.query.lat);
  const lon = Number(req.query.lon);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
    return res.status(400).json({ error: 'lat and lon are required numbers' });
  }
  const bearing = optionalNumber(req.query.bearing);
  const previousWayId = /^\d{1,19}$/.test(String(req.query.way ?? '')) ? String(req.query.way) : null;
  const road = await postgis.roadAt(lat, lon, { bearing, previousWayId });
  res.json({ v: road?.limit ?? null, way: road?.wayId ?? null, source: 'postgis' });
}));

// ---- Corrections by hand ("mapper", admins only) --------------------------------------------

/**
 * GET /api/signs/edits?status=applied|conflict|reverted — the corrections, newest first.
 * A "conflict" row is one the last rebuild could not place again: the sign it fixed is gone or
 * cannot be told apart from another. It is kept, and nothing is applied by itself.
 */
signRouter.get('/edits', guarded(async (req, res) => {
  if (!isAdminRequest(req)) return res.status(403).json({ error: 'admin only' });
  const status = ['applied', 'conflict', 'reverted'].includes(req.query.status) ? req.query.status : null;
  const edits = await signEditStore.list({ status, limit: req.query.limit });
  res.json({ count: edits.length, edits });
}));

/**
 * POST /api/signs/edits  { op, targetId?, kind?, value?, course?, lat?, lon?, note? }
 * op = add (a sign OSM does not have), edit (fix one), hide (it is not there). The signalisation
 * carries it at once, and the weekly rebuild replays it.
 */
signRouter.post('/edits', guarded(async (req, res) => {
  if (!isAdminRequest(req)) return res.status(403).json({ error: 'admin only' });
  const result = await signEditStore.create({
    op: String(req.body?.op || ''),
    targetId: req.body?.targetId ? String(req.body.targetId) : null,
    kind: req.body?.kind ? String(req.body.kind) : undefined,
    value: numberOrNull(req.body?.value),
    course: numberOrNull(req.body?.course),
    lat: Number(req.body?.lat),
    lon: Number(req.body?.lon),
    note: req.body?.note,
    authorId: authAccount(req)?.id ?? null,
  });
  if (result.error) return res.status(result.error === 'sign not found' ? 404 : 400).json(result);
  res.status(201).json(result);
}));

/** PATCH /api/signs/edits/:id — change what a correction says (not a hide). */
signRouter.patch('/edits/:id', guarded(async (req, res) => {
  if (!isAdminRequest(req)) return res.status(403).json({ error: 'admin only' });
  const result = await signEditStore.update(req.params.id, {
    kind: req.body?.kind ? String(req.body.kind) : undefined,
    value: numberOrNull(req.body?.value),
    course: numberOrNull(req.body?.course),
    lat: Number(req.body?.lat),
    lon: Number(req.body?.lon),
    note: req.body?.note,
  });
  if (result.error) return res.status(result.error === 'not found' ? 404 : 400).json(result);
  res.json(result);
}));

/** DELETE /api/signs/edits/:id — undo it: the sign goes back to what OpenStreetMap says. */
signRouter.delete('/edits/:id', guarded(async (req, res) => {
  if (!isAdminRequest(req)) return res.status(403).json({ error: 'admin only' });
  const result = await signEditStore.revert(req.params.id);
  if (result.error) return res.status(result.error === 'not found' ? 404 : 400).json(result);
  res.json(result);
}));

function numberOrNull(value) {
  if (value == null || value === '') return undefined;
  const n = Number(value);
  return Number.isFinite(n) ? n : undefined;
}
