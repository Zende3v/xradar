import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { fuseDelays } from '../src/traffic/delay-fusion.js';

const here = (fromM, toM, delayS) => ({ fromM, toM, delayS, level: 'jam', source: 'here' });
const zone = (fromM, toM, delayS, extra = {}) => ({ fromM, toM, delayS, level: 'jam', source: 'crowd', ...extra });
const total = (sections) => sections.reduce((sum, s) => sum + (s.delayS ?? 0), 0);
const added = (sections, known) => sections.filter((s) => !known.includes(s));
const shuffle = (list, seed) => {
  const out = [...list];
  let x = seed;
  for (let i = out.length - 1; i > 0; i--) {
    x = (x * 1103515245 + 12345) % 2147483648;
    const j = x % (i + 1);
    [out[i], out[j]] = [out[j], out[i]];
  }
  return out;
};
const deepFreeze = (list) => Object.freeze(list.map((s) => Object.freeze({ ...s })));

/** Référence brute O(n²) : complément exact, sans arrondi. */
function exactExtra(known, zones) {
  const valid = (s) => Number.isFinite(s.fromM) && Number.isFinite(s.toM) && s.toM > s.fromM
    && typeof s.delayS === 'number' && Number.isFinite(s.delayS) && s.delayS > 0;
  const k = known.filter(valid);
  const z = zones.filter(valid);
  const bounds = [...new Set([...k, ...z].flatMap((s) => [s.fromM, s.toM]))].sort((a, b) => a - b);
  let sum = 0;
  for (let i = 0; i < bounds.length - 1; i++) {
    const [a, b] = [bounds[i], bounds[i + 1]];
    const on = (s) => s.fromM <= a && s.toM >= b;
    const best = Math.max(0, ...z.filter(on).map((s) => s.delayS / (s.toM - s.fromM)));
    const done = k.filter(on).reduce((acc, s) => acc + s.delayS / (s.toM - s.fromM), 0);
    sum += Math.max(0, best - done) * (b - a);
  }
  return sum;
}

