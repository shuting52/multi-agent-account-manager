use crate::storage::{now_ms, normalize_uuid, AccountKey, ExternalAgentKind, ExternalAgentRecord};
use url::Url;
use uuid::Uuid;

use serde_json::Value;
#[cfg(desktop)]
use std::io::{Read, Write};
#[cfg(desktop)]
use std::process::{Command, Stdio};
#[cfg(desktop)]
use std::time::{Duration, Instant};

#[derive(Debug, Clone)]
#[allow(dead_code)]
pub enum ResolvedMemberTransport {
  WebView { key: AccountKey },
  OpenAiApi { agent: ExternalAgentRecord },
  LocalCli { agent: ExternalAgentRecord },
}

#[allow(clippy::too_many_arguments)]
pub fn normalize_external_agent_input(
  id: Option<&str>,
  kind: ExternalAgentKind,
  name: &str,
  base_url: &str,
  api_key: &str,
  model: &str,
  command: &str,
  args: Vec<String>,
  workdir: Option<&str>,
  created_at_ms: Option<u64>,
) -> Result<ExternalAgentRecord, String> {
  let clean_id = match id.map(str::trim).filter(|s| !s.is_empty()) {
    Some(raw) => normalize_uuid(raw).ok_or_else(|| "外部成员 ID 格式非法".to_string())?,
    None => Uuid::new_v4().hyphenated().to_string(),
  };
  let clean_name: String = name.trim().chars().take(40).collect();
  if clean_name.is_empty() {
    return Err("成员名称不能为空".to_string());
  }

  match kind {
    ExternalAgentKind::OpenaiApi => {
      let raw_url = base_url.trim();
      if raw_url.is_empty() {
        return Err("API Base URL 不能为空".to_string());
      }
      let candidate = if raw_url.contains("://") {
        raw_url.to_string()
      } else {
        format!("https://{raw_url}")
      };
      let parsed = Url::parse(&candidate).map_err(|_| "API Base URL 格式无效".to_string())?;
      let host = parsed.host_str().unwrap_or("").to_ascii_lowercase();
      let is_local_http = parsed.scheme() == "http"
        && matches!(host.as_str(), "localhost" | "127.0.0.1" | "[::1]" | "::1");
      if parsed.scheme() != "https" && !is_local_http {
        return Err("API Base URL 仅支持 https:// 或本机 http://localhost".to_string());
      }
      if host.is_empty() {
        return Err("API Base URL 缺少有效主机名".to_string());
      }
      let clean_model: String = model.trim().chars().take(120).collect();
      if clean_model.is_empty() {
        return Err("请填写模型名称（Model）".to_string());
      }
      Ok(ExternalAgentRecord {
        id: clean_id,
        kind,
        name: clean_name,
        base_url: parsed.to_string().trim_end_matches('/').to_string(),
        api_key: api_key.trim().chars().take(512).collect(),
        model: clean_model,
        command: String::new(),
        args: Vec::new(),
        workdir: None,
        created_at_ms: created_at_ms.unwrap_or_else(now_ms),
      })
    }
    ExternalAgentKind::LocalCli => {
      let clean_cmd = command.trim();
      if clean_cmd.is_empty() {
        return Err("CLI 命令不能为空（例如 claude 或 codex）".to_string());
      }
      if clean_cmd.contains('\n') || clean_cmd.contains('\r') || clean_cmd.contains('\0') {
        return Err("CLI 命令包含非法控制字符".to_string());
      }
      if clean_cmd.chars().count() > 260 {
        return Err("CLI 命令长度不能超过 260 个字符".to_string());
      }
      let clean_args: Vec<String> = args
        .into_iter()
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .take(24)
        .collect();
      let clean_workdir = workdir
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(|s| s.chars().take(260).collect());
      Ok(ExternalAgentRecord {
        id: clean_id,
        kind,
        name: clean_name,
        base_url: String::new(),
        api_key: String::new(),
        model: String::new(),
        command: clean_cmd.to_string(),
        args: clean_args,
        workdir: clean_workdir,
        created_at_ms: created_at_ms.unwrap_or_else(now_ms),
      })
    }
  }
}

