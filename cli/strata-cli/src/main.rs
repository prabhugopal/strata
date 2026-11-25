//! `strata`: inspect and drive Strata nodes through their management API.
//!
//!   strata server add dev http://localhost:8080
//!   strata types
//!   strata entities counter
//!   strata call counter/alice add 5
//!   strata get counter/alice
//!   strata events --follow

mod config;
mod output;

use clap::{Parser, Subcommand, ValueEnum};
use config::{Config, Server};
use serde_json::Value;
use std::io::{BufRead, IsTerminal, Read};
use std::process::ExitCode;
use std::time::Duration;
use strata_client::{Endpoint, Settings, Strata};

#[derive(Parser)]
#[command(name = "strata", version, about = "Inspect and drive Strata nodes", propagate_version = true)]
struct Cli {
    /// Server to talk to (a name from `strata server list`); defaults to the one set with `server use`.
    #[arg(short, long, global = true, env = "STRATA_SERVER")]
    server: Option<String>,
    /// Talk to this URL directly instead of a named server (token from STRATA_TOKEN).
    #[arg(long, global = true, env = "STRATA_URL", conflicts_with = "server")]
    url: Option<String>,
    /// Output format.
    #[arg(short = 'F', long, global = true, value_enum, default_value_t = Format::Text)]
    format: Format,
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Clone, Copy, ValueEnum, PartialEq)]
enum Format { Text, Json }

#[derive(Subcommand)]
enum Cmd {
    /// Manage the servers this CLI knows (kept in ~/.strata/config.toml).
    #[command(subcommand)]
    Server(ServerCmd),
    /// The node: version, store, uptime.
    Info,
    /// Cluster members and where entities live.
    Cluster,
    /// Entity types and their commands.
    Types,
    /// Entities of a type (stored and in memory).
    Entities {
        #[arg(value_name = "TYPE")]
        entity_type: String,
        /// Start after this id (from the previous page).
        #[arg(long)]
        after: Option<String>,
        #[arg(long)]
        limit: Option<u32>,
        /// Follow pages until the end.
        #[arg(long)]
        all: bool,
    },
    /// An entity's committed state: TYPE/ID.
    Get { entity: String },
    /// Run a command: TYPE/ID COMMAND [ARG]. ARG is JSON (a bare word is sent as a string);
    /// use - to read it from stdin.
    Call {
        entity: String,
        command: String,
        #[arg(allow_negative_numbers = true)]
        arg: Option<String>,
    },
    /// Recent events on the node.
    Events {
        #[arg(long = "type", value_name = "TYPE")]
        entity_type: Option<String>,
        #[arg(long)]
        id: Option<String>,
        /// Only events after this sequence number.
        #[arg(long)]
        after: Option<u64>,
        /// Keep printing new events.
        #[arg(short, long)]
        follow: bool,
    },
    /// Metrics: per-command totals, or the raw Prometheus text with --raw.
    Metrics {
        #[arg(long)]
        raw: bool,
    },
}

#[derive(Subcommand)]
enum ServerCmd {
    /// Add (or replace) a server after checking it answers as a Strata node.
    Add {
        name: String,
        url: String,
        /// Read the management token from stdin (never pass tokens as arguments).
        #[arg(long)]
        token_stdin: bool,
        /// Make it the default server.
        #[arg(long = "use")]
        make_default: bool,
    },
    List,
    Remove { name: String },
    /// Make a server the default.
    Use { name: String },
}

fn main() -> ExitCode {
    let cli = Cli::parse();
    let rt = tokio::runtime::Runtime::new().expect("tokio runtime");
    match rt.block_on(run(cli)) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("strata: {e}");
            ExitCode::FAILURE
        }
    }
}

type R<T> = Result<T, String>;

