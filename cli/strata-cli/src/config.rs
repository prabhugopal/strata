//! `~/.strata/config.toml`: settings (over the built-in defaults) and named servers.
//! Written readable only by the user, because server entries may hold tokens.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

const DEFAULTS: &str = include_str!("../defaults.toml");
pub const FILE: &str = "config.toml";

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Settings {
    pub base_path: String,
    pub timeout: u64,
    pub page: u32,
    pub follow_interval_ms: u64,
    pub events_per_poll: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Server {
    pub url: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub token: Option<String>,
}

/// The user's file as stored: only what they set.
#[derive(Debug, Default, Serialize, Deserialize)]
pub struct UserFile {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub default: Option<String>,
    #[serde(default, skip_serializing_if = "toml::Table::is_empty")]
    pub settings: toml::Table,
    #[serde(default)]
    pub servers: BTreeMap<String, Server>,
}

#[derive(Debug)]
pub struct Config {
    pub dir: PathBuf,
    pub file: UserFile,
    pub settings: Settings,
}

/// `$STRATA_HOME`, else `~/.strata`.
pub fn home_dir() -> PathBuf {
    if let Some(d) = std::env::var_os("STRATA_HOME").filter(|v| !v.is_empty()) {
        return PathBuf::from(d);
    }
    std::env::var_os("HOME").map(PathBuf::from).unwrap_or_else(|| PathBuf::from(".")).join(".strata")
}

impl Config {
    pub fn load(dir: &Path) -> Result<Self, String> {
        let path = dir.join(FILE);
        let file: UserFile = match std::fs::read_to_string(&path) {
            Ok(text) => toml::from_str(&text).map_err(|e| format!("{}: {e}", path.display()))?,
            Err(_) => UserFile::default(),
        };
        let mut merged: toml::Table = toml::from_str::<toml::Table>(DEFAULTS).expect("built-in defaults parse")["settings"]
            .as_table().expect("[settings]").clone();
        for (k, v) in &file.settings {
            if !merged.contains_key(k) {
                return Err(format!("{}: unknown setting [settings].{k}", path.display()));
            }
            merged.insert(k.clone(), v.clone());
        }
        let settings: Settings = toml::Value::Table(merged).try_into().map_err(|e| format!("{}: {e}", path.display()))?;
        Ok(Self { dir: dir.to_path_buf(), file, settings })
    }

    pub fn save(&self) -> Result<(), String> {
        std::fs::create_dir_all(&self.dir).map_err(|e| e.to_string())?;
        let path = self.dir.join(FILE);
        let text = toml::to_string_pretty(&self.file).map_err(|e| e.to_string())?;
        let tmp = path.with_extension("tmp");
        {
            use std::io::Write;
            let mut opts = std::fs::OpenOptions::new();
            opts.write(true).create(true).truncate(true);
            #[cfg(unix)]
            {
                use std::os::unix::fs::OpenOptionsExt;
                opts.mode(0o600);
            }
            opts.open(&tmp).and_then(|mut f| f.write_all(text.as_bytes())).map_err(|e| format!("{}: {e}", tmp.display()))?;
        }
        std::fs::rename(&tmp, &path).map_err(|e| e.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_overrides_and_private_file() {
        let dir = tempfile::tempdir().unwrap();
        let mut c = Config::load(dir.path()).unwrap();
        assert_eq!(c.settings.base_path, "/strata");
        c.file.settings.insert("timeout".into(), toml::Value::Integer(3));
        c.file.servers.insert("dev".into(), Server { url: "http://localhost:8080".into(), token: Some("t".into()) });
        c.file.default = Some("dev".into());
        c.save().unwrap();
        let again = Config::load(dir.path()).unwrap();
        assert_eq!(again.settings.timeout, 3);
        assert_eq!(again.settings.page, 100);
        assert_eq!(again.file.servers["dev"].token.as_deref(), Some("t"));
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(std::fs::metadata(dir.path().join(FILE)).unwrap().permissions().mode() & 0o777, 0o600);
        }
        std::fs::write(dir.path().join(FILE), "[settings]\nnope = 1\n").unwrap();
        assert!(Config::load(dir.path()).unwrap_err().contains("unknown setting"));
    }
}
