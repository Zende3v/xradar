/**
 * Fusion des retards superposés (contrat commun backend / EonaCore, 20/10).
 * Les sections connues restent intactes. Chaque observation à retard positif couvre [fromM, toM),
 * densité = secondes / mètre, largeur et densité finies. Sur chaque intervalle entre bornes :
 * complément = max(0, densité maximale des observations actives − somme des densités connues actives).
 * Deux observations du même endroit ne s'additionnent jamais ; des zones disjointes oui.
 * Le complément reste sur sa portion réelle, avec les champs de l'observation dominante.
 * Arrondi cumulé : total des compléments = total exact arrondi, sans perte par fragment.
 */

// Complément exact (s) en dessous : ignoré. Évite une pièce fantôme d'arrondi flottant.
const EPS_SECONDS = 1e-9;
// Pièces contiguës de même source : fusionnées si densités égales à ce facteur près de la dominante.
const EPS_RELATIVE = 1e-12;

/**
 * [sections] (connues, intactes) suivies des compléments de [observations], triés par début.
 * Marqueurs à 0 s : fermeture (`level: "closed"`) toujours ; avec [keepFree], observation sans
 * retard (absent, null ou zéro). Bornes finies, fin ≥ début (point admis). Aucune entrée modifiée.
 */
export function fuseDelays(sections, observations, { keepFree = false } = {}) {
  const known = [];
  for (const s of sections) {
    const d = densityOf(s);
    if (d > 0) known.push({ fromM: s.fromM, toM: s.toM, d });
  }
  const zones = [];
  const markers = [];
  for (const zone of observations) {
    const d = densityOf(zone);
    if (d > 0) zones.push({ fromM: zone.fromM, toM: zone.toM, d, zone, key: stableKey(zone) });
    if (!markerRange(zone)) continue;
    if (zone.level === 'closed' || (keepFree && isFreeDelay(zone.delayS))) {
      markers.push({ ...zone, delayS: 0 });
    }
  }
  const extra = complements(known, zones);
  return sections.concat(extra, dedupeMarkers(markers)).sort((a, b) => a.fromM - b.fromM);
}

/** Pièces exactes puis arrondies : [{ ...observation dominante, fromM, toM, delayS }]. */
function complements(known, zones) {
  if (!zones.length) return [];
  const bounds = [...new Set(zones.flatMap((z) => [z.fromM, z.toM]).concat(known.flatMap((k) => [k.fromM, k.toM])))]
    .sort((a, b) => a - b);
  // Ordre fixe des connus (borne puis densité) : sommes flottantes stables, quel que soit l'ordre reçu.
  const starts = [...known].sort((a, b) => cmpNum(a.fromM, b.fromM) || cmpNum(a.d, b.d));
  const ends = [...known].sort((a, b) => cmpNum(a.toM, b.toM) || cmpNum(a.d, b.d));
  const opening = [...zones].sort((a, b) => a.fromM - b.fromM);
  const heap = new Heap((x, y) => rank(x, y) < 0);
  let si = 0;
  let ei = 0;
  let oi = 0;
  let knownSum = 0;
  let knownCount = 0;
  const pieces = [];

  for (let i = 0; i < bounds.length - 1; i++) {
    const a = bounds[i];
    const b = bounds[i + 1];
    while (si < starts.length && starts[si].fromM <= a) { knownSum += starts[si++].d; knownCount++; }
    while (ei < ends.length && ends[ei].toM <= a) { knownSum -= ends[ei++].d; knownCount--; }
    // Somme remise à zéro quand plus rien n'est actif : aucune dérive flottante transportée.
    if (knownCount === 0) knownSum = 0;
    while (oi < opening.length && opening[oi].fromM <= a) heap.push(opening[oi++]);
    while (heap.size && heap.top.toM <= a) heap.pop();
    if (!heap.size) continue;
    const top = heap.top;
    const c = top.d - Math.max(0, knownSum);
    const exactS = c * (b - a);
    if (!(exactS > EPS_SECONDS)) continue;
    const last = pieces[pieces.length - 1];
    // Même source, même densité, contiguë : une seule pièce, rien d'étalé.
    if (last && last.toM === a && last.origin === top && Math.abs(last.c - c) <= EPS_RELATIVE * top.d) {
      last.toM = b;
      last.exactS += exactS;
    } else {
      pieces.push({ fromM: a, toM: b, c, exactS, origin: top });
    }
  }

  // Arrondi cumulé : chaque pièce reçoit round(cumul) − round(cumul précédent).
  const out = [];
  let exact = 0;
  let rounded = 0;
  for (const piece of pieces) {
    exact += piece.exactS;
    const next = Math.round(exact);
    const delayS = next - rounded;
    rounded = next;
    if (delayS > 0) out.push({ ...piece.origin.zone, fromM: piece.fromM, toM: piece.toM, delayS });
  }
  return out;
}