#[cfg_attr(not(desktop), allow(dead_code))]
pub fn build_openai_endpoint(base_url: &str) -> String {
  let clean = base_url.trim().trim_end_matches('/');
  if clean.ends_with("/chat/completions") {
    clean.to_string()
  } else {
    format!("{clean}/chat/completions")
  }
}

#[cfg_attr(not(desktop), allow(dead_code))]
pub fn extract_openai_sse_delta(line: &str) -> Option<String> {
  // 纯 serde_json 解析，全平台可用（execute_openai_api_turn 本身仍限桌面端，
  // 见其上方的注释：reqwest 只在桌面端目标的依赖里）。
  let trimmed = line.trim();
  let payload = trimmed.strip_prefix("data:")?.trim();
  if payload.is_empty() || payload == "[DONE]" {
    return None;
  }
  let parsed: Value = serde_json::from_str(payload).ok()?;
  let choice = parsed.get("choices")?.get(0)?;
  if let Some(text) = choice
    .get("delta")
    .and_then(|d| d.get("content"))
    .and_then(|v| v.as_str())
  {
    if !text.is_empty() {
      return Some(text.to_string());
    }
  }
  if let Some(text) = choice
    .get("message")
    .and_then(|m| m.get("content"))
    .and_then(|v| v.as_str())
  {
    if !text.is_empty() {
      return Some(text.to_string());
    }
  }
  if let Some(text) = choice.get("text").and_then(|v| v.as_str()) {
    if !text.is_empty() {
      return Some(text.to_string());
    }
  }
  None
}

#[cfg_attr(not(desktop), allow(dead_code))]
pub fn build_cli_args_and_stdin(args: &[String], prompt: &str) -> (Vec<String>, Option<String>) {
  let uses_placeholder = args.iter().any(|a| a.contains("{prompt}"));
  if uses_placeholder {
    let resolved = args
      .iter()
      .map(|a| a.replace("{prompt}", prompt))
      .collect::<Vec<_>>();
    (resolved, None)
  } else {
    (args.to_vec(), Some(prompt.to_string()))
  }
}

