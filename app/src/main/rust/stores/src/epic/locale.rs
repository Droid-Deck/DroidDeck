use super::manifest::FileEntry;

const LANGUAGE_TAGS: &[(&str, &[&str])] = &[
    ("ar", &["Arabic", "ar"]),
    ("bg", &["Bulgarian", "bg-BG", "bg"]),
    ("zh-Hans", &["Chinese", "ChineseSimplified", "zh-Hans", "zh_Hans", "zh"]),
    ("zh-Hant", &["ChineseTraditional", "zh-Hant", "zh_Hant"]),
    ("cs", &["Czech", "cs-CZ", "cs"]),
    ("da", &["Danish", "da-DK", "da"]),
    ("nl", &["Dutch", "nl-NL", "nl"]),
    ("en", &["English", "en-US", "en"]),
    ("fi", &["Finnish", "fi-FI", "fi"]),
    ("fr", &["French", "fr-FR", "fr"]),
    ("de", &["German", "de-DE", "de"]),
    ("el", &["Greek", "el-GR", "el"]),
    ("hu", &["Hungarian", "hu-HU", "hu"]),
    ("it", &["Italian", "it-IT", "it"]),
    ("ja", &["Japanese", "ja-JP", "ja"]),
    ("ko", &["Korean", "ko-KR", "ko"]),
    ("no", &["Norwegian", "nb-NO", "no"]),
    ("pl", &["Polish", "pl-PL", "pl"]),
    ("pt", &["Portuguese", "pt-PT", "pt"]),
    ("pt-BR", &["PortugueseBrazilian", "Brazilian", "pt-BR", "br"]),
    ("ro", &["Romanian", "ro-RO", "ro"]),
    ("ru", &["Russian", "ru-RU", "ru"]),
    ("es", &["Spanish", "es-ES", "es"]),
    ("es-419", &["SpanishLatinAmerica", "Latam", "es-MX", "es_mx"]),
    ("sv", &["Swedish", "sv-SE", "sv"]),
    ("th", &["Thai", "th-TH", "th"]),
    ("tr", &["Turkish", "tr-TR", "tr"]),
    ("uk", &["Ukrainian", "uk-UA", "uk"]),
    ("vi", &["Vietnamese", "vi-VN", "vi"]),
];

pub fn language_key(language: &str, region: &str) -> &'static str {
    let lang = language.trim().to_ascii_lowercase();
    let region = region.trim().to_ascii_uppercase();
    match lang.as_str() {
        "zh" => {
            if matches!(region.as_str(), "TW" | "HK" | "MO") {
                "zh-Hant"
            } else {
                "zh-Hans"
            }
        }
        "pt" => {
            if region == "BR" {
                "pt-BR"
            } else {
                "pt"
            }
        }
        "es" => {
            if region.is_empty() || region == "ES" {
                "es"
            } else {
                "es-419"
            }
        }
        "nb" | "nn" => "no",
        other => LANGUAGE_TAGS
            .iter()
            .map(|(k, _)| *k)
            .find(|k| *k == other)
            .unwrap_or("en"),
    }
}

pub fn tags_for_key(key: &str) -> Vec<String> {
    LANGUAGE_TAGS
        .iter()
        .find(|(k, _)| *k == key)
        .or_else(|| LANGUAGE_TAGS.iter().find(|(k, _)| *k == "en"))
        .map(|(_, tags)| tags.iter().map(|t| t.to_string()).collect())
        .unwrap_or_default()
}

pub fn launch_locale(key: &str) -> String {
    let head = key.split('-').next().unwrap_or("").trim();
    if head.is_empty() {
        "en".to_string()
    } else {
        head.to_string()
    }
}

pub fn select(files: &[FileEntry], selected: Option<&[String]>) -> Vec<usize> {
    let all = || (0..files.len()).collect::<Vec<_>>();
    let required = || {
        let untagged: Vec<usize> = (0..files.len()).filter(|&i| files[i].tags.is_empty()).collect();
        if untagged.is_empty() {
            all()
        } else {
            untagged
        }
    };
    let Some(selected) = selected else {
        return all();
    };
    if selected.is_empty() {
        return required();
    }
    let picked: Vec<usize> = (0..files.len())
        .filter(|&i| files[i].tags.is_empty() || files[i].tags.iter().any(|t| selected.contains(t)))
        .collect();
    if picked.is_empty() {
        required()
    } else {
        picked
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn file(path: &str, tags: &[&str]) -> FileEntry {
        FileEntry {
            path: path.into(),
            sha1: None,
            tags: tags.iter().map(|t| t.to_string()).collect(),
            parts: vec![],
            size: 1,
        }
    }

    #[test]
    fn locale_keys() {
        assert_eq!(language_key("zh", "TW"), "zh-Hant");
        assert_eq!(language_key("zh", "HK"), "zh-Hant");
        assert_eq!(language_key("zh", "CN"), "zh-Hans");
        assert_eq!(language_key("pt", "BR"), "pt-BR");
        assert_eq!(language_key("pt", "PT"), "pt");
        assert_eq!(language_key("es", ""), "es");
        assert_eq!(language_key("es", "ES"), "es");
        assert_eq!(language_key("es", "MX"), "es-419");
        assert_eq!(language_key("nb", "NO"), "no");
        assert_eq!(language_key("nn", ""), "no");
        assert_eq!(language_key("de", "AT"), "de");
        assert_eq!(language_key("xx", "YY"), "en");
        assert_eq!(launch_locale("zh-Hant"), "zh");
        assert_eq!(launch_locale("es-419"), "es");
        assert_eq!(launch_locale("fr"), "fr");
        assert_eq!(tags_for_key("pt-BR"), vec!["PortugueseBrazilian", "Brazilian", "pt-BR", "br"]);
        assert_eq!(tags_for_key("nope"), vec!["English", "en-US", "en"]);
    }

    #[test]
    fn selection_rules() {
        let files = vec![
            file("base", &[]),
            file("fr", &["French", "fr"]),
            file("de", &["German"]),
            file("opt", &["HighResTextures"]),
        ];
        let fr = tags_for_key("fr");
        assert_eq!(select(&files, Some(&fr)), vec![0, 1]);
        assert_eq!(select(&files, None), vec![0, 1, 2, 3]);
        assert_eq!(select(&files, Some(&[])), vec![0]);
        assert_eq!(select(&files, Some(&["french".to_string()])), vec![0]);
        let tagged_only = vec![file("a", &["X"]), file("b", &["Y"])];
        assert_eq!(select(&tagged_only, Some(&["Z".to_string()])), vec![0, 1]);
        assert_eq!(select(&tagged_only, Some(&["Y".to_string()])), vec![1]);
    }
}
