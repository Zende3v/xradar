import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHereBudget } from '../src/traffic/budget.js';
import { createHereCache } from '../src/traffic/here-cache.js';
import { hereAlong, hereSnapshot, hereTravel } from '../src/traffic/here.js';
import { measure } from '../src/routing/geometry.js';

function fixture(t, overrides = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'eona-budget-'));
  t.after(()=>rmSync(dir, { recursive: true, force: true }));
  const settings = { hereUsageFile: join(dir,'usage.json'), hereApiKey: 'offline-test', hereDailyCap: null,
    hereMonthlyBudgetEUR: 10, hereTrafficEURPer1000: 1000, hereImportEURPer1000: 2000, herePriceMargin: 1,
    hereDeepCoverage: false, hereAccountGapS: 60, rerouteCheckGapS: 60, hereAccountDailyMax: 250,
    hereBillingStart: '2000-01-01',
    hereTrafficFreeMonthly: 0, hereAdvancedTrafficFreeMonthly: 0, hereImportFreeMonthly: 0, ...overrides };
  let at = Date.parse('2026-09-30T22:00:00Z');
  const make = () => createHereBudget({ settings, now: ()=>at });
  return { settings, make, setTime: value=>{at=Date.parse(value);} };
}

test('Budget : réservations concurrentes bornées, redémarrage, jour et mois', async t=>{
  const f=fixture(t), b=f.make();
  const accepted=await Promise.all(Array.from({length:40},()=>Promise.resolve().then(()=>b.reserve('eta', 'flow', 'account-a'))));
  assert.equal(accepted.filter(Boolean).length,10);
  assert.equal(f.make().reserve('faster', 'incidents', 'account-a'),false);
  assert.equal(f.make().usage().estimatedMonthEUR,10);
  f.setTime('2026-10-01T00:00:00Z');
  assert.equal(b.reserve('faster', 'import', 'account-a'),true);
  assert.deepEqual(b.usage().monthByKind,{flow:0,incidents:0,import:1});
  assert.equal(b.usage().byUse.faster,1);
  f.setTime('2026-10-02T00:00:00Z');
  assert.equal(b.usage().used,0);
  assert.equal(b.usage().monthUsed,1);
});

test('Budget : ancien compteur conservé, fichier invalide ferme les appels', t=>{
  const f=fixture(t);
  writeFileSync(f.settings.hereUsageFile,JSON.stringify({day:'2026-09-30',month:'2026-09',monthUsed:7,used:2,byKind:{flow:1,incidents:1},byUse:{eta:2}}));
  const b=f.make();
  assert.deepEqual(b.usage().monthByKind,{flow:1,incidents:1,import:5});
  assert.equal(b.reserve('eta', 'flow', 'account-a'),false);
  writeFileSync(f.settings.hereUsageFile,'broken');
  assert.equal(f.make().reserve('eta', 'flow', 'account-a'),false);
});

test('Budget : plafond quotidien et écarts compte persistants, sans débit du quota', t=>{
  const f=fixture(t,{hereDailyCap:1}), b=f.make();
  assert.equal(b.reserve('eta','flow','account-a'),true);
  assert.equal(b.reserve('eta','incidents','account-a'),false);
  assert.equal(b.accountAllows('a',100_000,'eta'),true);
  assert.equal(b.accountAllows('a',100_000,'eta'),false);
  assert.equal(b.accountAllows('a',100_000,'faster'),true);
  assert.equal(f.make().accountAllows('a',100_001,'faster'),false);
  assert.equal(f.make().accountAllows('a',200_000,'faster'),true);
  assert.equal(f.make().accountAllows('a',200_000,'eta'),true);
  assert.equal(f.make().accountAllows('a',300_000,'eta'),true);
});

test('Cache : un appel simultané, expiration, borne, échec non conservé', async ()=>{
  let now=1000,calls=0;
  const c=createHereCache({ttlMs:100,maxEntries:2,now:()=>now});
  const load=async()=>++calls;
  assert.deepEqual(await Promise.all([c.get('a',load),c.get('a',load)]),[1,1]);
  assert.equal(await c.get('a',load),1);
  now+=101;
  assert.equal(await c.get('a',load),2);
  await c.get('b',load); await c.get('c',load);
  assert.equal(c.status().entries,2);
  await assert.rejects(c.get('failed',async()=>{throw Error('offline');}));
  assert.equal(await c.get('failed',load),5);
});

