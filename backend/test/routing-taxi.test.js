import assert from 'node:assert/strict';
import { once } from 'node:events';
import { after, before, it } from 'node:test';
import express from 'express';
import { createRoutingEngine } from '../src/routing/engine.js';
import { createValhallaClient } from '../src/routing/providers/valhalla.js';
import { createRouteRouter } from '../src/routing/routes.js';
import { ETOILE, PARIS, call, fakeOrs, fakeValhalla, line, trapNetwork, valhallaAnswer } from './helpers/routing.js';

trapNetwork(before, after);

it('Taxi natif : accès cartographiés, restrictions conservées, évitements stricts, départ maintenant', async () => {
  let sent;
  const client = createValhallaClient({
    baseUrl: 'http://127.0.0.1:8002', timeoutMs: 500, searchCutoffM: 1000, maxSnapM: 350,
    fetchImpl: async (_, init) => {
      sent = JSON.parse(init.body);
      return { status: 200, text: async () => JSON.stringify(valhallaAnswer(line(PARIS, ETOILE))) };
    },
  });
  await client.routes(PARIS, ETOILE, { vehicle: 'taxi', avoid: ['tolls', 'highways', 'ferries'], shortest: true });
  assert.equal(sent.costing, 'taxi');
  assert.deepEqual(sent.costing_options.taxi, {
    top_speed: 130, exclude_tolls: true, exclude_highways: true, exclude_ferries: true, shortest: true,
  });
  assert.deepEqual(sent.date_time, { type: 0 });
  assert.equal('ignore_access' in sent.costing_options.taxi, false);
  assert.equal('ignore_restrictions' in sent.costing_options.taxi, false);
});

it('Cache et détour Taxi gardent leur profil, séparé de voiture', async () => {
  const valhalla = fakeValhalla();
  const routing = createRoutingEngine({ ors: fakeOrs(), valhalla, mode: () => 'all' });
  const cache = new Map();
  const fasterCalls = [];
  const app = express();
  app.use(express.json());
  app.use('/api/route', createRouteRouter({
    routing, auth: () => ({ id: 'plus', role: 'client' }),
    accounts: { hasPlus: () => true, accessFor: () => ({ canNavigate: true }), tripCheck: () => ({ allowed: true, isNew: false }) },
    shadow: { afterRoute() {}, afterFaster() {} },
    guard: {
      cachedRoute: (_, __, ___, partition) => cache.get(partition),
      keepRoute: (_, __, ___, route, { partition, served }) => cache.set(partition, { route, served }),
      spendRoute: () => ({ ok: true }),
    },
    log() {}, liveReady: () => true, accountAllows: () => true,
    faster: async (_, options) => {
      fasterCalls.push(options);
      await options.draw({ from: PARIS, to: ETOILE, vehicle: options.vehicle });
      return { answer: { better: null }, compare: null };
    },
  }));
  const server = app.listen(0, '127.0.0.1');
  await once(server, 'listening');
  try {
    const path = `/api/route?from=${PARIS.lat},${PARIS.lon}&to=${ETOILE.lat},${ETOILE.lon}`;
    assert.equal((await call(server, 'GET', path + '&vehicle=taxi')).status, 200);
    assert.equal((await call(server, 'GET', path)).status, 200);
    assert.equal(valhalla.calls.length, 2);
    assert.equal(valhalla.calls[0].options.vehicle, 'taxi');
    assert.equal(valhalla.calls[1].options.vehicle, undefined);
    assert.equal((await call(server, 'POST', '/api/route/faster', { body: { vehicle: 'taxi', coordinates: line(PARIS, ETOILE) } })).status, 200);
    assert.equal(fasterCalls[0].vehicle, 'taxi');
    assert.equal(valhalla.calls[2].options.vehicle, 'taxi');
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});
