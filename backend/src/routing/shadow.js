import { config } from '../config.js';
import { db as pool } from '../db.js';
import { routing as defaultRouting } from './engine.js';
import { closestTo, drawVariants } from './faster.js';
import { gridOf, measure, samplesBetween, share } from './geometry.js';
import { routeFacts } from './log.js';

/**
 * The shadow mode (D7.1): after a route or a detour check was answered — never before, never on
 * the way of an answer —, the engine that did not serve computes the same thing, and both are
 * compared.
 *
 * - /api/route (kind `route`, cache hits aside): the same trip, the same avoid options and the
 *   same drivers' jams; for each engine its answer or its error, its latency, distance,
 *   duration, steps and a U-turn among its first two; then the common road of both routes (each
 *   one's share on the other, 0 to 1). A route Valhalla failed and ORS served (a fallback) is
 *   compared as it is, without asking anything more.
 * - /api/route/faster (kind `faster`, only when the engine was asked for variants): the other
 *   engine draws the same variants (same window, headings, avoid options, jams' polygons,
 *   alternatives) and they are sifted as the engine's were (faster.js) — never timed: no
 *   TomTom request for the shadow. Compared: routes drawn, candidates, the ones TomTom would
 *   have timed (viable), and, for a detour served, the common road of the closest candidate of
 *   the other engine with it.
 *
 * Measures only in routing.shadow_run: no coordinates, no account, kept config.shadowKeepDays.
 * For admin accounts only, when both diverge (D7.1: duration apart by more than
 * shadowTraceDurationRatio of the served route, or less than shadowTraceMinShare of common road;
 * for a detour, the closest candidate under shadowTraceMinShare), both lines whole in
 * routing.shadow_trace, kept config.shadowTraceKeepDays, read on a map from the console.
 * Past their time, lines are purged every hour from the start (index.js), trips or not,
 * Valhalla on or off, and never read, even before the purge.
 *
 * The comparisons wait in a bounded queue (shadowQueueMax; full: the comparison is dropped and
 * counted), shadowConcurrency at a time; one failing only goes to the logs.
 */

/** The avoid options a line keeps: the ones the apps send, nothing else a client could write. */
const AVOID = new Set(['tolls', 'highways', 'ferries', 'traffic']);
const ENGINES = ['ors', 'valhalla'];

// ---- Queue ------------------------------------------------------------------

/**
 * Jobs run in the background, [concurrency] at a time, [maxQueue] waiting at most. `after(res,
 * job)` queues [job] once [res] is sent (or its client gone).
 */
export function createShadowQueue({ maxQueue = config.shadowQueueMax, concurrency = config.shadowConcurrency } = {}) {
  const waiting = [];
  const counts = { done: 0, failed: 0, dropped: 0 };
  let running = 0;
  let idle = [];

  function pump() {
    while (running < concurrency && waiting.length) {
      const job = waiting.shift();
      running += 1;
      setImmediate(async () => {
        try {
          await job();
          counts.done += 1;
        } catch (e) {
          counts.failed += 1;
          console.warn('[shadow] comparison failed —', String(e?.message || e));
        } finally {
          running -= 1;
          pump();
          if (!running && !waiting.length) {
            const resolve = idle;
            idle = [];
            resolve.forEach((done) => done());
          }
        }
      });
    }
  }

  /** Queues [job]; false when the queue is full (the job is dropped). */
  function enqueue(job) {
    if (waiting.length >= maxQueue) {
      counts.dropped += 1;
      return false;
    }
    waiting.push(job);
    pump();
    return true;
  }

  /** Queues [job] once [res] is finished (or closed), never earlier. */
  function after(res, job) {
    let queued = false;
    const go = () => {
      if (queued) return;
      queued = true;
      enqueue(job);
    };
    if (res.writableFinished) go();
    else {
      res.once('finish', go);
      res.once('close', go);
    }
  }

  return {
    enqueue,
    after,
    stats: () => ({ queued: waiting.length, running, ...counts, max: maxQueue }),
    /** Resolves once nothing waits nor runs (tests). */
    idle: () => (!running && !waiting.length ? Promise.resolve() : new Promise((done) => idle.push(done))),
  };
}

