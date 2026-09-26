import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { after, before, describe, it } from 'node:test';
import { inspect } from 'node:util';
import {
  MAX_ALTERNATES,
  VALHALLA_ERROR,
  ValhallaError,
  createValhallaClient,
  decodePolyline6,
} from '../src/routing/providers/valhalla.js';

// No request ever leaves the machine: every client gets a fake fetch, and the real one is
// swapped for a trap while the file runs (checked by the last test).
const realFetch = globalThis.fetch;
let strayRequests = 0;
before(() => {
  globalThis.fetch = async () => {
    strayRequests += 1;
    throw new Error('network forbidden in these tests');
  };
});
after(() => {
  globalThis.fetch = realFetch;
});

// ---- Fixtures ---------------------------------------------------------------

const FROM = { lat: 48.856614, lon: 2.352222 }; // Paris, Hôtel de Ville
const TO = { lat: 48.873792, lon: 2.295028 }; // Paris, Étoile
/** The route's shape, [lon, lat]: it starts on FROM and ends on TO. */
const SHAPE = [
  [2.352222, 48.856614],
  [2.3487, 48.8579],
  [2.3399, 48.8606],
  [2.3301, 48.8651],
  [2.3129, 48.8698],
  [2.3006, 48.8721],
  [2.295028, 48.873792],
];
/** Every coordinate the tests use, as it could leak into a message. */
const COORDINATE_TEXT = /48[.,]8|48[.,]9|2[.,]35|2[.,]29|2[.,]3\d|91[.,]1/;

const SETTINGS = { baseUrl: 'http://127.0.0.1:8002/', timeoutMs: 2000, searchCutoffM: 1500, maxSnapM: 500 };

/** Polyline6, the reference algorithm, for the fixtures. */
function encodePolyline6(points) {
  let out = '';
  let lastLat = 0;
  let lastLon = 0;
  for (const [lon, lat] of points) {
    const la = Math.round(lat * 1e6);
    const lo = Math.round(lon * 1e6);
    out += encodeValue(la - lastLat) + encodeValue(lo - lastLon);
    lastLat = la;
    lastLon = lo;
  }
  return out;
}

function encodeValue(value) {
  let n = value < 0 ? -2 * value - 1 : 2 * value;
  let out = '';
  while (n >= 0x20) {
    out += String.fromCharCode((0x20 | (n % 32)) + 63);
    n = Math.floor(n / 32);
  }
  return out + String.fromCharCode(n + 63);
}

/** One step as Valhalla 3.9 writes it in OSRM format. */
function osrmStep(type, location, { modifier, exit, name = '', ref, distance, duration }) {
  const maneuver = { bearing_after: 90, bearing_before: 0, location: [...location], type };
  if (modifier !== undefined) maneuver.modifier = modifier;
  if (exit !== undefined) maneuver.exit = exit;
  const step = {
    intersections: [{ location: [...location], bearings: [90, 270], entry: [true, false], out: 0 }],
    maneuver,
    name,
    duration,
    distance,
    driving_side: 'right',
    weight: duration,
    mode: 'driving',
    geometry: encodePolyline6([location, location]),
  };
  if (ref !== undefined) step.ref = ref;
  return step;
}

const OSRM_STEPS = [
  osrmStep('depart', SHAPE[0], { name: '  Rue de Rivoli ', distance: 312.4, duration: 41.6 }),
  osrmStep('turn', SHAPE[1], { modifier: 'right', ref: 'D 906', distance: 820.6, duration: 95.2 }),
  osrmStep('rotary', SHAPE[2], { modifier: 'slight right', exit: 2, name: 'Avenue de Friedland', distance: 150.2, duration: 20.4 }),
  osrmStep('exit rotary', SHAPE[3], { modifier: 'right', name: 'Avenue de Friedland', distance: 49.9, duration: 5.3 }),
  osrmStep('off ramp', SHAPE[3], { modifier: 'slight right', exit: 4, ref: 'A 13; N 13', distance: 1000.4, duration: 60.4 }),
  osrmStep('fork', SHAPE[4], { modifier: 'slight left', name: 'Boulevard Périphérique', ref: 'BP', distance: 700, duration: 50 }),
  osrmStep('notification', SHAPE[4], { modifier: 'straight', name: 'Bac du Verdon', distance: 10, duration: 600 }),
  osrmStep('use lane', SHAPE[5], { modifier: 'straight', name: 'Avenue Foch', distance: 80, duration: 9 }),
  osrmStep('continue', SHAPE[5], { modifier: 'uturn', name: 'Avenue Foch', distance: 120.6, duration: 30.4 }),
  osrmStep('end of road', SHAPE[5], { modifier: 'sideways', name: 'Place Charles de Gaulle', distance: 60, duration: 12 }),
  osrmStep('arrive', SHAPE[6], { modifier: 'right', name: 'Place Charles de Gaulle', distance: 0, duration: 0 }),
];

/** The same steps as the app reads them (the fields and vocabulary of ORS's normalized steps). */
const APP_STEPS = [
  { type: 'depart', modifier: null, location: SHAPE[0], exit: null, name: 'Rue de Rivoli', distanceM: 312, durationS: 42 },
  { type: 'turn', modifier: 'right', location: SHAPE[1], exit: null, name: 'D 906', distanceM: 821, durationS: 95 },
  // The named roundabout, its exit step folded in (150.2 + 49.9 m, 20.4 + 5.3 s).
  { type: 'roundabout', modifier: 'slight right', location: SHAPE[2], exit: 2, name: 'Avenue de Friedland', distanceM: 200, durationS: 26 },
  { type: 'off ramp', modifier: 'slight right', location: SHAPE[3], exit: null, name: 'A 13; N 13', distanceM: 1000, durationS: 60 },
  { type: 'fork', modifier: 'slight left', location: SHAPE[4], exit: null, name: 'Boulevard Périphérique', distanceM: 700, durationS: 50 },
  { type: 'notification', modifier: 'straight', location: SHAPE[4], exit: null, name: 'Bac du Verdon', distanceM: 10, durationS: 600 },
  { type: 'continue', modifier: 'straight', location: SHAPE[5], exit: null, name: 'Avenue Foch', distanceM: 80, durationS: 9 },
  { type: 'continue', modifier: 'uturn', location: SHAPE[5], exit: null, name: 'Avenue Foch', distanceM: 121, durationS: 30 },
  { type: 'end of road', modifier: null, location: SHAPE[5], exit: null, name: 'Place Charles de Gaulle', distanceM: 60, durationS: 12 },
  { type: 'arrive', modifier: 'right', location: SHAPE[6], exit: null, name: 'Place Charles de Gaulle', distanceM: 0, durationS: 0 },
];