const points=[[48,2],[48.03,2]], path=measure(points);
const section={summary:{length:path.total,duration:600,baseDuration:500}};
const answer=sections=>async()=>({ok:true,json:async()=>({routes:[{sections}]})});
test('HERE : cache avant budget, trace complète, durée nulle et notice critique refusées', async ()=>{
  const cache=createHereCache(); let reservations=0, calls=0, trace;
  const fetchImpl=async(_url,init)=>{calls++;trace=JSON.parse(init.body).trace;return {ok:true,json:async()=>({routes:[{sections:[section]}]})};};
  const opts={cache,fetchImpl,reserve:()=>{reservations++;return true;}};
  await Promise.all([hereTravel(points,opts),hereTravel(points,opts)]);
  assert.equal(calls,1);assert.equal(reservations,1);
  assert.deepEqual(trace.at(-1),{lat:48.03,lng:2});
  assert.equal(await hereTravel(points,{cache:null,reserve:()=>true,fetchImpl:answer([{summary:{length:path.total,duration:0}}])}),null);
  assert.equal(await hereTravel(points,{cache:null,reserve:()=>true,fetchImpl:answer([{...section,notices:[{severity:'critical',code:'cannotMatch'}]}])}),null);
  await assert.rejects(hereTravel(points,{cache:null,reserve:()=>false,fetchImpl}),/budget/);
  assert.equal(calls,1);
});

test('HERE : import seul garde ETA ; incidents indisponibles restent signalés', async ()=>{
  const result=await hereSnapshot(points,{along:async()=>{throw Error('flow failed');},travel:async()=>({travelS:600})});
  assert.equal(result.live,true);assert.equal(result.traffic.travelS,600);
  assert.equal(result.traffic.flowPending,false);
  assert.equal(result.traffic.flowAvailable,false);assert.equal(result.traffic.incidentsAvailable,false);
  const partial=await hereAlong(points,{cache:null,reserve:()=>true,fetchImpl:async url=>{
    if(url.includes('/incidents?'))throw Error('incidents failed');
    return {ok:true,json:async()=>({results:[]})};
  }});
  assert.equal(partial.flowAvailable,true);assert.equal(partial.incidentsAvailable,false);
});

function deferred() {
  let resolve, reject;
  const promise=new Promise((yes,no)=>{resolve=yes;reject=no;});
  return { promise, resolve, reject };
}
const completeFlow={totalM:Math.round(path.total),delayS:90,sections:[{fromM:0,toM:400,delayS:90,source:'here'}],
  flowAvailable:true,incidentsAvailable:true,coverageM:path.total};
const nextTurn=()=>new Promise(resolve=>setImmediate(resolve));

test('HERE : durée prête sans attendre flow lent ; travail continue dans cache partagé', async ()=>{
  const waiting=deferred(), cache=createHereCache();let flowCalls=0,importCalls=0;
  const result=await hereSnapshot(points,{flowWaitMs:0,
    along:()=>cache.get('snapshot-flow',()=>{flowCalls++;return waiting.promise;}),
    travel:async()=>{importCalls++;return {travelS:600};},
  });
  assert.equal(result.live,true);assert.equal(result.traffic.travelS,600);
  assert.equal(result.traffic.flowPending,true);
  assert.equal(result.traffic.flowAvailable,false);assert.equal(result.traffic.incidentsAvailable,false);
  assert.equal(result.traffic.delayS,null);assert.deepEqual(result.traffic.sections,[]);
  assert.equal('coverageM' in result.traffic,false);
  assert.equal(cache.status().pending,1);
  const shared=cache.get('snapshot-flow',()=>{flowCalls++;return {};} );
  waiting.resolve(completeFlow);
  assert.deepEqual(await shared,completeFlow);
  assert.deepEqual(await cache.get('snapshot-flow',()=>{flowCalls++;return {};}),completeFlow);
  assert.equal(flowCalls,1);assert.equal(importCalls,1);
  assert.equal(result.traffic.delayS,null);assert.equal(result.traffic.flowPending,true);
});

test('HERE : flow déjà prêt conservé lorsque Import termine après délai', async ()=>{
  const waiting=deferred();
  const resultPromise=hereSnapshot(points,{flowWaitMs:0,along:async()=>completeFlow,travel:()=>waiting.promise});
  await new Promise(resolve=>setTimeout(resolve,5));
  waiting.resolve({travelS:600});
  const result=await resultPromise;
  assert.equal(result.traffic.travelS,600);assert.equal(result.traffic.flowPending,false);
  assert.equal(result.traffic.flowAvailable,true);assert.equal(result.traffic.incidentsAvailable,true);
  assert.equal(result.traffic.delayS,90);assert.deepEqual(result.traffic.sections,completeFlow.sections);
  assert.equal(result.traffic.coverageM,path.total);
});

