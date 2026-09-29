import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { measure } from '../src/routing/geometry.js';
import { datagouvAlong, parseEvents, parseSpeeds, parseStations, withDatagouv } from '../src/traffic/datagouv.js';

// A road due north along 2°E, from 48.00° to 48.10° (about 11.1 km).
const ROUTE = measure(Array.from({ length: 11 }, (_, i) => [48 + i * 0.01, 2]));
const M_PER_DEG = ROUTE.total / 0.1;

describe('data.gouv feeds', () => {
  it('reads the stations, rows one cell short included', () => {
    const csv = [
      'code_pme;source;source_2;code_insee_commune;axe;pr_debut;abscisse_debut;pr_fin;abscisse_fin;sens_gestionnaire;sens_cardinal;sens_migratoire;sens_giratoire;longueur;nb_voies;x_deb;y_deb;x_fin;y_fin;code_traficolor',
      'MUM76.h1;DIRNO;237676517;A28;76PR91D;800;76PR94D;840;1;NORD_SUD;Y;;3035;0;569981.6;6938140.0;568217.6;6935783.5;RO76',
      'A0001011200;DIRN;;;;0;;;;;;;0;0;;;;;',
    ].join('\n');
    assert.deepEqual(parseStations(csv), [
      { id: 'MUM76.h1', axe: 'A28', lengthM: 3035, x1: 569981.6, y1: 6938140, x2: 568217.6, y2: 6935783.5 },
    ]);
  });

  it('reads the speeds', () => {
    const xml = `<siteMeasurements><measurementSiteReference targetClass="MeasurementSiteRecord" id="S1" version="1.0"/>
      <measurementTimeDefault>2026-09-29T14:42:00.000+02:00</measurementTimeDefault>
      <basicData xsi:type="TrafficFlow"><vehicleFlowRate>610</vehicleFlowRate></basicData>
      <basicData xsi:type="TrafficSpeed"><averageVehicleSpeed><speed>31.0</speed></averageVehicleSpeed></basicData></siteMeasurements>`;
    assert.deepEqual([...parseSpeeds(xml)], [['S1', { speedKmh: 31, at: Date.parse('2026-09-29T12:42:00Z') }]]);
  });

  it('keeps the events in force that slow a driver', () => {
    const record = (type, body) => `<ns2:situationRecord xsi:type="ns2:${type}" id="${type}-1" version="1">${body}</ns2:situationRecord>`;
    const linear = `<ns2:groupOfLocations xsi:type="ns2:Linear"><ns2:tpegLinearLocation><ns2:tpegDirection>northBound</ns2:tpegDirection>
      <ns2:to xsi:type="ns2:TpegNonJunctionPoint"><ns2:pointCoordinates><ns2:latitude>48.05</ns2:latitude><ns2:longitude>2</ns2:longitude></ns2:pointCoordinates></ns2:to>
      <ns2:from xsi:type="ns2:TpegNonJunctionPoint"><ns2:pointCoordinates><ns2:latitude>48.04</ns2:latitude><ns2:longitude>2</ns2:longitude></ns2:pointCoordinates></ns2:from></ns2:tpegLinearLocation></ns2:groupOfLocations>`;
    const xml = [
      record('RoadOrCarriagewayOrLaneManagement', `<ns2:roadOrCarriagewayOrLaneManagementType>roadClosed</ns2:roadOrCarriagewayOrLaneManagementType>${linear}`),
      record('MaintenanceWorks', `<ns2:overallEndTime>2026-01-01T00:00:00Z</ns2:overallEndTime>${linear}`),
      record('Accident', `<ns2:validityStatus>suspended</ns2:validityStatus>${linear}`),
      record('ReroutingManagement', linear),
    ].join('');
    const events = parseEvents(xml, Date.parse('2026-09-29T12:00:00Z'));
    assert.deepEqual(events, [{
      id: 'RoadOrCarriagewayOrLaneManagement-1', kind: 'closed', level: 'closed', from: [48.04, 2], to: [48.05, 2], direction: 'northBound', endsAt: null,
    }]);
  });
});

describe('data.gouv on a route', () => {
  const now = Date.parse('2026-09-29T12:00:00Z');
  const store = {
    // 2 km at 30 km/h on a 110 road: (2000 / 8.33) - (2000 / 27.5) = 167 s.
    stations: [
      { id: 'slow', from: [48.02, 2], to: [48.02 + 2000 / M_PER_DEG, 2], lengthM: 2000, limitKmh: 110 },
      { id: 'flowing', from: [48.07, 2], to: [48.08, 2], lengthM: 1100, limitKmh: 110 },
      { id: 'other way', from: [48.09, 2], to: [48.085, 2], lengthM: 550, limitKmh: 110 },
    ],
    speeds: new Map([
      ['slow', { speedKmh: 30, at: now }],
      ['flowing', { speedKmh: 100, at: now }],
      ['other way', { speedKmh: 10, at: now }],
    ]),
    events: [
      { kind: 'closed', level: 'closed', from: [48.04, 2], to: [48.05, 2], direction: 'northBound', endsAt: null },
      { kind: 'works', level: 'slow', from: [48.06, 2], to: null, direction: 'southBound', endsAt: null },
      { kind: 'incident', level: 'slow', from: [48.065, 2], to: null, direction: 'bothWays', endsAt: null },
    ],
  };

  it('places the slow stations and the events the route meets, the way it goes', () => {
    const sections = datagouvAlong(ROUTE, store, now);
    assert.deepEqual(sections.map((s) => [s.kind, s.level, s.delayS]), [
      ['speed', 'heavy', 167],
      ['closed', 'closed', 0],
      ['incident', 'slow', 0],
    ]);
    assert.ok(sections.every((s) => s.source === 'datagouv' && s.toM > s.fromM));
  });

  it('adds only its extra time to the other sources, and always a closure', () => {
    const known = [{ fromM: 0, toM: 1000, delayS: 100, source: 'tomtom' }];
    const merged = withDatagouv(known, [
      { fromM: 0, toM: 1000, delayS: 160, level: 'jam', source: 'datagouv' },
      { fromM: 2000, toM: 2100, delayS: 0, level: 'closed', source: 'datagouv' },
    ]);
    assert.deepEqual(merged.map((s) => [s.source, s.delayS]), [['tomtom', 100], ['datagouv', 60], ['datagouv', 0]]);
  });
});
