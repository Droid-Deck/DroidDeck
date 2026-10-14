
use std::time::Duration;

pub const USER_AGENT: &str = "Valve/Steam HTTP Client 1.0";

pub fn connect_timeout() -> Duration {
    Duration::from_secs(15)
}

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

pub const MAX_CHUNK_ATTEMPTS: u32 = 5;

pub const FETCH_INFLIGHT_BUDGET_FLOOR_BYTES: u64 = 24 * 1024 * 1024;

pub const FETCH_INFLIGHT_BUDGET_HARD_CAP_BYTES: u64 = 96 * 1024 * 1024;

pub const TYPICAL_CHUNK_BYTES: u64 = NOMINAL_CHUNK_RESERVE_BYTES;

pub const BUDGET_HEADROOM_NUM: u64 = 3;
pub const BUDGET_HEADROOM_DEN: u64 = 2;

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


pub const PER_HOST_CAP: usize = 6;
pub const BOOTSTRAP_WINDOW: usize = 8;
pub const WINDOW_HARD_CAP: usize = 256;
pub const WINDOW_MIN_FLOOR: usize = 2;
pub const WINDOW_STEP_UP: usize = 4;
pub const WINDOW_SLOW_START_FACTOR: usize = 2;
pub const WINDOW_SHRINK_FACTOR: f64 = 0.75;
pub const WINDOW_PROBE_INTERVAL_MS: u64 = 2_000;
pub const WINDOW_COOLDOWN_MS: u64 = 3_000;
pub const WINDOW_BPS_EWMA_ALPHA: f64 = 0.4;
pub const WINDOW_IMPROVE_EPS: f64 = 0.05;
pub const WINDOW_DECLINE_EPS: f64 = 0.15;
pub const WINDOW_ERR_RATE_HIGH: f64 = 0.10;
pub const WINDOW_ERR_RATE_LOW: f64 = 0.05;
pub const WINDOW_ERR_BURST_IMMEDIATE: u32 = 3;
pub const WINDOW_PLATEAU_PATIENCE: u32 = 2;
pub const WINDOW_PLATEAU_REARM_MS: u64 = 30_000;
pub const WINDOW_PLATEAU_REARM_MAX_MS: u64 = 300_000;
pub const WINDOW_LOG_EVERY_PROBES: u32 = 5;
pub const SERVER_EXPLORE_EVERY: u64 = 8;
pub const NOMINAL_CHUNK_RESERVE_BYTES: u64 = 1024 * 1024;
pub const RATE_LIMIT_COOLDOWN_MS: u64 = 5_000;

pub fn retry_backoff_millis(attempt: u32) -> u64 {
    if attempt == 0 {
        return 0;
    }
    (300u64 << (attempt - 1)).min(4000)
}

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
