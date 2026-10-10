//! The numbers and the two small types [`fetch_core`](crate::fetch_core) shares with the Steam
//! depot writer it was carved out of.
//!
//! In Bannerlator these live in `depot_writer.rs` and `cdn_client.rs`, next to the Steam CM and
//! depot code, so that the Steam engine and the store engines run on ONE set of tunables and their
//! `fetch-window` log lines stay A/B-comparable. DroidDeck ships no Steam depot engine (the Steam
//! client runs natively in the Linux runtime), so only this slice came across: the values are
//! copied verbatim and keep their names, which is what lets a GOG/Epic/Amazon log from either app
//! be read against the same tuning notes.
//!
//! The important behaviours are structural (slow-start ramp -> hold at plateau -> back off on REAL
//! errors, per-host cap, hard byte budget), not the exact numbers. The r3/r4 notes below record
//! why each number is what it is, because every one of them was arrived at by a device run that
//! went wrong the other way.

use std::time::Duration;

/// Default `User-Agent` of the pooled client. Every store engine overrides it per request with
/// the agent its own launcher sends (`FetchOptions::headers`), so this value is only what a
/// request without an explicit header would carry.
pub const USER_AGENT: &str = "Valve/Steam HTTP Client 1.0";

/// TCP connect deadline for every request the pooled client makes.
pub fn connect_timeout() -> Duration {
    Duration::from_secs(15)
}

/// Classified fetch failure so the fetch driver can back off proportionally (429/5xx/timeout/reset
/// shrink the window + cool the host harder than a one-off protocol error).
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum FetchFailKind {
    Timeout,
    RateLimited,
    ServerFault,
    Connect,
    Other,
}

#[derive(Clone, Debug)]
pub struct AsyncFetchError {
    pub message: String,
    pub kind: FetchFailKind,
}

/// Attempts per item before the run gives up on it (fetch failures and sink retries both count).
pub const MAX_CHUNK_ATTEMPTS: u32 = 5;

/// Floor for the in-flight byte budget — the pre-r4 fixed value, so no run ever gets *less*
/// buffered memory than it had before the budget started scaling. See [`inflight_budget_bytes`].
pub const FETCH_INFLIGHT_BUDGET_FLOOR_BYTES: u64 = 24 * 1024 * 1024;

/// Hard ceiling for the in-flight byte budget. This is THE OOM guard: whatever the tier, the host
/// count or the chunk size, the RAW bytes in memory for one run can never exceed this. 96 MiB = 4×
/// the old fixed budget and still a small fraction of the heap an Android game installer runs in,
/// and it is only ever reached by a fast-tier run whose CDN pool is large enough to support a 64+
/// window. Raising this is the one change in the fetch layer that can reintroduce OOM, so it stays
/// fixed and small.
pub const FETCH_INFLIGHT_BUDGET_HARD_CAP_BYTES: u64 = 96 * 1024 * 1024;

/// Assumed RAW (on-the-wire, still-compressed) size of one chunk when sizing the budget. Store
/// chunks are ~1 MiB; this is the same nominal [`NOMINAL_CHUNK_RESERVE_BYTES`] uses at dispatch.
pub const TYPICAL_CHUNK_BYTES: u64 = NOMINAL_CHUNK_RESERVE_BYTES;

/// Headroom the budget carries over "one chunk per window slot" (3/2 = 1.5×), as a rational so the
/// arithmetic stays integer. The window is a count of concurrent *requests*; a slot's bytes are held
/// from DISPATCH until its WRITE completes, so at any instant some slots hold a fetched-but-not-yet-
/// written chunk while others hold a reservation for a fetch still on the wire. 1.5× covers that
/// overlap (and the occasional above-nominal chunk) without doubling the memory bound.
pub const BUDGET_HEADROOM_NUM: u64 = 3;
pub const BUDGET_HEADROOM_DEN: u64 = 2;