// ---- Comparison -------------------------------------------------------------

const latLon = (coordinates) => coordinates.map(([lon, lat]) => [lat, lon]);
const ratio = (value) => (Number.isFinite(value) ? Math.round(value * 1000) / 1000 : null);

/** The common road of two routes (the app's shape): each one's share on the other, 0 to 1. */
export function commonRoad(a, b) {
  const lineA = latLon(a.coordinates);
  const lineB = latLon(b.coordinates);
  return {
    aOnB: ratio(share(samplesBetween(measure(lineA), 0, Infinity), gridOf(lineB))),
    bOnA: ratio(share(samplesBetween(measure(lineB), 0, Infinity), gridOf(lineA))),
  };
}

/** What a line keeps of one engine's try: never its route, only facts about it. */
function engineFacts(attempt) {
  const none = { ok: null, error: null, latencyMs: null, distanceM: null, durationS: null, steps: null, uturnStart: null };
  if (!attempt) return none;
  if (attempt.skipped) return { ...none, error: attempt.error ?? null };
  if (!attempt.ok) return { ...none, ok: false, error: attempt.error ?? 'error', latencyMs: attempt.latencyMs ?? null };
  const facts = routeFacts(attempt.route);
  return {
    ok: true,
    error: null,
    latencyMs: attempt.latencyMs ?? null,
    distanceM: facts.distanceM,
    durationS: facts.durationS,
    steps: facts.steps,
    uturnStart: facts.uturnStart,
  };
}

/**
 * One /api/route comparison as a line: [attempts] ({ ors?, valhalla? }, engine.js) compared,
 * both routes' common road and whether they diverge ([durationRatio], [minShare]).
 */
export function routeLine({ mode, served, fallback, cause, avoid, attempts, durationRatio, minShare }) {
  const ors = engineFacts(attempts.ors);
  const valhalla = engineFacts(attempts.valhalla);
  let shares = { ors: null, valhalla: null };
  let divergent = null;
  if (ors.ok && valhalla.ok) {
    const road = commonRoad(attempts.ors.route, attempts.valhalla.route);
    shares = { ors: road.aOnB, valhalla: road.bOnA };
    const base = served === 'valhalla' ? valhalla.durationS : ors.durationS;
    divergent = Math.abs(ors.durationS - valhalla.durationS) > durationRatio * Math.max(base, 1)
      || Math.min(shares.ors, shares.valhalla) < minShare;
  }
  return { kind: 'route', mode, served, fallback: Boolean(fallback), cause, avoid, ors, valhalla, shares, divergent, detail: null };
}

/** One engine's variants for a detour check, as a line keeps them. */
function drawnFacts(drawn) {
  if (!drawn) return { facts: engineFacts(null), detail: null };
  const failedAll = drawn.found.length === 0 && drawn.errors.length > 0;
  const facts = {
    ...engineFacts(null),
    ok: drawn.skipped ? null : !failedAll,
    error: drawn.errors[0] ?? null,
    latencyMs: drawn.skipped ? null : drawn.latencyMs,
  };
  return {
    facts,
    detail: { routes: drawn.found.length, candidates: drawn.candidates.length, viable: drawn.viable.length, errors: drawn.errors.slice(0, 4) },
  };
}

/**
 * One /faster comparison as a line: the served engine's variants ([primary]: drawVariants) and
 * the other's ([other]), the detour served ([detour], a candidate) and the closest candidate of
 * the other engine ([closest]: closestTo).
 */
