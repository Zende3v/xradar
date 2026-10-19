#!/usr/bin/env node
/**
 * Rapport ETA v2, agrégats seuls. Lecture SELECT, sans migration ni écriture.
 * Usage : node bin/eona-eta-report-v2.js [--since=ISO] [--json] [--roads]
 */
import { pathToFileURL } from 'node:url';
import { tolerance, wilson, VARIANTS } from './eona-eta-report.js';
import { config } from '../src/config.js';
import { edgeTime, isPeak, periodAt, roadGroup } from '../src/routing/eona-time.js';
import { traceAttributes as traceShape } from '../src/routing/trace-attributes.js';

const CHECKPOINTS = [0, 25, 50, 75];
const PROGRESS = ['0-25', '25-50', '50-75', '75-100'];
const ROAD_CLASSES = ['motorway', 'trunk', 'primary', 'secondary', 'tertiary', 'unclassified', 'residential', 'service_other', 'living_street'];
const number = (v) => typeof v === 'number' && Number.isFinite(v);
const positive = (v) => number(v) && v > 0;
const nonnegative = (v) => number(v) && v >= 0;
const amount = (v) => nonnegative(v) ? v : 0;
const baseOf = (v) => ['here', 'engine', 'tomtom'].includes(v) ? v : 'inconnue';
const list = (v) => Array.isArray(v) ? v : [];

export function quantile(values, p) {
  const sorted = values.filter(number).sort((a, b) => a - b);
  if (!sorted.length) return null;
  const index = (sorted.length - 1) * p;
  const low = Math.floor(index);
  return sorted[low] + (sorted[Math.ceil(index)] - sorted[low]) * (index - low);
}

function distribution(values) {
  const valid = values.filter(number);
  return { n: valid.length, median: quantile(valid, 0.5), p25: quantile(valid, 0.25), p75: quantile(valid, 0.75) };
}

export function tripEnd(trip) {
  if (!positive(trip?.startedAt) || !positive(trip?.durationSeconds)) return null;
  const end = trip.startedAt + trip.durationSeconds * 1000;
  return number(end) && end <= 8.64e15 ? end : null;
}

function validCheck(check, trip) {
  return CHECKPOINTS.includes(check?.at) && positive(check.shownAt) && positive(check.arrivalAt)
    && check.shownAt >= trip.startedAt && check.shownAt <= tripEnd(trip) && check.arrivalAt >= check.shownAt;
}

function departureCheck(trip) {
  return list(trip?.etaChecks).find((c) => c?.at === 0 && validCheck(c, trip));
}

/** Premier mouvement absent ou incohérent : référence historique conservée. */
export function departureReference(trip) {
  const check = departureCheck(trip);
  const end = tripEnd(trip);
  const hasMove = positive(trip.firstMoveAt) && trip.firstMoveAt >= trip.startedAt && trip.firstMoveAt < end;
  const oldStart = positive(trip.departedAt) && trip.departedAt >= trip.startedAt && trip.departedAt < end
    ? trip.departedAt : check?.shownAt ?? trip.startedAt;
  return {
    waitS: hasMove && check ? Math.max(0, (trip.firstMoveAt - check.shownAt) / 1000) : null,
    shiftS: hasMove && check ? Math.max(0, (trip.firstMoveAt - check.shownAt) / 1000) : 0,
    start: hasMove ? trip.firstMoveAt : oldStart,
  };
}

const isNear = (trip) => trip.endReason === 'near'
  || (trip.endReason === 'interrupted' && nonnegative(trip.endDistanceM) && trip.endDistanceM <= 300);
const hasMovement = (trip) => positive(trip?.firstMoveAt) && trip.firstMoveAt >= trip.startedAt && trip.firstMoveAt < tripEnd(trip);

export function exclusion(trip) {
  if (!trip || typeof trip !== 'object') return 'sansMesures';
  if (trip.retargeted === true) return 'retargeted';
  if (trip.endReason === 'stopped' || (trip.endReason === 'interrupted' && !isNear(trip))) return trip.endReason;
  if (trip.manualStart === true) return 'manualStart';
  if (!(trip.endReason === 'arrived' || isNear(trip)) || !positive(tripEnd(trip))
    || !list(trip.etaChecks).some((c) => validCheck(c, trip))) return 'sansMesures';
  return null;
}

