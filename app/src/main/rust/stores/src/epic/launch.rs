#[derive(Clone, Debug, Default)]
pub struct LaunchData {
    pub display_name: String,
    pub account_id: String,
    pub namespace: String,
    pub locale: String,
    pub app_name: String,
    pub deployment_id: String,
    pub additional: String,
}

fn clean(value: &str) -> String {
    value.chars().filter(|c| !matches!(c, '\r' | '\n' | '\0')).collect()
}

pub fn arguments(d: &LaunchData) -> Vec<String> {
    let name = clean(&d.display_name).replace('"', "");
    let name = if name.trim().is_empty() { "EpicUser".to_string() } else { name };
    let account = Some(clean(&d.account_id)).filter(|a| !a.is_empty()).unwrap_or_else(|| "0".into());
    let locale = Some(clean(&d.locale)).filter(|l| !l.is_empty()).unwrap_or_else(|| "en".into());
    let mut args = vec![
        "-EpicPortal".to_string(),
        format!("-epicusername=\"{name}\""),
        format!("-epicuserid={account}"),
        format!("-epicsandboxid={}", clean(&d.namespace)),
        format!("-epiclocale={locale}"),
        format!("-epicapp={}", clean(&d.app_name)),
        "-epicenv=Prod".to_string(),
    ];
    let deployment = clean(&d.deployment_id);
    if !deployment.is_empty() {
        args.push(format!("-epicdeploymentid={deployment}"));
    }
    let extra: String = d.additional.chars().filter(|&c| c >= ' ' && c != '\u{7f}').collect();
    let extra = extra.trim();
    if !extra.is_empty() {
        args.push(extra.to_string());
    }
    args
}

pub fn split_command(command: &str) -> Vec<String> {
    let mut args = Vec::new();
    let mut current = String::new();
    let mut quoted = false;
    let mut started = false;
    let mut slashes = 0usize;
    let mut chars = command.chars().map(|c| if c == '\t' { ' ' } else { c }).filter(|&c| c >= ' ' && c != '\u{7f}').peekable();
    while let Some(c) = chars.next() {
        if c == '\\' {
            slashes += 1;
            started = true;
            continue;
        }
        if c == '"' {
            current.extend(std::iter::repeat('\\').take(slashes / 2));
            if slashes % 2 == 1 {
                current.push('"');
            } else if quoted && chars.peek() == Some(&'"') {
                chars.next();
                current.push('"');
            } else {
                quoted = !quoted;
            }
            slashes = 0;
            started = true;
            continue;
        }
        current.extend(std::iter::repeat('\\').take(slashes));
        slashes = 0;
        if c == ' ' && !quoted {
            if started {
                args.push(std::mem::take(&mut current));
                started = false;
            }
        } else {
            current.push(c);
            started = true;
        }
    }
    current.extend(std::iter::repeat('\\').take(slashes));
    if started {
        args.push(current);
    }
    args.retain(|a| !a.is_empty());
    args
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn launch_command_splits_like_a_command_line() {
        assert_eq!(split_command("-PCStore=EGS"), vec!["-PCStore=EGS"]);
        assert_eq!(
            split_command("  -a\t-b=\"c d\" \"-e f\" \"\" -g\r\n"),
            vec!["-a", "-b=c d", "-e f", "-g"]
        );
        assert_eq!(
            split_command(r#"-name="a\" b" -dir=C:\Games\ "-q=\\" -r"#),
            vec![r#"-name=a" b"#, r"-dir=C:\Games\", r"-q=\", "-r"]
        );
        assert_eq!(split_command("-name=\"a\"\" b\" -s=\"\"\""), vec!["-name=a\" b", "-s=\""]);
        assert!(split_command("   ").is_empty());
    }

    #[test]
    fn arguments_in_order() {
        let args = arguments(&LaunchData {
            display_name: "Some \"One\"\r\n".into(),
            account_id: "acc".into(),
            namespace: "ns".into(),
            locale: "fr".into(),
            app_name: "Fig".into(),
            deployment_id: "dep".into(),
            additional: "  -nosplash\t-dx12 ".into(),
        });
        assert_eq!(
            args,
            vec![
                "-EpicPortal",
                "-epicusername=\"Some One\"",
                "-epicuserid=acc",
                "-epicsandboxid=ns",
                "-epiclocale=fr",
                "-epicapp=Fig",
                "-epicenv=Prod",
                "-epicdeploymentid=dep",
                "-nosplash-dx12",
            ]
        );
        let bare = arguments(&LaunchData { namespace: "ns".into(), app_name: "A".into(), ..Default::default() });
        assert_eq!(bare[1], "-epicusername=\"EpicUser\"");
        assert_eq!(bare[2], "-epicuserid=0");
        assert_eq!(bare[4], "-epiclocale=en");
        assert_eq!(bare.len(), 7);
    }
}
