import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { measure } from '../src/routing/geometry.js';
import { corridorOf, flexiblePolyline, placeOnRoute } from '../src/traffic/here.js';
import { speedStore } from '../src/traffic/speeds.js';

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