export function fasterLine({ mode, served, avoid, primary, other, detour, closest, minShare }) {
  const drawn = { [served]: drawnFacts(primary), [served === 'ors' ? 'valhalla' : 'ors']: drawnFacts(other) };
  const detourShare = detour ? ratio(closest?.share ?? 0) : null;
  return {
    kind: 'faster',
    mode,
    served,
    fallback: false,
    cause: null,
    avoid,
    ors: drawn.ors.facts,
    valhalla: drawn.valhalla.facts,
    shares: { ors: null, valhalla: null },
    divergent: detour ? detourShare < minShare : null,
    detail: { ors: drawn.ors.detail, valhalla: drawn.valhalla.detail, switched: Boolean(detour), detourShare },
  };
}

// ---- Store ------------------------------------------------------------------

const RUN_COLUMNS = `id, at, kind, mode, served, fallback, cause, avoid,
  ors_ok, ors_error, ors_latency_ms, ors_distance_m, ors_duration_s, ors_steps, ors_uturn_start,
  valhalla_ok, valhalla_error, valhalla_latency_ms, valhalla_distance_m, valhalla_duration_s, valhalla_steps, valhalla_uturn_start,
  share_ors, share_valhalla, divergent, detail`;

/**
 * routing.shadow_run and routing.shadow_trace (schema.sql) over [db]: written by the shadow,
 * read by the console. Measures kept [keepDays], traces [traceKeepDays] and never longer than
 * their measure (ON DELETE CASCADE): `start()` purges now, then every [purgeEveryMs] (an hour),
 * with or without trips; the reads already leave out what the next purge deletes.
 */