/** Densité d'une section à retard valide (s/m), sinon 0. Largeur et densité finies exigées. */
function densityOf(s) {
  if (!finiteRange(s)) return 0;
  const delayS = s.delayS;
  if (typeof delayS !== 'number' || !Number.isFinite(delayS) || delayS <= 0) return 0;
  const width = s.toM - s.fromM;
  if (!Number.isFinite(width) || !(width > 0)) return 0;
  const d = delayS / width;
  return Number.isFinite(d) ? d : 0;
}

function finiteRange(s) {
  return s != null && Number.isFinite(s.fromM) && Number.isFinite(s.toM) && s.toM > s.fromM;
}

/** Marqueur admis : bornes finies, fin ≥ début. Intervalle inversé rejeté, jamais retourné. */
function markerRange(s) {
  return s != null && Number.isFinite(s.fromM) && Number.isFinite(s.toM) && s.toM >= s.fromM;
}

/** Sans retard : absent, null ou zéro. Négatif ou invalide : aucun marqueur libre. */
function isFreeDelay(delayS) {
  return delayS == null || delayS === 0;
}

/** Doublons exacts de marqueurs retirés, ordre fixe : résultat indépendant des répétitions et de l'ordre. */
function dedupeMarkers(markers) {
  const byKey = new Map();
  for (const m of markers) byKey.set(stableKey(m), m);
  return [...byKey.entries()]
    .sort(([ka, a], [kb, b]) => a.fromM - b.fromM || cmpText(ka, kb))
    .map(([, m]) => m);
}

/**
 * Rang commun backend / Swift, négatif si x domine : densité décroissante, puis début, fin, retard
 * croissants, source, niveau, nature (absente d'abord). Repli backend sur champs supplémentaires.
 */
function rank(x, y) {
  return cmpNum(y.d, x.d)
    || cmpNum(x.fromM, y.fromM)
    || cmpNum(x.toM, y.toM)
    || cmpNum(x.zone.delayS, y.zone.delayS)
    || cmpText(x.zone.source, y.zone.source)
    || cmpText(x.zone.level, y.zone.level)
    || cmpText(x.zone.kind, y.zone.kind)
    || cmpText(x.key, y.key);
}

function cmpNum(a, b) {
  return a < b ? -1 : a > b ? 1 : 0;
}

/** Ordre lexicographique ; valeur absente avant présente. */
function cmpText(a, b) {
  if (a == null || b == null) return (a == null ? 0 : 1) - (b == null ? 0 : 1);
  const sa = String(a);
  const sb = String(b);
  return sa < sb ? -1 : sa > sb ? 1 : 0;
}

function stableKey(obj) {
  return JSON.stringify(Object.keys(obj).sort().map((k) => [k, obj[k]]));
}

/** Tas binaire minimal : [better(x, y)] vrai si x passe avant y. */
class Heap {
  constructor(better) {
    this.better = better;
    this.items = [];
  }

  get size() { return this.items.length; }

  get top() { return this.items[0]; }

  push(item) {
    const items = this.items;
    items.push(item);
    let i = items.length - 1;
    while (i > 0) {
      const p = (i - 1) >> 1;
      if (!this.better(items[i], items[p])) break;
      [items[i], items[p]] = [items[p], items[i]];
      i = p;
    }
  }

  pop() {
    const items = this.items;
    const last = items.pop();
    if (!items.length) return;
    items[0] = last;
    let i = 0;
    for (;;) {
      const l = 2 * i + 1;
      const r = l + 1;
      let best = i;
      if (l < items.length && this.better(items[l], items[best])) best = l;
      if (r < items.length && this.better(items[r], items[best])) best = r;
      if (best === i) break;
      [items[i], items[best]] = [items[best], items[i]];
      i = best;
    }
  }
}