test('HERE : délai flow commence avant réponse Import', async t=>{
  t.mock.timers.enable({apis:['setTimeout']});
  const duration=deferred(),flow=deferred();
  let ready=false;
  const resultPromise=hereSnapshot(points,{flowWaitMs:2500,along:()=>flow.promise,travel:()=>duration.promise})
    .then(result=>{ready=true;return result;});
  await nextTurn();
  t.mock.timers.tick(2500);
  duration.resolve({travelS:600});
  await nextTurn();
  assert.equal(ready,true);
  const result=await resultPromise;
  assert.equal(result.traffic.travelS,600);assert.equal(result.traffic.flowPending,true);
  flow.resolve(completeFlow);
  await nextTurn();
});

test('HERE : Import nul ou rejeté attend flow utilisable malgré délai dépassé', async ()=>{
  for(const fails of [false,true]){
    const flow=deferred();let done=false;
    const resultPromise=hereSnapshot(points,{flowWaitMs:0,along:()=>flow.promise,
      travel:async()=>{if(fails)throw Error('import unavailable');return null;},
    }).then(result=>{done=true;return result;});
    await new Promise(resolve=>setTimeout(resolve,5));
    assert.equal(done,false);
    flow.resolve(completeFlow);
    const result=await resultPromise;
    assert.equal(result.live,true);assert.equal(result.traffic.travelS,null);
    assert.equal(result.traffic.flowPending,false);assert.equal(result.traffic.flowAvailable,true);
    assert.equal(result.traffic.delayS,90);assert.deepEqual(result.traffic.sections,completeFlow.sections);
  }
});

test('HERE : rejet flow tardif consommé après instantané partiel', async ()=>{
  const flow=deferred();
  const result=await hereSnapshot(points,{flowWaitMs:0,along:()=>flow.promise,travel:async()=>({travelS:600})});
  assert.equal(result.traffic.flowPending,true);
  flow.reject(Error('late flow failure'));
  await nextTurn();
  assert.equal(result.live,true);assert.equal(result.traffic.travelS,600);
});


test('Budget : tarif ou plafond invalide ferme les appels',t=>{
  const f=fixture(t,{hereMonthlyBudgetEUR:NaN});
  assert.equal(f.make().reserve('eta', 'flow', 'account-a'),false);
  assert.equal(f.make().usage().blocked,'invalid pricing');
});

function basePlan(t, overrides = {}) {
  const f = fixture(t, { hereMonthlyBudgetEUR: 0, hereTrafficEURPer1000: 2.33,
    hereAdvancedTrafficEURPer1000: 4.66, hereImportEURPer1000: 4.66,
    hereTrafficFreeMonthly: 5000, hereAdvancedTrafficFreeMonthly: 2500, hereImportFreeMonthly: 2500,
    hereBillingStart: '2026-10-14', ...overrides });
  f.setTime('2026-10-14T00:00:00Z');
  return f;
}

test('Base Plan : franchises distinctes, flow et incidents partagent leur produit', t => {
  const f = basePlan(t, { hereTrafficFreeMonthly: 2, hereAdvancedTrafficFreeMonthly: 1, hereImportFreeMonthly: 2 });
  const b = f.make();
  assert.equal(b.reserve('eta', 'flow', 'a'), true);
  assert.equal(b.reserve('faster', 'incidents', 'a'), true);
  assert.equal(b.reserve('eta', 'flow', 'a'), false);
  assert.equal(b.reserve('eta', 'import', 'a'), true);
  assert.equal(b.reserve('faster', 'import', 'a'), true);
  assert.equal(b.reserve('eta', 'import', 'a'), false);
  f.settings.hereDeepCoverage = true;
  assert.equal(b.reserve('faster', 'incidents', 'a'), true);
  assert.equal(b.reserve('eta', 'flow', 'a'), false);
  const u = f.make().usage();
  for (const window of Object.values(u.billingWindows)) {
    assert.deepEqual(window.usedByProduct, { realTimeTraffic: 2, advancedTraffic: 1, routeImport: 2 });
    assert.equal(window.estimatedOverageEUR, 0);
  }
  assert.deepEqual(u.franchises, { realTimeTraffic: 2, advancedTraffic: 1, routeImport: 2 });
  assert.equal(u.monthlyBudgetEUR, 0);
  assert.equal(u.estimatedMonthEUR, 0);
  assert.equal(u.estimatedOverageEUR, 0);
  assert.equal(u.accountDailyMax, 250);
  assert.equal(u.fasterAccountDailyMax, 250);
  assert.equal(u.used, 5);
});

