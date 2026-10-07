import assert from 'node:assert/strict';
import { once } from 'node:events';
import { after, before, describe, it } from 'node:test';
import express from 'express';
import { db } from '../src/db.js';
import { fuelStore } from '../src/fuel/store.js';
import { parseStations } from '../src/fuel/feed.js';
import { placeRouter } from '../src/places/routes.js';
import { cashPayment, describe as describePlace } from '../src/places/store.js';

const origin = { lat: 48.7921, lon: 2.3634 };
const tags = {
  name: 'Station test', 'payment:cash': 'yes', 'ref:FR:prix-carburants': '94076001',
  opening_hours: '24/7',
};
const place = { id: 'node/1', kind: 'fuel', ...origin, distanceM: 123, tags, commune: 'Villejuif' };
const saved = { query: db.query, byId: fuelStore.byId, grid: fuelStore.grid, meta: fuelStore.meta };
const queries = [];
let server;

before(async () => {
  const [station] = parseStations(`<pdv id="94076001" latitude="4879210" longitude="236340">
    <prix id="1" nom="Gazole" maj="2026-10-08 01:00:00" valeur="1.599"/>
    <horaires automate-24-24="1"><jour id="1" ferme="0"/></horaires>
    <services><service>Espace bébé</service></services>
  </pdv>`);
  fuelStore.byId = new Map([[station.id, station]]);
  fuelStore.grid = new Map();
  fuelStore.meta = { ready: true, refreshedAt: '2026-10-08T00:00:00Z' };
  db.query = async (sql, values) => {
    queries.push({ sql, values });
    return { rows: [{ id: place.id, tags, commune: place.commune, ...origin, distance_m: place.distanceM }] };
  };
  const app = express();
  app.use('/api/places', placeRouter);
  server = app.listen(0, '127.0.0.1');
  await once(server, 'listening');
});

after(async () => {
  db.query = saved.query;
  fuelStore.byId = saved.byId;
  fuelStore.grid = saved.grid;
  fuelStore.meta = saved.meta;
  server.closeAllConnections();
  await new Promise(resolve => server.close(resolve));
});

const call = suffix => fetch(`http://127.0.0.1:${server.address().port}/api/places/near?lat=${origin.lat}&lon=${origin.lon}&kind=fuel${suffix}`);

describe('Stations : espèces explicites', () => {
  it('accepte yes et only ; refuse no ; toute autre valeur reste inconnue', () => {
    for (const value of ['yes', ' YES ', 'only']) {
      assert.deepEqual(cashPayment({ 'payment:cash': value }), { accepted: true, source: 'osm' });
    }
    assert.deepEqual(cashPayment({ 'payment:cash': 'no' }), { accepted: false, source: 'osm' });
    for (const value of [undefined, '', 'unknown', 'interval=Mo-Sa 08:30-19:30', 'yes;no']) {
      assert.deepEqual(cashPayment({ 'payment:cash': value }), { accepted: null, source: null });
    }
    assert.deepEqual(cashPayment({ brand: 'Total', opening_hours: '24/7' }), { accepted: null, source: null });
  });

  it('conserve horaires de station sans attribuer horaires au paiement espèces', () => {
    const result = describePlace(place, { officialHours: '24/7' });
    assert.equal(result.hours.alwaysOpen, true);
    assert.equal(result.hours.source, 'official');
    assert.deepEqual(result.cashPayment, { accepted: true, source: 'osm' });
    assert.equal(result.cashPayment.hours, undefined);
    assert.equal(describePlace({ ...place, kind: 'parking' }).cashPayment, undefined);
  });

  it('ancien appel conserve limite et prix officiels ; champs espèces restent additifs', async () => {
    const response = await call('');
    assert.equal(response.status, 200);
    const result = await response.json();
    assert.deepEqual(queries.at(-1).values, [origin.lat, origin.lon, 20]);
    assert.deepEqual(result.search, { radiusM: null, cashOnly: false });
    assert.equal(result.places[0].fuel.stationId, '94076001');
    assert.equal(result.places[0].fuel.prices[0].price, 1.599);
    assert.equal(result.places[0].fuel.prices[0].fuel, 'Gazole');
    assert.equal(result.places[0].distanceM, 123);
    assert.deepEqual(result.places[0].cashPayment, { accepted: true, source: 'osm' });
  });

  it('rayon borné et espèces passent ensemble au pool, sans prix perdus', async () => {
    const response = await call('&pool=1&radius=25000&cash=1');
    assert.equal(response.status, 200);
    const result = await response.json();
    assert.deepEqual(queries.at(-1).values, [origin.lat, origin.lon, 60, 25000]);
    assert.deepEqual(result.search, { radiusM: 25000, cashOnly: true });
    assert.equal(result.places[0].fuel.prices[0].price, 1.599);
  });

  it('refuse filtres invalides avant accès base', async () => {
    const count = queries.length;
    for (const suffix of ['&radius=0', '&radius=-1', '&radius=50001', '&radius=no', '&radius=Infinity', '&cash=yes', '&cash=1&kind=parking']) {
      assert.equal((await call(suffix)).status, 400, suffix);
    }
    assert.equal(queries.length, count);
  });
});