/** Erreur positive : arrivée tardive. Le décalage concerne seulement le relevé 0 %. */
export function judge(trip, variant = null) {
  const end = tripEnd(trip);
  const reference = departureReference(trip);
  const paused = variant ? amount(trip.pausedSeconds) + (variant.uncertainIsPause ? amount(trip.uncertainSeconds) : 0) : 0;
  const before = (c) => variant ? amount(c?.pausedBefore) + (variant.uncertainIsPause ? amount(c?.uncertainBefore) : 0) : 0;
  const checks = [];
  const seen = new Set();
  for (const check of list(trip.etaChecks)) {
    if (!validCheck(check, trip) || seen.has(check.at)) continue;
    seen.add(check.at);
    const adjusted = end - Math.max(0, paused - before(check)) * 1000;
    const rawRemainingS = (adjusted - check.shownAt) / 1000;
    const shiftS = check.at === 0 ? reference.shiftS : 0;
    checks.push({
      at: check.at,
      rawErrorS: (adjusted - check.arrivalAt) / 1000,
      errorS: (adjusted - check.arrivalAt) / 1000 - shiftS,
      rawRemainingS,
      remainingS: rawRemainingS - shiftS,
    });
  }
  const pausedAfterDeparture = Math.max(0, paused - before(departureCheck(trip)));
  return { durationS: (end - reference.start) / 1000 - pausedAfterDeparture, checks };
}

function errors(checks, movement) {
  const values = checks.filter((c) => number(movement ? c.errorS : c.rawErrorS));
  const good = values.filter((c) => Math.abs(movement ? c.errorS : c.rawErrorS)
    <= tolerance(movement ? c.remainingS : c.rawRemainingS)).length;
  const signed = values.map((c) => movement ? c.errorS : c.rawErrorS);
  return {
    n: values.length, good, goodRate: values.length ? good / values.length : null,
    wilson95: wilson(good, values.length),
    medianErrorS: quantile(signed, 0.5),
    medianAbsErrorS: quantile(signed.map(Math.abs), 0.5),
  };
}

function checkpoints(judgments) {
  const all = judgments.flatMap((j) => j.checks);
  return CHECKPOINTS.map((at) => {
    const checks = all.filter((c) => c.at === at);
    return { at, raw: errors(checks, false), sinceMovement: errors(checks, true) };
  });
}

export function speedClass(kmh) {
  return !nonnegative(kmh) ? 'inconnue' : kmh < 25 ? '<25' : kmh < 45 ? '25-45' : '>=45';
}

function slicesOf(trip, durationS) {
  const platform = ['ios', 'android'].includes(trip.platform) ? trip.platform : 'inconnue';
  // Aucun texte libre ne devient un nom de groupe.
  const version = typeof trip.appVersion === 'string'
    && /^\d{1,6}(?:\.\d{1,6}){0,3}(?: \(\d{1,8}\))?$/.test(trip.appVersion) ? trip.appVersion : 'inconnue';
  return [
    ['all', 'tous'],
    ['platformVersion', platform + ' ' + version],
    ['departureBase', baseOf(departureCheck(trip)?.base)],
    ['duration', durationS < 1200 ? '<20' : durationS <= 3600 ? '20-60' : '>60'],
    ['averageSpeed', positive(durationS) && nonnegative(trip.distanceMeters)
      ? speedClass(trip.distanceMeters * 3.6 / durationS) : 'inconnue'],
    ['peak', isPeak(departureReference(trip).start) ? 'pointe' : 'hors pointe'],
    ['recalculated', amount(trip.recalcCount) + amount(trip.fasterCount) > 0 ? 'oui' : 'non'],
  ];
}

/**
 * Temps réel chronologique restant, sans retrait des arrêts. Réel/moteur seulement si
 * engineBasis = 'raw' : avant ce marqueur, engineLeftS pouvait porter le temps EONA (lot 4).
 * [counts] reçoit les relevés moteur bruts et ceux de base inconnue.
 */
export function timelineSamples(trip, counts = null) {
  const end = tripEnd(trip);
  const out = [];
  for (const point of list(trip.timeline)) {
    if (!point || !positive(point.t) || point.t < trip.startedAt || point.t > end || !nonnegative(point.drivenM)) continue;
    const totalM = nonnegative(point.leftM) ? point.drivenM + point.leftM : trip.distanceMeters;
    if (!positive(totalM)) continue;
    const share = Math.max(0, Math.min(1, point.drivenM / totalM));
    const realS = (end - point.t) / 1000;
    const displayedRatio = positive(point.leftS) ? realS / point.leftS : null;
    const raw = point.engineBasis === 'raw';
    if (counts && positive(point.engineLeftS)) counts[raw ? 'raw' : 'unknown']++;
    const engineRatio = raw && positive(point.engineLeftS) ? realS / point.engineLeftS : null;
    if (displayedRatio == null && engineRatio == null) continue;
    out.push({
      progress: PROGRESS[Math.min(3, Math.floor(share * 4))],
      base: baseOf(point.base), speed: speedClass(point.kmh), displayedRatio, engineRatio,
    });
  }
  return out;
}

