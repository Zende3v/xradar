import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { fromLocal } from '../src/search/local.js';
import { score } from '../src/search/rank.js';

/** A pool that answers like the published index: the fold, then the places near, then the notable ones. */
function fakePool({ folded, near = [], notable = [], hinted = () => [] }) {
  const asked = [];
  const client = {
    async query(text, values) {
      asked.push({ text, values });
      if (text.includes('search.fold')) return { rows: [{ q: folded }] };
      if (text.includes('town_hint')) return { rows: hinted(values[0]) };
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

  it('enseigne + ville : cherchée autour de la ville typée, commune voisine comprise', async () => {
    const { asked, pool } = fakePool({
      folded: 'fitness park orly',
      hinted: (town) => (town === 'orly' ? [row(7, 'Fitness Park', { key: 'leisure', value: 'fitness_centre', city: 'Thiais', postcode: '94320', town_hint: 'Orly' })] : []),
    });
    const places = await fromLocal('Fitness Park Orly', { lat: 48.79, lon: 2.36 }, { pool });
    const hint = asked.find((q) => q.text.includes('town_hint'));
    assert.equal(hint.values[0], 'orly');
    assert.deepEqual(hint.values.slice(-2), ['fitness', 'park']);
    assert.deepEqual(places.map((p) => [p.name, p.city, p.townHint]), [['Fitness Park', 'Thiais', 'Orly']]);
    // Classement : « orly » vaut la ville, comme pour un lieu dans Orly même.
    const { parts } = score({ ...places[0], distanceM: 9000 }, { query: 'Fitness Park Orly', index: 0, total: 1 });
    assert.equal(parts.name, 1);
    assert.equal(parts.distance, 0.8);
  });

  it('ville seule tapée en entier : rien cherché autour', async () => {
    const { pool } = fakePool({
      folded: 'vitry sur seine',
      hinted: () => [row(9, 'Boulangerie', { city: 'Vitry-sur-Seine', town_hint: 'Vitry-sur-Seine' })],
    });
    assert.deepEqual(await fromLocal('Vitry sur Seine', { lat: 48.79, lon: 2.39 }, { pool }), []);
  });
});
