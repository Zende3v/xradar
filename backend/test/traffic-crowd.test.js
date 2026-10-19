import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { crowdExtraS, withCrowd } from '../src/traffic/crowd.js';
import { withDatagouv } from '../src/traffic/datagouv.js';

const total = (sections) => sections.reduce((sum, s) => sum + (s.delayS ?? 0), 0);

describe('withCrowd', () => {
  const here = { fromM: 0, toM: 500, delayS: 100, level: 'jam', source: 'here' };
  const report = { fromM: 0, toM: 500, delayS: 180, level: 'jam', speedKmh: null, source: 'crowd' };
  const speeds = { fromM: 0, toM: 500, delayS: 140, level: 'jam', speedKmh: 14, source: 'crowd', kind: 'speed' };

  it('signalement et vitesses EONA au même endroit : un seul complément, jamais leur somme', () => {
    const merged = withCrowd([here], [report, speeds]);
    assert.equal(total(merged), 180);
    assert.equal(crowdExtraS(merged), 80);
    assert.equal(merged[0], here);
    // Ancien calcul : 80 + 40 = 120 s ajoutées, 220 s au total.
    assert.notEqual(total(merged), 220);
  });

  it('sans HERE : le pire des deux, l\'ordre ne compte pas', () => {
    assert.equal(crowdExtraS(withCrowd([], [speeds, report])), 180);
    assert.deepEqual(withCrowd([], [speeds, report]), withCrowd([], [report, speeds, report]));
  });

  it('data.gouv ensuite : ne repaie pas ce que HERE et EONA ont déjà compté', () => {
    const merged = withCrowd([here], [report]);
    const datagouv = { fromM: 0, toM: 1000, delayS: 600, level: 'jam', source: 'datagouv', kind: 'speed' };
    const out = withDatagouv(merged, [datagouv]);
    // 0–500 : 0,6 s/m contre 0,36 déjà comptés → 120 s ; 500–1000 : 300 s.
    assert.equal(total(out) - total(merged), 420);
    assert.deepEqual(out.filter((s) => s.source === 'datagouv').map((s) => [s.fromM, s.toM, s.delayS]), [[0, 500, 120], [500, 1000, 300]]);
    // Source coupée : ETA identique sans elle.
    assert.deepEqual(withDatagouv(merged, []), merged);
  });
});