export function createShadowStore({
  db = pool,
  keepDays = config.shadowKeepDays,
  traceKeepDays = config.shadowTraceKeepDays,
  purgeEveryMs = 60 * 60 * 1000,
} = {}) {
  let timer = null;

  /** Deletes the lines past their time: traces first (30 days), then measures (90 days). */
  async function purge() {
    await db.query('DELETE FROM routing.shadow_trace WHERE at < now() - make_interval(days => $1)', [traceKeepDays]);
    await db.query('DELETE FROM routing.shadow_run WHERE at < now() - make_interval(days => $1)', [keepDays]);
  }

  /** One purge whose failure only goes to the logs: the next one tries again. */
  function purgeQuietly() {
    return purge().catch((e) => console.warn('[shadow] purge —', String(e?.message || e)));
  }

  /**
   * Purges now, then every [purgeEveryMs], Valhalla on or off (index.js, once the schema was
   * tried). Nothing at import; the timer never holds the process. Resolves once the first purge
   * is over, done or failed: never rejects.
   */
  function start() {
    if (timer) return Promise.resolve();
    timer = setInterval(purgeQuietly, purgeEveryMs);
    if (timer.unref) timer.unref();
    return purgeQuietly();
  }

  function stop() {
    if (timer) clearInterval(timer);
    timer = null;
  }

  /** Writes one line (routeLine, fasterLine); its id. */
  async function recordRun(line) {
    const values = [
      text(line.kind, 16),
      text(line.mode, 16),
      text(line.served, 16),
      Boolean(line.fallback),
      text(line.cause, 40),
      Array.isArray(line.avoid) ? [...new Set(line.avoid.filter((a) => AVOID.has(a)))] : [],
    ];
    for (const engine of ENGINES) {
      const facts = line[engine] ?? {};
      values.push(
        flag(facts.ok),
        text(facts.error, 40),
        whole(facts.latencyMs),
        whole(facts.distanceM),
        whole(facts.durationS),
        whole(facts.steps),
        flag(facts.uturnStart),
      );
    }
    values.push(share01(line.shares?.ors), share01(line.shares?.valhalla), flag(line.divergent), line.detail ? JSON.stringify(line.detail) : null);
    const { rows } = await db.query(
      `INSERT INTO routing.shadow_run (kind, mode, served, fallback, cause, avoid,
         ors_ok, ors_error, ors_latency_ms, ors_distance_m, ors_duration_s, ors_steps, ors_uturn_start,
         valhalla_ok, valhalla_error, valhalla_latency_ms, valhalla_distance_m, valhalla_duration_s, valhalla_steps, valhalla_uturn_start,
         share_ors, share_valhalla, divergent, detail)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20, $21, $22, $23, $24)
       RETURNING id`,
      values,
    );
    return rows?.[0]?.id != null ? Number(rows[0].id) : null;
  }

  /** Both lines of a divergent comparison of an admin account ([ors], [valhalla]: [lon, lat] lists). */
  async function recordTrace({ runId, kind, ors, valhalla }) {
    await db.query(
      `INSERT INTO routing.shadow_trace (run_id, kind, ors_geom, valhalla_geom)
       VALUES ($1, $2, ST_SetSRID(ST_GeomFromGeoJSON($3), 4326), ST_SetSRID(ST_GeomFromGeoJSON($4), 4326))`,
      [runId, text(kind, 16), lineString(ors), lineString(valhalla)],
    );
  }

  /**
   * The measures kept, newest first: from [since] (ISO) on, of [kind] (route | faster), [limit]
   * at most.
   */
  async function runs({ since = null, kind = null, limit = 200 } = {}) {
    const { rows } = await db.query(
      `SELECT ${RUN_COLUMNS} FROM routing.shadow_run
       WHERE ($1::timestamptz IS NULL OR at >= $1) AND ($2::text IS NULL OR kind = $2)
         AND at >= now() - make_interval(days => $4)
       ORDER BY at DESC, id DESC LIMIT $3`,
      [since, kind, limit, keepDays],
    );
    return rows.map(runOf);
  }

  /**
   * Per kind since [since], over the measures kept: comparisons, fallbacks, divergences, and per
   * engine: asked, answered, median and p95 latency of the answers, U-turns at the start; then
   * the errors by code.
   */
  async function summary({ since = null } = {}) {
    const engineSql = (e) => `
      count(${e}_ok)::int AS ${e}_asked,
      count(*) FILTER (WHERE ${e}_ok)::int AS ${e}_answered,
      percentile_cont(0.5) WITHIN GROUP (ORDER BY ${e}_latency_ms) FILTER (WHERE ${e}_ok) AS ${e}_p50_ms,
      percentile_cont(0.95) WITHIN GROUP (ORDER BY ${e}_latency_ms) FILTER (WHERE ${e}_ok) AS ${e}_p95_ms,
      count(*) FILTER (WHERE ${e}_uturn_start)::int AS ${e}_uturns`;
    const { rows } = await db.query(
      `SELECT kind, count(*)::int AS runs,
         count(*) FILTER (WHERE fallback)::int AS fallbacks,
         count(*) FILTER (WHERE divergent)::int AS divergent,
         ${engineSql('ors')},
         ${engineSql('valhalla')}
       FROM routing.shadow_run
       WHERE ($1::timestamptz IS NULL OR at >= $1) AND at >= now() - make_interval(days => $2)
       GROUP BY kind ORDER BY kind`,
      [since, keepDays],
    );
    const errors = await db.query(
      `SELECT kind, 'ors' AS engine, ors_error AS error, count(*)::int AS n FROM routing.shadow_run
       WHERE ors_error IS NOT NULL AND ($1::timestamptz IS NULL OR at >= $1)
         AND at >= now() - make_interval(days => $2)
       GROUP BY kind, ors_error
       UNION ALL
       SELECT kind, 'valhalla' AS engine, valhalla_error AS error, count(*)::int AS n FROM routing.shadow_run
       WHERE valhalla_error IS NOT NULL AND ($1::timestamptz IS NULL OR at >= $1)
         AND at >= now() - make_interval(days => $2)
       GROUP BY kind, valhalla_error
       ORDER BY n DESC`,
      [since, keepDays],
    );
    return {
      since,
      kinds: rows.map((row) => ({
        kind: row.kind,
        runs: row.runs,
        fallbacks: row.fallbacks,
        divergent: row.divergent,
        ...Object.fromEntries(ENGINES.map((e) => [e, {
          asked: row[`${e}_asked`],
          answered: row[`${e}_answered`],
          p50Ms: round(row[`${e}_p50_ms`]),
          p95Ms: round(row[`${e}_p95_ms`]),
          uturnStart: row[`${e}_uturns`],
        }])),
      })),
      errors: errors.rows.map((row) => ({ kind: row.kind, engine: row.engine, error: row.error, count: row.n })),
    };
  }

  /** The traces kept (and their measure), newest first, before [before] (ISO), without their lines. */
  async function traces({ before = null, limit = 50 } = {}) {
    const { rows } = await db.query(
      `SELECT t.id, t.at, t.kind, t.run_id, r.mode, r.served, r.avoid, r.ors_distance_m, r.ors_duration_s,
              r.valhalla_distance_m, r.valhalla_duration_s, r.share_ors, r.share_valhalla, r.detail
       FROM routing.shadow_trace t JOIN routing.shadow_run r ON r.id = t.run_id
       WHERE ($1::timestamptz IS NULL OR t.at < $1)
         AND t.at >= now() - make_interval(days => $3) AND r.at >= now() - make_interval(days => $4)
       ORDER BY t.at DESC, t.id DESC LIMIT $2`,
      [before, limit, traceKeepDays, keepDays],
    );
    return rows.map(traceOf);
  }

  /** One trace kept as a GeoJSON FeatureCollection (one line per engine), or null. */
  async function trace(id) {
    const { rows } = await db.query(
      `SELECT t.id, t.at, t.kind, t.run_id, r.mode, r.served, r.avoid, r.ors_distance_m, r.ors_duration_s,
              r.valhalla_distance_m, r.valhalla_duration_s, r.share_ors, r.share_valhalla, r.detail,
              ST_AsGeoJSON(t.ors_geom) AS ors_line, ST_AsGeoJSON(t.valhalla_geom) AS valhalla_line
       FROM routing.shadow_trace t JOIN routing.shadow_run r ON r.id = t.run_id
       WHERE t.id = $1
         AND t.at >= now() - make_interval(days => $2) AND r.at >= now() - make_interval(days => $3)`,
      [id, traceKeepDays, keepDays],
    );
    const row = rows[0];
    if (!row) return null;
    const summaryOf = traceOf(row);
    return {
      type: 'FeatureCollection',
      properties: summaryOf,
      features: ENGINES.filter((e) => row[`${e}_line`]).map((e) => ({
        type: 'Feature',
        properties: { engine: e, distanceM: row[`${e}_distance_m`], durationS: row[`${e}_duration_s`] },
        geometry: JSON.parse(row[`${e}_line`]),
      })),
    };
  }

  return { recordRun, recordTrace, purge, start, stop, runs, summary, traces, trace };
}

