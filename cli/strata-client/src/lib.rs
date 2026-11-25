//! Typed access to a Strata node's management API (`GET /strata/info`, `/types`, entities,
//! events, metrics; `POST` to run a command). Paths are built from typed [`Endpoint`]s with
//! every name percent-encoded as one path segment, so callers can't reach other URLs.

use serde::Deserialize;
use serde_json::{json, Value};
use std::time::{Duration, Instant};

#[derive(Debug, thiserror::Error)]
pub enum StrataError {
    #[error("{0}")]
    Invalid(String),
    #[error("Could not connect to {0}")]
    Unreachable(String),
    #[error("Timed out after {0}s")]
    Timeout(u64),
    #[error("The management token was refused (HTTP 401). Set the server's token in Stratum.")]
    Unauthorized,
    #[error("{message} (HTTP {status})")]
    Http { status: u16, message: String },
    #[error("{0}")]
    Failed(String),
}

impl serde::Serialize for StrataError {
    fn serialize<S: serde::Serializer>(&self, s: S) -> std::result::Result<S::Ok, S::Error> {
        s.serialize_str(&self.to_string())
    }
}

pub type Result<T> = std::result::Result<T, StrataError>;

/// What to read from a node.
#[derive(Debug, Clone, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum Endpoint {
    Info,
    Cluster,
    Types,
    #[serde(rename_all = "camelCase")]
    Entities { entity_type: String, after: Option<String>, limit: Option<u32> },
    #[serde(rename_all = "camelCase")]
    Entity { entity_type: String, id: String },
    #[serde(rename_all = "camelCase")]
    Events { after: Option<u64>, entity_type: Option<String>, id: Option<String>, limit: Option<u32> },
    Metrics,
}

/// Connection settings for one node.
#[derive(Debug, Clone)]
pub struct Settings {
    pub url: String,
    pub token: Option<String>,
    pub base_path: String,
    pub timeout: Duration,
}

pub struct Strata {
    http: reqwest::Client,
    base: reqwest::Url,
    token: Option<String>,
    timeout: Duration,
}

const MAX_NAME: usize = 1024;

fn name(what: &str, v: &str) -> Result<()> {
    if v.is_empty() || v.len() > MAX_NAME || v.chars().any(char::is_control) {
        Err(StrataError::Invalid(format!("Invalid {what}")))
    } else {
        Ok(())
    }
}

impl Strata {
    pub fn new(s: Settings) -> Result<Self> {
        if !(s.url.starts_with("http://") || s.url.starts_with("https://")) || s.url.chars().any(|c| c.is_whitespace() || c.is_control()) {
            return Err(StrataError::Invalid("URL must start with http:// or https://".into()));
        }
        if !s.base_path.starts_with('/') {
            return Err(StrataError::Invalid("strata.base_path must start with '/'".into()));
        }
        let mut base = reqwest::Url::parse(&s.url).map_err(|e| StrataError::Invalid(format!("Invalid URL: {e}")))?;
        {
            let mut segs = base.path_segments_mut().map_err(|_| StrataError::Invalid("URL can't have a path".into()))?;
            segs.pop_if_empty();
            for part in s.base_path.split('/').filter(|p| !p.is_empty()) {
                segs.push(part);
            }
        }
        if let Some(t) = &s.token {
            if t.is_empty() || t.chars().any(char::is_control) {
                return Err(StrataError::Invalid("Invalid token".into()));
            }
        }
        let http = reqwest::Client::builder().timeout(s.timeout).build().map_err(|e| StrataError::Failed(e.to_string()))?;
        Ok(Self { http, base, token: s.token, timeout: s.timeout })
    }

    /// The URL for a path below the management base, each part one encoded segment.
    pub fn url(&self, parts: &[&str], query: &[(&str, String)]) -> reqwest::Url {
        let mut u = self.base.clone();
        {
            let mut segs = u.path_segments_mut().expect("base is a hierarchical URL");
            for p in parts {
                segs.push(p);
            }
        }
        if !query.is_empty() {
            u.query_pairs_mut().extend_pairs(query.iter().map(|(k, v)| (*k, v.as_str())));
        }
        u
    }