/// Byte budget for the *compressed* bytes buffered in the fetch→process channel, scaled to the
/// window ceiling this run can actually reach.
///
/// The decouple channel is bounded by BYTES, not chunk count: a count cap makes in-flight memory
/// swing wildly with chunk size (a 4-chunk cap is ~4 MB for 1 MB chunks but ~120 MB for 30 MB
/// chunks). Budgeting bytes keeps the heap bounded regardless of game size while giving the fetch
/// pool bandwidth-delay-product headroom so the socket stays busy while process workers
/// decompress+write.
///
/// r4: a device run proved a fixed 24 MiB budget becomes the binding constraint the moment the
/// adaptive window works — on a 1 Gbps line the window grew to 42 but the budget admitted only ~24
/// chunks, so `in_flight` stalled at 23–28 with `budget_stalls` in the hundreds and
/// `host_stalls=0`. A budget that refuses slots the window has already decided are safe is not a
/// memory guard, it is a throttle. So the budget scales WITH the effective ceiling (tier ∧
/// hosts×per-host-cap ∧ hard cap) and is clamped between the old fixed value and a hard cap. Low
/// tiers are unaffected; a fast link gets a budget that stops binding before the window does.
///
/// Budget only the RAW bytes actually queued (reserved at DISPATCH, released on WRITE-COMPLETE); the
/// decoded expansion is transient inside a process worker and never accumulates. See
/// [`budget_admits`] for the single-chunk deadlock guard.
pub fn inflight_budget_bytes(effective_ceiling: usize) -> u64 {
    (effective_ceiling as u64)
        .saturating_mul(TYPICAL_CHUNK_BYTES)
        .saturating_mul(BUDGET_HEADROOM_NUM)
        .saturating_div(BUDGET_HEADROOM_DEN)
        .clamp(
            FETCH_INFLIGHT_BUDGET_FLOOR_BYTES,
            FETCH_INFLIGHT_BUDGET_HARD_CAP_BYTES,
        )
}

// ─── Adaptive-window tuning ────────────────────────────────────────────────────────────────────
// r3 retune: the first device run pinned the window at the floor on a 1 Gbps link. The controller
// shrank on throughput dips and latency jitter (normal CDN noise) as well as on errors, shrank a flat
// −6 on every single error, and only grew when throughput rose >10% in a 700 ms sample. The shrinks
// dominated, so the window ratcheted to the floor and stayed there. r3 makes growth eager and makes
// ERRORS the only shrink signal.

