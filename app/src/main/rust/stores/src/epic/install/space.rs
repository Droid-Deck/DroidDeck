use std::path::Path;

pub const MIB: u64 = 1024 * 1024;
pub const TRANSIENT: u64 = 64 * MIB;
pub const CACHE_CAP: u64 = 1024 * MIB;
pub const CACHE_WHEN_UNKNOWN: u64 = 256 * MIB;
pub const MIN_USEFUL_SCRATCH: u64 = 64 * MIB;

pub fn free_bytes(path: &Path) -> i64 {
    let mut probe = Some(path);
    while let Some(p) = probe {
        if p.exists() {
            return statvfs_available(p);
        }
        probe = p.parent();
    }
    -1
}

#[cfg(unix)]
fn statvfs_available(path: &Path) -> i64 {
    use std::os::unix::ffi::OsStrExt;
    let Ok(c) = std::ffi::CString::new(path.as_os_str().as_bytes()) else {
        return -1;
    };
    let mut st: libc::statvfs = unsafe { std::mem::zeroed() };
    if unsafe { libc::statvfs(c.as_ptr(), &mut st) } != 0 {
        return -1;
    }
    (st.f_bavail as u64).saturating_mul(st.f_frsize as u64).min(i64::MAX as u64) as i64
}

#[cfg(not(unix))]
fn statvfs_available(_path: &Path) -> i64 {
    -1
}

pub fn same_volume(a: &Path, b: &Path) -> bool {
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        let dev = |p: &Path| {
            let mut probe = Some(p);
            while let Some(q) = probe {
                if let Ok(m) = std::fs::metadata(q) {
                    return Some(m.dev());
                }
                probe = q.parent();
            }
            None
        };
        matches!((dev(a), dev(b)), (Some(x), Some(y)) if x == y)
    }
    #[cfg(not(unix))]
    {
        let _ = (a, b);
        false
    }
}

pub fn human(bytes: u64) -> String {
    const GIB: f64 = 1024.0 * 1024.0 * 1024.0;
    let b = bytes as f64;
    if b >= GIB {
        format!("{:.1} GB", b / GIB)
    } else if b >= MIB as f64 {
        format!("{:.1} MB", b / MIB as f64)
    } else if b >= 1024.0 {
        format!("{:.0} KB", b / 1024.0)
    } else {
        format!("{bytes} B")
    }
}

#[derive(Clone, Copy, Debug)]
pub struct PendingSize {
    pub new_size: u64,
    pub old_size: u64,
}

#[derive(Clone, Copy, Debug)]
pub struct Scratch {
    pub free: i64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SpacePlan {
    pub cache_on_scratch: bool,
    pub cache_budget: u64,
    pub overlap: Option<u64>,
    pub narrow: bool,
    pub cache_moved_beside_game: bool,
    pub game_need: u64,
    pub net_peak: u64,
}

fn fits(need: u64, free: i64) -> bool {
    free < 0 || need <= free as u64
}

fn half_of_rest(free: i64, other: u64) -> u64 {
    if free < 0 {
        return CACHE_WHEN_UNKNOWN;
    }
    ((free as u64).saturating_sub(other).saturating_sub(TRANSIENT) / 2).min(CACHE_CAP)
}

pub fn net_peak(pending: &[PendingSize]) -> u64 {
    let mut running: i128 = 0;
    let mut peak: i128 = 0;
    for p in pending {
        peak = peak.max(running + p.new_size as i128);
        running += p.new_size as i128 - p.old_size as i128;
    }
    peak.max(0) as u64
}

pub fn plan(pending: &[PendingSize], free_game: i64, scratch: Option<Scratch>, label: &str) -> Result<SpacePlan, String> {
    let peak = net_peak(pending);
    let largest_replaced = pending.iter().filter(|p| p.old_size > 0).map(|p| p.new_size).max();

    let mut cache_on_scratch = false;
    let mut moved = false;
    let mut budget = 0;
    if let Some(s) = scratch {
        let b = half_of_rest(s.free, 0);
        if s.free < 0 || b >= MIN_USEFUL_SCRATCH {
            cache_on_scratch = true;
            budget = b;
        } else {
            moved = true;
        }
    }
    if !cache_on_scratch {
        budget = half_of_rest(free_game, peak);
    }
    let cache_on_game = if cache_on_scratch { 0 } else { budget + TRANSIENT };
    let overlap = largest_replaced.map(|largest| {
        if free_game < 0 {
            largest
        } else {
            largest.max((free_game as u64).saturating_sub(peak).saturating_sub(cache_on_game))
        }
    });
    let game_need = peak + overlap.unwrap_or(0) + cache_on_game;

    if let Some(s) = scratch.filter(|_| cache_on_scratch) {
        let need = budget + TRANSIENT;
        if !fits(need, s.free) {
            return Err(format!(
                "Not enough internal space for the download cache: it needs {}, {} is free",
                human(need),
                human(s.free.max(0) as u64)
            ));
        }
    }
    if fits(game_need, free_game) {
        return Ok(SpacePlan {
            cache_on_scratch,
            cache_budget: budget,
            overlap,
            narrow: false,
            cache_moved_beside_game: moved,
            game_need,
            net_peak: peak,
        });
    }
    if fits(peak, free_game) {
        return Ok(SpacePlan {
            cache_on_scratch: false,
            cache_budget: 0,
            overlap: Some(0),
            narrow: true,
            cache_moved_beside_game: moved,
            game_need: peak,
            net_peak: peak,
        });
    }
    let free = free_game.max(0) as u64;
    let mut message = format!(
        "Not enough space on {label} for this install: it needs {}, {} is free ({} short)",
        human(peak),
        human(free),
        human(peak.saturating_sub(free))
    );
    if moved {
        message.push_str(". The download cache had to move beside the game because internal storage is full");
    }
    Err(message)
}

#[cfg(test)]
mod tests {
    use super::*;

