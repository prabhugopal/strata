//! Human-readable output. `-F json` prints the management API's JSON instead.

use serde_json::{json, Value};

pub fn json(v: &Value) {
    println!("{}", serde_json::to_string_pretty(v).unwrap_or_default());
}

/// Left-aligned columns sized to their content.
pub fn table(headers: &[&str], rows: &[Vec<String>]) {
    let mut w: Vec<usize> = headers.iter().map(|h| h.chars().count()).collect();
    for r in rows {
        for (i, c) in r.iter().enumerate() {
            w[i] = w[i].max(c.chars().count());
        }
    }
    let line = |cells: Vec<String>| {
        let parts: Vec<String> = cells.iter().enumerate().map(|(i, c)| format!("{c:<width$}", width = w[i])).collect();
        println!("{}", parts.join("  ").trim_end());
    };
    line(headers.iter().map(|h| h.to_string()).collect());
    for r in rows {
        line(r.clone());
    }
}

fn s(v: &Value) -> String {
    match v {
        Value::Null => "-".into(),
        Value::String(x) => x.clone(),
        Value::Number(n) if n.as_f64().is_some_and(|f| f.fract() == 0.0 && f.abs() < 1e15) => format!("{}", n.as_f64().unwrap() as i64),
        other => other.to_string(),
    }
}

fn short_node(addr: &str) -> String {
    addr.split_once('@').map(|(_, a)| a.to_string()).unwrap_or_else(|| addr.to_string())
}

pub fn info(name: &str, v: &Value) {
    println!("{name}: Strata {} on {}", s(&v["version"]), short_node(&s(&v["node"])));
    println!("  store       {}", s(&v["store"]));
    println!("  durability  {}", s(&v["defaultDurability"]));
    println!("  up for      {}s", s(&v["uptimeSeconds"]));
    println!("  types       {}", s(&v["types"]));
    println!("  commands    {}", if v["allowInvoke"] == true { "allowed" } else { "read-only" });
}

pub fn cluster(v: &Value) {
    let by_type = v["entitiesByTypeAndNode"].as_object().cloned().unwrap_or_default();
    let rows: Vec<Vec<String>> = v["members"].as_array().into_iter().flatten().map(|m| {
        let addr = s(&m["address"]);
        let n: u64 = by_type.values().map(|per| per[&addr].as_u64().unwrap_or(0)).sum();
        let mut tags = vec![];
        if m["address"] == v["self"] { tags.push("this node"); }
        if m["address"] == v["leader"] { tags.push("leader"); }
        vec![short_node(&addr), if m["reachable"] == true { s(&m["status"]) } else { "unreachable".into() }, n.to_string(), tags.join(", ")]
    }).collect();
    table(&["NODE", "STATUS", "ENTITIES", ""], &rows);
}

pub fn types(v: &Value) {
    for t in v.as_array().into_iter().flatten() {
        println!("{}  ({}, {} in memory{})", s(&t["name"]), s(&t["durability"]), s(&t["live"]),
            t["mount"].as_str().map(|m| format!(", HTTP {m}")).unwrap_or_default());
        let rows: Vec<Vec<String>> = t["commands"].as_array().into_iter().flatten().map(|c| {
            let params = c["params"].as_array().map(|ps| ps.iter().map(|p| format!("{}: {}", s(&p["name"]), s(&p["schema"]["type"]).replace('-', "any"))).collect::<Vec<_>>().join(", "));
            let takes = params.unwrap_or_else(|| if c["argument"].is_null() { String::new() } else { "argument".into() });
            let route = c["route"].as_object().map(|r| format!("{} {}", s(&r["method"]), s(&r["path"]))).unwrap_or_default();
            vec![format!("  {}", s(&c["name"])), if c["readOnly"] == true { "query".into() } else { "command".into() }, format!("({takes})"), route]
        }).collect();
        table(&["  COMMAND", "KIND", "TAKES", "ROUTE"], &rows);
        println!();
    }
}