function osrmRoute({ shape = SHAPE, steps = OSRM_STEPS, distance = 3303.7, duration = 950.2 } = {}) {
  return {
    weight_name: 'auto',
    weight: duration,
    duration,
    distance,
    legs: [{ via_waypoints: [], weight: duration, duration, steps, distance, summary: 'Rue de Rivoli, Avenue Foch' }],
    geometry: encodePolyline6(shape),
  };
}

/** A whole /route answer (a fresh copy: tests may break it). */
function osrmAnswer({ routes = [osrmRoute()], waypoints, warnings } = {}) {
  const json = {
    code: 'Ok',
    waypoints: waypoints ?? [
      { distance: 2.4, name: 'Rue de Rivoli', location: [...SHAPE[0]] },
      { distance: 3.1, name: 'Place Charles de Gaulle', location: [...SHAPE[SHAPE.length - 1]] },
    ],
    routes,
  };
  if (warnings) json.warnings = warnings;
  return structuredClone(json);
}

const STATUS = {
  version: '3.9.0',
  tileset_last_modified: 1790480467, // 2026-09-27T03:41:07Z
  available_actions: ['status', 'route', 'locate'],
  has_tiles: true,
  has_admins: true,
  has_timezones: true,
  has_live_traffic: false,
  has_transit_tiles: false,
  osm_changeset: 118000123,
  // A small extent around the centre of Paris: TO lies outside it.
  bbox: {
    type: 'FeatureCollection',
    features: [{
      type: 'Feature',
      properties: {},
      geometry: { type: 'Polygon', coordinates: [[[2.34, 48.85], [2.36, 48.85], [2.36, 48.86], [2.34, 48.86], [2.34, 48.85]]] },
    }],
  },
};

/** An answer as fetch gives it. */
function answer(status, body) {
  const text = typeof body === 'string' ? body : JSON.stringify(body);
  return { ok: status >= 200 && status < 300, status, text: async () => text };
}

/** A client on a fake fetch: [reply] gets each request ({ url, init, body }) and gives the answer. */
function fake(reply, settings = {}) {
  const calls = [];
  const fetchImpl = async (url, init) => {
    const call = { url, init, body: JSON.parse(init.body) };
    calls.push(call);
    return reply(call);
  };
  return { valhalla: createValhallaClient({ ...SETTINGS, fetchImpl, ...settings }), calls };
}

/** The ValhallaError [promise] ends with; its code must be [code]. */
async function failure(promise, code, label = code) {
  const error = await promise.then(
    () => assert.fail(`${label}: a ${code} failure was expected`),
    (e) => e,
  );
  assert.ok(error instanceof ValhallaError, `${label}: a ValhallaError, not ${error}`);
  assert.equal(error.code, code, `${label}: ${error.message}`);
  assert.equal(typeof error.status, 'number', label);
  return error;
}

/** Everything an error shows once logged or sent: its message and its fields. */
const shown = (error) => `${error.message} ${JSON.stringify({ ...error })} ${inspect({ ...error })}`;

const later = (ms, value) => new Promise((resolve) => setTimeout(() => resolve(value), ms));

// ---- Tests ------------------------------------------------------------------

