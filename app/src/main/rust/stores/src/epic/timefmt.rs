fn days_from_civil(year: i64, month: i64, day: i64) -> i64 {
    let y = if month <= 2 { year - 1 } else { year };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let mp = (month + 9) % 12;
    let doy = (153 * mp + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

fn digits(s: &str, from: usize, len: usize) -> Option<i64> {
    let part = s.get(from..from + len)?;
    if !part.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    part.parse().ok()
}

pub fn utc_prefix_seconds(text: &str) -> Option<i64> {
    let s = text.trim();
    let bytes = s.as_bytes();
    if bytes.len() < 19 || bytes[4] != b'-' || bytes[7] != b'-' || bytes[13] != b':' || bytes[16] != b':' {
        return None;
    }
    if bytes[10] != b'T' && bytes[10] != b't' && bytes[10] != b' ' {
        return None;
    }
    let (y, mo, d) = (digits(s, 0, 4)?, digits(s, 5, 2)?, digits(s, 8, 2)?);
    let (h, mi, se) = (digits(s, 11, 2)?, digits(s, 14, 2)?, digits(s, 17, 2)?);
    if !(1..=12).contains(&mo) || !(1..=31).contains(&d) || h > 23 || mi > 59 || se > 60 {
        return None;
    }
    Some(days_from_civil(y, mo, d) * 86_400 + h * 3600 + mi * 60 + se)
}

pub fn iso_seconds(text: &str) -> Option<i64> {
    let s = text.trim();
    let base = utc_prefix_seconds(s)?;
    let mut rest = &s[19..];
    if let Some(stripped) = rest.strip_prefix('.') {
        let n = stripped.bytes().take_while(|b| b.is_ascii_digit()).count();
        rest = &stripped[n..];
    }
    if rest.is_empty() || rest.eq_ignore_ascii_case("z") {
        return Some(base);
    }
    let sign = match rest.as_bytes()[0] {
        b'+' => 1,
        b'-' => -1,
        _ => return None,
    };
    let offset: String = rest[1..].chars().filter(|c| *c != ':').collect();
    if !offset.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    let (hh, mm) = match offset.len() {
        2 => (offset.parse::<i64>().ok()?, 0),
        4 => (offset[..2].parse::<i64>().ok()?, offset[2..].parse::<i64>().ok()?),
        _ => return None,
    };
    Some(base - sign * (hh * 3600 + mm * 60))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn epoch_and_known_dates() {
        assert_eq!(utc_prefix_seconds("1970-01-01T00:00:00"), Some(0));
        assert_eq!(utc_prefix_seconds("2000-03-01T00:00:00.000Z"), Some(951_868_800));
        assert_eq!(utc_prefix_seconds("2026-10-13T12:34:56.789Z"), Some(1_791_894_896));
        assert_eq!(utc_prefix_seconds("not a date"), None);
    }

    #[test]
    fn offsets_in_every_spelling() {
        let z = iso_seconds("2024-05-01T10:00:00Z").unwrap();
        assert_eq!(iso_seconds("2024-05-01T12:00:00+02:00"), Some(z));
        assert_eq!(iso_seconds("2024-05-01T12:00:00+0200"), Some(z));
        assert_eq!(iso_seconds("2024-05-01T08:00:00.123-02:00"), Some(z));
        assert_eq!(iso_seconds("2024-05-01T10:00:00"), Some(z));
        assert_eq!(iso_seconds("2024-05-01T10:00:00 garbage"), None);
    }
}
