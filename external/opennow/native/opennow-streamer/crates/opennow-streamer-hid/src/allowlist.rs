pub const GENERIC_HID_ALLOWLIST: [(u16, u16); 18] = [
    (0x044f, 0x0402),
    (0x044f, 0x0404),
    (0x044f, 0xb67c),
    (0x044f, 0xb687),
    (0x044f, 0xb68d),
    (0x044f, 0xb68f),
    (0x044f, 0xb697),
    (0x046d, 0xc24f),
    (0x046d, 0xc262),
    (0x046d, 0xc266),
    (0x046d, 0xc268),
    (0x046d, 0xc26e),
    (0x046d, 0xc272),
    (0x046d, 0xc276),
    (0x06a3, 0x075c),
    (0x06a3, 0x0762),
    (0x0738, 0x2221),
    (0x0738, 0xa221),
];

pub fn generic_hid_allowed(vendor: u16, product: u16) -> bool {
    GENERIC_HID_ALLOWLIST.contains(&(vendor, product))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn allowlist_is_exact_and_does_not_admit_entire_vendors() {
        let expected = [
            (0x044f, 0x0402),
            (0x044f, 0x0404),
            (0x044f, 0xb67c),
            (0x044f, 0xb687),
            (0x044f, 0xb68d),
            (0x044f, 0xb68f),
            (0x044f, 0xb697),
            (0x046d, 0xc24f),
            (0x046d, 0xc262),
            (0x046d, 0xc266),
            (0x046d, 0xc268),
            (0x046d, 0xc26e),
            (0x046d, 0xc272),
            (0x046d, 0xc276),
            (0x06a3, 0x075c),
            (0x06a3, 0x0762),
            (0x0738, 0x2221),
            (0x0738, 0xa221),
        ];
        assert_eq!(GENERIC_HID_ALLOWLIST, expected);
        for (vendor, product) in expected {
            assert!(generic_hid_allowed(vendor, product));
            assert!(!generic_hid_allowed(vendor, product ^ 1));
            assert!(!generic_hid_allowed(vendor ^ 0x8000, product));
        }
        assert!(!generic_hid_allowed(0x054c, 0x05c4));
        assert!(!generic_hid_allowed(0x044f, 0xb10a));
        assert!(!generic_hid_allowed(0x046d, 0xffff));
    }
}