describe('request sent to Valhalla', () => {
  it('asks the auto profile at 130 km/h, OSRM steps, polyline6 and no roundabout exits, in a POST body', async () => {
    const { valhalla, calls } = fake(() => answer(200, osrmAnswer()));
    await valhalla.routes(FROM, TO);
    assert.equal(calls.length, 1);
    const [{ url, init, body }] = calls;
    assert.equal(url, 'http://127.0.0.1:8002/route');
    assert.equal(init.method, 'POST');
    assert.equal(init.headers['Content-Type'], 'application/json');
    assert.ok(init.signal instanceof AbortSignal);
    assert.deepEqual(body, {
      locations: [
        { lat: FROM.lat, lon: FROM.lon, search_cutoff: 1500 },
        { lat: TO.lat, lon: TO.lon, search_cutoff: 1500 },
      ],
      costing: 'auto',
      costing_options: { auto: { top_speed: 130 } },
      format: 'osrm',
      shape_format: 'polyline6',
      roundabout_exits: false,
    });
    assert.ok(!COORDINATE_TEXT.test(url), 'no coordinate in the URL');
  });

  it('sends each avoid option as a strict exclusion, and only those asked', async () => {
    const { valhalla, calls } = fake(() => answer(200, osrmAnswer()));
    await valhalla.routes(FROM, TO, { avoid: ['tolls', 'highways', 'ferries', 'tolls'] });
    await valhalla.routes(FROM, TO, { avoid: ['ferries'] });
    await valhalla.routes(FROM, TO, { avoid: [] });
    assert.deepEqual(calls[0].body.costing_options, {
      auto: { top_speed: 130, exclude_tolls: true, exclude_highways: true, exclude_ferries: true },
    });
    assert.deepEqual(calls[1].body.costing_options, { auto: { top_speed: 130, exclude_ferries: true } });
    assert.deepEqual(calls[2].body.costing_options, { auto: { top_speed: 130 } });
  });

  it('turns ORS avoid_polygons into closed exclude_polygons rings, without alternates', async () => {
    const { valhalla, calls } = fake(() => answer(200, osrmAnswer()));
    const closedSquare = [[2.33, 48.86], [2.34, 48.86], [2.34, 48.87], [2.33, 48.87], [2.33, 48.86]];
    const openTriangle3d = [[2.31, 48.865, 35], [2.32, 48.865, 35], [2.32, 48.875, 35]];
    await valhalla.routes(FROM, TO, {
      avoid: ['tolls'],
      polygons: { type: 'MultiPolygon', coordinates: [[closedSquare], [openTriangle3d]] },
    });
    await valhalla.routes(FROM, TO, { polygons: { type: 'Polygon', coordinates: [closedSquare] } });
    assert.deepEqual(calls[0].body.exclude_polygons, [
      closedSquare,
      [[2.31, 48.865], [2.32, 48.865], [2.32, 48.875], [2.31, 48.865]],
    ]);
    assert.equal('alternates' in calls[0].body, false);
    assert.deepEqual(calls[0].body.costing_options.auto, { top_speed: 130, exclude_tolls: true });
    assert.deepEqual(calls[1].body.exclude_polygons, [closedSquare]);
  });

  it('asks alternates alone and reads every route back, best first', async () => {
    const routes = [
      osrmRoute(),
      osrmRoute({ distance: 3500.2, duration: 990.6 }),
      osrmRoute({ distance: 4100, duration: 1010 }),
    ];
    const { valhalla, calls } = fake(() => answer(200, osrmAnswer({ routes })));
    const found = await valhalla.routes(FROM, TO, { alternates: 2 });
    await valhalla.routes(FROM, TO, { alternates: 0 });
    assert.equal(calls[0].body.alternates, 2);
    assert.equal('exclude_polygons' in calls[0].body, false);
    assert.equal('alternates' in calls[1].body, false);
    assert.deepEqual(found.map((r) => [r.distanceM, r.durationS, r.engine]), [
      [3304, 950, 'valhalla'],
      [3500, 991, 'valhalla'],
      [4100, 1010, 'valhalla'],
    ]);
  });

  it('sends bearings per point, in whole degrees (Valhalla drops anything else)', async () => {
    const { valhalla, calls } = fake(() => answer(200, osrmAnswer()));
    await valhalla.routes(FROM, TO, { bearings: [[359.6, 44.6], [90.2, 45]] });
    await valhalla.routes(FROM, TO, { bearings: [null, [180, 30]] });
    await valhalla.routes(FROM, TO, { bearings: [[12.3, 45]] });
    assert.deepEqual(calls[0].body.locations, [
      { lat: FROM.lat, lon: FROM.lon, search_cutoff: 1500, heading: 0, heading_tolerance: 45 },
      { lat: TO.lat, lon: TO.lon, search_cutoff: 1500, heading: 90, heading_tolerance: 45 },
    ]);
    assert.deepEqual(calls[1].body.locations, [
      { lat: FROM.lat, lon: FROM.lon, search_cutoff: 1500 },
      { lat: TO.lat, lon: TO.lon, search_cutoff: 1500, heading: 180, heading_tolerance: 30 },
    ]);
    assert.deepEqual(calls[2].body.locations[0], { lat: FROM.lat, lon: FROM.lon, search_cutoff: 1500, heading: 12, heading_tolerance: 45 });
    assert.equal('heading' in calls[2].body.locations[1], false);
  });

  it('rounds searchCutoffM to whole metres (Valhalla drops anything else)', async () => {
    const { valhalla, calls } = fake(() => answer(200, osrmAnswer()), { searchCutoffM: 1234.6 });
    await valhalla.routes(FROM, TO);
    assert.deepEqual(calls[0].body.locations.map((l) => l.search_cutoff), [1235, 1235]);
  });
});