describe('fusion des retards superposés', () => {
  it('cas 1 : HERE 100 s, signalement 180 s et vitesses 140 s au même endroit : 180 s, complément 80 s', () => {
    const known = [here(0, 500, 100)];
    const out = fuseDelays(known, [zone(0, 500, 180), zone(0, 500, 140)]);
    assert.equal(total(out), 180);
    assert.deepEqual(added(out, known).map((s) => [s.fromM, s.toM, s.delayS]), [[0, 500, 80]]);
    assert.equal(out[0], known[0], 'HERE intact');
  });

  it('cas 2 : sans HERE, 180 s ; doublons et ordre inversé ne changent rien', () => {
    const a = zone(0, 500, 180);
    const b = zone(0, 500, 140);
    const ref = fuseDelays([], [a, b]);
    assert.equal(total(ref), 180);
    assert.deepEqual(fuseDelays([], [b, a]), ref);
    assert.deepEqual(fuseDelays([], [b, a, b, a, a]), ref);
  });

  it('cas 3 : zones disjointes additionnées : 320 s', () => {
    assert.equal(total(fuseDelays([], [zone(0, 500, 180), zone(500, 1000, 140)])), 320);
  });

  it('cas 4 : chevauchement partiel, jamais maximum global : 150 s', () => {
    const out = fuseDelays([], [zone(0, 1000, 100), zone(500, 1500, 100)]);
    assert.equal(total(out), 150);
    assert.equal(Math.min(...out.map((s) => s.fromM)), 0);
    assert.equal(Math.max(...out.map((s) => s.toM)), 1500);
  });

  it('cas 5 : complément 50 s seulement sur 500–1000', () => {
    const known = [here(0, 500, 100)];
    const out = fuseDelays(known, [zone(0, 1000, 100)]);
    assert.equal(total(out), 150);
    assert.deepEqual(added(out, known).map((s) => [s.fromM, s.toM, s.delayS]), [[500, 1000, 50]]);
  });

  it('cas 6 : HERE plus lent partout : aucun complément', () => {
    const known = [here(0, 1000, 200)];
    assert.deepEqual(fuseDelays(known, [zone(0, 500, 100)]), known);
  });

  it('cas 7 : fragmentation artificielle ne change pas le total arrondi (petits retards)', () => {
    const whole = fuseDelays([], [zone(0, 1000, 7.3)]);
    assert.equal(total(whole), 7);
    // Même zone en dix morceaux de 0,73 s : l'arrondi par morceau donnerait 10.
    const pieces = Array.from({ length: 10 }, (_, i) => zone(i * 100, (i + 1) * 100, 0.73, { id: i }));
    assert.equal(total(fuseDelays([], pieces)), 7);
    // Zones dominées qui découpent les bornes : même total, une seule pièce.
    const dominated = [zone(0, 1000, 7.3), zone(100, 200, 0.2), zone(305, 311, 0.01), zone(650, 990, 0.4)];
    const out = fuseDelays([], dominated);
    assert.equal(total(out), 7);
    assert.equal(out.length, 1);
    // HERE fragmenté sous la zone : complément exact 3,3 s → 3 s.
    const known = Array.from({ length: 10 }, (_, i) => here(i * 100, (i + 1) * 100, 0.4));
    assert.equal(total(added(fuseDelays(known, [zone(0, 1000, 7.3)]), known)), 3);
  });

  it('cas 8 : fermeture publique à 0 s conservée ; source vide sans effet', () => {
    const known = [here(0, 1000, 100)];
    const closed = { fromM: 2000, toM: 2100, delayS: 0, level: 'closed', source: 'datagouv' };
    const free = { fromM: 3000, toM: 3200, delayS: 0, level: 'slow', source: 'datagouv' };
    const out = fuseDelays(known, [closed, free], { keepFree: true });
    assert.deepEqual(out.map((s) => [s.source, s.level, s.delayS]), [['here', 'jam', 100], ['datagouv', 'closed', 0], ['datagouv', 'slow', 0]]);
    // Sans keepFree : fermeture gardée, marqueur sans retard non.
    assert.deepEqual(fuseDelays(known, [closed, free]).map((s) => s.level), ['jam', 'closed']);
    // Fermeture dominée par HERE : jamais supprimée.
    assert.ok(fuseDelays([here(0, 3000, 900)], [{ ...closed, delayS: 10 }]).some((s) => s.level === 'closed'));
    assert.deepEqual(fuseDelays(known, []), known);
  });

  it('cas 9 : entrées invalides : aucun plantage, aucun temps, aucune valeur non finie', () => {
    const bad = [
      zone(0, 0, 100), zone(500, 400, 100), zone(NaN, 100, 50), zone(0, Infinity, 50), zone(-Infinity, 10, 50),
      zone(0, 100, -50), zone(0, 100, NaN), zone(0, 100, Infinity), zone(0, 100, undefined), zone(0, 100, '100'), zone(0, 100, null),
    ];
    const known = [here(0, 0, 40), here(0, 100, NaN)];
    const out = fuseDelays(known, bad);
    assert.deepEqual(added(out, known), []);
    assert.ok(out.every((s) => !Number.isFinite(s.delayS) || s.delayS >= 0));
    // keepFree : seuls retards absent et null deviennent marqueur 0 s (doublon fusionné) ; négatif, NaN, infini, texte non.
    const kept = added(fuseDelays(known, bad, { keepFree: true }), known);
    assert.deepEqual(kept.map((s) => [s.fromM, s.toM, s.delayS]), [[0, 100, 0]]);
    // Un HERE invalide ne compte pas comme déjà payé.
    const invalid = [here(0, 100, NaN), here(50, 50, 30)];
    assert.deepEqual(added(fuseDelays(invalid, [zone(0, 100, 20)]), invalid).map((s) => s.delayS), [20]);
  });

  it('ne garde aucun complément sur une portion déjà couverte', () => {
    const known = [here(200, 300, 50)];
    const out = added(fuseDelays(known, [zone(0, 1000, 100)]), known);
    assert.deepEqual(out.map((s) => [s.fromM, s.toM, s.delayS]), [[0, 200, 20], [300, 1000, 70]]);
  });

  it('garde les champs de l\'observation dominante, égalités départagées sans dépendre de l\'ordre', () => {
    const report = zone(0, 500, 180, { kind: 'report', level: 'heavy' });
    const speed = zone(0, 500, 140, { kind: 'speed', speedKmh: 12 });
    const [piece] = fuseDelays([], [speed, report]);
    assert.equal(piece.kind, 'report');
    assert.equal(piece.level, 'heavy');
    const a = zone(0, 500, 100, { kind: 'speed', speedKmh: 20 });
    const b = zone(0, 500, 100, { kind: 'speed', speedKmh: 25 });
    assert.deepEqual(fuseDelays([], [a, b]), fuseDelays([], [b, a]));
  });

  it('ne modifie pas les entrées', () => {
    const known = deepFreeze([here(0, 500, 100)]);
    const zones = deepFreeze([zone(0, 1000, 300), zone(200, 400, 200)]);
    const copy = structuredClone([known, zones]);
    fuseDelays(known, zones, { keepFree: true });
    assert.deepEqual([known, zones], copy);
  });

  it('suit la référence exacte, quel que soit l\'ordre (tirages pseudo-aléatoires)', () => {
    let x = 42;
    const rand = (n) => { x = (x * 1103515245 + 12345) % 2147483648; return x % n; };
    for (let run = 0; run < 200; run++) {
      const make = (count, source) => Array.from({ length: count }, () => {
        const from = rand(3000);
        return { fromM: from, toM: from + 1 + rand(1500), delayS: rand(400) / (1 + rand(4)), level: 'jam', source };
      });
      const known = make(rand(5), 'here');
      const zones = make(1 + rand(8), 'crowd');
      const out = fuseDelays(known, zones);
      const extra = added(out, known);
      assert.ok(Math.abs(total(extra) - Math.round(exactExtra(known, zones))) <= 1, `tirage ${run}`);
      assert.ok(extra.every((s) => Number.isInteger(s.delayS) && s.delayS > 0 && s.toM > s.fromM));
      assert.deepEqual(added(fuseDelays(known, shuffle(zones, run + 1)), known), extra);
    }
  });

  describe('fixtures communes backend / Swift (revue 20/10)', () => {
    const closed = (fromM, toM, delayS) => ({ fromM, toM, delayS, level: 'closed', source: 'datagouv' });
    const rows = (list) => list.map((s) => [s.fromM, s.toM, s.delayS, s.level]);

    it('fermeture 0–1000/100 sous HERE 0–500/100 : marqueur 0–1000/0 et complément 500–1000/50', () => {
      const known = [here(0, 500, 100)];
      for (const keepFree of [false, true]) {
        const out = fuseDelays(known, [closed(0, 1000, 100)], { keepFree });
        assert.deepEqual(rows(added(out, known)), [[0, 1000, 0, 'closed'], [500, 1000, 50, 'closed']]);
        assert.equal(out[0], known[0]);
      }
    });

    it('fermeture 100–200/0, keepFree=false : conservée', () => {
      assert.deepEqual(rows(fuseDelays([], [closed(100, 200, 0)])), [[100, 200, 0, 'closed']]);
    });

    it('marqueur sans retard nil, keepFree=true : conservé à zéro', () => {
      const nil = { fromM: 0, toM: 100, delayS: null, level: 'slow', source: 'datagouv' };
      const absent = { fromM: 300, toM: 300, level: 'slow', source: 'datagouv' };
      assert.deepEqual(rows(fuseDelays([], [nil, absent], { keepFree: true })), [[0, 100, 0, 'slow'], [300, 300, 0, 'slow']]);
      assert.deepEqual(fuseDelays([], [nil, absent]), []);
      // Retard négatif : aucun marqueur libre, sauf fermeture.
      assert.deepEqual(fuseDelays([], [zone(0, 100, -5)], { keepFree: true }), []);
      assert.deepEqual(rows(fuseDelays([], [closed(0, 100, -5)])), [[0, 100, 0, 'closed']]);
    });

    it('fermeture 500–100/0 : rejetée, jamais retournée 100–500', () => {
      for (const keepFree of [false, true]) {
        assert.deepEqual(fuseDelays([], [closed(500, 100, 0)], { keepFree }), []);
        assert.deepEqual(fuseDelays([], [closed(500, 100, 100)], { keepFree }), []);
      }
    });

    it('densité infinie par largeur sous-normale, ou largeur infinie : aucun temps', () => {
      assert.deepEqual(fuseDelays([], [zone(0, 1e-320, 1)]), []);
      assert.deepEqual(fuseDelays([], [zone(-1e308, 1e308, 100)]), []);
      // Connu invalide de même forme : jamais compté comme déjà payé.
      const known = [here(0, 1e-320, 1), here(-1e308, 1e308, 100)];
      assert.deepEqual(rows(added(fuseDelays(known, [zone(0, 100, 20)]), known)), [[0, 100, 20, 'jam']]);
    });

    it('égalité de densité, même origine, fin différente : zone courte d\'abord, mêmes portions et nature', () => {
      const speed = zone(0, 500, 50, { kind: 'speed' });
      const report = zone(0, 1000, 100, { kind: 'report' });
      const expected = [[0, 500, 50, 'speed'], [500, 1000, 50, 'report']];
      for (const list of [[speed, report], [report, speed]]) {
        assert.deepEqual(fuseDelays([], list).map((s) => [s.fromM, s.toM, s.delayS, s.kind]), expected);
      }
    });

    it('égalité complète : source, niveau puis nature absente d\'abord', () => {
      const pick = (list) => fuseDelays([], list).map((s) => [s.source, s.level, s.kind]);
      const a = zone(0, 500, 100, { source: 'a', level: 'jam' });
      const b = zone(0, 500, 100, { source: 'b', level: 'heavy' });
      assert.deepEqual(pick([b, a]), [['a', 'jam', undefined]]);
      const c = zone(0, 500, 100, { level: 'heavy' });
      assert.deepEqual(pick([zone(0, 500, 100), c]), [['crowd', 'heavy', undefined]]);
      const d = zone(0, 500, 100, { kind: 'speed' });
      assert.deepEqual(pick([d, zone(0, 500, 100)]), [['crowd', 'jam', undefined]]);
    });
  });

  it('reste rapide sur une grande liste', () => {
    const zones = Array.from({ length: 20000 }, (_, i) => zone(i * 10, i * 10 + 500, 50));
    const t0 = Date.now();
    const out = fuseDelays([here(0, 100000, 1000)], zones);
    assert.ok(Date.now() - t0 < 2000);
    // Densité 0,1 s/m sur 0–200 490 m, HERE 0,01 s/m sur 0–100 000 m.
    assert.equal(total(out), Math.round(1000 + 0.09 * 100000 + 0.1 * 100490));
  });
});