pub fn entities(items: &[Value], next: Option<&str>) {
    let rows: Vec<Vec<String>> = items.iter().map(|e| vec![
        s(&e["id"]), if e["live"] == true { "in memory".into() } else { "stored".into() }, s(&e["revision"]), s(&e["updatedAt"]),
    ]).collect();
    table(&["ID", "WHERE", "REVISION", "UPDATED"], &rows);
    if let Some(n) = next {
        eprintln!("More: --after {n} (or --all)");
    }
}

pub fn entity(v: &Value) {
    eprintln!("{}/{}  revision {}  {}  {}", s(&v["type"]), s(&v["id"]), s(&v["revision"]),
        if v["live"] == true { "in memory" } else { "stored" }, s(&v["durability"]));
    json(&v["state"]);
}

pub fn event(e: &Value) {
    let at = s(&e["at"]);
    let time = at.split('T').nth(1).map(|t| t.trim_end_matches('Z')).map(|t| &t[..t.len().min(12)]).unwrap_or(&at).to_string();
    let what = e["command"].as_str().map(|c| format!(" {c}")).unwrap_or_default();
    let rev = e["revision"].as_u64().map(|r| format!(" -> r{r}")).unwrap_or_default();
    let ms = e["durationMicros"].as_f64().map(|u| format!(" {:.1}ms", u / 1000.0)).unwrap_or_default();
    let err = e["error"].as_str().map(|x| format!("  {x}")).unwrap_or_default();
    println!("{time}  {:<9} {}/{}{what}{rev}{ms}{err}", s(&e["kind"]), s(&e["type"]), s(&e["id"]));
}

/// Per type/command totals since start, from the Prometheus text.
pub fn metrics_json(text: &str) -> Value {
    let mut rows: std::collections::BTreeMap<(String, String), [f64; 4]> = Default::default(); // ok, failed, sum s, count
    for line in text.lines().filter(|l| !l.starts_with('#')) {
        let Some((head, value)) = line.rsplit_once(' ') else { continue };
        let Ok(value) = value.parse::<f64>() else { continue };
        let (name, labels) = head.split_once('{').map(|(n, l)| (n, l.trim_end_matches('}'))).unwrap_or((head, ""));
        let label = |k: &str| labels.split(',').find_map(|p| p.strip_prefix(&format!("{k}=\""))).map(|v| v.trim_end_matches('"').to_string());
        let (Some(t), Some(c)) = (label("type"), label("command")) else { continue };
        let r = rows.entry((t, c)).or_default();
        match name {
            "strata_commands_total" => if label("outcome").as_deref() == Some("ok") { r[0] += value } else { r[1] += value },
            "strata_command_duration_seconds_sum" => r[2] += value,
            "strata_command_duration_seconds_count" => r[3] += value,
            _ => {}
        }
    }
    Value::Array(rows.into_iter().map(|((t, c), r)| json!({
        "type": t, "command": c, "ok": r[0], "failed": r[1], "avgMs": if r[3] > 0.0 { Some(r[2] / r[3] * 1000.0) } else { None },
    })).collect())
}

pub fn metrics(text: &str) {
    let rows: Vec<Vec<String>> = metrics_json(text).as_array().into_iter().flatten().map(|r| vec![
        s(&r["type"]), s(&r["command"]), s(&r["ok"]), s(&r["failed"]),
        r["avgMs"].as_f64().map(|v| format!("{v:.2}")).unwrap_or("-".into()),
    ]).collect();
    if rows.is_empty() { eprintln!("No commands yet."); return; }
    table(&["TYPE", "COMMAND", "OK", "FAILED", "AVG MS"], &rows);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn metrics_summary() {
        let text = "# TYPE x counter\nstrata_commands_total{type=\"counter\",command=\"add\",outcome=\"ok\"} 3\nstrata_commands_total{type=\"counter\",command=\"add\",outcome=\"failed\"} 1\nstrata_command_duration_seconds_sum{type=\"counter\",command=\"add\"} 0.004\nstrata_command_duration_seconds_count{type=\"counter\",command=\"add\"} 4\n";
        let v = metrics_json(text);
        assert_eq!(v[0]["ok"], 3.0);
        assert_eq!(v[0]["failed"], 1.0);
        assert_eq!(v[0]["avgMs"], 1.0);
    }
}