describe('refusals', () => {
  it('refuses what Valhalla would not take whole, before any request', async () => {
    const { valhalla, calls } = fake(() => answer(200, osrmAnswer()));
    const square = [[[2.33, 48.86], [2.34, 48.86], [2.34, 48.87], [2.33, 48.86]]];
    const cases = [
      ['avoid traffic (the façade turns it into polygons)', FROM, TO, { avoid: ['traffic'] }],
      ['avoid unknown', FROM, TO, { avoid: ['tolls', 'motorways'] }],
      ['avoid not a list', FROM, TO, { avoid: 'tolls' }],
      ['avoid prototype key', FROM, TO, { avoid: ['toString'] }],
      ['avoid not text', FROM, TO, { avoid: [1] }],
      ['polygons with alternates', FROM, TO, { polygons: { type: 'MultiPolygon', coordinates: [square] }, alternates: 1 }],
      ['too many alternates', FROM, TO, { alternates: MAX_ALTERNATES + 1 }],
      ['negative alternates', FROM, TO, { alternates: -1 }],
      ['fractional alternates', FROM, TO, { alternates: 1.5 }],
      ['alternates as text', FROM, TO, { alternates: '2' }],
      ['heading out of range', FROM, TO, { bearings: [[400, 45]] }],
      ['bearing without tolerance', FROM, TO, { bearings: [[90]] }],
      ['three bearings', FROM, TO, { bearings: [[90, 45], [90, 45], [90, 45]] }],
      ['negative tolerance', FROM, TO, { bearings: [[90, -5]] }],
      ['heading not a number', FROM, TO, { bearings: [[Number.NaN, 45]] }],
      ['bearings not a list', FROM, TO, { bearings: 'north' }],
      ['not a polygon', FROM, TO, { polygons: { type: 'Point', coordinates: [2.3, 48.8] } }],
      ['polygons as a list', FROM, TO, { polygons: square }],
      ['empty MultiPolygon', FROM, TO, { polygons: { type: 'MultiPolygon', coordinates: [] } }],
      ['polygon with a hole', FROM, TO, { polygons: { type: 'MultiPolygon', coordinates: [[square[0], square[0]]] } }],
      ['ring of two corners', FROM, TO, { polygons: { type: 'MultiPolygon', coordinates: [[[[2.33, 48.86], [2.34, 48.86], [2.33, 48.86]]]] } }],
      ['corner off the globe', FROM, TO, { polygons: { type: 'MultiPolygon', coordinates: [[[[200, 48.86], [2.34, 48.86], [2.34, 48.87]]]] } }],
      ['corner as text', FROM, TO, { polygons: { type: 'MultiPolygon', coordinates: [[[['2.33', '48.86'], [2.34, 48.86], [2.34, 48.87]]]] } }],
      ['from missing', null, TO, {}],
      ['from off the globe', { lat: 91.123456, lon: 2.352222 }, TO, {}],
      ['to as text', FROM, { lat: '48.873792', lon: '2.295028' }, {}],
      ['to without lon', FROM, { lat: 48.873792 }, {}],
      ['options not an object', FROM, TO, 'fast'],
      ['signal not an AbortSignal', FROM, TO, { signal: {} }],
    ];
    for (const [label, from, to, options] of cases) {
      const error = await failure(valhalla.routes(from, to, options), VALHALLA_ERROR.INVALID_REQUEST, label);
      assert.equal(error.status, 400, label);
      assert.ok(!COORDINATE_TEXT.test(shown(error)), `${label}: no coordinate in ${error.message}`);
    }
    assert.equal(calls.length, 0, 'nothing was sent');
  });

  it('refuses a route whose strict exclusions the server dropped (warning 208)', async () => {
    const warnings = [{ code: 208, text: 'Hard exclusions are not allowed on this server, ignoring hard excludes' }];
    const { valhalla } = fake(() => answer(200, osrmAnswer({ warnings })));
    const error = await failure(valhalla.routes(FROM, TO, { avoid: ['tolls'] }), VALHALLA_ERROR.EXCLUSIONS_IGNORED);
    assert.equal(error.status, 502);
    assert.equal(error.valhallaCode, 208);
  });

  it('refuses a route whose avoided areas the server dropped (warning 204)', async () => {
    const warnings = [{ code: 204, text: '"exclude_polygons" received invalid input, ignoring exclude_polygons' }];
    const { valhalla } = fake(() => answer(200, osrmAnswer({ warnings })));
    const polygons = { type: 'MultiPolygon', coordinates: [[[[2.33, 48.86], [2.34, 48.86], [2.34, 48.87], [2.33, 48.86]]]] };
    const error = await failure(valhalla.routes(FROM, TO, { polygons }), VALHALLA_ERROR.POLYGONS_REJECTED);
    assert.equal(error.status, 502);
    assert.equal(error.valhallaCode, 204);
  });

  it('reads PerimeterExceeded as avoided areas refused', async () => {
    const body = { code: 'PerimeterExceeded', message: 'Perimeter of avoid polygons exceeds the max limit.' };
    const { valhalla } = fake(() => answer(400, body));
    const polygons = { type: 'MultiPolygon', coordinates: [[[[2.33, 48.86], [2.34, 48.86], [2.34, 48.87], [2.33, 48.86]]]] };
    const error = await failure(valhalla.routes(FROM, TO, { polygons }), VALHALLA_ERROR.POLYGONS_REJECTED);
    assert.deepEqual([error.status, error.upstreamStatus, error.valhallaCode], [502, 400, 'PerimeterExceeded']);
  });

  it('keeps a route found on a relaxed second pass (warning 401 is not a refusal)', async () => {
    const warnings = [{ code: 401, text: 'Routing failed on first pass, retrying with relaxed restrictions' }];
    const { valhalla } = fake(() => answer(200, osrmAnswer({ warnings })));
    const [route] = await valhalla.routes(FROM, TO, { avoid: ['highways'] });
    assert.equal(route.engine, 'valhalla');
  });
});

