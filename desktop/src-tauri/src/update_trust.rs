//! Release trust is fixed at build time, never selected by the process environment.
pub const ENDPOINT: &str =
    "https://github.com/terrydavisthe2nd/nexa/releases/latest/download/latest.json";

pub fn public_key(value: Option<&str>) -> Result<&str, String> {
    value
        .map(str::trim)
        .filter(|key| !key.is_empty())
        .ok_or_else(|| {
            "Desktop updates are disabled: this build has no owner-approved NEXA_UPDATER_PUBLIC_KEY"
                .to_string()
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn missing_key_disables_updates() {
        assert!(public_key(None).is_err());
        assert!(public_key(Some(" \n")).is_err());
    }
    #[test]
    fn configured_key_is_preserved() {
        assert_eq!(
            public_key(Some(" owner-controlled-key\n")).unwrap(),
            "owner-controlled-key"
        );
    }
    #[test]
    fn endpoint_is_the_canonical_repository() {
        assert_eq!(
            ENDPOINT,
            "https://github.com/terrydavisthe2nd/nexa/releases/latest/download/latest.json"
        );
    }
}