function profileGroups(samples, keys) {
  const groups = new Map();
  for (const sample of samples) {
    const key = JSON.stringify(keys.map((k) => sample[k]));
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(sample);
  }
  return [...groups].sort(([a], [b]) => a.localeCompare(b)).map(([, points]) => ({
    ...Object.fromEntries(keys.map((k) => [k, points[0][k]])),
    n: points.length,
    displayedRatio: distribution(points.map((p) => p.displayedRatio)),
    engineRatio: distribution(points.map((p) => p.engineRatio)),
  }));
}

export function stopSummary(trips) {
  const counts = [], seconds = [], shares = [], durations = [], initialSeconds = [];
  let logs = 0, initialKnown = 0, initialTrips = 0;
  for (const trip of trips) {
    const hasLog = Array.isArray(trip.stopLog);
    const stops = list(trip.stopLog).filter((s) => positive(s?.at) && positive(s.seconds)
      && s.at < tripEnd(trip) && s.at + s.seconds * 1000 > trip.startedAt);
    if (hasLog) logs++;
    const count = nonnegative(trip.stops) ? trip.stops : hasLog ? stops.length : null;
    const total = nonnegative(trip.stoppedSeconds) ? trip.stoppedSeconds
      : hasLog ? stops.reduce((sum, s) => sum + s.seconds, 0) : null;
    counts.push(count);
    seconds.push(total);
    shares.push(total != null && total <= trip.durationSeconds ? total / trip.durationSeconds : null);
    durations.push(...stops.map((s) => s.seconds));
    const check = departureCheck(trip);
    if (hasLog && check) {
      initialKnown++;
      // Un arrêt déjà commencé compte s'il recouvre le relevé 0 %.
      const initial = stops.filter((s) => s.at < check.shownAt + 15000 && s.at + s.seconds * 1000 > check.shownAt);
      if (initial.length) {
        initialTrips++;
        initialSeconds.push(initial.reduce((sum, s) => sum
          + (Math.min(tripEnd(trip), s.at + s.seconds * 1000) - Math.max(s.at, check.shownAt)) / 1000, 0));
      }
    }
  }
  return {
    tripsWithLog: logs, tripsWithoutLog: trips.length - logs,
    countPerTrip: distribution(counts), secondsPerTrip: distribution(seconds),
    stoppedSharePerTrip: distribution(shares), loggedStopSeconds: distribution(durations),
    initial: { tripsWithDepartureAndLog: initialKnown, trips: initialTrips, seconds: distribution(initialSeconds) },
  };
}

