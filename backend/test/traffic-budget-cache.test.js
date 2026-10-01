import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
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
    hereDeepCoverage: false, hereAccountGapS: 60, rerouteCheckGapS: 60, hereAccountDailyMax: 2,
    hereFasterAccountDailyMax: 1, ...overrides };
  let at = Date.parse('2026-09-30T22:00:00Z');
  const make = () => createHereBudget({ settings, now: ()=>at });
  return { settings, make, setTime: value=>{at=Date.parse(value);} };
}

test('Budget : réservations concurrentes bornées, redémarrage, jour et mois', async t=>{
  const f=fixture(t), b=f.make();
  const accepted=await Promise.all(Array.from({length:40},()=>Promise.resolve().then(()=>b.reserve('eta','flow'))));
  assert.equal(accepted.filter(Boolean).length,10);
  assert.equal(f.make().reserve('faster','incidents'),false);
  assert.equal(f.make().usage().estimatedMonthEUR,10);
  f.setTime('2026-10-01T00:00:00Z');
  assert.equal(b.reserve('faster','import'),true);
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
  assert.equal(b.reserve('eta','flow'),false);
  writeFileSync(f.settings.hereUsageFile,'broken');
  assert.equal(f.make().reserve('eta','flow'),false);
});

test('Budget : limite quotidienne, quotas compte ETA/détours persistants et séparés', t=>{
  const f=fixture(t,{hereDailyCap:1}), b=f.make();
  assert.equal(b.reserve('eta','flow'),true);
  assert.equal(b.reserve('eta','incidents'),false);
  assert.equal(b.accountAllows('a',100_000,'eta'),true);
  assert.equal(b.accountAllows('a',100_000,'eta'),false);
  assert.equal(b.accountAllows('a',100_000,'faster'),true);
  assert.equal(f.make().accountAllows('a',200_000,'faster'),false);
  assert.equal(f.make().accountAllows('a',200_000,'eta'),true);
  assert.equal(f.make().accountAllows('a',300_000,'eta'),false);
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
  const partial=await hereAlong(points,{cache:null,reserve:()=>true,fetchImpl:async url=>{
    if(url.includes('/incidents?'))throw Error('incidents failed');
    return {ok:true,json:async()=>({results:[]})};
  }});
  assert.equal(partial.flowAvailable,true);assert.equal(partial.incidentsAvailable,false);
});


test('Budget : tarif ou plafond invalide ferme les appels',t=>{
  const f=fixture(t,{hereMonthlyBudgetEUR:NaN});
  assert.equal(f.make().reserve('eta','flow'),false);
  assert.equal(f.make().usage().blocked,'invalid pricing');
});