test('Base Plan : vrais plafonds 5000/2500/2500, zéro dépassement', t => {
  const f = basePlan(t);
  const b = f.make();
  b.usage();
  const saved = JSON.parse(readFileSync(f.settings.hereUsageFile, 'utf8'));
  saved.billing.dailyUTC['2026-10-14'] = { realTimeTraffic: 4999, advancedTraffic: 2499, routeImport: 2499 };
  writeFileSync(f.settings.hereUsageFile, JSON.stringify(saved));
  const full = f.make();
  assert.equal(full.reserve('eta', 'flow', 'a'), true);
  assert.equal(full.reserve('eta', 'incidents', 'a'), false);
  assert.equal(full.reserve('eta', 'import', 'a'), true);
  assert.equal(full.reserve('faster', 'import', 'a'), false);
  f.settings.hereDeepCoverage = true;
  assert.equal(full.reserve('faster', 'flow', 'a'), true);
  assert.equal(full.reserve('eta', 'incidents', 'a'), false);
  assert.equal(full.usage().estimatedOverageEUR, 0);
});

test('Base Plan : mois civil remis à zéro, anniversaire encore plein', t => {
  const f = basePlan(t, { hereTrafficFreeMonthly: 2 });
  f.setTime('2026-10-31T23:59:59Z');
  const b = f.make();
  assert.equal(b.reserve('eta', 'flow', 'a'), true);
  assert.equal(b.reserve('faster', 'incidents', 'a'), true);
  f.setTime('2026-11-01T00:00:00Z');
  assert.equal(f.make().reserve('eta', 'flow', 'a'), false);
  assert.equal(b.usage().billingWindows.calendar.usedByProduct.realTimeTraffic, 0);
  assert.equal(b.usage().billingWindows.anniversary.usedByProduct.realTimeTraffic, 2);
  assert.equal(b.usage().billingWindows.anniversary.start, '2026-10-14');
  assert.equal(b.usage().billingWindows.anniversary.end, '2026-11-14');
  f.setTime('2026-11-14T00:00:00Z');
  assert.equal(b.reserve('eta', 'flow', 'a'), true);
  assert.equal(b.usage().billingWindows.anniversary.start, '2026-11-14');
});

test('Base Plan : anniversaire remis à zéro, mois civil encore plein', t => {
  const f = basePlan(t, { hereImportFreeMonthly: 2 });
  f.setTime('2026-11-13T23:59:59Z');
  const b = f.make();
  assert.equal(b.reserve('eta', 'import', 'a'), true);
  assert.equal(b.reserve('faster', 'import', 'a'), true);
  f.setTime('2026-11-14T00:00:00Z');
  assert.equal(f.make().reserve('eta', 'import', 'a'), false);
  assert.equal(b.usage().billingWindows.calendar.usedByProduct.routeImport, 2);
  assert.equal(b.usage().billingWindows.anniversary.usedByProduct.routeImport, 0);
  f.setTime('2026-12-01T00:00:00Z');
  assert.equal(b.reserve('eta', 'import', 'a'), true);
});

test('Base Plan : 1317 appels ancienne clé exclus, historique intégral conservé', t => {
  const f = basePlan(t);
  writeFileSync(f.settings.hereUsageFile, JSON.stringify({
    day: '2026-10-15', month: '2026-10', monthUsed: 1317, used: 0,
    monthByKind: { flow: 500, incidents: 500, import: 317 },
    history: { version: 1, since: '2026-10-05T20:28:00.000Z', baselineMonth: '2026-10',
      totalRequests: 1317, totalByKind: { flow: 500, incidents: 500, import: 317 }, daily: {} },
  }));
  f.setTime('2026-10-15T00:00:00Z');
  const b = f.make();
  const u = b.usage();
  assert.equal(u.history.since, '2026-10-05T20:28:00.000Z');
  assert.equal(u.history.totalRequests, 1317);
  assert.equal(u.history.baselineMonth, '2026-10');
  assert.equal(u.monthUsed, 1317);
  assert.equal(u.estimatedMonthEUR, 0);
  for (const window of Object.values(u.billingWindows)) {
    assert.deepEqual(window.usedByProduct, { realTimeTraffic: 0, advancedTraffic: 0, routeImport: 0 });
  }
  assert.equal(b.reserve('eta', 'import', 'a'), true);
  assert.equal(f.make().usage().history.totalRequests, 1318);
  assert.equal(f.make().usage().billingWindows.calendar.usedByProduct.routeImport, 1);
});

