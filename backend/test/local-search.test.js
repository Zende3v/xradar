import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { fromLocal } from '../src/search/local.js';

/** A pool that answers like the published index: the fold, then the places near, then the notable ones. */
function fakePool({ folded, near = [], notable = [] }) {
  const asked = [];
  const client = {
    async query(text, values) {
      asked.push({ text, values });
      if (text.includes('search.fold')) return { rows: [{ q: folded }] };
      if (text.includes('ST_Expand')) return { rows: near };
      if (text.includes('weight >= 0.6')) return { rows: notable };
      return { rows: [] };
    },
    release() {},
  };
  return { asked, pool: { connect: async () => client } };
}

const row = (id, name, extra = {}) => ({
  id, osm_type: 'N', osm_id: id, key: 'shop', value: 'supermarket', name, housenumber: null, street: null,
  postcode: '94310', city: 'Orly', lat: 48.74, lon: 2.4, ...extra,
});

describe('EONA\'s own place index', () => {
  it('asks each word of 3 letters or more, near the driver and all over France, each place once', async () => {
    const { asked, pool } = fakePool({
      folded: 'e leclerc orly',
      near: [row(1, 'E.Leclerc', { housenumber: '8', street: 'Place Gaston Viens' })],
      notable: [row(1, 'E.Leclerc'), row(2, 'Orly', { key: 'place', value: 'town', postcode: null })],
    });
    const places = await fromLocal('E.Leclerc Orly', { lat: 48.79, lon: 2.36 }, { pool });
    const near = asked.find((q) => q.text.includes('ST_Expand'));
    // "e" is too short to ask: two words, two conditions, after the query, the position and the radius.
    assert.deepEqual(near.values.slice(4), ['leclerc', 'orly']);
    assert.equal((near.text.match(/<% poi\.doc/g) ?? []).length, 2);
    // The notable places first, each place once.
    assert.deepEqual(places.map((p) => [p.id, p.name, p.subtitle, p.osmKey]), [
      ['osm:N1', 'E.Leclerc', '94310 Orly', 'shop'],
      ['osm:N2', 'Orly', 'Orly', 'place'],
    ]);

  });

  it('asks nothing for words too short, and only the notable places without a position', async () => {
    const short = fakePool({ folded: 'st' });
    assert.deepEqual(await fromLocal('St', null, { pool: short.pool }), []);
    assert.ok(!short.asked.some((q) => q.text.includes('<%')));

    const far = fakePool({ folded: 'orly', notable: [row(2, 'Orly')] });
    await fromLocal('orly', null, { pool: far.pool });
    assert.ok(!far.asked.some((q) => q.text.includes('ST_Expand')));
    assert.ok(far.asked.some((q) => q.text.includes('weight >= 0.6')));
  });
});