    const GB: u64 = 1024 * MIB;

    fn fresh(sizes: &[u64]) -> Vec<PendingSize> {
        sizes.iter().map(|&s| PendingSize { new_size: s, old_size: 0 }).collect()
    }

    #[test]
    fn net_peak_credits_old_copies_at_their_swap() {
        assert_eq!(net_peak(&fresh(&[10, 20])), 30);
        let update = vec![
            PendingSize { new_size: 100, old_size: 90 },
            PendingSize { new_size: 50, old_size: 60 },
        ];
        assert_eq!(net_peak(&update), 100);
        assert_eq!(net_peak(&[PendingSize { new_size: 5, old_size: 500 }]), 5);
        assert_eq!(net_peak(&[]), 0);
    }

    #[test]
    fn a_fresh_install_has_no_overlap_limit() {
        let p = plan(&fresh(&[2 * GB]), (10 * GB) as i64, None, "Internal").unwrap();
        assert_eq!(p.overlap, None);
        assert!(!p.narrow && !p.cache_on_scratch);
        assert_eq!(p.cache_budget, CACHE_CAP);
    }

    #[test]
    fn unknown_free_space_is_never_no_space() {
        let p = plan(&fresh(&[50 * GB]), -1, Some(Scratch { free: -1 }), "Card").unwrap();
        assert!(p.cache_on_scratch);
        assert_eq!(p.cache_budget, CACHE_WHEN_UNKNOWN);
        let update = vec![PendingSize { new_size: GB, old_size: GB }];
        assert_eq!(plan(&update, -1, None, "Card").unwrap().overlap, Some(GB));
    }

    #[test]
    fn scratch_too_small_moves_the_cache_beside_the_game() {
        let p = plan(&fresh(&[GB]), (20 * GB) as i64, Some(Scratch { free: (100 * MIB) as i64 }), "Card").unwrap();
        assert!(!p.cache_on_scratch && p.cache_moved_beside_game);
    }

    #[test]
    fn tight_space_falls_back_to_one_file_at_a_time() {
        let pending = fresh(&[GB, GB]);
        let free = (2 * GB + 10 * MIB) as i64;
        let p = plan(&pending, free, None, "Internal").unwrap();
        assert!(p.narrow);
        assert_eq!(p.cache_budget, 0);
        assert_eq!(p.game_need, 2 * GB);
    }

    #[test]
    fn not_fitting_names_the_storage_and_shortfall() {
        let err = plan(&fresh(&[3 * GB]), GB as i64, None, "SD card").unwrap_err();
        assert!(err.contains("SD card"), "{err}");
        assert!(err.contains("2.0 GB short"), "{err}");
        assert!(plan(&fresh(&[1]), 0, None, "x").is_err());
        assert!(plan(&fresh(&[0]), 0, None, "x").is_ok());
    }

    #[test]
    fn updates_get_an_overlap_budget_of_at_least_the_largest_replaced_file() {
        let update = vec![
            PendingSize { new_size: GB, old_size: GB },
            PendingSize { new_size: 2 * GB, old_size: 2 * GB },
        ];
        let p = plan(&update, (40 * GB) as i64, None, "Internal").unwrap();
        assert!(p.overlap.unwrap() >= 2 * GB);
        assert!(!p.narrow);
    }
}