test('Base Plan : jour UTC avant abonnement exclu même sans historique détaillé', t => {
  const f = basePlan(t);
  writeFileSync(f.settings.hereUsageFile, JSON.stringify({ day: '2026-10-13', month: '2026-10',
    monthUsed: 1317, used: 1317, monthByKind: { flow: 1317, incidents: 0, import: 0 } }));
  const b = f.make();
  assert.equal(b.usage().billingWindows.calendar.usedByProduct.realTimeTraffic, 0);
  assert.equal(b.usage().history.totalRequests, 1317);
  assert.equal(b.reserve('eta', 'flow', 'a'), true);
});

test('Base Plan : réservation interdite avant début, puis possible à minuit UTC', t => {
  const f = basePlan(t);
  f.setTime('2026-10-13T23:59:59Z');
  const b = f.make();
  assert.equal(b.reserve('eta', 'flow', 'a'), false);
  assert.equal(b.usage().blocked, 'billing not started');
  f.setTime('2026-10-14T00:00:00Z');
  assert.equal(b.reserve('eta', 'flow', 'a'), true);
});

test('Base Plan : quota 250 transactions commun ETA, détour, Import, persistant et UTC', t => {
  const f = basePlan(t), b = f.make();
  for (let n = 0; n < 250; n++) {
    assert.equal(b.reserve(n % 2 ? 'eta' : 'faster', ['flow', 'incidents', 'import'][n % 3], 'a'), true);
  }
  assert.equal(b.reserve('eta', 'import', 'a'), false);
  assert.equal(f.make().reserve('faster', 'flow', 'a'), false);
  assert.equal(b.usage().lastDenied, 'account daily cap');
  assert.equal(b.reserve('eta', 'import', 'b'), true);
  f.setTime('2026-10-14T22:01:00Z');
  assert.equal(b.reserve('eta', 'import', 'a'), false);
  f.setTime('2026-10-15T00:00:00Z');
  assert.equal(b.reserve('eta', 'import', 'a'), true);
  assert.equal(b.usage().history.totalRequests, 252);
});

test('Base Plan : dépassement positif partagé entre produits et converti au tarif', t => {
  const f = basePlan(t, { hereMonthlyBudgetEUR: 2, herePriceMargin: 1,
    hereTrafficEURPer1000: 1000, hereAdvancedTrafficEURPer1000: 2000, hereImportEURPer1000: 2000,
    hereTrafficFreeMonthly: 1, hereAdvancedTrafficFreeMonthly: 1, hereImportFreeMonthly: 1 });
  const b = f.make();
  assert.equal(b.reserve('eta', 'flow', 'a'), true);
  assert.equal(b.reserve('eta', 'import', 'a'), true);
  assert.equal(b.reserve('eta', 'import', 'a'), true);
  assert.equal(b.reserve('faster', 'flow', 'a'), false);
  assert.equal(b.usage().estimatedOverageEUR, 2);
  assert.equal(b.usage().billingWindows.calendar.products.routeImport.limit, 2);
  assert.equal(b.reserve('eta', 'import', 'a'), false);
});

test('Base Plan : cache et mutualisation ne débitent pas les autres comptes', async t => {
  const f = basePlan(t, { hereAccountDailyMax: 1 }), b = f.make(), cache = createHereCache();
  let calls = 0;
  const options = { cache, reserve: b.reserve, fetchImpl: async () => {
    calls++;
    return { ok: true, json: async () => ({ routes: [{ sections: [section] }] }) };
  } };
  await Promise.all([hereTravel(points, { ...options, accountId: 'a' }), hereTravel(points, { ...options, accountId: 'b' })]);
  await hereTravel(points, { ...options, accountId: 'a' });
  assert.equal(calls, 1);
  assert.equal(b.usage().used, 1);
  assert.equal(b.reserve('faster', 'flow', 'a'), false);
  assert.equal(b.reserve('faster', 'flow', 'b'), true);
  assert.equal(b.usage().used, 2);
});

