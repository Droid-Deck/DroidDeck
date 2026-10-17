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

#[cfg(test)]
mod tests {
    use super::*;

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
