//! Cleans a Rich Presence activity the way Discord's own RPC server accepts it,
//! before the page sends it on to chatd (which checks everything again).

use serde_json::{Map, Value};

const TEXT: usize = 128;
const LINK: usize = 256;
const IMAGE: usize = 1024;
const LABEL: usize = 32;
const BUTTON_URL: usize = 512;
const TEN_YEARS: f64 = (10.0 * 365.0 * 24.0 * 3600.0 * 1000.0) as f64;

/// Characters that are invisible or reorder text: control characters and
/// bidirectional overrides.
fn hidden(code: u32) -> bool {
    code < 0x20
        || code == 0x7f
        || code == 0x200e
        || code == 0x200f
        || (0x202a..=0x202e).contains(&code)
        || (0x2066..=0x2069).contains(&code)
}

fn line(v: Option<&Value>, max: usize) -> Option<String> {
    let v = v?.as_str()?;
    let mut s = String::with_capacity(v.len());
    let mut in_break = false;
    for ch in v.chars() {
        if ch == '\r' || ch == '\n' || ch == '\t' {
            if !in_break {
                s.push(' ');
                in_break = true;
            }
        } else {
            in_break = false;
            if !hidden(ch as u32) {
                s.push(ch);
            }
        }
    }
    let s = s.trim();
    if s.is_empty() {
        return None;
    }
    let mut chars: Vec<char> = s.chars().collect();
    if chars.len() > max {
        chars.truncate(max);
        let cut: String = chars.into_iter().collect();
        let cut = cut.trim();
        if cut.is_empty() {
            None
        } else {
            Some(cut.to_string())
        }
    } else {
        Some(s.to_string())
    }
}

/// URL length as JavaScript counts it (UTF-16 code units).
fn js_len(s: &str) -> usize {
    s.encode_utf16().count()
}

fn link(v: Option<&Value>, max: usize) -> Option<String> {
    let v = v?.as_str()?;
    if js_len(v) > max {
        return None;
    }
    let url = tauri::Url::parse(v.trim()).ok()?;
    if url.scheme() != "https" && url.scheme() != "http" {
        return None;
    }
    if url.host_str().map(|h| h.is_empty()).unwrap_or(true) {
        return None;
    }
    if !url.username().is_empty() {
        return None;
    }
    if url.password().map(|p| !p.is_empty()).unwrap_or(false) {
        return None;
    }
    Some(url.as_str().to_string())
}

fn image(v: Option<&Value>) -> Option<String> {
    let v = v?.as_str()?;
    let trimmed = v.trim();
    if trimmed.is_empty() || js_len(v) > IMAGE {
        return None;
    }
    Some(trimmed.to_string())
}

fn number_eq(v: Option<&Value>, n: i64) -> bool {
    matches!(v.and_then(Value::as_f64), Some(x) if x == n as f64)
}

fn integer(v: &Value) -> Option<i64> {
    let x = v.as_f64()?;
    if !x.is_finite() || x.fract() != 0.0 {
        return None;
    }
    if x < i64::MIN as f64 || x > i64::MAX as f64 {
        return None;
    }
    Some(x as i64)
}

fn time(v: Option<&Value>, now: u64) -> Option<i64> {
    let n = match v? {
        Value::String(s) if !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit()) => {
            s.parse::<f64>().ok()?
        }
        Value::Number(num) => num.as_f64()?,
        _ => return None,
    };
    if !n.is_finite() || n <= 0.0 {
        return None;
    }
    // Libraries send seconds as often as milliseconds; nothing in milliseconds
    // is below 1e12.
    let ms = if n < 1e12 {
        (n * 1000.0).round()
    } else {
        n.round()
    };
    if (ms - now as f64).abs() > TEN_YEARS {
        return None;
    }
    if ms < i64::MIN as f64 || ms > i64::MAX as f64 {
        return None;
    }
    Some(ms as i64)
}

fn object(v: Option<&Value>) -> Option<&Map<String, Value>> {
    match v? {
        Value::Object(map) => Some(map),
        _ => None,
    }
}

fn get<'a>(obj: &'a Map<String, Value>, key: &str) -> Option<&'a Value> {
    obj.get(key)
}