export function report(records, { since = null, analysisTrips = [] } = {}) {
  const trips = list(records).filter((t) => since == null || (number(t?.startedAt) && t.startedAt >= since));
  const excluded = { stopped: 0, interrupted: 0, retargeted: 0, manualStart: 0, sansMesures: 0 };
  const kept = [];
  for (const trip of trips) {
    const reason = exclusion(trip);
    if (reason) excluded[reason]++;
    else kept.push(trip);
  }
  const variants = {};
  for (const variant of VARIANTS) {
    const slices = new Map();
    for (const trip of kept) {
      const judgment = judge(trip, variant);
      for (const [dimension, value] of slicesOf(trip, judgment.durationS)) {
        const key = JSON.stringify([dimension, value]);
        if (!slices.has(key)) slices.set(key, { dimension, value, judgments: [] });
        slices.get(key).judgments.push(judgment);
      }
    }
    variants[variant.key] = {
      title: variant.title,
      slices: [...slices].sort(([a], [b]) => a.localeCompare(b)).map(([, s]) => ({
        dimension: s.dimension, value: s.value, trips: s.judgments.length, checkpoints: checkpoints(s.judgments),
      })),
    };
  }
  const waits = kept.map((t) => departureReference(t).waitS);
  const engineBasis = { raw: 0, unknown: 0 };
  const samples = kept.flatMap((trip) => timelineSamples(trip, engineBasis));
  const near = kept.filter(isNear);
  return {
    version: 2,
    since: since == null ? null : new Date(since).toISOString(),
    rules: {
      error: 'Fin - arrivée affichée, secondes ; positif = retard.',
      departure: 'Brute chronologique ; mouvement : arrivée 0 % décalée de max(0, firstMoveAt - shownAt).',
      fallback: 'Premier mouvement absent ou hors trajet : référence historique.',
      variants: 'Pauses connues retirées ; arrêts incertains retirés ou gardés, comme v1.',
      good: '|erreur| <= min(480 s, max(120 s, 10 % du temps réel restant)). Wilson 95 %.',
      slices: 'Durée et vitesse depuis mouvement, sinon departedAt ou relevé 0 % ; pauses selon variante.',
      peak: 'Paris : lun-ven 07-10 h et 16-20 h ; sam 10-19 h.',
      timeline: 'Fin - t ; part = drivenM / (drivenM + leftM), sinon distance finale ; vitesse du relevé.',
      engineBasis: 'Réel/moteur : relevés engineBasis = raw seuls ; autres comptés en base moteur inconnue, hors ratio.',
      stops: 'Totaux collectés par trajet ; part arrêtée sur durée complète, attente initiale comprise.',
      initialStop: 'Début strictement avant relevé 0 % + 15 s, arrêt recouvrant ou suivant ce relevé.',
      selection: 'ETA : arrived, near, interrupted à 300 m ou moins ; autres exclusions inchangées.',
      roads: 'Routes et arrêts : tous les trajets avec tracé et premier mouvement valide, même exclus des erreurs ETA.',
      roadRatios: 'Sommes réel/moteur et réel/EONA, par groupe et période Paris ; moteur = longueur/vitesse, sans transitions.',
      roadStops: 'Sans arrêts : retrait GPS des arrêts consignés >= 10 s ; dénominateurs moteur/EONA inchangés, journaux absents exclus.',
      roadPeriod: 'Période au milieu de chaque intervalle GPS ; table EONA du lot 4 partagée avec la façade.',
    },
    trips: {
      read: trips.length, kept: kept.length, excluded,
      arrived: kept.length - near.length, near: near.length,
      nearEndDistanceM: distribution(near.map((t) => nonnegative(t.endDistanceM) ? t.endDistanceM : null)),
    },
    departure: checkpoints(kept.map((t) => judge(t)))[0],
    departureWaitS: { ...distribution(waits), unavailable: waits.filter((v) => v == null).length },
    variants,
    timeline: {
      read: kept.reduce((n, t) => n + list(t.timeline).length, 0), used: samples.length,
      engineBasis: { raw: engineBasis.raw, unknown: engineBasis.unknown },
      byProgress: profileGroups(samples, ['progress']),
      byBase: profileGroups(samples, ['base']),
      bySpeed: profileGroups(samples, ['speed']),
      byProgressBaseSpeed: profileGroups(samples, ['progress', 'base', 'speed']),
    },
    stops: stopSummary(analysisTrips.filter((t) => hasMovement(t) && (since == null || t.startedAt >= since))),
    roads: null,
  };
}

/** Paquets complets, ordre seq ; aucune reconstruction au travers d'un paquet manquant. */
export function assembleTrace(packets) {
  const ordered = [...packets].sort((a, b) => a.seq - b.seq);
  const points = [];
  for (let i = 0; i < ordered.length; i++) {
    if (ordered[i].seq !== i || !Array.isArray(ordered[i].points)) throw new Error('trace_incomplete');
    for (const point of ordered[i].points) {
      if (!Array.isArray(point) || !positive(point[0]) || !number(point[1]) || !number(point[2])
        || Math.abs(point[1]) > 90 || Math.abs(point[2]) > 180) throw new Error('trace_invalid');
      const previous = points.at(-1);
      if (previous && point[0] <= previous[0]) {
        if (point[0] === previous[0] && point[1] === previous[1] && point[2] === previous[2]) continue;
        throw new Error('trace_invalid');
      }
      points.push(point);
    }
  }
  if (points.length < 2) throw new Error('trace_short');
  return points;
}

/** Intervalles d'arrêt fusionnés : aucune seconde soustraite deux fois. */
function stopIntervals(stops) {
  const ranges = list(stops).filter((s) => positive(s?.at) && number(s.seconds) && s.seconds >= 10)
    .map((s) => [s.at, s.at + s.seconds * 1000]).sort((a, b) => a[0] - b[0]);
  const merged = [];
  for (const range of ranges) {
    const last = merged.at(-1);
    if (last && range[0] <= last[1]) last[1] = Math.max(last[1], range[1]);
    else merged.push([...range]);
  }
  return merged;
}