describe('routes read back', () => {
  it('reads Valhalla steps as the app reads ORS steps', async () => {
    const { valhalla } = fake(() => answer(200, osrmAnswer()));
    const [route] = await valhalla.routes(FROM, TO);
    assert.deepEqual(route, {
      distanceM: 3304,
      durationS: 950,
      coordinates: SHAPE,
      steps: APP_STEPS,
      engine: 'valhalla',
      mapVersion: null,
    });
  });

  it('keeps the fields and types of ORS normalized routes and steps', async () => {
    const { valhalla } = fake(() => answer(200, osrmAnswer()));
    const [route] = await valhalla.routes(FROM, TO);
    // normalizeOrsFeature (ors.js): the same keys, in the same order.
    assert.deepEqual(Object.keys(route), ['distanceM', 'durationS', 'coordinates', 'steps', 'engine', 'mapVersion']);
    for (const step of route.steps) {
      assert.deepEqual(Object.keys(step), ['type', 'modifier', 'location', 'exit', 'name', 'distanceM', 'durationS']);
      assert.equal(typeof step.type, 'string');
      assert.ok(step.modifier === null || typeof step.modifier === 'string');
      assert.ok(step.location.length === 2 && step.location.every(Number.isFinite));
      assert.ok(step.exit === null || Number.isInteger(step.exit));
      assert.equal(typeof step.name, 'string');
      assert.ok(Number.isInteger(step.distanceM) && Number.isInteger(step.durationS));
    }
    // A U-turn among the first steps is still seen as routeFacts (log.js) looks for it.
    const uturnSteps = [OSRM_STEPS[0], osrmStep('continue', SHAPE[1], { modifier: 'uturn', distance: 5, duration: 2 }), ...OSRM_STEPS.slice(1)];
    const { valhalla: other } = fake(() => answer(200, osrmAnswer({ routes: [osrmRoute({ steps: uturnSteps })] })));
    const [turned] = await other.routes(FROM, TO);
    assert.ok(turned.steps.slice(0, 2).some((step) => step.modifier === 'uturn'));
  });

  it('refuses an answer with a wrong number or a missing field', async () => {
    const broken = [
      ['code not Ok', (a) => { a.code = 'NoRoute'; }],
      ['no routes', (a) => { a.routes = []; }],
      ['routes missing', (a) => { delete a.routes; }],
      ['distance as text', (a) => { a.routes[0].distance = '3303.7'; }],
      ['distance missing', (a) => { delete a.routes[0].distance; }],
      ['negative duration', (a) => { a.routes[0].duration = -1; }],
      ['geometry missing', (a) => { delete a.routes[0].geometry; }],
      ['two legs', (a) => { a.routes[0].legs.push(a.routes[0].legs[0]); }],
      ['steps missing', (a) => { delete a.routes[0].legs[0].steps; }],
      ['step distance missing', (a) => { delete a.routes[0].legs[0].steps[1].distance; }],
      ['step duration null', (a) => { a.routes[0].legs[0].steps[1].duration = null; }],
      ['step location off the globe', (a) => { a.routes[0].legs[0].steps[1].maneuver.location = [200, 48.8]; }],
      ['step location as text', (a) => { a.routes[0].legs[0].steps[1].maneuver.location = ['2.3487', '48.8579']; }],
      ['step without maneuver', (a) => { delete a.routes[0].legs[0].steps[1].maneuver; }],
      ['step type missing', (a) => { delete a.routes[0].legs[0].steps[1].maneuver.type; }],
      ['exit not whole', (a) => { a.routes[0].legs[0].steps[2].maneuver.exit = 2.5; }],
      ['modifier not text', (a) => { a.routes[0].legs[0].steps[1].maneuver.modifier = 90; }],
      ['name not text', (a) => { a.routes[0].legs[0].steps[0].name = 42; }],
      ['ref not text', (a) => { a.routes[0].legs[0].steps[1].ref = ['D 906']; }],
      ['first step not depart', (a) => { a.routes[0].legs[0].steps[0].maneuver.type = 'turn'; }],
      ['last step not arrive', (a) => { a.routes[0].legs[0].steps.at(-1).maneuver.type = 'continue'; }],
      ['waypoints missing', (a) => { delete a.waypoints; }],
      ['one waypoint', (a) => { a.waypoints.pop(); }],
      ['waypoint location as text', (a) => { a.waypoints[0].location = ['2.352222', 48.856614]; }],
      ['waypoint distance negative', (a) => { a.waypoints[1].distance = -3; }],
    ];
    for (const [label, breakIt] of broken) {
      const json = osrmAnswer();
      breakIt(json);
      const { valhalla } = fake(() => answer(200, json));
      const error = await failure(valhalla.routes(FROM, TO), VALHALLA_ERROR.INVALID_RESPONSE, label);
      assert.equal(error.status, 502, label);
    }
  });
});

describe('shape', () => {
  it('decodes polyline6 (the reference example, read at 6 decimals)', () => {
    // Google's polyline example, [(38.5, -120.2), (40.7, -120.95), (43.252, -126.453)] at 5 decimals.
    assert.deepEqual(decodePolyline6('_p~iF~ps|U_ulLnnqC_mqNvxq`@'), [[-12.02, 3.85], [-12.095, 4.07], [-12.6453, 4.3252]]);
    assert.deepEqual(decodePolyline6(encodePolyline6(SHAPE)), SHAPE);
  });

  it('refuses a shape cut in the middle of a number or of a point', () => {
    const encoded = encodePolyline6(SHAPE);
    const cases = [
      ['last number unfinished', encoded.slice(0, -1)],
      ['latitude without its longitude', encoded + encodeValue(0)],
    ];
    for (const [label, cut] of cases) {
      assert.throws(() => decodePolyline6(cut), (e) => e instanceof ValhallaError
        && e.code === VALHALLA_ERROR.INVALID_RESPONSE && /cut short/.test(e.message), label);
    }
  });

  it('refuses a route whose shape stops before its last step', async () => {
    const route = osrmRoute({ shape: SHAPE.slice(0, -1) });
    const { valhalla } = fake(() => answer(200, osrmAnswer({ routes: [route] })));
    const error = await failure(valhalla.routes(FROM, TO), VALHALLA_ERROR.INVALID_RESPONSE);
    assert.match(error.message, /cut short/);
  });

  it('refuses a shape missing, unreadable, off the globe or of one point', () => {
    const cases = [
      ['missing', ''],
      ['not text', 42],
      ['character out of the alphabet', ' '],
      ['endless number', '~~~~~~~~~'],
      ['latitude 95°', encodePolyline6([[2.35, 95], [2.35, 48]])],
      ['one point', encodePolyline6([SHAPE[0]])],
    ];
    for (const [label, encoded] of cases) {
      assert.throws(() => decodePolyline6(encoded), (e) => e instanceof ValhallaError && e.code === VALHALLA_ERROR.INVALID_RESPONSE, label);
    }
  });
});