// 注意：本函数保持桌面端限定——它用的 reqwest 只在 Cargo.toml 的
// [target.'cfg(not(any(target_os = "android", target_os = "ios")))']
// 依赖里，移动端目标上没有该 crate；放行它必须改 Cargo.toml（不在本次任务内）。
// 移动端分派处（execute_external_member_turn）对 OpenAI API 成员返回明确错误。
#[cfg(desktop)]
pub async fn execute_openai_api_turn<F>(
  agent: &ExternalAgentRecord,
  system_prompt: &str,
  user_prompt: &str,
  mut on_progress: F,
) -> Result<String, String>
where
  F: FnMut(String),
{
  let endpoint = build_openai_endpoint(&agent.base_url);
  let client = reqwest::Client::builder()
    .connect_timeout(Duration::from_secs(15))
    .timeout(Duration::from_secs(120))
    .build()
    .map_err(|e| format!("初始化 API HTTP 客户端失败: {e}"))?;

  let mut messages = Vec::new();
  if !system_prompt.trim().is_empty() {
    messages.push(serde_json::json!({
      "role": "system",
      "content": system_prompt.trim()
    }));
  }
  messages.push(serde_json::json!({
    "role": "user",
    "content": user_prompt
  }));

  let body = serde_json::json!({
    "model": agent.model,
    "stream": true,
    "messages": messages,
  });

  let body_bytes = serde_json::to_vec(&body).map_err(|e| format!("序列化 API 请求失败: {e}"))?;
  let mut req = client
    .post(&endpoint)
    .header(reqwest::header::CONTENT_TYPE, "application/json")
    .body(body_bytes);
  if !agent.api_key.trim().is_empty() {
    req = req.bearer_auth(agent.api_key.trim());
  }

  let mut resp = req
    .send()
    .await
    .map_err(|e| format!("请求模型 API 失败: {e}"))?;

  let status = resp.status();
  if !status.is_success() {
    let raw_err = resp.text().await.unwrap_or_default();
    let msg = serde_json::from_str::<Value>(&raw_err)
      .ok()
      .and_then(|v| {
        v.get("error")
          .and_then(|e| e.get("message"))
          .and_then(|m| m.as_str())
          .map(|s| s.to_string())
      })
      .unwrap_or_else(|| raw_err.chars().take(240).collect());
    return Err(format!("HTTP {}: {}", status.as_u16(), msg.trim()));
  }

  let content_type = resp
    .headers()
    .get(reqwest::header::CONTENT_TYPE)
    .and_then(|v| v.to_str().ok())
    .unwrap_or("")
    .to_ascii_lowercase();

  if !content_type.contains("text/event-stream") && content_type.contains("json") {
    let raw_json = resp
      .text()
      .await
      .map_err(|e| format!("读取模型响应失败: {e}"))?;
    let parsed: Value =
      serde_json::from_str(&raw_json).map_err(|e| format!("解析模型 JSON 失败: {e}"))?;
    let reply = parsed
      .get("choices")
      .and_then(|c| c.get(0))
      .and_then(|c0| c0.get("message"))
      .and_then(|m| m.get("content"))
      .and_then(|v| v.as_str())
      .map(|s| s.trim().to_string())
      .filter(|s| !s.is_empty())
      .ok_or_else(|| "API 返回的 JSON 中缺少回复内容".to_string())?;
    return Ok(reply);
  }

  let mut accumulated = String::new();
  let mut line_buf: Vec<u8> = Vec::new();
  let mut raw_fallback: Vec<u8> = Vec::new();
  let mut last_emit = Instant::now() - Duration::from_secs(1);

  while let Some(chunk) = resp
    .chunk()
    .await
    .map_err(|e| format!("流式读取 API 响应中断: {e}"))?
  {
    if raw_fallback.len() < 16384 {
      let take = (16384 - raw_fallback.len()).min(chunk.len());
      raw_fallback.extend_from_slice(&chunk[..take]);
    }
    line_buf.extend_from_slice(&chunk);
    while let Some(pos) = line_buf.iter().position(|&b| b == b'\n') {
      let line_bytes: Vec<u8> = line_buf.drain(..=pos).collect();
      let line = String::from_utf8_lossy(&line_bytes);
      if let Some(delta) = extract_openai_sse_delta(&line) {
        accumulated.push_str(&delta);
      }
    }
    if !accumulated.is_empty() && last_emit.elapsed() >= Duration::from_millis(180) {
      last_emit = Instant::now();
      on_progress(accumulated.trim().to_string());
    }
  }

  if !line_buf.is_empty() {
    let line = String::from_utf8_lossy(&line_buf);
    if let Some(delta) = extract_openai_sse_delta(&line) {
      accumulated.push_str(&delta);
    }
  }

  if accumulated.trim().is_empty() && !raw_fallback.is_empty() {
    let text_fallback = String::from_utf8_lossy(&raw_fallback);
    if let Ok(parsed) = serde_json::from_str::<Value>(text_fallback.trim()) {
      if let Some(content) = parsed
        .get("choices")
        .and_then(|c| c.get(0))
        .and_then(|c0| c0.get("message"))
        .and_then(|m| m.get("content"))
        .and_then(|v| v.as_str())
      {
        accumulated = content.to_string();
      }
    }
  }

  let final_text = accumulated.trim().to_string();
  if final_text.is_empty() {
    return Err("模型 API 未返回有效回复文本".to_string());
  }
  Ok(final_text)
}

#[cfg(desktop)]
fn build_augmented_path() -> Option<String> {
  #[cfg(unix)]
  {
    let cur = std::env::var("PATH").unwrap_or_default();
    let home = std::env::var("HOME").unwrap_or_default();
    let mut parts: Vec<String> = if cur.is_empty() {
      Vec::new()
    } else {
      cur.split(':').map(|s| s.to_string()).collect()
    };
    let mut extras = vec![
      "/opt/homebrew/bin".to_string(),
      "/usr/local/bin".to_string(),
      "/usr/bin".to_string(),
      "/bin".to_string(),
    ];
    if !home.is_empty() {
      extras.push(format!("{home}/.local/bin"));
      extras.push(format!("{home}/.npm-global/bin"));
      extras.push(format!("{home}/.cargo/bin"));
    }
    for ex in extras {
      if !parts.iter().any(|p| p == &ex) {
        parts.push(ex);
      }
    }
    Some(parts.join(":"))
  }
  #[cfg(not(unix))]
  {
    None
  }
}

