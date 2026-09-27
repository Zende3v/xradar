import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { signsAlong } from '../src/signs/postgis.js';

// Which signs a route meets, from the rows ROUTE_SIGNS_SQL returns (no database): off_m is the
// distance to the route's line, at_m the distance along it, route_course its course there.
const LENGTH_M = 400;
const row = (id, kind, atM, offM, course = null, routeCourse = 0) => ({
  id, kind, value: null, course, lat: 48.76, lon: 2.4,
  frac: atM / LENGTH_M, at_m: atM, off_m: offM, route_course: routeCourse,
});
const met = (rows) => signsAlong(rows).map((entry) => entry.sign.id);

describe('signs along a route', () => {
  it('keeps the approach of a junction, not the lights of its cross streets', () => {
    // Choisy-le-Roi, Lattre-de-Tassigny / Général-Leclerc, met from avenue du Maréchal-de-Lattre
    // northwards then out by avenue du 25-Août-1944.
    const rows = [
      row('lattre-approach', 'traffic_signals', 100, 0, 348, 348),
      row('lattre-crossing', 'traffic_signals', 112, 0),
      row('cross-street-light', 'traffic_signals', 130, 9.2),
      row('cross-street-approach', 'traffic_signals', 138, 11.8, 246, 11),
      row('corner-approach', 'traffic_signals', 140, 10, 30, 20),
      row('opposite-way', 'traffic_signals', 145, 0, 168, 348),
      row('next-light', 'traffic_signals', 151, 0, 324, 324),
    ];
    assert.deepEqual(met(rows), ['lattre-approach', 'next-light']);
  });

  it('keeps a junction light mapped on its central node, once', () => {
    const rows = [
      row('central', 'traffic_signals', 50, 0),
      row('same-junction', 'traffic_signals', 70, 0),
      row('cross-street-approach', 'traffic_signals', 50, 8),
      row('next-junction', 'traffic_signals', 80, 0),
    ];
    assert.deepEqual(met(rows), ['central', 'next-junction']);
  });

  it('leaves the other signs as they were', () => {
    const rows = [
      row('stop-beside', 'stop', 10, 10, 30, 20),
      row('stop-next', 'stop', 15, 0, 0, 0),
      row('stop-other-way', 'stop', 20, 0, 180, 0),
      row('crossing-on-route', 'crossing', 30, 5),
      row('crossing-off-route', 'crossing', 35, 7),
      row('speed', 'speed_sign', 40, 0, 0, 0),
    ];
    assert.deepEqual(met(rows), ['stop-beside', 'stop-next', 'crossing-on-route']);
  });
});