async fn run(cli: Cli) -> R<()> {
    let mut cfg = Config::load(&config::home_dir())?;
    let json = cli.format == Format::Json;
    if let Cmd::Server(cmd) = cli.cmd {
        return server_cmd(&mut cfg, cmd, json).await;
    }
    let (name, server) = resolve(&cfg, cli.server.as_deref(), cli.url.as_deref())?;
    let node = client(&cfg, &server)?;
    let s = &cfg.settings;
    match cli.cmd {
        Cmd::Server(_) => unreachable!(),
        Cmd::Info => {
            let v = node.get(&Endpoint::Info).await.map_err(|e| e.to_string())?;
            if json { output::json(&v) } else { output::info(&name, &v) }
        }
        Cmd::Cluster => {
            let v = node.get(&Endpoint::Cluster).await.map_err(|e| e.to_string())?;
            if json { output::json(&v) } else { output::cluster(&v) }
        }
        Cmd::Types => {
            let v = node.get(&Endpoint::Types).await.map_err(|e| e.to_string())?;
            if json { output::json(&v) } else { output::types(&v) }
        }
        Cmd::Entities { entity_type, after, limit, all } => {
            let mut after = after;
            let mut items = vec![];
            loop {
                let v = node.get(&Endpoint::Entities { entity_type: entity_type.clone(), after: after.clone(), limit: Some(limit.unwrap_or(s.page)) })
                    .await.map_err(|e| e.to_string())?;
                items.extend(v["items"].as_array().cloned().unwrap_or_default());
                after = v["next"].as_str().map(str::to_string);
                if !all || after.is_none() { break; }
            }
            if json { output::json(&serde_json::json!({ "items": items, "next": after })) } else { output::entities(&items, after.as_deref()) }
        }
        Cmd::Get { entity } => {
            let (t, id) = split(&entity)?;
            let v = node.get(&Endpoint::Entity { entity_type: t.into(), id: id.into() }).await.map_err(|e| e.to_string())?;
            if json { output::json(&v) } else { output::entity(&v) }
        }
        Cmd::Call { entity, command, arg } => {
            let (t, id) = split(&entity)?;
            let body = argument(arg)?;
            let v = node.invoke(t, id, &command, body).await.map_err(|e| e.to_string())?;
            if json { output::json(&v) } else { output::json(&v["result"]) }
        }
        Cmd::Events { entity_type, id, after, follow } => {
            let mut after = after.unwrap_or(0);
            loop {
                let v = node.get(&Endpoint::Events { after: Some(after), entity_type: entity_type.clone(), id: id.clone(), limit: Some(s.events_per_poll) })
                    .await.map_err(|e| e.to_string())?;
                let items = v["items"].as_array().cloned().unwrap_or_default();
                let last = v["lastSeq"].as_u64().unwrap_or(after);
                if last < after { after = 0; continue; } // the node restarted
                for e in &items {
                    if json { println!("{e}"); } else { output::event(e); }
                }
                after = last;
                if !follow { break; }
                tokio::time::sleep(Duration::from_millis(s.follow_interval_ms)).await;
            }
        }
        Cmd::Metrics { raw } => {
            let v = node.get(&Endpoint::Metrics).await.map_err(|e| e.to_string())?;
            let text = v["text"].as_str().unwrap_or_default();
            if raw { print!("{text}"); } else if json { output::json(&output::metrics_json(text)) } else { output::metrics(text) }
        }
    }
    Ok(())
}