describe('snapping', () => {
  it('refuses a destination snapped past maxSnapM (out of the map)', async () => {
    const { valhalla } = fake(() => answer(200, osrmAnswer()));
    const abroad = { lat: 48.8920, lon: 2.295028 }; // ~2 km north of where Valhalla snapped it
    const error = await failure(valhalla.routes(FROM, abroad), VALHALLA_ERROR.OUT_OF_COVERAGE);
    assert.equal(error.status, 404);
    assert.equal(error.point, 'to');
    assert.ok(error.snapM > 1900 && error.snapM < 2100, `snapM ${error.snapM}`);
    assert.ok(!COORDINATE_TEXT.test(shown(error)), error.message);
  });

  it('trusts Valhalla\'s own snap distance too', async () => {
    const json = osrmAnswer();
    json.waypoints[0].distance = 800;
    const { valhalla } = fake(() => answer(200, json));
    const error = await failure(valhalla.routes(FROM, TO), VALHALLA_ERROR.OUT_OF_COVERAGE);
    assert.deepEqual([error.point, error.snapM], ['from', 800]);
  });

  it('refuses a route whose shape ends past maxSnapM, whatever the waypoints say', async () => {
    const shape = [...SHAPE.slice(0, -1), [2.295028, 48.8828]]; // ~1 km north of TO
    const steps = [...OSRM_STEPS.slice(0, -1), osrmStep('arrive', shape.at(-1), { distance: 0, duration: 0 })];
    const { valhalla } = fake(() => answer(200, osrmAnswer({ routes: [osrmRoute({ shape, steps })] })));
    const error = await failure(valhalla.routes(FROM, TO), VALHALLA_ERROR.OUT_OF_COVERAGE);
    assert.equal(error.point, 'to');
  });

  it('keeps a point snapped within maxSnapM', async () => {
    const { valhalla } = fake(() => answer(200, osrmAnswer()));
    const [route] = await valhalla.routes(FROM, { lat: TO.lat + 0.0018, lon: TO.lon }); // ~200 m
    assert.equal(route.engine, 'valhalla');
  });

  it('reads NoSegment (no road within searchCutoffM) as out of the map', async () => {
    const body = { code: 'NoSegment', message: 'One of the supplied input coordinates could not snap to street segment.' };
    const { valhalla } = fake(() => answer(400, body));
    const error = await failure(valhalla.routes(FROM, TO), VALHALLA_ERROR.OUT_OF_COVERAGE);
    assert.deepEqual([error.status, error.upstreamStatus, error.valhallaCode], [404, 400, 'NoSegment']);
  });
});

describe('errors', () => {
  it('reads Valhalla refusals as constant codes', async () => {
    const cases = [
      [400, { code: 'NoRoute', message: 'Impossible route between points' }, VALHALLA_ERROR.NO_ROUTE, 404, 'NoRoute'],
      [400, { code: 'InvalidValue', message: 'The successfully parsed query parameters are invalid.' }, VALHALLA_ERROR.REJECTED, 502, 'InvalidValue'],
      [400, { code: 'DistanceExceeded', message: 'Path distance exceeds the max distance limit.' }, VALHALLA_ERROR.REJECTED, 502, 'DistanceExceeded'],
      [404, { code: 'InvalidService', message: 'Service name is invalid.' }, VALHALLA_ERROR.REJECTED, 502, 'InvalidService'],
      [503, { code: 'ServiceUnavailable', message: 'The service is shutting down.' }, VALHALLA_ERROR.UNAVAILABLE, 503, 'ServiceUnavailable'],
      [500, '<html><body>Internal Server Error</body></html>', VALHALLA_ERROR.UNAVAILABLE, 503, null],
      [400, { code: 'constructor' }, VALHALLA_ERROR.REJECTED, 502, 'constructor'],
      [400, { code: 'No Route; 48.85' }, VALHALLA_ERROR.REJECTED, 502, null],
    ];
    for (const [httpStatus, body, code, status, valhallaCode] of cases) {
      const { valhalla } = fake(() => answer(httpStatus, body));
      const error = await failure(valhalla.routes(FROM, TO), code, `HTTP ${httpStatus}`);
      assert.deepEqual([error.status, error.upstreamStatus, error.valhallaCode], [status, httpStatus, valhallaCode]);
    }
  });

  it('reads a network failure as unavailable, with its system code only', async () => {
    const { valhalla } = fake(() => {
      throw Object.assign(new TypeError('fetch failed near 48.856614,2.352222'), {
        cause: Object.assign(new Error('connect ECONNREFUSED 127.0.0.1:8002'), { code: 'ECONNREFUSED' }),
      });
    });
    const error = await failure(valhalla.routes(FROM, TO), VALHALLA_ERROR.UNAVAILABLE);
    assert.equal(error.message, 'Valhalla: unreachable (ECONNREFUSED)');
    assert.equal(error.upstreamStatus, null);
  });

  it('reads a body cut off as unavailable, and a body that is not JSON as invalid', async () => {
    const cut = fake(() => ({ ok: true, status: 200, text: async () => { throw new Error('terminated'); } }));
    const cutError = await failure(cut.valhalla.routes(FROM, TO), VALHALLA_ERROR.UNAVAILABLE);
    assert.equal(cutError.upstreamStatus, 200);
    for (const text of ['not json', '[1, 2]', 'null']) {
      const { valhalla } = fake(() => answer(200, text));
      await failure(valhalla.routes(FROM, TO), VALHALLA_ERROR.INVALID_RESPONSE, text);
    }
    const { valhalla } = fake(() => ({ status: 200 }));
    await failure(valhalla.routes(FROM, TO), VALHALLA_ERROR.INVALID_RESPONSE, 'no body');
  });

  it('never shows a coordinate: not the request\'s, not Valhalla\'s text', async () => {
    const replies = [
      ['NoRoute quoting points', () => answer(400, { code: 'NoRoute', message: 'No path from 48.856614,2.352222 to 48.873792,2.295028' })],
      ['crash page quoting points', () => answer(500, '<pre>route 48.856614 2.352222 failed</pre>')],
      ['JSON cut inside a point', () => answer(200, '{"code":"Ok","waypoints":[{"location":[2.352222,48.856614')],
      ['network error quoting points', () => { throw new TypeError('fetch failed near 48.856614,2.352222'); }],
      ['shape cut short', () => answer(200, osrmAnswer({ routes: [osrmRoute({ shape: SHAPE.slice(0, -1) })] }))],
      ['status payload quoting points', () => answer(200, { version: '48.856614 2.352222 ', tileset_last_modified: 1 })],
    ];
    for (const [label, reply] of replies) {
      const { valhalla } = fake(reply);
      const error = await valhalla.routes(FROM, TO).catch((e) => e);
      assert.ok(error instanceof ValhallaError, label);
      assert.ok(!COORDINATE_TEXT.test(shown(error)), `${label}: ${shown(error)}`);
      const statusError = await valhalla.status().catch((e) => e);
      assert.ok(statusError instanceof ValhallaError, label);
      assert.ok(!COORDINATE_TEXT.test(shown(statusError)), `${label} (status): ${shown(statusError)}`);
    }
  });
});