function runOf(row) {
  const engine = (e) => ({
    ok: row[`${e}_ok`],
    error: row[`${e}_error`],
    latencyMs: row[`${e}_latency_ms`],
    distanceM: row[`${e}_distance_m`],
    durationS: row[`${e}_duration_s`],
    steps: row[`${e}_steps`],
    uturnStart: row[`${e}_uturn_start`],
  });
  return {
    id: Number(row.id),
    at: new Date(row.at).toISOString(),
    kind: row.kind,
    mode: row.mode,
    served: row.served,
    fallback: row.fallback,
    cause: row.cause,
    avoid: row.avoid ?? [],
    ors: engine('ors'),
    valhalla: engine('valhalla'),
    shares: { ors: row.share_ors, valhalla: row.share_valhalla },
    divergent: row.divergent,
    detail: row.detail ?? null,
  };
}

function traceOf(row) {
  return {
    id: Number(row.id),
    at: new Date(row.at).toISOString(),
    kind: row.kind,
    runId: Number(row.run_id),
    mode: row.mode,
    served: row.served,
    avoid: row.avoid ?? [],
    ors: { distanceM: row.ors_distance_m, durationS: row.ors_duration_s },
    valhalla: { distanceM: row.valhalla_distance_m, durationS: row.valhalla_duration_s },
    shares: { ors: row.share_ors, valhalla: row.share_valhalla },
    detail: row.detail ?? null,
  };
}

