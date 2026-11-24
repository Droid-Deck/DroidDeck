const QUOTE: u16 = b'"' as u16;
const BACKSLASH: u16 = b'\\' as u16;

fn blank(c: u16) -> bool {
    c == b' ' as u16 || c == b'\t' as u16
}

fn trim_start(line: &[u16]) -> &[u16] {
    let start = line.iter().position(|&c| !blank(c)).unwrap_or(line.len());
    &line[start..]
}

/// Splits this program's command line into the game executable that follows the program name and
/// the rest of the line, which reaches the game unchanged.
pub fn split(line: &[u16]) -> Option<(Vec<u16>, &[u16])> {
    let mut quoted = false;
    let mut end = line.len();
    for (i, &c) in line.iter().enumerate() {
        if c == QUOTE {
            quoted = !quoted;
        } else if blank(c) && !quoted {
            end = i;
            break;
        }
    }
    let rest = trim_start(&line[end..]);
    let mut exe = Vec::new();
    let mut quoted = false;
    let mut slashes = 0;
    let mut end = rest.len();
    for (i, &c) in rest.iter().enumerate() {
        if c == BACKSLASH {
            slashes += 1;
            continue;
        }
        if c == QUOTE {
            exe.extend(std::iter::repeat(BACKSLASH).take(slashes / 2));
            if slashes % 2 == 1 {
                exe.push(QUOTE);
            } else {
                quoted = !quoted;
            }
        } else if blank(c) && !quoted {
            exe.extend(std::iter::repeat(BACKSLASH).take(slashes));
            end = i;
            slashes = 0;
            break;
        } else {
            exe.extend(std::iter::repeat(BACKSLASH).take(slashes));
            exe.push(c);
        }
        slashes = 0;
    }
    exe.extend(std::iter::repeat(BACKSLASH).take(slashes));
    if exe.is_empty() {
        return None;
    }
    Some((exe, trim_start(&rest[end..])))
}

#[cfg(test)]
mod tests {
    use super::split;

    fn wide(s: &str) -> Vec<u16> {
        s.encode_utf16().collect()
    }

    fn parts(line: &str) -> Option<(String, String)> {
        let line = wide(line);
        split(&line).map(|(exe, rest)| (String::from_utf16(&exe).unwrap(), String::from_utf16(rest).unwrap()))
    }

    #[test]
    fn the_game_gets_the_rest_of_the_line_exactly_as_written() {
        assert_eq!(
            parts(r#""Z:\usr\local\lib\droiddeck-epic\EpicGamesLauncher.exe" "Z:\root\Games\Stores\Epic\Grand Theft Auto V\PlayGTAV.exe" -AUTH_LOGIN=unused  -epicusername="A B" -nobattleye"#),
            Some((
                r"Z:\root\Games\Stores\Epic\Grand Theft Auto V\PlayGTAV.exe".into(),
                r#"-AUTH_LOGIN=unused  -epicusername="A B" -nobattleye"#.into()
            ))
        );
    }

    #[test]
    fn a_game_without_arguments_gets_none() {
        assert_eq!(parts(r#"EpicGamesLauncher.exe "C:\Games\x.exe"  "#), Some((r"C:\Games\x.exe".into(), String::new())));
        assert_eq!(parts(r"EpicGamesLauncher.exe C:\Games\x.exe"), Some((r"C:\Games\x.exe".into(), String::new())));
    }

    #[test]
    fn the_program_name_follows_its_own_quoting_rules() {
        assert_eq!(parts("\"C:\\a b\\\"EpicGamesLauncher.exe\tgame.exe -x"), Some(("game.exe".into(), "-x".into())));
    }

    #[test]
    fn the_executable_follows_the_argument_quoting_rules() {
        assert_eq!(parts(r#"e "C:\dir with\"part\"s\x.exe" -y"#), Some((r#"C:\dir with"part"s\x.exe"#.into(), "-y".into())));
        assert_eq!(parts(r#"e C:\a"b c"\x.exe -y"#), Some((r"C:\ab c\x.exe".into(), "-y".into())));
        assert_eq!(parts(r"e C:\games\ -y"), Some((r"C:\games\".into(), "-y".into())));
    }

    #[test]
    fn a_missing_executable_is_refused() {
        assert_eq!(parts("EpicGamesLauncher.exe"), None);
        assert_eq!(parts("EpicGamesLauncher.exe   "), None);
        assert_eq!(parts(r#"EpicGamesLauncher.exe "" -x"#), None);
        assert_eq!(parts(""), None);
    }
}