// Each test is capped: a provider that lost its deadline fails here instead of hanging the run.
describe('deadline and abort', { timeout: 3000 }, () => {
  it('times out while the answer does not come, even from a fetch that ignores its signal', async () => {
    const { valhalla, calls } = fake(() => new Promise(() => {}), { timeoutMs: 50 });
    const startedAt = Date.now();
    const error = await failure(valhalla.routes(FROM, TO), VALHALLA_ERROR.TIMEOUT);
    assert.equal(error.status, 504);
    assert.ok(Date.now() - startedAt < 1500);
    assert.equal(calls[0].init.signal.aborted, true, 'the request itself is dropped');
  });

  it('times out while the body does not come: the deadline covers it', async () => {
    const { valhalla } = fake(() => ({ ok: true, status: 200, text: () => new Promise(() => {}) }), { timeoutMs: 50 });
    await failure(valhalla.routes(FROM, TO), VALHALLA_ERROR.TIMEOUT);
    const late = fake(() => ({ ok: true, status: 200, text: () => later(200, JSON.stringify(osrmAnswer())) }), { timeoutMs: 50 });
    await failure(late.valhalla.routes(FROM, TO), VALHALLA_ERROR.TIMEOUT);
    const slowStatus = fake(() => ({ ok: true, status: 200, text: () => later(200, JSON.stringify(STATUS)) }), { timeoutMs: 50 });
    await failure(slowStatus.valhalla.status(), VALHALLA_ERROR.TIMEOUT);
  });

  it('counts the reading of the JSON within the deadline', async () => {
    const realNow = Date.now;
    const text = JSON.stringify(osrmAnswer());
    // The body comes at once, but the clock says a minute went by: as if parsing took that long.
    const { valhalla } = fake(() => ({
      ok: true,
      status: 200,
      text: async () => {
        const shifted = realNow() + 60_000;
        Date.now = () => shifted;
        return text;
      },
    }), { timeoutMs: 1000 });
    try {
      await failure(valhalla.routes(FROM, TO), VALHALLA_ERROR.TIMEOUT);
    } finally {
      Date.now = realNow;
    }
  });

  it('reports a timeout, not a network failure, when the fetch honours its signal', async () => {
    const { valhalla } = fake(({ init }) => new Promise((_, reject) => {
      init.signal.addEventListener('abort', () => reject(new DOMException('This operation was aborted', 'AbortError')));
    }), { timeoutMs: 50 });
    await failure(valhalla.routes(FROM, TO), VALHALLA_ERROR.TIMEOUT);
  });

  it('stops at once on the caller\'s signal: before, during the answer, during the body', async () => {
    const { valhalla, calls } = fake(() => ({ ok: true, status: 200, text: () => new Promise(() => {}) }), { timeoutMs: 5000 });
    const stopped = new AbortController();
    stopped.abort();
    const before = await failure(valhalla.routes(FROM, TO, { signal: stopped.signal }), VALHALLA_ERROR.ABORTED);
    assert.equal(before.status, 499);
    await failure(valhalla.status({ signal: stopped.signal }), VALHALLA_ERROR.ABORTED);
    assert.equal(calls.length, 0, 'nothing sent once aborted');

    const duringBody = new AbortController();
    setTimeout(() => duringBody.abort(), 20);
    const startedAt = Date.now();
    await failure(valhalla.routes(FROM, TO, { signal: duringBody.signal }), VALHALLA_ERROR.ABORTED);
    assert.ok(Date.now() - startedAt < 1000);

    const honouring = fake(({ init }) => new Promise((_, reject) => {
      init.signal.addEventListener('abort', () => reject(new DOMException('This operation was aborted', 'AbortError')));
    }));
    const duringAnswer = new AbortController();
    setTimeout(() => duringAnswer.abort(), 20);
    await failure(honouring.valhalla.routes(FROM, TO, { signal: duringAnswer.signal }), VALHALLA_ERROR.ABORTED);
  });

  it('lets go of its deadline and of the caller\'s signal once the answer is read', async () => {
    const { valhalla, calls } = fake(() => answer(200, osrmAnswer()), { timeoutMs: 30 });
    const caller = new AbortController();
    await valhalla.routes(FROM, TO, { signal: caller.signal });
    await later(80);
    caller.abort();
    assert.equal(calls[0].init.signal.aborted, false, 'neither the deadline nor the caller aborted a finished call');
  });
});