#[cfg(desktop)]
enum CliStreamEvent {
  Stdout(String),
  Stderr(String),
  Finished(Result<Option<i32>, String>),
}

#[cfg(desktop)]
pub async fn execute_local_cli_turn<F>(
  agent: &ExternalAgentRecord,
  full_prompt: &str,
  mut on_progress: F,
) -> Result<String, String>
where
  F: FnMut(String),
{
  let (final_args, stdin_payload) = build_cli_args_and_stdin(&agent.args, full_prompt);
  let mut cmd = Command::new(&agent.command);
  cmd.args(&final_args);
  if let Some(dir) = agent.workdir.as_deref().filter(|d| !d.trim().is_empty()) {
    cmd.current_dir(dir);
  }
  if let Some(path_val) = build_augmented_path() {
    cmd.env("PATH", path_val);
  }
  if stdin_payload.is_some() {
    cmd.stdin(Stdio::piped());
  } else {
    cmd.stdin(Stdio::null());
  }
  cmd.stdout(Stdio::piped());
  cmd.stderr(Stdio::piped());

  #[cfg(windows)]
  {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x08000000;
    cmd.creation_flags(CREATE_NO_WINDOW);
  }

  let mut child = cmd
    .spawn()
    .map_err(|e| format!("启动本地 CLI「{}」失败: {e}", agent.command))?;

  if let (Some(mut stdin_handle), Some(input_text)) = (child.stdin.take(), stdin_payload) {
    std::thread::spawn(move || {
      let _ = stdin_handle.write_all(input_text.as_bytes());
      let _ = stdin_handle.flush();
    });
  }

  let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<CliStreamEvent>();

  if let Some(mut stdout) = child.stdout.take() {
    let tx_out = tx.clone();
    std::thread::spawn(move || {
      let mut buf = [0u8; 4096];
      while let Ok(n) = stdout.read(&mut buf) {
        if n == 0 {
          break;
        }
        let s = String::from_utf8_lossy(&buf[..n]).to_string();
        let _ = tx_out.send(CliStreamEvent::Stdout(s));
      }
    });
  }

  if let Some(mut stderr) = child.stderr.take() {
    let tx_err = tx.clone();
    std::thread::spawn(move || {
      let mut buf = [0u8; 2048];
      while let Ok(n) = stderr.read(&mut buf) {
        if n == 0 {
          break;
        }
        let s = String::from_utf8_lossy(&buf[..n]).to_string();
        let _ = tx_err.send(CliStreamEvent::Stderr(s));
      }
    });
  }

  let (kill_tx, kill_rx) = std::sync::mpsc::channel::<()>();
  let tx_done = tx.clone();
  drop(tx);
  std::thread::spawn(move || {
    loop {
      match child.try_wait() {
        Ok(Some(status)) => {
          let _ = tx_done.send(CliStreamEvent::Finished(Ok(status.code())));
          return;
        }
        Ok(None) => {
          if kill_rx.try_recv().is_ok() {
            let _ = child.kill();
            let _ = child.wait();
            let _ = tx_done.send(CliStreamEvent::Finished(Err(
              "本地 CLI 执行超时（120 秒）".to_string(),
            )));
            return;
          }
          std::thread::sleep(Duration::from_millis(80));
        }
        Err(err) => {
          let _ = tx_done.send(CliStreamEvent::Finished(Err(format!(
            "等待 CLI 进程退出失败: {err}"
          ))));
          return;
        }
      }
    }
  });

  let mut stdout_acc = String::new();
  let mut stderr_acc = String::new();
  let mut exit_result: Option<Result<Option<i32>, String>> = None;
  let mut last_emit = Instant::now() - Duration::from_secs(1);
  let deadline = tokio::time::Instant::now() + Duration::from_secs(120);

  loop {
    let remain = deadline.saturating_duration_since(tokio::time::Instant::now());
    if remain.is_zero() {
      let _ = kill_tx.send(());
      return Err("本地 CLI 执行超时（120 秒）".to_string());
    }
    match tokio::time::timeout(remain, rx.recv()).await {
      Ok(Some(CliStreamEvent::Stdout(chunk))) => {
        stdout_acc.push_str(&chunk);
        if last_emit.elapsed() >= Duration::from_millis(180) && !stdout_acc.trim().is_empty() {
          last_emit = Instant::now();
          on_progress(stdout_acc.trim().to_string());
        }
      }
      Ok(Some(CliStreamEvent::Stderr(chunk))) => {
        if stderr_acc.len() < 2048 {
          stderr_acc.push_str(&chunk);
        }
      }
      Ok(Some(CliStreamEvent::Finished(res))) => {
        exit_result = Some(res);
      }
      Ok(None) => break,
      Err(_) => {
        let _ = kill_tx.send(());
        return Err("本地 CLI 执行超时（120 秒）".to_string());
      }
    }
  }

  let final_out = stdout_acc.trim().to_string();
  match exit_result {
    Some(Err(timeout_err)) => Err(timeout_err),
    Some(Ok(Some(code))) if code != 0 && final_out.is_empty() => {
      let err_detail: String = stderr_acc.trim().chars().take(240).collect();
      if err_detail.is_empty() {
        Err(format!("本地 CLI 退出码 {code}，未返回内容"))
      } else {
        Err(format!("CLI 错误({code}): {err_detail}"))
      }
    }
    _ => {
      if final_out.is_empty() {
        let err_detail: String = stderr_acc.trim().chars().take(240).collect();
        if err_detail.is_empty() {
          Err("本地 CLI 未输出有效内容".to_string())
        } else {
          Err(err_detail)
        }
      } else {
        Ok(final_out)
      }
    }
  }
}