/** Contrat Valhalla 3.9.0 ; temps GPS, sans dépendance à la destination. */
export function roadSamples(points, matched, { stops = null } = {}) {
  const edges = matched?.edges;
  const matches = matched?.matched_points;
  if (!Array.isArray(edges) || !edges.length || !Array.isArray(matches) || matches.length !== points.length) {
    throw new Error('match_invalid');
  }
  const offsets = [0];
  for (const edge of edges) {
    const source = edge.source_percent_along ?? 0;
    const target = edge.target_percent_along ?? 1;
    if (!nonnegative(edge.length) || !nonnegative(source) || !number(target) || target > 1 || target <= source) {
      throw new Error('match_invalid');
    }
    offsets.push(offsets.at(-1) + edge.length);
  }
  const position = (match) => {
    const i = match?.edge_index;
    if (match?.type === 'unmatched' || !Number.isInteger(i) || i < 0 || i >= edges.length) return null;
    const edge = edges[i];
    const from = edge.source_percent_along ?? 0, to = edge.target_percent_along ?? 1;
    const fraction = match.distance_along_edge;
    if (!number(fraction) || fraction < from - 0.00001 || fraction > to + 0.00001) return null;
    return { i, km: offsets[i] + edge.length * Math.max(0, Math.min(1, (fraction - from) / (to - from))) };
  };
  const ranges = stopIntervals(stops);
  const byEdge = new Map();
  let skippedIntervals = 0, skippedS = 0, observedIntervals = 0;
  for (let i = 1; i < points.length; i++) {
    const seconds = (points[i][0] - points[i - 1][0]) / 1000;
    const a = position(matches[i - 1]), b = position(matches[i]);
    const discontinuity = [matches[i - 1], matches[i]].some((m) => m?.begin_route_discontinuity || m?.end_route_discontinuity);
    if (!positive(seconds) || !a || !b || b.i < a.i || b.km < a.km || discontinuity) {
      skippedIntervals++;
      skippedS += amount(seconds);
      continue;
    }
    const at = (points[i - 1][0] + points[i][0]) / 2;
    const period = periodAt(at);
    const stoppedS = Array.isArray(stops) ? ranges.reduce((sum, [from, to]) => sum
      + Math.max(0, Math.min(to, points[i][0]) - Math.max(from, points[i - 1][0])) / 1000, 0) : null;
    const km = b.km - a.km;
    observedIntervals++;
    for (let edgeIndex = a.i; edgeIndex <= b.i; edgeIndex++) {
      const length = Math.max(0, Math.min(b.km, offsets[edgeIndex + 1]) - Math.max(a.km, offsets[edgeIndex]));
      const share = km > 0 ? length / km : edgeIndex === a.i ? 1 : 0;
      if (!positive(share)) continue;
      const key = JSON.stringify([edgeIndex, period]);
      if (!byEdge.has(key)) byEdge.set(key, { index: edgeIndex, period, at, km: 0, realS: 0,
        stoppedS: stoppedS == null ? null : 0, crossed: false });
      const sample = byEdge.get(key);
      sample.km += length;
      sample.realS += seconds * share;
      if (stoppedS != null) sample.stoppedS += stoppedS * share;
      if (length > 0 && b.km >= offsets[edgeIndex + 1] - 1e-8
        && (edges[edgeIndex].target_percent_along ?? 1) === 1) sample.crossed = true;
    }
  }
  if (!observedIntervals) throw new Error('match_unusable');
  return {
    skippedIntervals, skippedS, observedIntervals,
    samples: [...byEdge.values()].map((sample) => {
      const edge = edges[sample.index];
      const signals = typeof edge.end_node?.traffic_signal === 'boolean'
        ? Number(sample.crossed && edge.end_node.traffic_signal) : null;
      const time = edgeTime(edge, sample.at, { km: sample.km, signals: signals ?? 0, period: sample.period });
      const engineS = time.engineS ?? (sample.km === 0 && positive(edge.speed) ? 0 : null);
      return {
        roadClass: ROAD_CLASSES.includes(edge.road_class) ? edge.road_class : 'inconnue',
        group: roadGroup(edge.road_class), period: sample.period,
        km: sample.km, realS: sample.realS, stoppedS: sample.stoppedS,
        engineS, eonaS: engineS == null ? null : engineS + time.extraS,
        realKmh: sample.realS > 0 ? sample.km * 3600 / sample.realS : null,
        engineKmh: positive(edge.speed) ? edge.speed : null,
        speedType: ['tagged', 'classified'].includes(edge.speed_type) ? edge.speed_type : 'inconnu',
        signals,
      };
    }),
  };
}