describe('status and map metadata', () => {
  it('reads the detailed /status, checked', async () => {
    const { valhalla, calls } = fake(() => answer(200, STATUS));
    const payload = await valhalla.status();
    assert.equal(calls[0].url, 'http://127.0.0.1:8002/status');
    assert.deepEqual(calls[0].body, { verbose: true });
    assert.deepEqual(payload, {
      version: '3.9.0',
      tileset_last_modified: 1790480467,
      has_tiles: true,
      has_admins: true,
      has_timezones: true,
      has_live_traffic: false,
      has_transit_tiles: false,
      osm_changeset: 118000123,
      available_actions: ['status', 'route', 'locate'],
      bbox: STATUS.bbox,
      warnings: null,
    });
  });

  it('gives routes the map date once a status has read it, and never decides from the bbox', async () => {
    const { valhalla } = fake(({ url }) => answer(200, url.endsWith('/status') ? STATUS : osrmAnswer()));
    const [before] = await valhalla.routes(FROM, TO);
    assert.equal(before.mapVersion, null);
    await valhalla.status();
    // TO lies outside the status bbox: the bbox is the tiles' extent, not France's border.
    const [after] = await valhalla.routes(FROM, TO);
    assert.equal(after.mapVersion, '2026-09-27T03:41:07Z');
  });

  it('reads a server without verbose status: details null, map date kept', async () => {
    const plain = { version: '3.9.0', tileset_last_modified: 1790480467, available_actions: ['status', 'route'] };
    const { valhalla } = fake(({ url }) => answer(200, url.endsWith('/status') ? plain : osrmAnswer()));
    const payload = await valhalla.status();
    assert.deepEqual(payload, {
      version: '3.9.0',
      tileset_last_modified: 1790480467,
      has_tiles: null,
      has_admins: null,
      has_timezones: null,
      has_live_traffic: null,
      has_transit_tiles: null,
      osm_changeset: null,
      available_actions: ['status', 'route'],
      bbox: null,
      warnings: null,
    });
    const [route] = await valhalla.routes(FROM, TO);
    assert.equal(route.mapVersion, '2026-09-27T03:41:07Z');
  });

  it('forgets the map when the server has no tiles, keeps it when a status is unreadable', async () => {
    let statusBody = STATUS;
    const { valhalla } = fake(({ url }) => answer(200, url.endsWith('/status') ? statusBody : osrmAnswer()));
    await valhalla.status();
    const unreadable = [
      { ...STATUS, version: 42 },
      { ...STATUS, version: '' },
      { ...STATUS, tileset_last_modified: -1 },
      { ...STATUS, tileset_last_modified: 1.5 },
      { ...STATUS, tileset_last_modified: '1790480467' },
      { ...STATUS, has_live_traffic: 'yes' },
      { ...STATUS, osm_changeset: -3 },
      { ...STATUS, available_actions: [1] },
      { ...STATUS, bbox: 'France' },
      { ...STATUS, warnings: 'none' },
      [STATUS],
    ];
    for (const body of unreadable) {
      statusBody = body;
      await failure(valhalla.status(), VALHALLA_ERROR.INVALID_RESPONSE, JSON.stringify(body).slice(0, 60));
    }
    const [kept] = await valhalla.routes(FROM, TO);
    assert.equal(kept.mapVersion, '2026-09-27T03:41:07Z');
    statusBody = { ...STATUS, has_tiles: false, tileset_last_modified: 0 };
    const payload = await valhalla.status();
    assert.equal(payload.has_tiles, false);
    const [forgotten] = await valhalla.routes(FROM, TO);
    assert.equal(forgotten.mapVersion, null);
  });

  it('keeps each client\'s map to itself', async () => {
    const serve = ({ url }) => answer(200, url.endsWith('/status') ? STATUS : osrmAnswer());
    const a = fake(serve);
    const b = fake(serve);
    await a.valhalla.status();
    const [[fromA], [fromB]] = await Promise.all([a.valhalla.routes(FROM, TO), b.valhalla.routes(FROM, TO)]);
    assert.equal(fromA.mapVersion, '2026-09-27T03:41:07Z');
    assert.equal(fromB.mapVersion, null);
  });

  it('reads a refused status like a refused route', async () => {
    const { valhalla } = fake(() => answer(503, { code: 'ServiceUnavailable', message: 'The service is shutting down.' }));
    const error = await failure(valhalla.status(), VALHALLA_ERROR.UNAVAILABLE);
    assert.equal(error.upstreamStatus, 503);
  });
});

describe('client', () => {
  it('uses globalThis.fetch when no fetchImpl is given', async () => {
    const trap = globalThis.fetch;
    let seen = null;
    globalThis.fetch = async (url, init) => {
      seen = { url, body: JSON.parse(init.body) };
      return answer(200, STATUS);
    };
    try {
      await createValhallaClient(SETTINGS).status();
    } finally {
      globalThis.fetch = trap;
    }
    assert.deepEqual(seen, { url: 'http://127.0.0.1:8002/status', body: { verbose: true } });
  });

  it('refuses unusable settings at once', () => {
    const changes = [
      { baseUrl: undefined },
      { baseUrl: 'ftp://127.0.0.1:8002' },
      { baseUrl: 'valhalla' },
      { timeoutMs: 0 },
      { timeoutMs: -5 },
      { timeoutMs: Number.NaN },
      { timeoutMs: 3e9 },
      { timeoutMs: '2000' },
      { searchCutoffM: 0 },
      { searchCutoffM: 0.4 },
      { searchCutoffM: undefined },
      { maxSnapM: -1 },
      { maxSnapM: Number.POSITIVE_INFINITY },
      { fetchImpl: 'fetch' },
    ];
    for (const change of changes) {
      assert.throws(
        () => createValhallaClient({ ...SETTINGS, fetchImpl: async () => answer(200, STATUS), ...change }),
        (e) => e instanceof ValhallaError && e.code === VALHALLA_ERROR.INVALID_CONFIG && e.status === 500,
        JSON.stringify(change),
      );
    }
  });

  it('stands alone: reads no config.js, only pure helpers', () => {
    const source = readFileSync(new URL('../src/routing/providers/valhalla.js', import.meta.url), 'utf8');
    const imports = source.match(/^import .*$/gm) ?? [];
    assert.deepEqual(imports, ["import { haversine } from '../../radars/geo.js';"]);
  });
});

it('never reached the network', () => {
  assert.equal(strayRequests, 0);
});