#[cfg(test)]
mod tests {
  use super::{
    build_cli_args_and_stdin, build_openai_endpoint, extract_openai_sse_delta,
    normalize_external_agent_input,
  };
  use crate::storage::ExternalAgentKind;

  #[test]
  fn openai_endpoint_and_sse_delta_parse_cleanly() {
    assert_eq!(
      build_openai_endpoint("https://api.deepseek.com/v1/"),
      "https://api.deepseek.com/v1/chat/completions"
    );
    assert_eq!(
      build_openai_endpoint("https://api.openai.com/v1/chat/completions"),
      "https://api.openai.com/v1/chat/completions"
    );

    let chunks = [
      "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}",
      "data: {\"choices\":[{\"delta\":{\"content\":\"Valkey-\"}}]}",
      "data: {\"choices\":[{\"delta\":{\"content\":\"9921\"}}]}",
      "data: [DONE]",
    ];
    let combined: String = chunks
      .iter()
      .filter_map(|line| extract_openai_sse_delta(line))
      .collect();
    assert_eq!(combined, "Valkey-9921");
  }

  #[test]
  fn cli_args_placeholder_or_stdin_fallback() {
    let (args1, stdin1) =
      build_cli_args_and_stdin(&["-p".into(), "{prompt}".into()], "hello world");
    assert_eq!(args1, vec!["-p", "hello world"]);
    assert_eq!(stdin1, None);

    let (args2, stdin2) = build_cli_args_and_stdin(&["exec".into()], "hello world");
    assert_eq!(args2, vec!["exec"]);
    assert_eq!(stdin2.as_deref(), Some("hello world"));
  }

  #[test]
  fn normalize_external_agent_validates_api_and_cli() {
    let bad_url = normalize_external_agent_input(
      None,
      ExternalAgentKind::OpenaiApi,
      "BadApi",
      "http://example.com/v1",
      "sk-1",
      "gpt-4o",
      "",
      vec![],
      None,
      None,
    );
    assert!(bad_url.is_err());

    let local_ok = normalize_external_agent_input(
      None,
      ExternalAgentKind::OpenaiApi,
      "LocalOllama",
      "http://127.0.0.1:11434/v1/",
      "",
      "qwen2.5",
      "",
      vec![],
      None,
      None,
    )
    .expect("localhost http should succeed");
    assert_eq!(local_ok.base_url, "http://127.0.0.1:11434/v1");

    let cli_ok = normalize_external_agent_input(
      None,
      ExternalAgentKind::LocalCli,
      "Claude Code",
      "",
      "",
      "",
      "claude",
      vec!["-p".into()],
      Some("/tmp"),
      None,
    )
    .expect("cli agent should succeed");
    assert_eq!(cli_ok.command, "claude");
    assert_eq!(cli_ok.account_key().provider_id.as_str(), "cli");
  }
}