test('Base Plan : échec disque ne débite aucun compteur et interdit appel réseau', async t => {
  const f = basePlan(t), b = f.make();
  assert.equal(b.reserve('eta', 'flow', 'a'), true);
  const before = b.usage();
  mkdirSync(f.settings.hereUsageFile + '.tmp');
  let calls = 0;
  await assert.rejects(hereTravel(points, { accountId: 'a', cache: null, reserve: b.reserve,
    fetchImpl: async () => { calls++; throw Error('network forbidden'); } }), /budget/);
  assert.equal(calls, 0);
  const after = b.usage();
  assert.equal(after.used, before.used);
  assert.equal(after.monthUsed, before.monthUsed);
  assert.deepEqual(after.byUse, before.byUse);
  assert.deepEqual(after.byKind, before.byKind);
  assert.equal(after.history.totalRequests, before.history.totalRequests);
  assert.deepEqual(after.billingWindows, before.billingWindows);
  assert.equal(after.blocked, 'storage unavailable');
  const saved = JSON.parse(readFileSync(f.settings.hereUsageFile, 'utf8'));
  assert.equal(saved.accountTransactions['account:a'], 1);
  assert.equal(saved.billing.dailyUTC['2026-10-14'].routeImport, 0);
});

test('Base Plan : toutes sources transportent le compte, y compris instantané', async t => {
  const f = basePlan(t), b = f.make(), reservations = [];
  const reserve = (...args) => { reservations.push(args); return b.reserve(...args); };
  await hereAlong(points, { accountId: 'owner', use: 'faster', cache: null, reserve,
    fetchImpl: async () => ({ ok: true, json: async () => ({ results: [] }) }) });
  await hereTravel(points, { accountId: 'owner', cache: null, reserve, fetchImpl: answer([section]) });
  assert.deepEqual(reservations, [['faster', 'flow', 'owner'], ['faster', 'incidents', 'owner'], ['eta', 'import', 'owner']]);
  const seen = [];
  await hereSnapshot(points, { accountId: 'owner',
    along: async (_points, options) => { seen.push(options); return completeFlow; },
    travel: async (_points, options) => { seen.push(options); return { travelS: 600 }; },
  });
  assert.deepEqual(seen, [{ use: 'eta', accountId: 'owner' }, { use: 'eta', accountId: 'owner' }]);
});

test('Base Plan : compte absent et configuration invalide refusés', t => {
  const f = basePlan(t), b = f.make();
  assert.equal(b.reserve('eta', 'flow'), false);
  assert.equal(b.usage().used, 0);
  for (const overrides of [
    { hereBillingStart: '2026-02-30' }, { hereTrafficFreeMonthly: -1 }, { hereImportFreeMonthly: 1.5 },
    { hereAdvancedTrafficEURPer1000: 0 }, { hereAccountDailyMax: NaN }, { hereMonthlyBudgetEUR: -1 },
  ]) {
    const invalid = basePlan(t, overrides).make();
    assert.equal(invalid.reserve('eta', 'flow', 'a'), false);
  }
});


test('Base Plan : zéro dépassement reste strict même avec tarif minuscule', t => {
  const f = basePlan(t, { hereTrafficFreeMonthly: 0, hereTrafficEURPer1000: 1e-12 });
  assert.equal(f.make().reserve('eta', 'flow', 'a'), false);
});

test('Base Plan : migration Paris incertaine prudente dans chaque fenêtre', t => {
  const f = basePlan(t, { hereImportFreeMonthly: 1 });
  f.setTime('2026-11-14T00:00:00Z');
  writeFileSync(f.settings.hereUsageFile, JSON.stringify({ day: '2026-11-14', month: '2026-11',
    monthUsed: 1, used: 0, monthByKind: { flow: 0, incidents: 0, import: 1 },
    history: { version: 1, since: '2026-10-01T12:00:00.000Z', baselineMonth: '2026-10',
      totalRequests: 1, totalByKind: { flow: 0, incidents: 0, import: 1 },
      daily: { '2026-11-14': { flow: 0, incidents: 0, import: 1 } },
    },
  }));
  const b = f.make(), usage = b.usage();
  assert.equal(usage.legacyBillingConservative, true);
  assert.equal(usage.history.totalRequests, 1);
  assert.equal(usage.billingWindows.calendar.usedByProduct.routeImport, 1);
  assert.equal(usage.billingWindows.anniversary.usedByProduct.routeImport, 1);
  assert.equal(b.reserve('eta', 'import', 'a'), false);
  assert.deepEqual(f.make().usage().billingWindows, usage.billingWindows);
});