/// A cleaned activity, or None when the program cleared it or sent nothing
/// usable.
pub fn normalize_activity(client_id: &str, input: Option<&Value>, now: u64) -> Option<Value> {
    let a = object(input)?;
    let mut out = Map::new();
    out.insert(
        "application_id".to_string(),
        Value::String(client_id.to_string()),
    );
    out.insert("type".to_string(), Value::Number(0.into()));
    if number_eq(get(a, "type"), 2) || number_eq(get(a, "type"), 3) || number_eq(get(a, "type"), 5)
    {
        out.insert("type".to_string(), get(a, "type").unwrap().clone());
    }
    if let Some(name) = line(get(a, "name"), TEXT) {
        out.insert("name".to_string(), Value::String(name));
    }
    for display in [0i64, 1, 2] {
        if number_eq(get(a, "status_display_type"), display) {
            out.insert(
                "status_display_type".to_string(),
                Value::Number(display.into()),
            );
            break;
        }
    }
    if let Some(details) = line(get(a, "details"), TEXT) {
        out.insert("details".to_string(), Value::String(details));
    }
    if let Some(state) = line(get(a, "state"), TEXT) {
        out.insert("state".to_string(), Value::String(state));
    }
    if let Some(details_url) = link(get(a, "details_url"), LINK) {
        out.insert("details_url".to_string(), Value::String(details_url));
    }
    if let Some(state_url) = link(get(a, "state_url"), LINK) {
        out.insert("state_url".to_string(), Value::String(state_url));
    }

    if let Some(ts) = object(get(a, "timestamps")) {
        let start = time(get(ts, "start"), now);
        let mut end = time(get(ts, "end"), now);
        if let (Some(start), Some(end_at)) = (start, end) {
            if end_at <= start {
                end = None;
            }
        }
        if start.is_some() || end.is_some() {
            let mut timestamps = Map::new();
            if let Some(start) = start {
                timestamps.insert("start".to_string(), Value::Number(start.into()));
            }
            if let Some(end) = end {
                timestamps.insert("end".to_string(), Value::Number(end.into()));
            }
            out.insert("timestamps".to_string(), Value::Object(timestamps));
        }
    }

    if let Some(assets_in) = object(get(a, "assets")) {
        let mut assets = Map::new();
        if let Some(v) = image(get(assets_in, "large_image")) {
            assets.insert("large_image".to_string(), Value::String(v));
        }
        if let Some(v) = line(get(assets_in, "large_text"), TEXT) {
            assets.insert("large_text".to_string(), Value::String(v));
        }
        if let Some(v) = link(get(assets_in, "large_url"), LINK) {
            assets.insert("large_url".to_string(), Value::String(v));
        }
        if let Some(v) = image(get(assets_in, "small_image")) {
            assets.insert("small_image".to_string(), Value::String(v));
        }
        if let Some(v) = line(get(assets_in, "small_text"), TEXT) {
            assets.insert("small_text".to_string(), Value::String(v));
        }
        if let Some(v) = link(get(assets_in, "small_url"), LINK) {
            assets.insert("small_url".to_string(), Value::String(v));
        }
        if !assets.is_empty() {
            out.insert("assets".to_string(), Value::Object(assets));
        }
    }

    if let Some(party_in) = object(get(a, "party")) {
        let mut party = Map::new();
        if let Some(id) = line(get(party_in, "id"), TEXT) {
            party.insert("id".to_string(), Value::String(id));
        }
        if let Some(Value::Array(size)) = get(party_in, "size") {
            if size.len() == 2 {
                if let (Some(cur), Some(max)) = (integer(&size[0]), integer(&size[1])) {
                    // Discord's own library checks these bounds in the client;
                    // keeping them here means we never send a nonsense size on.
                    if cur >= 0 && max >= 1 && cur <= max {
                        party.insert(
                            "size".to_string(),
                            Value::Array(vec![
                                Value::Number(cur.into()),
                                Value::Number(max.into()),
                            ]),
                        );
                    }
                }
            }
        }
        if !party.is_empty() {
            out.insert("party".to_string(), Value::Object(party));
        }
    }

    // Discord shows the first two buttons at most, so a third never stands in
    // for a broken one.
    if let Some(Value::Array(buttons_in)) = get(a, "buttons") {
        let mut buttons = Vec::new();
        for b in buttons_in.iter().take(2) {
            let b = object(Some(b));
            let label = b.and_then(|b| line(get(b, "label"), LABEL));
            let url = b.and_then(|b| link(get(b, "url"), BUTTON_URL));
            if let (Some(label), Some(url)) = (label, url) {
                let mut button = Map::new();
                button.insert("label".to_string(), Value::String(label));
                button.insert("url".to_string(), Value::String(url));
                buttons.push(Value::Object(button));
            }
        }
        if !buttons.is_empty() {
            out.insert("buttons".to_string(), Value::Array(buttons));
        }
    }

    if get(a, "instance") == Some(&Value::Bool(true)) {
        out.insert("instance".to_string(), Value::Bool(true));
    }
    Some(Value::Object(out))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn keeps_what_discord_accepts_and_drops_the_rest() {
        let now = 1_700_000_000_000u64;
        let activity = normalize_activity(
            "123",
            Some(&json!({
                "type": 2,
                "name": "  A game\n",
                "details": "Playing",
                "details_url": "https://example.com/a",
                "state_url": "https://:pw@evil.example",
                "timestamps": { "start": 1_700_000_000, "end": 1_700_000_000 },
                "assets": { "large_image": " art ", "large_text": "Art" },
                "party": { "id": "p", "size": [1, 2] },
                "buttons": [{ "label": " One ", "url": "https://example.com" }, { "label": "Bad", "url": "javascript:1" }],
                "instance": true
            })),
            now,
        )
        .unwrap();
        assert_eq!(activity["application_id"], "123");
        assert_eq!(activity["type"], 2);
        assert_eq!(activity["name"], "A game");
        assert!(activity.get("details_url").is_some());
        assert!(activity.get("state_url").is_none());
        assert_eq!(activity["timestamps"]["start"], 1_700_000_000_000i64);
        assert!(activity["timestamps"].get("end").is_none());
        assert_eq!(activity["assets"]["large_image"], "art");
        assert_eq!(activity["party"]["size"], json!([1, 2]));
        assert_eq!(activity["buttons"].as_array().unwrap().len(), 1);
        assert_eq!(activity["instance"], true);
    }

    #[test]
    fn null_for_something_that_is_not_an_object() {
        assert!(normalize_activity("1", Some(&json!(null)), 0).is_none());
        assert!(normalize_activity("1", Some(&json!([1])), 0).is_none());
        assert!(normalize_activity("1", None, 0).is_none());
    }
}
