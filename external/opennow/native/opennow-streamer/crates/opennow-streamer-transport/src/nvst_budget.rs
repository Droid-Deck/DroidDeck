pub(crate) const TRANSPORT_AGGREGATE_CAPACITY: usize = 128 * 1024;
pub(crate) const TEARDOWN_RESERVE: usize = 64 * 1024;
pub(crate) const NORMAL_BUDGET: usize = TRANSPORT_AGGREGATE_CAPACITY - TEARDOWN_RESERVE;
pub(crate) const TEXT_BATCH_GUARD: usize = 128 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum WriteClass {
    Normal,
    Teardown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum WriteAdmission {
    Admitted,
    OverBudget,
    EmptyWrite,
}

impl WriteAdmission {
    pub(crate) const fn is_admitted(self) -> bool {
        matches!(self, Self::Admitted)
    }
}

pub(crate) fn admit_write(
    buffered_total: usize,
    candidate: usize,
    class: WriteClass,
) -> WriteAdmission {
    if candidate == 0 {
        return WriteAdmission::EmptyWrite;
    }
    let limit = match class {
        WriteClass::Normal => NORMAL_BUDGET,
        WriteClass::Teardown => TRANSPORT_AGGREGATE_CAPACITY,
    };
    if buffered_total.saturating_add(candidate) <= limit {
        WriteAdmission::Admitted
    } else {
        WriteAdmission::OverBudget
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_effective_ceiling_is_the_actual_transport_capacity() {
        assert_eq!(TRANSPORT_AGGREGATE_CAPACITY, 128 * 1024);
        assert_eq!(
            NORMAL_BUDGET,
            TRANSPORT_AGGREGATE_CAPACITY - TEARDOWN_RESERVE
        );
        assert_eq!(
            admit_write(TRANSPORT_AGGREGATE_CAPACITY - 1, 1, WriteClass::Teardown),
            WriteAdmission::Admitted
        );
        assert_eq!(
            admit_write(TRANSPORT_AGGREGATE_CAPACITY, 1, WriteClass::Teardown),
            WriteAdmission::OverBudget
        );
    }

    #[test]
    fn normal_admission_stops_at_the_shared_budget() {
        assert_eq!(
            admit_write(0, 1, WriteClass::Normal),
            WriteAdmission::Admitted
        );
        assert_eq!(
            admit_write(NORMAL_BUDGET - 1, 1, WriteClass::Normal),
            WriteAdmission::Admitted
        );
        assert_eq!(
            admit_write(NORMAL_BUDGET, 1, WriteClass::Normal),
            WriteAdmission::OverBudget
        );
    }

    #[test]
    fn normal_admission_always_leaves_the_teardown_reservation_free() {
        for buffered in 0..=NORMAL_BUDGET {
            for candidate in [1_usize, 64, 1024, 4096] {
                if admit_write(buffered, candidate, WriteClass::Normal).is_admitted() {
                    let headroom = NORMAL_BUDGET - (buffered + candidate);
                    assert!(
                        TRANSPORT_AGGREGATE_CAPACITY - (buffered + candidate) >= TEARDOWN_RESERVE,
                        "normal write at buffered={buffered} candidate={candidate} left headroom {}",
                        TRANSPORT_AGGREGATE_CAPACITY - (buffered + candidate)
                    );
                    let _ = headroom;
                    assert_eq!(
                        admit_write(buffered + candidate, 1, WriteClass::Teardown),
                        WriteAdmission::Admitted
                    );
                }
            }
        }
    }

    #[test]
    fn teardown_admission_still_works_after_a_normal_flood_fills_its_budget() {
        assert_eq!(
            admit_write(NORMAL_BUDGET, 1, WriteClass::Normal),
            WriteAdmission::OverBudget
        );
        assert_eq!(
            admit_write(NORMAL_BUDGET, 1, WriteClass::Teardown),
            WriteAdmission::Admitted
        );
        assert_eq!(
            admit_write(NORMAL_BUDGET, TEARDOWN_RESERVE, WriteClass::Teardown),
            WriteAdmission::Admitted
        );
        assert_eq!(
            admit_write(NORMAL_BUDGET, TEARDOWN_RESERVE + 1, WriteClass::Teardown),
            WriteAdmission::OverBudget
        );
    }

    #[test]
    fn the_text_guard_is_never_stricter_than_the_shared_ceiling() {
        assert_eq!(TEXT_BATCH_GUARD, 128 * 1024);
        assert_eq!(
            admit_write(0, TEXT_BATCH_GUARD, WriteClass::Normal),
            WriteAdmission::OverBudget
        );
        assert_eq!(
            admit_write(0, TEXT_BATCH_GUARD, WriteClass::Teardown),
            WriteAdmission::Admitted
        );
    }

    #[test]
    fn empty_writes_are_rejected_before_touching_the_transport() {
        assert_eq!(
            admit_write(0, 0, WriteClass::Normal),
            WriteAdmission::EmptyWrite
        );
        assert_eq!(
            admit_write(0, 0, WriteClass::Teardown),
            WriteAdmission::EmptyWrite
        );
    }
}