/** Sommes pondérées par le temps. Les médianes par arête restent secondaires. */
export function aggregateRoads(samples, { byClass = false } = {}) {
  const groups = new Map();
  for (const sample of samples) {
    const key = JSON.stringify([sample.group, sample.period, ...(byClass ? [sample.roadClass] : [])]);
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(sample);
  }
  return [...groups].sort(([a], [b]) => a.localeCompare(b)).map(([, group]) => {
    const sum = (items, key) => items.reduce((n, s) => n + s[key], 0);
    const comparable = group.filter((s) => nonnegative(s.engineS) && nonnegative(s.eonaS));
    const knownStops = comparable.filter((s) => nonnegative(s.stoppedS));
    const totals = (items, removeStops) => {
      const realS = sum(items, 'realS') - (removeStops ? sum(items, 'stoppedS') : 0);
      const engineS = sum(items, 'engineS'), eonaS = sum(items, 'eonaS');
      return { samples: items.length, km: sum(items, 'km'), realS, engineS, eonaS,
        stoppedS: removeStops ? sum(items, 'stoppedS') : null,
        realToEngineRatio: engineS > 0 ? realS / engineS : null,
        realToEonaRatio: eonaS > 0 ? realS / eonaS : null };
    };
    const signalKnown = group.filter((s) => s.signals != null);
    const signalKm = sum(signalKnown, 'km'), signals = sum(signalKnown, 'signals');
    return {
      group: group[0].group, period: group[0].period,
      ...(byClass ? { roadClass: group[0].roadClass } : {}), edges: group.length,
      km: sum(group, 'km'), realS: sum(group, 'realS'),
      withStops: totals(comparable, false), withoutStops: totals(knownStops, true),
      realKmh: distribution(group.map((s) => s.realKmh)),
      engineKmh: distribution(group.map((s) => s.engineKmh)),
      edgeRatios: {
        realToEngine: distribution(group.map((s) => s.engineS > 0 ? s.realS / s.engineS : null)),
        realToEona: distribution(group.map((s) => s.eonaS > 0 ? s.realS / s.eonaS : null)),
      },
      speedTypes: Object.fromEntries(['tagged', 'classified', 'inconnu'].map((v) => [v, group.filter((s) => s.speedType === v).length])),
      signals: signalKnown.length ? signals : null,
      signalCoverageKm: signalKm, signalsPerKm: signalKm > 0 ? signals / signalKm : null,
    };
  });
}

/** Adaptateur GPS du même client local que la façade. */
export function traceAttributes(points, options = {}) {
  return traceShape(points.map(([t, lat, lon]) => ({ lat, lon, time: t / 1000 })), {
    baseUrl: config.valhallaUrl, timeoutMs: 5000, ...options,
  });
}

/** Injection query et map matching : tests sans PostgreSQL ni Valhalla. */
export async function loadReport({ query, since = null, roads = false, matchTrace = traceAttributes } = {}) {
  const { rows } = await query(
    'SELECT account_id, trip_id, record, EXISTS(SELECT 1 FROM crowd.trip_trace trace WHERE trace.account_id = crowd.trip.account_id AND trace.trip_id = crowd.trip.trip_id) AS has_trace FROM crowd.trip WHERE ($1::timestamptz IS NULL OR started_at >= $1::timestamptz)',
    [since == null ? null : new Date(since).toISOString()],
  );
  const eligible = rows.filter((r) => hasMovement(r.record) && (since == null || r.record.startedAt >= since));
  const analysisTrips = eligible.filter((r) => r.has_trace === true).map((r) => r.record);
  const result = report(rows.map((r) => r.record), { since, analysisTrips });
  if (!roads) return result;
  const expectedS = eligible.filter((r) => r.has_trace !== false)
    .reduce((sum, r) => sum + (tripEnd(r.record) - r.record.firstMoveAt) / 1000, 0);
  const status = { attempted: 0, matched: 0, missing: 0, failed: 0, rejected: {},
    observedIntervals: 0, skippedIntervals: 0, skippedS: 0, expectedS, matchedTraceS: 0, assignedS: 0, timeCoverage: null, groups: [], classes: [] };
  const samples = [];
  for (const row of eligible) {
    if (row.has_trace === false) { status.missing++; continue; }
    const { rows: packets } = await query(
      'SELECT seq, points FROM crowd.trip_trace WHERE account_id = $1 AND trip_id = $2 ORDER BY seq',
      [row.account_id, row.trip_id],
    );
    if (!packets.length) { status.missing++; continue; }
    status.attempted++;
    if (row.has_trace !== true) analysisTrips.push(row.record);
    try {
      const points = assembleTrace(packets).filter((p) => p[0] >= departureReference(row.record).start && p[0] <= tripEnd(row.record));
      if (points.length < 2) throw new Error('trace_short');
      const profile = roadSamples(points, await matchTrace(points), { stops: row.record.stopLog });
      status.matched++;
      status.matchedTraceS += (points.at(-1)[0] - points[0][0]) / 1000;
      status.observedIntervals += profile.observedIntervals;
      status.skippedIntervals += profile.skippedIntervals;
      status.skippedS += profile.skippedS;
      samples.push(...profile.samples);
    } catch (error) {
      status.failed++;
      const reason = ['trace_incomplete', 'trace_invalid', 'trace_short', 'match_invalid', 'match_unusable', 'match_timeout', 'match_http']
        .includes(error?.message) ? error.message : 'match_unavailable';
      status.rejected[reason] = (status.rejected[reason] ?? 0) + 1;
    }
  }
  status.groups = aggregateRoads(samples);
  status.classes = aggregateRoads(samples, { byClass: true });
  result.stops = stopSummary(analysisTrips);
  status.assignedS = status.groups.reduce((sum, g) => sum + g.realS, 0);
  status.timeCoverage = expectedS > 0 ? status.assignedS / expectedS : null;
  result.roads = status;
  return result;
}