    pub fn endpoint_url(&self, e: &Endpoint) -> Result<reqwest::Url> {
        Ok(match e {
            Endpoint::Info => self.url(&["info"], &[]),
            Endpoint::Cluster => self.url(&["cluster"], &[]),
            Endpoint::Types => self.url(&["types"], &[]),
            Endpoint::Metrics => self.url(&["metrics"], &[]),
            Endpoint::Entities { entity_type, after, limit } => {
                name("entity type", entity_type)?;
                let mut q = vec![];
                if let Some(a) = after { q.push(("after", a.clone())); }
                if let Some(l) = limit { q.push(("limit", l.to_string())); }
                self.url(&["types", entity_type, "entities"], &q)
            }
            Endpoint::Entity { entity_type, id } => {
                name("entity type", entity_type)?;
                name("entity id", id)?;
                self.url(&["types", entity_type, "entities", id], &[])
            }
            Endpoint::Events { after, entity_type, id, limit } => {
                let mut q = vec![];
                if let Some(a) = after { q.push(("after", a.to_string())); }
                if let Some(t) = entity_type { name("entity type", t)?; q.push(("type", t.clone())); }
                if let Some(i) = id { name("entity id", i)?; q.push(("id", i.clone())); }
                if let Some(l) = limit { q.push(("limit", l.to_string())); }
                self.url(&["events"], &q)
            }
        })
    }

    /// Read an endpoint. JSON endpoints return their JSON; metrics returns `{"text": "..."}`.
    pub async fn get(&self, e: &Endpoint) -> Result<Value> {
        let url = self.endpoint_url(e)?;
        let (status, text, _) = self.send(reqwest::Method::GET, url, None).await?;
        check(status, &text)?;
        if matches!(e, Endpoint::Metrics) {
            return Ok(json!({ "text": text }));
        }
        serde_json::from_str(&text).map_err(|e| StrataError::Failed(format!("Not a Strata management API response: {e}")))
    }

    /// Run a command. `body` is the JSON argument (None for none). Returns `{result, durationMicros, ms}`.
    pub async fn invoke(&self, entity_type: &str, id: &str, command: &str, body: Option<String>) -> Result<Value> {
        name("entity type", entity_type)?;
        name("entity id", id)?;
        name("command", command)?;
        if let Some(b) = &body {
            serde_json::from_str::<Value>(b).map_err(|e| StrataError::Invalid(format!("The argument isn't valid JSON: {e}")))?;
        }
        let url = self.url(&["types", entity_type, "entities", id, command], &[]);
        let (status, text, ms) = self.send(reqwest::Method::POST, url, body).await?;
        check(status, &text)?;
        let mut v: Value = serde_json::from_str(&text).map_err(|e| StrataError::Failed(e.to_string()))?;
        v["ms"] = json!(ms);
        Ok(v)
    }

    async fn send(&self, m: reqwest::Method, url: reqwest::Url, body: Option<String>) -> Result<(u16, String, f64)> {
        let shown = format!("{}://{}", url.scheme(), url.host_str().unwrap_or("?"));
        let mut req = self.http.request(m, url);
        if let Some(t) = &self.token {
            req = req.bearer_auth(t);
        }
        if let Some(b) = body {
            req = req.header("content-type", "application/json").body(b);
        }
        let started = Instant::now();
        let resp = req.send().await.map_err(|e| {
            if e.is_timeout() { StrataError::Timeout(self.timeout.as_secs()) }
            else if e.is_connect() { StrataError::Unreachable(shown.clone()) }
            else { StrataError::Failed(e.to_string()) }
        })?;
        let status = resp.status().as_u16();
        let text = resp.text().await.map_err(|e| StrataError::Failed(e.to_string()))?;
        Ok((status, text, started.elapsed().as_secs_f64() * 1000.0))
    }
}

