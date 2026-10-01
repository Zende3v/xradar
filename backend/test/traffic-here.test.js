import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { measure } from '../src/routing/geometry.js';
import { corridorOf, flexiblePolyline, hereTravel as liveHereTravel, placeOnRoute } from '../src/traffic/here.js';
import { speedStore } from '../src/traffic/speeds.js';

const hereTravel = (points, options) => liveHereTravel(points, { reserve: () => true, cache: null, ...options });

// A road due north along 2°E, from 48.00° to 48.10° (about 11.1 km).
const ROUTE = measure(Array.from({ length: 11 }, (_, i) => [48 + i * 0.01, 2]));
const M_PER_DEG = ROUTE.total / 0.1;
const link = (fromLat, toLat) => ({ shape: { links: [{ points: [{ lat: fromLat, lng: 2 }, { lat: toLat, lng: 2 }] }] }, length: Math.abs(toLat - fromLat) * M_PER_DEG });

describe('HERE traffic', () => {
  it('encodes HERE\'s own example of a Flexible Polyline', () => {
    const points = [[52.529583, 13.379416], [52.53282, 13.399157], [52.538458, 13.395896], [52.541277, 13.408856], [52.539085, 13.424048]];
    assert.equal(flexiblePolyline(points, 6), 'BG-6kmkDw1zwZqqG6xmBsgL5rGmwFgqZ_oEw1d');
  });

  it('keeps the corridor within HERE\'s 300 points', () => {
    const long = measure(Array.from({ length: 2001 }, (_, i) => [45 + i * 0.001, 2]));
    assert.ok(corridorOf(long).polyline.length > 10);
  });

  it('places the flow and the incidents that follow the route, the way it goes', () => {
    const flow = [
      // 2 km at 10 m/s where 25 m/s flows: 200 - 80 = 120 s lost.
      { location: link(48.02, 48.02 + 2000 / M_PER_DEG), currentFlow: { speed: 10, speedUncapped: 10, freeFlow: 25, jamFactor: 6, traversability: 'open' } },
      // Flowing: nothing.
      { location: link(48.05, 48.06), currentFlow: { speed: 24, freeFlow: 25, jamFactor: 0.5, traversability: 'open' } },
      // The other way: left out.
      { location: link(48.09, 48.08), currentFlow: { speed: 2, freeFlow: 25, jamFactor: 9, traversability: 'open' } },
    ];
    const incidents = [{ location: link(48.07, 48.075), incidentDetails: { roadClosed: true, type: 'roadClosure' } }];
    const sections = placeOnRoute(ROUTE, flow, incidents);
    assert.deepEqual(sections.map((s) => [s.kind, s.level, s.delayS, s.source]), [
      ['speed', 'jam', 120, 'here'],
      ['closed', 'closed', 0, 'here'],
    ]);
  });

  it('counts a road HERE sends twice, or under overlapping items, once: the worst piece per metre', () => {
    const jam = { speed: 10, speedUncapped: 10, freeFlow: 25, jamFactor: 6, traversability: 'open' };
    const start = 48.02;
    const flow = [
      // The same 2 km twice (120 s lost each), then 2 km slow over its second half: 100 - 80 = 20 s.
      { location: link(start, start + 2000 / M_PER_DEG), currentFlow: jam },
      { location: link(start, start + 2000 / M_PER_DEG), currentFlow: jam },
      { location: link(start + 1000 / M_PER_DEG, start + 3000 / M_PER_DEG), currentFlow: { speed: 20, freeFlow: 25, jamFactor: 3, traversability: 'open' } },
    ];
    const sections = placeOnRoute(ROUTE, flow, []);
    // The jam once, then only the slow piece's last 1 000 m: 10 s.
    assert.deepEqual(sections.map((s) => [s.level, s.delayS]), [['jam', 120], ['slow', 10]]);
  });
});

describe('HERE\'s time for our route (Route Import)', () => {
  const points = ROUTE.points;
  const answer = (sections, status = 200) => async (url, init) => {
    answer.sent = { url: String(url), trace: JSON.parse(init.body).trace };
    return { ok: status === 200, status, json: async () => ({ routes: [{ sections }] }) };
  };
  const section = (length, duration) => ({ summary: { length, duration, baseDuration: duration - 60, typicalDuration: duration - 30 } });

  it('adds up its sections, the traffic included, from a trace of our own route', async () => {
    const half = ROUTE.total / 2;
    const travel = await hereTravel(points, { fetchImpl: answer([section(half, 600), section(half, 900)]) });
    assert.deepEqual(travel, { travelS: 1500, baseS: 1380, typicalS: 1440, lengthM: Math.round(ROUTE.total) });
    assert.ok(answer.sent.url.includes('/import?') && answer.sent.url.includes('transportMode=car'));
    assert.ok(answer.sent.trace.length > 100 && answer.sent.trace.length <= 2000);
  });

  it('gives no time when HERE matched another road, and throws when it does not answer', async () => {
    assert.equal(await hereTravel(points, { fetchImpl: answer([section(ROUTE.total * 1.3, 900)]) }), null);
    await assert.rejects(hereTravel(points, { fetchImpl: answer([], 503) }), /HERE import 503/);
  });
});

describe('the drivers\' speeds', () => {
  it('covers the route where fresh samples lie on it the same way, and finds the slow stretches', () => {
    const now = Date.now();
    const sample = (lat, speedKmh, course = 0) => ({ lat, lon: 2, course, speedKmh, limitKmh: 90, t: now - 1000 });
    // Two samples per 500 m over the first 2 km, crawling at 20 km/h under a 90 limit.
    const samples = [];
    for (let m = 100; m < 2000; m += 250) samples.push(sample(48 + m / M_PER_DEG, 20));
    assert.equal(speedStore.add('test-trip-1', samples.slice(0, 20), now), 8);
    // The other way: not this route's.
    speedStore.add('test-trip-2', [sample(48.05, 5, 180)], now);
    const { coverage, sections } = speedStore.along(ROUTE, ROUTE.total, now);
    assert.ok(coverage > 0.15 && coverage < 0.25);
    assert.equal(sections.length, 4);
    assert.ok(sections.every((s) => s.source === 'crowd' && s.kind === 'speed' && s.delayS > 0));
  });
});