/// Max concurrent requests to any single CDN host. CDNs throttle/reset per host, so a big window
/// is reached by spreading across MORE hosts, never by piling onto one.
pub const PER_HOST_CAP: usize = 6;
/// Initial in-flight window. Slow-start doubles from here, so this only sets how fast the first
/// couple of probes get useful — the ceiling still comes from the tier × hosts clamp.
pub const BOOTSTRAP_WINDOW: usize = 8;
/// Hard safety ceiling on the window regardless of tier (also clamped to distinct-hosts × per-host).
pub const WINDOW_HARD_CAP: usize = 256;
/// The window never shrinks below this (keeps a little pipelining even on a rough link).
pub const WINDOW_MIN_FLOOR: usize = 2;
/// Additive growth step once slow-start has ended (after the first real back-off).
pub const WINDOW_STEP_UP: usize = 4;
/// Slow-start multiplier: until the first back-off the window DOUBLES each healthy probe, so a fast
/// link reaches a useful window in seconds instead of never.
pub const WINDOW_SLOW_START_FACTOR: usize = 2;
/// Proportional shrink on a real error signal (never a flat step: a flat −6 slammed a small window
/// straight to the floor). Always at least −1 while above the floor.
pub const WINDOW_SHRINK_FACTOR: f64 = 0.75;
/// How often the adaptive window re-evaluates. Long enough that one jittery sample cannot drive a
/// decision; decisions use the EWMA, not the raw sample.
pub const WINDOW_PROBE_INTERVAL_MS: u64 = 2_000;
/// After a back-off, hold the window (no growth) for this long — hysteresis against oscillation.
pub const WINDOW_COOLDOWN_MS: u64 = 3_000;
/// EWMA smoothing for the measured throughput samples (higher = more responsive).
pub const WINDOW_BPS_EWMA_ALPHA: f64 = 0.4;
/// Throughput above `best × (1 + this)` counts as a genuine improvement (keeps slow-start climbing).
pub const WINDOW_IMPROVE_EPS: f64 = 0.05;
/// Throughput below `best × (1 − this)` counts as falling — growth STOPS (hold). It never shrinks:
/// only real errors shrink.
pub const WINDOW_DECLINE_EPS: f64 = 0.15;
/// Error-rate thresholds over one probe window: above HIGH shrinks; growth needs at/below LOW
/// (in between = hold).
pub const WINDOW_ERR_RATE_HIGH: f64 = 0.10;
pub const WINDOW_ERR_RATE_LOW: f64 = 0.05;
/// Errors inside ONE probe interval that trigger an immediate (don't-wait-for-the-probe) shrink. A
/// single stray timeout no longer moves the window; a burst does. A 429 always shrinks immediately.
pub const WINDOW_ERR_BURST_IMMEDIATE: u32 = 3;
/// Consecutive non-improving probes still allowed to grow before declaring a plateau — throughput
/// lags a window change by a probe or two, so one flat sample must not stop the ramp.
pub const WINDOW_PLATEAU_PATIENCE: u32 = 2;
/// Once plateaued, re-arm the ramp this long later (doubling, capped) to re-probe for headroom if the
/// link improved. Bounded re-probing, not a creep: a weak link answers with errors and shrinks back.
pub const WINDOW_PLATEAU_REARM_MS: u64 = 30_000;
pub const WINDOW_PLATEAU_REARM_MAX_MS: u64 = 300_000;
/// Even when the window does not change, emit one `fetch-window` line every N probes so a stuck
/// window explains itself in the log (`reason=` says why it is not moving).
pub const WINDOW_LOG_EVERY_PROBES: u32 = 5;
/// Every Nth exploit dispatch is instead an exploration pick (epsilon-greedy ≈ 1/N) so a
/// demoted-but-recovered host is retried rather than starved forever.
pub const SERVER_EXPLORE_EVERY: u64 = 8;
/// Reservation for an item whose compressed size the manifest didn't carry (keeps the byte budget
/// honest at dispatch time).
pub const NOMINAL_CHUNK_RESERVE_BYTES: u64 = 1024 * 1024;
/// Extra host cooldown floor when a host answers 429 (rate-limited) — back off harder than a
/// one-off timeout.
pub const RATE_LIMIT_COOLDOWN_MS: u64 = 5_000;

/// Exponential back-off between attempts of one item: 0, 300, 600, 1200, 2400 ms … capped at 4 s.
pub fn retry_backoff_millis(attempt: u32) -> u64 {
    if attempt == 0 {
        return 0;
    }
    (300u64 << (attempt - 1)).min(4000)
}

/// Byte-budget admission test for the fetch→process channel. Always admits when nothing is in
/// flight (`in_flight == 0`) so a single item LARGER than the whole budget can never deadlock — it
/// simply waits until the channel drains, then goes through alone.
#[inline]
pub fn budget_admits(in_flight: u64, raw_len: u64, budget: u64) -> bool {
    in_flight == 0 || in_flight.saturating_add(raw_len) <= budget
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn backoff_is_exponential_and_capped() {
        assert_eq!(retry_backoff_millis(0), 0);
        assert_eq!(retry_backoff_millis(1), 300);
        assert_eq!(retry_backoff_millis(2), 600);
        assert_eq!(retry_backoff_millis(4), 2400);
        assert_eq!(retry_backoff_millis(5), 4000);
        assert_eq!(retry_backoff_millis(12), 4000);
    }

    #[test]
    fn budget_scales_between_floor_and_cap() {
        assert_eq!(inflight_budget_bytes(1), FETCH_INFLIGHT_BUDGET_FLOOR_BYTES);
        assert_eq!(inflight_budget_bytes(8), FETCH_INFLIGHT_BUDGET_FLOOR_BYTES);
        assert_eq!(inflight_budget_bytes(32), 48 * 1024 * 1024);
        assert_eq!(inflight_budget_bytes(1024), FETCH_INFLIGHT_BUDGET_HARD_CAP_BYTES);
    }

    #[test]
    fn single_oversized_item_is_always_admitted() {
        assert!(budget_admits(0, u64::MAX, 1));
        assert!(budget_admits(10, 5, 15));
        assert!(!budget_admits(10, 6, 15));
    }
}
