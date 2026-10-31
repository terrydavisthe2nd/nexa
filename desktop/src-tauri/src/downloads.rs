//! Treat decoded download names as untrusted on every supported platform.
use std::path::{Path, PathBuf};

fn safe_name(name: &str) -> bool {
    !name.is_empty()
        && name != "."
        && name != ".."
        && !name.ends_with(['.', ' '])
        && !name.chars().any(|c| {
            c.is_control() || matches!(c, '/' | '\\' | ':' | '<' | '>' | '"' | '|' | '?' | '*')
        })
        && !matches!(
            name.split('.')
                .next()
                .unwrap_or("")
                .to_ascii_uppercase()
                .as_str(),
            "CON"
                | "PRN"
                | "AUX"
                | "NUL"
                | "COM1"
                | "COM2"
                | "COM3"
                | "COM4"
                | "COM5"
                | "COM6"
                | "COM7"
                | "COM8"
                | "COM9"
                | "LPT1"
                | "LPT2"
                | "LPT3"
                | "LPT4"
                | "LPT5"
                | "LPT6"
                | "LPT7"
                | "LPT8"
                | "LPT9"
        )
}

/// Invalid names become a harmless basename. Refuse rather than overwrite when
/// every collision slot is occupied. symlink_metadata also sees dangling links.
pub fn destination(dir: &Path, decoded_name: &str) -> Option<PathBuf> {
    let name = if safe_name(decoded_name) {
        decoded_name
    } else {
        "download"
    };
    let path = Path::new(name);
    let stem = path.file_stem()?.to_str()?;
    let extension = path.extension().and_then(|s| s.to_str());
    for number in 1..10_000 {
        let basename = if number == 1 {
            name.to_string()
        } else {
            match extension {
                Some(ext) => format!("{stem} ({number}).{ext}"),
                None => format!("{stem} ({number})"),
            }
        };
        let candidate = dir.join(basename);
        match std::fs::symlink_metadata(&candidate) {
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Some(candidate),
            Ok(_) => continue,
            Err(_) => return None,
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    fn scratch() -> PathBuf {
        static NEXT: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
        let serial = NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let dir = std::env::temp_dir().join(format!(
            "nexa-download-test-{}-{}-{}",
            std::process::id(),
            serial,
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir(&dir).unwrap();
        dir
    }
    #[test]
    fn hostile_decoded_names_stay_in_downloads() {
        let dir = scratch();
        for name in [
            "../Library/LaunchAgents/payload.plist",
            "/tmp/payload",
            "..\\Startup\\payload.exe",
            "C:\\payload",
            "a:b",
            ".",
            "..",
            "",
            "NUL.txt",
            "CON",
            "file.",
            "file ",
            "a\nfile",
        ] {
            assert_eq!(
                destination(&dir, name),
                Some(dir.join("download")),
                "{name:?}"
            );
        }
        std::fs::remove_dir(dir).unwrap();
    }
    #[test]
    fn ordinary_names_and_collision_suffixes_work() {
        let dir = scratch();
        let name = "photo café.png";
        assert_eq!(destination(&dir, name), Some(dir.join(name)));
        std::fs::write(dir.join(name), b"existing").unwrap();
        assert_eq!(
            destination(&dir, name),
            Some(dir.join("photo café (2).png"))
        );
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn exhausted_collisions_refuse_download() {
        let dir = scratch();
        std::fs::write(dir.join("file.txt"), b"existing").unwrap();
        for number in 2..10_000 {
            std::fs::write(dir.join(format!("file ({number}).txt")), b"existing").unwrap();
        }
        assert_eq!(destination(&dir, "file.txt"), None);
        assert_eq!(std::fs::read(dir.join("file.txt")).unwrap(), b"existing");
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[cfg(unix)]
    #[test]
    fn dangling_symlinks_are_not_download_targets() {
        let dir = scratch();
        std::os::unix::fs::symlink(dir.join("absent"), dir.join("photo.png")).unwrap();
        assert_eq!(
            destination(&dir, "photo.png"),
            Some(dir.join("photo (2).png"))
        );
        assert!(!dir.join("absent").exists());
        std::fs::remove_dir_all(dir).unwrap();
    }
}
