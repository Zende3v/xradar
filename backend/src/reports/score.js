import { config } from '../config.js';

/**
 * The report score, shared by the road events (reports/store.js) and the speed-limit
 * maintenance (speedlimits/store.js). A model is { factors, baseDurationMs, persistent? }.
 *
 *   k          = ln(factors / minimumScore)
 *   timeScore  = factors × exp(-(k × age) / baseDurationMs)
 *
 * so a score is exactly the minimum when its age reaches the base duration. The crowd
 * scales it instead of moving the clock: × confirmationBonus × contradictionPenalty.
 * The constants live in config (reportScoreMinimum, reportConfirmStep…).
 */

/** min(1 + 0.10 × √confirmations, 1.40). */
export function confirmationBonus(confirmations) {
  return Math.min(1 + config.reportConfirmStep * Math.sqrt(confirmations || 0), config.reportConfirmCap);
}

/** 1 / (1 + 0.25 × √contradictions). */
export function contradictionPenalty(contradictions) {
  return 1 / (1 + config.reportContradictionStep * Math.sqrt(contradictions || 0));
}

/** factors × exp(-(k × age) / baseDuration); a persistent model never decays. */
export function timeScore(model, ageMs) {
  if (model.persistent || model.baseDurationMs == null) return model.factors;
  const k = Math.log(model.factors / config.reportScoreMinimum);
  return model.factors * Math.exp(-(k * Math.max(0, ageMs)) / model.baseDurationMs);
}

/** Time and crowd together: what something reported is worth [ageMs] after [since]. */
export function crowdScore(model, ageMs, confirmations, contradictions) {
  return timeScore(model, ageMs) * confirmationBonus(confirmations) * contradictionPenalty(contradictions);
}

/** When that score will cross the minimum, counting from [since]. */
export function expiryFor(model, since, confirmations, contradictions) {
  if (model.persistent || model.baseDurationMs == null) return since + config.reportPermanentMs;
  const crowd = confirmationBonus(confirmations) * contradictionPenalty(contradictions);
  const ratio = (model.factors * crowd) / config.reportScoreMinimum;
  if (!(ratio > 1)) return since; // already worthless
  const k = Math.log(model.factors / config.reportScoreMinimum);
  return since + (model.baseDurationMs * Math.log(ratio)) / k;
}
