use std::collections::{BTreeSet, HashMap};

pub const NONE: u32 = u32::MAX;

#[derive(Clone, Debug)]
pub struct PlanPart {
    pub chunk: u32,
    pub chunk_offset: u32,
    pub size: u32,
    pub file_offset: u64,
}

#[derive(Clone, Debug)]
pub struct PlanFile {
    pub size: u64,
    pub parts: Vec<PlanPart>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Producer {
    pub chunk: u32,
    pub anchor: u32,
    pub preloaded: bool,
    pub copy: u32,
    pub uses: Vec<u32>,
}

#[derive(Debug)]
pub struct Schedule {
    pub file_start: Vec<u32>,
    pub use_file: Vec<u32>,
    pub use_producer: Vec<u32>,
    pub producers: Vec<Producer>,
    pub fetch: Vec<u32>,
    pub peak_cache: u64,
    pub refetches: u32,
}

impl Schedule {
    pub fn uses_of_file(&self, file: usize) -> std::ops::Range<usize> {
        self.file_start[file] as usize..self.file_start[file + 1] as usize
    }
}

pub fn build(files: &[PlanFile], windows: &[u32], budget: u64, cached: &[u32]) -> Schedule {
    let mut file_start = Vec::with_capacity(files.len() + 1);
    let mut use_chunk = Vec::new();
    let mut use_file = Vec::new();
    for (f, file) in files.iter().enumerate() {
        file_start.push(use_chunk.len() as u32);
        for part in &file.parts {
            use_chunk.push(part.chunk);
            use_file.push(f as u32);
        }
    }
    file_start.push(use_chunk.len() as u32);
    let total = use_chunk.len();

    let mut next_use = vec![NONE; total];
    let mut first_use: HashMap<u32, u32> = HashMap::new();
    {
        let mut seen: HashMap<u32, u32> = HashMap::new();
        for u in (0..total).rev() {
            let c = use_chunk[u];
            if let Some(&n) = seen.get(&c) {
                next_use[u] = n;
            }
            seen.insert(c, u as u32);
        }
        for (c, u) in seen {
            first_use.insert(c, u);
        }
    }

    let window = |c: u32| windows.get(c as usize).copied().unwrap_or(0) as u64;
    let mut producers: Vec<Producer> = Vec::new();
    let mut fetch = Vec::new();
    let mut use_producer = vec![NONE; total];
    let mut active: HashMap<u32, u32> = HashMap::new();
    let mut copies: HashMap<u32, u32> = HashMap::new();
    let mut resident: HashMap<u32, u32> = HashMap::new();
    let mut by_next: BTreeSet<(u32, u32)> = BTreeSet::new();
    let mut occupancy = 0u64;
    let mut peak = 0u64;

    let mut preload: Vec<(u32, u32)> = cached
        .iter()
        .filter_map(|&c| first_use.get(&c).map(|&u| (u, c)))
        .collect();
    preload.sort_unstable();
    preload.dedup_by_key(|(_, c)| *c);
    for (u, c) in preload {
        if occupancy + window(c) > budget {
            continue;
        }
        occupancy += window(c);
        let id = producers.len() as u32;
        producers.push(Producer { chunk: c, anchor: NONE, preloaded: true, copy: 0, uses: Vec::new() });
        copies.insert(c, 1);
        active.insert(c, id);
        resident.insert(c, u);
        by_next.insert((u, c));
    }
    peak = peak.max(occupancy);

    for u in 0..total {
        let (c, f) = (use_chunk[u], use_file[u]);
        let valid = active
            .get(&c)
            .copied()
            .filter(|&p| producers[p as usize].anchor == f || resident.contains_key(&c));
        let p = match valid {
            Some(p) => p,
            None => {
                let id = producers.len() as u32;
                let copy = copies.entry(c).or_insert(0);
                producers.push(Producer { chunk: c, anchor: f, preloaded: false, copy: *copy, uses: Vec::new() });
                *copy += 1;
                fetch.push(id);
                active.insert(c, id);
                id
            }
        };
        producers[p as usize].uses.push(u as u32);
        use_producer[u] = p;

        let next = next_use[u];
        if next == NONE {
            if let Some(pos) = resident.remove(&c) {
                by_next.remove(&(pos, c));
                occupancy -= window(c);
            }
            active.remove(&c);
            continue;
        }
        if let Some(pos) = resident.get_mut(&c) {
            by_next.remove(&(*pos, c));
            *pos = next;
            by_next.insert((next, c));
            continue;
        }
        if use_file[next as usize] == f {
            continue;
        }
        loop {
            if occupancy + window(c) <= budget {
                occupancy += window(c);
                resident.insert(c, next);
                by_next.insert((next, c));
                peak = peak.max(occupancy);
                break;
            }
            match by_next.iter().next_back().copied() {
                Some((far, victim)) if far > next => {
                    by_next.remove(&(far, victim));
                    resident.remove(&victim);
                    occupancy -= window(victim);
                }
                _ => break,
            }
        }
    }

    let refetches = producers.iter().filter(|p| !p.preloaded && p.copy > 0).count() as u32;
    Schedule { file_start, use_file, use_producer, producers, fetch, peak_cache: peak, refetches }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn file(chunks: &[u32]) -> PlanFile {
        let mut off = 0;
        PlanFile {
            size: chunks.len() as u64 * 10,
            parts: chunks
                .iter()
                .map(|&c| {
                    let p = PlanPart { chunk: c, chunk_offset: 0, size: 10, file_offset: off };
                    off += 10;
                    p
                })
                .collect(),
        }
    }

    #[test]
    fn single_use_chunks_are_fetched_once_and_never_cached() {
        let s = build(&[file(&[0, 1]), file(&[2])], &[10, 10, 10], 1000, &[]);
        assert_eq!(s.fetch.len(), 3);
        assert_eq!(s.peak_cache, 0);
        assert_eq!(s.refetches, 0);
        assert_eq!(s.uses_of_file(1), 2..3);
    }

    #[test]
    fn repeats_inside_one_file_need_no_cache() {
        let s = build(&[file(&[0, 0, 0])], &[10], 0, &[]);
        assert_eq!(s.fetch.len(), 1);
        assert_eq!(s.producers[0].uses, vec![0, 1, 2]);
    }

    #[test]
    fn a_chunk_shared_across_files_is_kept_within_budget() {
        let s = build(&[file(&[0, 1]), file(&[0])], &[10, 10], 10, &[]);
        assert_eq!(s.fetch.len(), 2);
        assert_eq!(s.peak_cache, 10);
        assert_eq!(s.use_producer[2], s.use_producer[0]);
    }

    #[test]
    fn without_budget_a_later_file_refetches() {
        let s = build(&[file(&[0, 1]), file(&[0])], &[10, 10], 0, &[]);
        assert_eq!(s.fetch.len(), 3);
        assert_eq!(s.refetches, 1);
        assert_eq!(s.producers[2].copy, 1);
        assert_eq!(s.producers[2].anchor, 1);
    }

    #[test]
    fn eviction_drops_the_chunk_needed_furthest_away() {
        let files = [file(&[0, 1]), file(&[1]), file(&[1]), file(&[0])];
        let s = build(&files, &[10, 10], 10, &[]);
        let fetched: Vec<u32> = s.fetch.iter().map(|&p| s.producers[p as usize].chunk).collect();
        assert_eq!(fetched, vec![0, 1, 0]);
        assert_eq!(s.peak_cache, 10);
    }

    #[test]
    fn cached_chunks_are_reused_soonest_first() {
        let s = build(&[file(&[0]), file(&[1]), file(&[2])], &[10, 10, 10], 20, &[2, 0, 1, 7]);
        assert!(s.fetch.is_empty() || s.fetch.iter().all(|&p| s.producers[p as usize].chunk == 2));
        let preloaded: Vec<u32> = s.producers.iter().filter(|p| p.preloaded).map(|p| p.chunk).collect();
        assert_eq!(preloaded, vec![0, 1]);
        assert_eq!(s.fetch.len(), 1);
    }
}
