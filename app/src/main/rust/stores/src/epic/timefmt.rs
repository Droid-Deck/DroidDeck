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

const MONTHS: [&str; 12] = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/// RFC 7231 IMF-fixdate, the HTTP `Date` header ("Sun, 06 Nov 1994 08:49:37 GMT").
pub fn http_date_seconds(text: &str) -> Option<i64> {
    let mut parts = text.trim().split(' ');
    let weekday = parts.next()?;
    if !weekday.ends_with(',') {
        return None;
    }
    let day_text = parts.next()?;
    if day_text.len() != 2 {
        return None;
    }
    let day = digits(day_text, 0, 2)?;
    let month_text = parts.next()?;
    let month = MONTHS.iter().position(|m| m.eq_ignore_ascii_case(month_text))? as i64 + 1;
    let year_text = parts.next()?;
    if year_text.len() != 4 {
        return None;
    }
    let year = digits(year_text, 0, 4)?;
    let clock = parts.next()?;
    let clock_bytes = clock.as_bytes();
    if clock_bytes.len() != 8 || clock_bytes[2] != b':' || clock_bytes[5] != b':' {
        return None;
    }
    let (h, mi, se) = (digits(clock, 0, 2)?, digits(clock, 3, 2)?, digits(clock, 6, 2)?);
    // IMF-fixdate ends in GMT; any other zone (or trailing junk) is not a clock we can trust.
    let zone = parts.next()?;
    if !zone.eq_ignore_ascii_case("GMT") || parts.next().is_some() {
        return None;
    }
    if h > 23 || mi > 59 || se > 60 || !(1..=31).contains(&day) || !(1..=12).contains(&month) {
        return None;
    }
    Some(days_from_civil(year, month, day) * 86_400 + h * 3600 + mi * 60 + se)
}

fn civil_from_days(days: i64) -> (i64, i64, i64) {
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let year = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    (if month <= 2 { year + 1 } else { year }, month, day)
}

/// EGL names save manifests by a UTC timestamp in this exact shape, and the name order is
/// the time order, so uploads stamp with `save_stamp(server_time)` and compare names.
pub fn save_stamp(secs: i64) -> String {
    let (year, month, day) = civil_from_days(secs.div_euclid(86_400));
    let clock = secs.rem_euclid(86_400);
    let (h, mi, s) = (clock / 3600, clock % 3600 / 60, clock % 60);
    format!("{year:04}.{month:02}.{day:02}-{h:02}.{mi:02}.{s:02}")
}

/// Inverse of `save_stamp` on the name body ("2026.11.28-12.34.56").
pub fn stamp_seconds(text: &str) -> Option<i64> {
    let bytes = text.as_bytes();
    if text.len() != 19
        || bytes[4] != b'.'
        || bytes[7] != b'.'
        || bytes[10] != b'-'
        || bytes[13] != b'.'
        || bytes[16] != b'.'
    {
        return None;
    }
    let (y, mo, d) = (digits(text, 0, 4)?, digits(text, 5, 2)?, digits(text, 8, 2)?);
    let (h, mi, se) = (digits(text, 11, 2)?, digits(text, 14, 2)?, digits(text, 17, 2)?);
    if !(1..=12).contains(&mo) || h > 23 || mi > 59 || se > 60 {
        return None;
    }
    // Real calendar dates only: an impossible day would fold into a later month and the
    // stamp would no longer round-trip to the name it came from.
    let leap = y % 4 == 0 && (y % 100 != 0 || y % 400 == 0);
    let days_in_month = [31, if leap { 29 } else { 28 }, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
    if d < 1 || d > days_in_month[(mo - 1) as usize] {
        return None;
    }
    Some(days_from_civil(y, mo, d) * 86_400 + h * 3600 + mi * 60 + se)
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

    #[test]
    fn http_dates_parse_to_unix_seconds() {
        // The RFC 7231 example: 1994-11-06 08:49:37 UTC.
        assert_eq!(http_date_seconds("Sun, 06 Nov 1994 08:49:37 GMT"), Some(784_111_777));
        assert_eq!(http_date_seconds("Mon, 01 Jan 2024 10:00:00 GMT"), Some(1_704_103_200));
        assert_eq!(http_date_seconds("mon, 06 nov 1994 08:49:37 GMT"), Some(784_111_777));
        assert_eq!(http_date_seconds("Sun Nov 6 08:49:37 1994"), None);
        // Only GMT is a zone we can take at face value, and nothing may trail it.
        assert_eq!(http_date_seconds("Mon, 01 Jan 2024 10:00:00 PST"), None);
        assert_eq!(http_date_seconds("Mon, 01 Jan 2024 10:00:00 GMT junk"), None);
        assert_eq!(http_date_seconds("garbage"), None);
        assert_eq!(http_date_seconds(""), None);
    }

    #[test]
    fn save_stamps_round_trip_and_keep_name_order() {
        assert_eq!(save_stamp(0), "1970.01.01-00.00.00");
        assert_eq!(save_stamp(1_791_894_896), "2026.10.13-12.34.56");
        for text in ["1970.01.01-00.00.00", "2024.05.01-10.00.00", "2026.10.13-12.34.56"] {
            assert_eq!(save_stamp(stamp_seconds(text).unwrap()), text);
        }
        // Sorting the names lexicographically is the same as sorting by time: the
        // property EGL's newness check (and our stamp logic) relies on.
        let mut stamps: Vec<i64> = (1_700_000_000..1_700_000_060).collect();
        stamps.push(1_699_999_999); // one second before the range, same evening
        stamps.push(1_700_063_999); // the next afternoon
        stamps.sort();
        let names: Vec<String> = stamps.iter().map(|&s| save_stamp(s)).collect();
        let mut by_name = names.clone();
        by_name.sort();
        assert_eq!(by_name, names);
        assert_eq!(stamp_seconds(&save_stamp(1_700_000_000)), Some(1_700_000_000));
        assert_eq!(stamp_seconds("2026.13.01-00.00.00"), None);
        // Impossible calendar dates fold into later months and break the round trip.
        assert_eq!(stamp_seconds("2026.02.29-00.00.00"), None);
        assert_eq!(stamp_seconds("2026.02.30-00.00.00"), None);
        assert_eq!(save_stamp(stamp_seconds("2024.02.29-12.00.00").unwrap()), "2024.02.29-12.00.00");
        assert_eq!(stamp_seconds("2026-01-01T00:00:00"), None);
    }
}
