import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHereBudget, hereParisWeek } from '../src/traffic/budget.js';

function fixture(t, { at = '2026-10-05T10:00:00Z', overrides = {}, saved } = {}) {
  const directory = mkdtempSync(join(tmpdir(), 'eona-here-history-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const settings = {
    hereUsageFile: join(directory, 'usage.json'), hereApiKey: 'offline-test', hereDailyCap: null,
    hereMonthlyBudgetEUR: 100_000, hereTrafficEURPer1000: 1, hereImportEURPer1000: 2,
    herePriceMargin: 1.2, hereDeepCoverage: false, hereAccountGapS: 60, rerouteCheckGapS: 60,
    hereAccountDailyMax: 2, hereFasterAccountDailyMax: 1, ...overrides,
  };
  if (saved) writeFileSync(settings.hereUsageFile, JSON.stringify(saved));
  let time = Date.parse(at);
  return {
    settings, make: () => createHereBudget({ settings, now: () => time }),
    setTime(value) { time = Date.parse(value); },
    saved: () => JSON.parse(readFileSync(settings.hereUsageFile, 'utf8')),
  };
}

test('Historique HERE : dernier mois ancien conservé, aucun faux jour ou semaine', t => {
  const f = fixture(t, { saved: {
    day: '2026-09-30', month: '2026-09', monthUsed: 9, used: 3,
    byKind: { flow: 2, incidents: 1 }, byUse: { eta: 3 },
  } });
  const usage = f.make().usage();
  assert.equal(usage.month, '2026-10');
  assert.equal(usage.monthUsed, 0);
  assert.deepEqual(usage.history, {
    since: '2026-10-05T10:00:00.000Z', baselineMonth: '2026-09', totalRequests: 9,
    totalEstimatedEUR: 0.018, weekStart: '2026-10-05', weekRequests: 0, weekEstimatedEUR: 0,
    weekComplete: false, earlierHistoryComplete: false,
  });
  assert.deepEqual(f.saved().history.totalByKind, { flow: 2, incidents: 1, import: 6 });
  assert.deepEqual(f.saved().history.daily, {});
  assert.deepEqual(f.make().usage().history, usage.history);
});

test('Historique HERE : types mensuels prioritaires, solde ancien classé import', t => {
  const f = fixture(t, { saved: {
    day: '2026-10-05', month: '2026-10', monthUsed: 20, used: 2,
    monthByKind: { flow: 8, incidents: 3, import: 2 }, byKind: { flow: 1, incidents: 1 },
  } });
  assert.equal(f.make().usage().history.totalRequests, 20);
  assert.deepEqual(f.saved().history.totalByKind, { flow: 8, incidents: 3, import: 9 });
  assert.equal(f.make().usage().history.totalRequests, 20);
});

test('Historique HERE : réservations Paris, budget UTC et cumul après mois/redémarrage', t => {
  const f = fixture(t, { at: '2026-09-30T21:59:00Z' });
  const budget = f.make();
  assert.equal(budget.reserve('eta', 'flow'), true);
  f.setTime('2026-09-30T22:01:00Z');
  assert.equal(budget.reserve('faster', 'import'), true);
  assert.equal(budget.usage().month, '2026-09');
  assert.equal(budget.usage().used, 2);
  assert.deepEqual(f.saved().history.daily, {
    '2026-09-30': { flow: 1, incidents: 0, import: 0 },
    '2026-10-01': { flow: 0, incidents: 0, import: 1 },
  });
  f.setTime('2026-10-01T00:01:00Z');
  assert.equal(budget.reserve('eta', 'incidents'), true);
  assert.equal(budget.usage().monthUsed, 1);
  assert.equal(budget.usage().history.totalRequests, 3);
  assert.equal(budget.usage().history.weekRequests, 3);
  assert.equal(budget.usage().history.totalEstimatedEUR, 0.0048);
  assert.deepEqual(f.make().usage().history, budget.usage().history);
});

test('Historique HERE : semaine lundi Paris, sans remettre plafond UTC à zéro', t => {
  const f = fixture(t, { at: '2026-10-02T12:00:00Z' });
  const budget = f.make();
  budget.reserve('eta', 'flow');
  f.setTime('2026-10-04T21:59:00Z');
  budget.reserve('eta', 'incidents');
  assert.equal(budget.usage().history.weekStart, '2026-09-28');
  assert.equal(budget.usage().history.weekRequests, 2);
  f.setTime('2026-10-04T22:00:00Z');
  assert.equal(budget.usage().history.weekRequests, 0);
  assert.equal(budget.usage().history.weekComplete, true);
  budget.reserve('faster', 'import');
  assert.equal(budget.usage().history.weekStart, '2026-10-05');
  assert.equal(budget.usage().history.weekRequests, 1);
  assert.equal(budget.usage().history.totalRequests, 3);
  assert.equal(budget.usage().used, 2);
});

test('Historique HERE : début exact lundi requis pour semaine complète', t => {
  const partial = fixture(t, { at: '2026-10-04T22:00:01Z' });
  assert.equal(partial.make().usage().history.weekComplete, false);
  const exact = fixture(t, { at: '2026-10-04T22:00:00Z' });
  const budget = exact.make();
  assert.equal(budget.usage().history.weekComplete, true);
  exact.setTime('2026-10-05T12:00:00Z');
  assert.equal(exact.make().usage().history.weekComplete, true);
});

test('Historique HERE : passage été Paris, semaine 167 heures et jour sans trou', t => {
  const before = hereParisWeek(Date.parse('2026-03-29T00:30:00Z'));
  const after = hereParisWeek(Date.parse('2026-03-29T22:00:00Z'));
  assert.deepEqual(before, { startDay: '2026-03-23', startAt: Date.parse('2026-03-22T23:00:00Z') });
  assert.deepEqual(after, { startDay: '2026-03-30', startAt: Date.parse('2026-03-29T22:00:00Z') });
  assert.equal((after.startAt - before.startAt) / 3_600_000, 167);
  const f = fixture(t, { at: '2026-03-29T00:30:00Z' });
  const budget = f.make();
  budget.reserve('eta', 'flow');
  f.setTime('2026-03-29T01:30:00Z');
  budget.reserve('eta', 'flow');
  assert.equal(f.saved().history.daily['2026-03-29'].flow, 2);
  f.setTime('2026-03-29T22:00:00Z');
  assert.equal(budget.usage().history.weekRequests, 0);
});

test('Historique HERE : passage hiver Paris, semaine 169 heures et heure répétée unique', t => {
  const before = hereParisWeek(Date.parse('2026-10-25T00:30:00Z'));
  const after = hereParisWeek(Date.parse('2026-10-25T23:00:00Z'));
  assert.deepEqual(before, { startDay: '2026-10-19', startAt: Date.parse('2026-10-18T22:00:00Z') });
  assert.deepEqual(after, { startDay: '2026-10-26', startAt: Date.parse('2026-10-25T23:00:00Z') });
  assert.equal((after.startAt - before.startAt) / 3_600_000, 169);
  const f = fixture(t, { at: '2026-10-25T00:30:00Z' });
  const budget = f.make();
  budget.reserve('eta', 'flow');
  f.setTime('2026-10-25T01:30:00Z');
  budget.reserve('eta', 'flow');
  assert.equal(f.saved().history.daily['2026-10-25'].flow, 2);
  f.setTime('2026-10-25T23:00:00Z');
  assert.equal(budget.usage().history.weekRequests, 0);
});

test('Historique HERE : coût recalcule taux, marge et couverture configurés actuellement', t => {
  const f = fixture(t);
  const budget = f.make();
  budget.reserve('eta', 'flow');
  budget.reserve('eta', 'incidents');
  budget.reserve('faster', 'import');
  assert.equal(budget.usage().history.totalEstimatedEUR, 0.0048);
  f.settings.hereTrafficEURPer1000 = 3;
  f.settings.hereImportEURPer1000 = 4;
  f.settings.herePriceMargin = 1.5;
  f.settings.hereDeepCoverage = true;
  assert.equal(budget.usage().history.totalEstimatedEUR, 0.024);
  assert.equal(budget.usage().history.weekEstimatedEUR, 0.024);
  f.settings.hereTrafficEURPer1000 = NaN;
  assert.equal(budget.usage().history.totalEstimatedEUR, null);
  assert.equal(budget.usage().history.weekEstimatedEUR, null);
  assert.equal(budget.reserve('eta', 'flow'), false);
});

test('Historique HERE : fichier absent de configuration donne coût inconnu et ferme appels', t => {
  const f = fixture(t, { overrides: { hereUsageFile: null } });
  const budget = f.make();
  assert.equal(budget.usage().history.totalEstimatedEUR, null);
  assert.equal(budget.usage().history.weekEstimatedEUR, null);
  assert.equal(budget.usage().history.weekComplete, false);
  assert.equal(budget.reserve('eta', 'flow'), false);
  assert.equal(budget.usage().blocked, 'storage unavailable');
});

test('Historique HERE : échec écriture atomique refuse appel et garde cumul précédent', t => {
  const f = fixture(t);
  const budget = f.make();
  assert.equal(budget.reserve('eta', 'flow'), true);
  mkdirSync(f.settings.hereUsageFile + '.tmp');
  assert.equal(budget.reserve('eta', 'flow'), false);
  assert.equal(budget.usage().history.totalRequests, 1);
  assert.equal(budget.usage().history.weekRequests, 1);
  assert.equal(budget.usage().blocked, 'storage unavailable');
  assert.equal(f.saved().history.totalRequests, 1);
  assert.equal(f.make().reserve('eta', 'flow'), false);
  assert.equal(f.saved().history.totalRequests, 1);
});

test('Historique HERE : rétention quotidienne bornée, cumul conservé après deux ans', t => {
  const f = fixture(t, { at: '2024-01-01T12:00:00Z' });
  const budget = f.make();
  budget.reserve('eta', 'flow');
  f.setTime('2026-10-05T12:00:00Z');
  budget.reserve('faster', 'import');
  assert.deepEqual(Object.keys(f.saved().history.daily), ['2026-10-05']);
  assert.equal(budget.usage().history.totalRequests, 2);
  assert.equal(budget.usage().history.weekRequests, 1);
  assert.equal(f.make().usage().history.totalRequests, 2);
});

test('Historique HERE : données corrompues refusent nouvelles réservations', t => {
  const f = fixture(t);
  f.make().reserve('eta', 'flow');
  const saved = f.saved();
  saved.history.totalRequests = 0;
  writeFileSync(f.settings.hereUsageFile, JSON.stringify(saved));
  const budget = f.make();
  assert.equal(budget.reserve('eta', 'flow'), false);
  assert.equal(budget.usage().history.totalEstimatedEUR, null);
  assert.equal(budget.usage().blocked, 'storage unavailable');
});