const whole = (value) => (typeof value === 'number' && Number.isFinite(value) ? Math.round(value) : null);
const flag = (value) => (typeof value === 'boolean' ? value : null);
const text = (value, max) => (value == null || value === '' ? null : String(value).slice(0, max));
const share01 = (value) => (typeof value === 'number' && Number.isFinite(value) ? Math.min(Math.max(value, 0), 1) : null);
const round = (value) => (value == null ? null : Math.round(Number(value)));
const lineString = (coordinates) => (Array.isArray(coordinates) && coordinates.length >= 2
  ? JSON.stringify({ type: 'LineString', coordinates })
  : null);

// ---- Shadow -----------------------------------------------------------------

/**
 * The shadow mode over a routing façade ([routing], engine.js), a [queue] and a [store].
 * afterRoute / afterFaster are called once the answer is given; they only queue.
 */
export function createShadow({
  routing,
  queue,
  store,
  durationRatio = config.shadowTraceDurationRatio,
  minShare = config.shadowTraceMinShare,
} = {}) {
  /**
   * After a computed /api/route answer ([res]): [plan] and [outcome] from the façade, the trip
   * asked, and whether the account is an admin ([admin]: only then are lines kept).
   */
  function afterRoute(res, { plan, outcome, from, to, avoid, admin }) {
    // Valhalla off: nothing to compare with.
    if (!plan?.shadow || !outcome) return;
    queue.after(res, async () => {
      const attempts = { ...outcome.attempts };
      if (!attempts[plan.shadow]) {
        attempts[plan.shadow] = await routing.shadowRoute(plan.shadow, from, to, avoid, outcome.polygons ?? null);
      }
      const line = routeLine({
        mode: plan.mode,
        served: outcome.engine,
        fallback: outcome.fallback,
        cause: outcome.cause,
        avoid,
        attempts,
        durationRatio,
        minShare,
      });
      const runId = await store.recordRun(line);
      if (admin && line.divergent && runId != null) {
        await store.recordTrace({
          runId,
          kind: 'route',
          ors: attempts.ors.route.coordinates,
          valhalla: attempts.valhalla.route.coordinates,
        });
      }
    });
  }

  /**
   * After a /api/route/faster answer that asked an engine ([compare] from checkFaster; null:
   * nothing to compare). The other engine draws the same variants; TomTom is never asked.
   */
  function afterFaster(res, { plan, compare, avoid, admin }) {
    if (!plan?.shadow || !compare) return;
    queue.after(res, async () => {
      const other = await drawVariants(compare.plan, routing.drawer(plan.shadow, { shadow: true }));
      const closest = closestTo(compare.detour, other.candidates);
      const line = fasterLine({
        mode: plan.mode,
        served: plan.primary,
        avoid,
        primary: compare.drawn,
        other,
        detour: compare.detour,
        closest,
        minShare,
      });
      const runId = await store.recordRun(line);
      if (admin && line.divergent && runId != null) {
        const lines = { [plan.primary]: compare.detour.route.coordinates, [plan.shadow]: closest.candidate?.route.coordinates ?? null };
        await store.recordTrace({ runId, kind: 'faster', ors: lines.ors, valhalla: lines.valhalla });
      }
    });
  }

  return { afterRoute, afterFaster, stats: () => queue.stats() };
}

export const shadowStore = createShadowStore();
export const shadow = createShadow({ routing: defaultRouting, queue: createShadowQueue(), store: shadowStore });