export function parseArgs(args) {
  const options = { since: null, json: false, roads: false, help: false };
  for (const arg of args) {
    if (arg === '--json') options.json = true;
    else if (arg === '--roads') options.roads = true;
    else if (arg === '--help') options.help = true;
    else if (arg.startsWith('--since=')) {
      const value = arg.slice(8);
      const date = Date.parse(value);
      if (!/^\d{4}-\d{2}-\d{2}(?:T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,3})?)?(?:Z|[+-]\d{2}:\d{2}))?$/.test(value) || !Number.isFinite(date)) {
        throw new Error('Date --since ISO invalide.');
      }
      const [year, month, day] = value.slice(0, 10).split('-').map(Number);
      if (month < 1 || month > 12 || day < 1 || day > new Date(Date.UTC(year, month, 0)).getUTCDate()) {
        throw new Error('Date --since ISO invalide.');
      }
      options.since = date;
    } else throw new Error('Option inconnue. Utiliser --help.');
  }
  return options;
}

export function formatReport(result) {
  const value = (v) => v == null ? '-' : Number(v).toFixed(2);
  const stats = (s) => 'n=' + s.n + ' méd=' + value(s.median) + ' p25=' + value(s.p25) + ' p75=' + value(s.p75);
  const error = (s) => 'n=' + s.n + ' biais=' + value(s.medianErrorS) + ' s |erreur|=' + value(s.medianAbsErrorS)
    + ' s bonnes=' + value(s.goodRate == null ? null : s.goodRate * 100) + ' % Wilson='
    + (s.wilson95 ? s.wilson95.map((v) => value(v * 100)).join('-') + ' %' : '-');
  const out = ['Rapport ETA v2'];
  if (result.since) out.push('Depuis : ' + result.since);
  const labels = {
    error: 'Erreur', departure: 'Départ', fallback: 'Repli', variants: 'Variantes',
    good: 'Bonne ETA', slices: 'Découpes', peak: 'Pointe', timeline: 'Profil', engineBasis: 'Base moteur',
    stops: 'Arrêts', initialStop: 'Arrêt initial', roads: 'Routes', selection: 'Sélection',
    roadRatios: 'Ratios routiers', roadStops: 'Arrêts routiers', roadPeriod: 'Période routière',
  };
  for (const [key, rule] of Object.entries(result.rules)) out.push(labels[key] + ' : ' + rule);
  out.push('Trajets lus=' + result.trips.read + ' retenus=' + result.trips.kept
    + ' arrivés=' + result.trips.arrived + ' proches=' + result.trips.near);
  out.push('Exclus : ' + JSON.stringify(result.trips.excluded));
  out.push('Distance de fin, arrivées proches (m) : ' + stats(result.trips.nearEndDistanceM));
  out.push('Départ brut : ' + error(result.departure.raw));
  out.push('Départ depuis mouvement : ' + error(result.departure.sinceMovement));
  out.push('Attente au départ (s) : ' + stats(result.departureWaitS) + ' absente=' + result.departureWaitS.unavailable);
  const dimensions = {
    all: 'Tous', platformVersion: 'Version', departureBase: 'Base départ',
    duration: 'Durée (min)', averageSpeed: 'Vitesse moyenne (km/h)', peak: 'Pointe', recalculated: 'Recalcul',
  };
  for (const variant of Object.values(result.variants)) {
    out.push('', variant.title);
    for (const slice of variant.slices) {
      out.push(dimensions[slice.dimension] + ' : ' + slice.value + ' ; trajets=' + slice.trips);
      for (const check of slice.checkpoints) {
        if (!check.raw.n) continue;
        out.push('  ' + check.at + ' % brut : ' + error(check.raw));
        if (check.at === 0) out.push('  0 % depuis mouvement : ' + error(check.sinceMovement));
      }
    }
  }
  out.push('', 'Profil minute : lus=' + result.timeline.read + ' retenus=' + result.timeline.used);
  out.push('Relevés moteur : bruts=' + result.timeline.engineBasis.raw
    + ' base moteur inconnue=' + result.timeline.engineBasis.unknown + ' (hors réel/moteur)');
  for (const key of ['byProgress', 'byBase', 'bySpeed', 'byProgressBaseSpeed']) {
    for (const group of result.timeline[key]) {
      const label = [
        group.progress ? 'Part ' + group.progress + ' %' : '',
        group.base ? 'base ' + group.base : '', group.speed ? 'vitesse ' + group.speed + ' km/h' : '',
      ].filter(Boolean).join(', ');
      out.push(label + ' ; réel/affiché : ' + stats(group.displayedRatio) + ' ; réel/moteur : ' + stats(group.engineRatio));
    }
  }
  out.push('', 'Arrêts : journaux=' + result.stops.tripsWithLog + ' absents=' + result.stops.tripsWithoutLog);
  out.push('Nombre par trajet : ' + stats(result.stops.countPerTrip));
  out.push('Secondes arrêtées par trajet : ' + stats(result.stops.secondsPerTrip));
  out.push('Part arrêtée par trajet (0-1) : ' + stats(result.stops.stoppedSharePerTrip));
  out.push('Durée des arrêts consignés (s) : ' + stats(result.stops.loggedStopSeconds));
  out.push('Arrêt initial : trajets=' + result.stops.initial.trips + '/' + result.stops.initial.tripsWithDepartureAndLog
    + ' ; secondes : ' + stats(result.stops.initial.seconds));
  if (result.roads) {
    const roads = result.roads;
    out.push('', 'Tracés : tentés=' + roads.attempted + ' appariés=' + roads.matched
      + ' absents=' + roads.missing + ' échecs=' + roads.failed);
    out.push('Échecs : ' + JSON.stringify(roads.rejected));
    out.push('Couverture : attendue=' + value(roads.expectedS) + ' s GPS apparié=' + value(roads.matchedTraceS)
      + ' s attribuée=' + value(roads.assignedS) + ' s part=' + value(roads.timeCoverage));
    out.push('Intervalles GPS : retenus=' + roads.observedIntervals + ' exclus=' + roads.skippedIntervals
      + ' secondes exclues=' + value(roads.skippedS));
    for (const group of [...roads.groups, ...roads.classes]) {
      out.push([group.group, group.period, group.roadClass].filter(Boolean).join(', ') + ' : ' + value(group.km) + ' km ; ' + value(group.realS) + ' s');
      out.push('  Vitesse réelle (km/h) : ' + stats(group.realKmh) + ' ; moteur : ' + stats(group.engineKmh));
      for (const [key, label] of [['withStops', 'Avec arrêts'], ['withoutStops', 'Sans arrêts >= 10 s']]) {
        const totals = group[key];
        out.push('  ' + label + ' : réel=' + value(totals.realS) + ' s moteur=' + value(totals.engineS)
          + ' s EONA=' + value(totals.eonaS) + ' s ; réel/moteur=' + value(totals.realToEngineRatio)
          + ' réel/EONA=' + value(totals.realToEonaRatio) + ' ; échantillons=' + totals.samples);
      }
      out.push('  Médianes par arête : réel/moteur ' + stats(group.edgeRatios.realToEngine)
        + ' ; réel/EONA ' + stats(group.edgeRatios.realToEona) + ' ; origine vitesse : ' + JSON.stringify(group.speedTypes));
      out.push('  Feux=' + value(group.signals) + ' ; feux/km=' + value(group.signalsPerKm)
        + ' ; couverture=' + value(group.signalCoverageKm) + ' km');
    }
  }
  return out.join('\n');
}

async function main() {
  let options;
  try { options = parseArgs(process.argv.slice(2)); }
  catch (error) { console.error(error.message); process.exitCode = 1; return; }
  if (options.help) {
    console.log('Usage : node bin/eona-eta-report-v2.js [--since=ISO] [--json] [--roads]');
    return;
  }
  let db;
  try {
    ({ db } = await import('../src/db.js'));
    const result = await loadReport({ ...options, query: (...args) => db.query(...args) });
    console.log(options.json ? JSON.stringify(result, null, 2) : formatReport(result));
  } catch {
    // Aucun message SQL, identifiant ou corps Valhalla ne sort du rapport.
    console.error('Rapport ETA v2 indisponible : lecture SQL ou analyse locale échouée.');
    process.exitCode = 1;
  } finally {
    if (db) await db.end();
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await main();