async fn server_cmd(cfg: &mut Config, cmd: ServerCmd, json: bool) -> R<()> {
    match cmd {
        ServerCmd::Add { name, url, token_stdin, make_default } => {
            if name.is_empty() || name.len() > 64 || !name.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_') {
                return Err("server names are 1-64 letters, digits, - or _".into());
            }
            let token = if token_stdin {
                if std::io::stdin().is_terminal() { eprint!("Management token: "); }
                let mut line = String::new();
                std::io::stdin().lock().read_line(&mut line).map_err(|e| e.to_string())?;
                Some(line.trim().to_string()).filter(|t| !t.is_empty())
            } else {
                std::env::var("STRATA_TOKEN").ok().filter(|t| !t.is_empty())
            };
            let server = Server { url: url.trim_end_matches('/').to_string(), token };
            let info = client(cfg, &server)?.get(&Endpoint::Info).await.map_err(|e| e.to_string())?;
            if info["product"] != "strata" {
                return Err(format!("{url} answered, but not as a Strata node"));
            }
            cfg.file.servers.insert(name.clone(), server);
            if make_default || cfg.file.default.is_none() { cfg.file.default = Some(name.clone()); }
            cfg.save()?;
            eprintln!("Added {name}: {} ({} store, Strata {})", info["node"].as_str().unwrap_or("?"), info["store"].as_str().unwrap_or("?"), info["version"].as_str().unwrap_or("?"));
        }
        ServerCmd::List => {
            if json {
                let list: Vec<Value> = cfg.file.servers.iter().map(|(n, s)| serde_json::json!({
                    "name": n, "url": s.url, "hasToken": s.token.is_some(), "default": cfg.file.default.as_deref() == Some(n),
                })).collect();
                output::json(&Value::Array(list));
            } else if cfg.file.servers.is_empty() {
                eprintln!("No servers yet. Add one: strata server add dev http://localhost:8080");
            } else {
                let rows: Vec<Vec<String>> = cfg.file.servers.iter().map(|(n, s)| vec![
                    if cfg.file.default.as_deref() == Some(n) { format!("* {n}") } else { format!("  {n}") },
                    s.url.clone(), if s.token.is_some() { "token".into() } else { "-".into() },
                ]).collect();
                output::table(&["SERVER", "URL", "AUTH"], &rows);
            }
        }
        ServerCmd::Remove { name } => {
            if cfg.file.servers.remove(&name).is_none() { return Err(format!("no server named {name}")); }
            if cfg.file.default.as_deref() == Some(&name) { cfg.file.default = cfg.file.servers.keys().next().cloned(); }
            cfg.save()?;
            eprintln!("Removed {name}");
        }
        ServerCmd::Use { name } => {
            if !cfg.file.servers.contains_key(&name) { return Err(format!("no server named {name}")); }
            cfg.file.default = Some(name.clone());
            cfg.save()?;
            eprintln!("Using {name}");
        }
    }
    Ok(())
}

fn resolve(cfg: &Config, name: Option<&str>, url: Option<&str>) -> R<(String, Server)> {
    if let Some(u) = url {
        return Ok((u.to_string(), Server { url: u.to_string(), token: std::env::var("STRATA_TOKEN").ok().filter(|t| !t.is_empty()) }));
    }
    let name = name.map(str::to_string).or_else(|| cfg.file.default.clone())
        .ok_or("no server chosen: add one with `strata server add NAME URL`, or pass --url")?;
    let server = cfg.file.servers.get(&name).cloned().ok_or_else(|| format!("no server named {name} (see `strata server list`)"))?;
    Ok((name, server))
}

fn client(cfg: &Config, s: &Server) -> R<Strata> {
    Strata::new(Settings {
        url: s.url.clone(), token: s.token.clone(), base_path: cfg.settings.base_path.clone(),
        timeout: Duration::from_secs(cfg.settings.timeout),
    }).map_err(|e| e.to_string())
}

/// `type/id`, where the id may itself contain slashes.
fn split(entity: &str) -> R<(&str, &str)> {
    match entity.split_once('/') {
        Some((t, id)) if !t.is_empty() && !id.is_empty() => Ok((t, id)),
        _ => Err(format!("expected TYPE/ID, e.g. counter/alice (got {entity})")),
    }
}

/// The JSON argument: as given if it's JSON, a bare word as a string, `-` from stdin.
fn argument(arg: Option<String>) -> R<Option<String>> {
    let Some(a) = arg else { return Ok(None) };
    let text = if a == "-" {
        let mut s = String::new();
        std::io::stdin().read_to_string(&mut s).map_err(|e| e.to_string())?;
        s
    } else { a };
    let text = text.trim().to_string();
    Ok(Some(if serde_json::from_str::<Value>(&text).is_ok() { text } else { Value::String(text).to_string() }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn entity_refs_and_arguments() {
        assert_eq!(split("counter/alice").unwrap(), ("counter", "alice"));
        assert_eq!(split("files/a/b").unwrap(), ("files", "a/b"));
        assert!(split("counter").is_err() && split("/x").is_err() && split("x/").is_err());
        assert_eq!(argument(Some("5".into())).unwrap().as_deref(), Some("5"));
        assert_eq!(argument(Some(r#"{"a":1}"#.into())).unwrap().as_deref(), Some(r#"{"a":1}"#));
        assert_eq!(argument(Some("apple".into())).unwrap().as_deref(), Some(r#""apple""#));
        assert_eq!(argument(None).unwrap(), None);
    }
}