fn check(status: u16, text: &str) -> Result<()> {
    match status {
        200..=299 => Ok(()),
        401 => Err(StrataError::Unauthorized),
        _ => {
            let message = serde_json::from_str::<Value>(text).ok()
                .and_then(|v| v.get("error").and_then(|e| e.as_str()).map(str::to_string))
                .unwrap_or_else(|| text.chars().take(300).collect());
            Err(StrataError::Http { status, message })
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    fn client(url: &str, token: Option<&str>) -> Strata {
        Strata::new(Settings { url: url.into(), token: token.map(str::to_string), base_path: "/strata".into(), timeout: Duration::from_secs(5) }).unwrap()
    }

    #[test]
    fn names_are_single_encoded_segments() {
        let s = client("http://localhost:8080/", None);
        let u = s.endpoint_url(&Endpoint::Entity { entity_type: "cart".into(), id: "a/../b c?x".into() }).unwrap();
        assert_eq!(u.as_str(), "http://localhost:8080/strata/types/cart/entities/a%2F..%2Fb%20c%3Fx");
        let u = s.endpoint_url(&Endpoint::Events { after: Some(7), entity_type: Some("cart".into()), id: None, limit: None }).unwrap();
        assert_eq!(u.as_str(), "http://localhost:8080/strata/events?after=7&type=cart");
        assert!(s.endpoint_url(&Endpoint::Entity { entity_type: "".into(), id: "x".into() }).is_err());
    }

    #[test]
    fn rejects_bad_settings() {
        for url in ["ftp://x", "localhost:8080", "http://a b"] {
            assert!(Strata::new(Settings { url: url.into(), token: None, base_path: "/strata".into(), timeout: Duration::from_secs(1) }).is_err());
        }
        assert!(Strata::new(Settings { url: "http://x".into(), token: Some("a\nb".into()), base_path: "/strata".into(), timeout: Duration::from_secs(1) }).is_err());
    }

    /// One canned HTTP response; returns the request it received.
    async fn serve_once(status: &'static str, body: &'static str) -> (String, tokio::task::JoinHandle<String>) {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let handle = tokio::spawn(async move {
            let (mut sock, _) = listener.accept().await.unwrap();
            let mut buf = vec![0u8; 8192];
            let n = sock.read(&mut buf).await.unwrap();
            let resp = format!("HTTP/1.1 {status}\r\ncontent-type: application/json\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{body}", body.len());
            sock.write_all(resp.as_bytes()).await.unwrap();
            String::from_utf8_lossy(&buf[..n]).to_string()
        });
        (url, handle)
    }

    #[tokio::test]
    async fn sends_token_and_reads_json() {
        let (url, req) = serve_once("200 OK", r#"{"product":"strata"}"#).await;
        let v = client(&url, Some("t0k")).get(&Endpoint::Info).await.unwrap();
        assert_eq!(v["product"], "strata");
        let req = req.await.unwrap();
        assert!(req.starts_with("GET /strata/info "));
        assert!(req.to_lowercase().contains("authorization: bearer t0k"));
    }

    #[tokio::test]
    async fn maps_errors() {
        let (url, _) = serve_once("401 Unauthorized", r#"{"error":"no"}"#).await;
        assert!(matches!(client(&url, None).get(&Endpoint::Types).await, Err(StrataError::Unauthorized)));
        let (url, _) = serve_once("422 Unprocessable Entity", r#"{"error":"insufficient funds"}"#).await;
        let e = client(&url, None).invoke("account", "a", "withdraw", Some("5".into())).await.unwrap_err();
        assert_eq!(e.to_string(), "insufficient funds (HTTP 422)");
        assert!(matches!(client("http://127.0.0.1:9", None).get(&Endpoint::Info).await, Err(StrataError::Unreachable(_))));
        assert!(matches!(client(&url, None).invoke("a", "b", "c", Some("{bad".into())).await, Err(StrataError::Invalid(_))));
    }
}
