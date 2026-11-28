use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use tauri::AppHandle;
#[cfg(desktop)]
use tauri::Emitter;
use url::Url;

/// 更新清单只走 raw：仓库固定路径 releases/latest.json（每版覆盖写）。
/// 检查与下载全程不经过 github.com / api.github.com，不跳 Release 页。
const LATEST_MANIFEST_URL: &str =
  "https://raw.githubusercontent.com/shuting52/multi-agent-account-manager/main/releases/latest.json";

static UPDATE_IN_FLIGHT: AtomicBool = AtomicBool::new(false);

struct UpdateLockGuard;

impl UpdateLockGuard {
  fn try_acquire() -> Result<Self, String> {
    if UPDATE_IN_FLIGHT
      .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
      .is_err()
    {
      return Err("已有更新任务正在进行中".to_string());
    }
    Ok(Self)
  }
}

impl Drop for UpdateLockGuard {
  fn drop(&mut self) {
    UPDATE_IN_FLIGHT.store(false, Ordering::SeqCst);
  }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateCheckResult {
  pub current_version: String,
  pub latest_version: String,
  pub has_update: bool,
  pub release_notes: String,
  pub pub_date: Option<String>,
  pub download_url: Option<String>,
  pub sha256: Option<String>,
  pub filename: Option<String>,
  pub size: Option<u64>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallUpdateRequest {
  pub version: String,
  pub download_url: String,
  pub sha256: Option<String>,
  pub filename: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallUpdateOutcome {
  pub status: String,
  pub message: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateProgressEvent {
  pub stage: String,
  pub downloaded: u64,
  pub total: Option<u64>,
  pub percent: Option<u8>,
  pub message: String,
}

#[derive(Debug, Deserialize)]
struct LatestManifest {
  version: String,
  #[serde(default)]
  notes: String,
  #[serde(default)]
  pub_date: Option<String>,
  #[serde(default)]
  platforms: HashMap<String, LatestPlatformAsset>,
}

#[derive(Debug, Deserialize)]
struct LatestPlatformAsset {
  url: String,
  #[serde(default)]
  sha256: Option<String>,
  #[serde(default)]
  filename: Option<String>,
  #[serde(default)]
  size: Option<u64>,
}

fn clean_version(raw: &str) -> &str {
  raw.trim().trim_start_matches(['v', 'V'])
}

#[derive(Debug, Eq, PartialEq)]
struct ParsedVersion {
  nums: Vec<u64>,
  is_prerelease: bool,
}

fn parse_version(raw: &str) -> ParsedVersion {
  let no_prefix = clean_version(raw);
  let no_build = no_prefix.split('+').next().unwrap_or(no_prefix);
  let (core, is_prerelease) = match no_build.split_once('-') {
    Some((c, pre)) => (c, !pre.trim().is_empty()),
    None => (no_build, false),
  };
  let nums = core
    .split('.')
    .map(|seg| seg.trim().parse::<u64>().unwrap_or(0))
    .collect();
  ParsedVersion { nums, is_prerelease }
}

fn is_newer_version(current: &str, latest: &str) -> bool {
  let c = parse_version(current);
  let l = parse_version(latest);
  let max_len = c.nums.len().max(l.nums.len());
  for i in 0..max_len {
    let cv = *c.nums.get(i).unwrap_or(&0);
    let lv = *l.nums.get(i).unwrap_or(&0);
    if lv != cv {
      return lv > cv;
    }
  }
  c.is_prerelease && !l.is_prerelease
}

fn is_allowed_github_host(host: &str) -> bool {
  let lower = host.to_ascii_lowercase();
  lower == "github.com"
    || lower.ends_with(".github.com")
    || lower == "githubusercontent.com"
    || lower.ends_with(".githubusercontent.com")
}

fn validate_download_url(raw: &str) -> Result<Url, String> {
  let parsed = Url::parse(raw.trim()).map_err(|_| "无效的更新包下载地址".to_string())?;
  if parsed.scheme() != "https" {
    return Err("仅允许通过 HTTPS 下载更新包".to_string());
  }
  let host = parsed.host_str().ok_or_else(|| "更新地址缺少主机名".to_string())?;
  if !is_allowed_github_host(host) {
    return Err(format!("不支持的更新源主机: {host}"));
  }
  Ok(parsed)
}

fn sanitize_filename(raw: Option<&str>, fallback: &str) -> String {
  let base = raw
    .unwrap_or("")
    .rsplit(['/', '\\'])
    .next()
    .unwrap_or("")
    .trim();
  let cleaned: String = base
    .chars()
    .map(|c| {
      if c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-') {
        c
      } else {
        '_'
      }
    })
    .collect();
  let trimmed = cleaned.trim_start_matches('.').to_string();
  if trimmed.is_empty() || trimmed == ".." {
    fallback.to_string()
  } else {
    trimmed
  }
}

fn default_filename_for_platform(version: &str, url: &str) -> String {
  let from_url = Url::parse(url)
    .ok()
    .and_then(|u| u.path_segments().and_then(|mut s| s.next_back().map(str::to_string)));
  if let Some(name) = from_url {
    if !name.trim().is_empty() {
      return sanitize_filename(Some(&name), "update.bin");
    }
  }
  let ver = clean_version(version);
  match crate::platform::current_platform_name() {
    "macos" => format!("account-manager-{ver}-macos.app.tar.gz"),
    "windows" => format!("account-manager-{ver}-windows-setup.exe"),
    "android" => format!("account-manager-{ver}-android.apk"),
    "ios" => "account-manager-ios-unsigned.ipa".to_string(),
    _ => format!("account-manager-{ver}.bin"),
  }
}

fn extract_sha256_digest(raw: Option<&str>) -> Option<String> {
  let clean = raw?.trim();
  if clean.is_empty() {
    return None;
  }
  let hex = clean
    .strip_prefix("sha256:")
    .or_else(|| clean.strip_prefix("SHA256:"))
    .unwrap_or(clean)
    .trim();
  if hex.len() == 64 && hex.chars().all(|c| c.is_ascii_hexdigit()) {
    Some(hex.to_ascii_lowercase())
  } else {
    None
  }
}

#[cfg(desktop)]
fn build_http_client(user_agent: &str, timeout_secs: u64) -> Result<reqwest::Client, String> {
  reqwest::Client::builder()
    .user_agent(user_agent)
    .connect_timeout(std::time::Duration::from_secs(15))
    .timeout(std::time::Duration::from_secs(timeout_secs))
    .redirect(reqwest::redirect::Policy::limited(10))
    .build()
    .map_err(|e| format!("创建网络客户端失败: {e}"))
}

async fn fetch_text_with_status(
  app: &AppHandle,
  url: &str,
  user_agent: &str,
  accept: Option<&str>,
) -> Result<(u16, String), String> {
  #[cfg(desktop)]
  {
    let _ = app;
    let client = build_http_client(user_agent, 25)?;
    let mut req = client.get(url);
    if let Some(acc) = accept {
      req = req.header("Accept", acc);
    }
    let resp = req
      .send()
      .await
      .map_err(|e| format!("检查更新网络请求失败: {e}"))?;
    if resp.url().scheme() != "https" {
      return Err("更新响应重定向到了非 HTTPS 地址".to_string());
    }
    let status = resp.status().as_u16();
    let body = resp
      .text()
      .await
      .map_err(|e| format!("读取更新响应内容失败: {e}"))?;
    Ok((status, body))
  }

  #[cfg(mobile)]
  {
    let res = crate::platform::mobile::fetch_text(app, url, user_agent, accept).await?;
    Ok((res.status, res.body))
  }
}

pub async fn check_for_updates(app: &AppHandle) -> Result<UpdateCheckResult, String> {
  let current_version = app.package_info().version.to_string();
  let platform_key = crate::platform::current_platform_name();
  let user_agent = format!("multi-agent-account-manager/{current_version}");

  // 只读 raw 上的清单，没有第二条路：拉不到或解析失败就直接报错，
  // 不再回退到 GitHub Release / api.github.com。
  let (status, body) = fetch_text_with_status(
    app,
    LATEST_MANIFEST_URL,
    &user_agent,
    Some("application/json"),
  )
  .await?;

  if !(200..=299).contains(&status) {
    return Err(format!("检查更新失败（更新清单 HTTP {status}）"));
  }

  let manifest: LatestManifest =
    serde_json::from_str(&body).map_err(|e| format!("解析更新清单失败: {e}"))?;
  let latest_version = clean_version(&manifest.version).to_string();
  let asset = manifest.platforms.get(platform_key);
  let has_update = is_newer_version(&current_version, &latest_version);
  Ok(UpdateCheckResult {
    current_version,
    latest_version: if latest_version.is_empty() {
      app.package_info().version.to_string()
    } else {
      latest_version
    },
    has_update,
    release_notes: manifest.notes,
    pub_date: manifest.pub_date,
    download_url: asset.map(|a| a.url.clone()),
    sha256: asset.and_then(|a| extract_sha256_digest(a.sha256.as_deref())),
    filename: asset.and_then(|a| a.filename.clone()),
    size: asset.and_then(|a| a.size),
  })
}

#[cfg(desktop)]
fn emit_progress(
  app: &AppHandle,
  stage: &str,
  downloaded: u64,
  total: Option<u64>,
  percent: Option<u8>,
  message: &str,
) {
  let _ = app.emit(
    "update-progress",
    UpdateProgressEvent {
      stage: stage.to_string(),
      downloaded,
      total,
      percent,
      message: message.to_string(),
    },
  );
}

#[cfg(desktop)]
async fn download_to_updates_cache(
  app: &AppHandle,
  url: &str,
  expected_sha256: Option<&str>,
  filename: &str,
  user_agent: &str,
) -> Result<(std::path::PathBuf, u64), String> {
  use sha2::{Digest, Sha256};
  use std::io::Write;
  use tauri::Manager;

  let cache_root = app
    .path()
    .app_cache_dir()
    .map_err(|e| format!("获取缓存目录失败: {e}"))?;
  let updates_dir = cache_root.join("updates");
  std::fs::create_dir_all(&updates_dir).map_err(|e| format!("创建更新目录失败: {e}"))?;

  let dest_path = updates_dir.join(filename);
  let expected_hex = extract_sha256_digest(expected_sha256);

  if let Some(ref want) = expected_hex {
    if dest_path.is_file() {
      if let Ok(actual) = compute_file_sha256_hex(&dest_path) {
        if actual == *want {
          let len = std::fs::metadata(&dest_path).map(|m| m.len()).unwrap_or(0);
          return Ok((dest_path, len));
        }
      }
    }
  }

  let part_path = updates_dir.join(format!("{filename}.part"));
  let _ = std::fs::remove_file(&part_path);

  emit_progress(app, "downloading", 0, None, Some(0), "正在连接下载服务器...");

  let client = build_http_client(user_agent, 600)?;
  let mut resp = client
    .get(url)
    .send()
    .await
    .map_err(|e| format!("下载更新包失败: {e}"))?;

  if resp.url().scheme() != "https" {
    return Err("下载响应重定向到了非 HTTPS 地址".to_string());
  }
  if !resp.status().is_success() {
    return Err(format!("下载更新包失败（HTTP {}）", resp.status().as_u16()));
  }

  let total = resp.content_length().filter(|&n| n > 0);
  let mut file =
    std::fs::File::create(&part_path).map_err(|e| format!("创建临时更新文件失败: {e}"))?;
  let mut hasher = Sha256::new();
  let mut downloaded: u64 = 0;
  let mut last_emit = std::time::Instant::now();
  let mut last_percent: Option<u8> = None;

  while let Some(chunk) = resp
    .chunk()
    .await
    .map_err(|e| {
      let _ = std::fs::remove_file(&part_path);
      format!("读取下载数据失败: {e}")
    })?
  {
    file.write_all(&chunk).map_err(|e| {
      let _ = std::fs::remove_file(&part_path);
      format!("写入临时更新文件失败: {e}")
    })?;
    hasher.update(&chunk);
    downloaded = downloaded.saturating_add(chunk.len() as u64);

    let pct = total.map(|t| ((downloaded.saturating_mul(100)) / t).min(100) as u8);
    if last_emit.elapsed() >= std::time::Duration::from_millis(150) || (pct.is_some() && pct != last_percent) {
      last_emit = std::time::Instant::now();
      last_percent = pct;
      emit_progress(app, "downloading", downloaded, total, pct, "正在下载更新包...");
    }
  }

  file.flush().map_err(|e| format!("刷新缓存失败: {e}"))?;
  drop(file);

  emit_progress(
    app,
    "verifying",
    downloaded,
    total.or(Some(downloaded)),
    Some(100),
    "正在校验更新包完整性...",
  );

  let actual_hex = format!("{:x}", hasher.finalize());
  if let Some(want) = expected_hex {
    if actual_hex != want {
      let _ = std::fs::remove_file(&part_path);
      return Err("更新包 SHA-256 校验失败，请重试".to_string());
    }
  }

  if dest_path.exists() {
    let _ = std::fs::remove_file(&dest_path);
  }
  std::fs::rename(&part_path, &dest_path).map_err(|e| {
    let _ = std::fs::remove_file(&part_path);
    format!("保存更新包失败: {e}")
  })?;

  if let Ok(entries) = std::fs::read_dir(&updates_dir) {
    for entry in entries.flatten() {
      let p = entry.path();
      if p != dest_path {
        if p.is_dir() {
          let _ = std::fs::remove_dir_all(&p);
        } else {
          let _ = std::fs::remove_file(&p);
        }
      }
    }
  }

  Ok((dest_path, downloaded))
}

#[cfg(desktop)]
fn compute_file_sha256_hex(path: &std::path::Path) -> Result<String, std::io::Error> {
  use sha2::{Digest, Sha256};
  use std::io::Read;

  let mut file = std::fs::File::open(path)?;
  let mut hasher = Sha256::new();
  let mut buf = [0u8; 16 * 1024];
  loop {
    let n = file.read(&mut buf)?;
    if n == 0 {
      break;
    }
    hasher.update(&buf[..n]);
  }
  Ok(format!("{:x}", hasher.finalize()))
}

#[cfg(target_os = "macos")]
fn find_current_app_bundle() -> Option<std::path::PathBuf> {
  let exe = std::env::current_exe().ok()?;
  let mut cur = exe.as_path();
  while let Some(parent) = cur.parent() {
    if parent
      .extension()
      .and_then(|s| s.to_str())
      .map(|ext| ext.eq_ignore_ascii_case("app"))
      .unwrap_or(false)
    {
      return Some(parent.to_path_buf());
    }
    cur = parent;
  }
  None
}

#[cfg(target_os = "macos")]
fn has_write_permission(dir: &std::path::Path) -> bool {
  let probe = dir.join(format!(".maam-write-probe-{}", std::process::id()));
  match std::fs::OpenOptions::new()
    .write(true)
    .create_new(true)
    .open(&probe)
  {
    Ok(_) => {
      let _ = std::fs::remove_file(&probe);
      true
    }
    Err(_) => false,
  }
}

#[cfg(target_os = "macos")]
async fn install_macos_update(
  app: &AppHandle,
  dest_path: &std::path::Path,
  downloaded: u64,
) -> Result<InstallUpdateOutcome, String> {
  let lower_name = dest_path
    .file_name()
    .and_then(|n| n.to_str())
    .unwrap_or("")
    .to_ascii_lowercase();

  if lower_name.ends_with(".dmg") {
    open::that_detached(dest_path).map_err(|e| format!("打开 DMG 安装包失败: {e}"))?;
    let msg = "已打开 DMG 镜像，请将应用拖入 Applications 完成覆盖安装";
    emit_progress(app, "ready", downloaded, Some(downloaded), Some(100), msg);
    return Ok(InstallUpdateOutcome {
      status: "installer_launched".to_string(),
      message: msg.to_string(),
    });
  }

  emit_progress(
    app,
    "installing",
    downloaded,
    Some(downloaded),
    Some(100),
    "正在解压新版本应用...",
  );

  let parent_dir = dest_path
    .parent()
    .ok_or_else(|| "更新包路径异常".to_string())?
    .to_path_buf();
  let extract_dir = parent_dir.join(format!(
    "extract-{}",
    std::time::SystemTime::now()
      .duration_since(std::time::UNIX_EPOCH)
      .map(|d| d.as_secs())
      .unwrap_or(0)
  ));
  let archive_path = dest_path.to_path_buf();
  let extract_dir_for_task = extract_dir.clone();

  let extracted_app = tokio::task::spawn_blocking(move || -> Result<std::path::PathBuf, String> {
    if extract_dir_for_task.exists() {
      let _ = std::fs::remove_dir_all(&extract_dir_for_task);
    }
    std::fs::create_dir_all(&extract_dir_for_task)
      .map_err(|e| format!("创建解压目录失败: {e}"))?;

    let status = std::process::Command::new("/usr/bin/tar")
      .arg("-xzf")
      .arg(&archive_path)
      .arg("-C")
      .arg(&extract_dir_for_task)
      .status()
      .map_err(|e| format!("调用 tar 解压失败: {e}"))?;

    if !status.success() {
      return Err("解压更新压缩包失败".to_string());
    }

    let mut found_app = None;
    for entry in std::fs::read_dir(&extract_dir_for_task)
      .map_err(|e| format!("扫描解压目录失败: {e}"))?
      .flatten()
    {
      let p = entry.path();
      if p.is_dir()
        && p
          .extension()
          .and_then(|s| s.to_str())
          .map(|ext| ext.eq_ignore_ascii_case("app"))
          .unwrap_or(false)
      {
        found_app = Some(p);
        break;
      }
    }

    let app_bundle = found_app.ok_or_else(|| "更新包中未找到 .app 应用目录".to_string())?;
    let _ = std::process::Command::new("/usr/bin/xattr")
      .args(["-dr", "com.apple.quarantine"])
      .arg(&app_bundle)
      .status();
    Ok(app_bundle)
  })
  .await
  .map_err(|e| format!("解压任务中断: {e}"))??;

  let Some(current_app) = find_current_app_bundle() else {
    open::that_detached(&extract_dir).map_err(|e| format!("打开新版本目录失败: {e}"))?;
    let msg = "当前处于开发运行模式，已解压新版本到目录，请手动替换";
    emit_progress(app, "ready", downloaded, Some(downloaded), Some(100), msg);
    return Ok(InstallUpdateOutcome {
      status: "installer_launched".to_string(),
      message: msg.to_string(),
    });
  };

  let app_parent = current_app
    .parent()
    .ok_or_else(|| "无法定位当前应用所在目录".to_string())?;
  if !has_write_permission(app_parent) {
    open::that_detached(&extract_dir).map_err(|e| format!("打开新版本目录失败: {e}"))?;
    let msg = "当前应用目录无写入权限，已打开新版本所在目录，请手动拖入替换";
    emit_progress(app, "ready", downloaded, Some(downloaded), Some(100), msg);
    return Ok(InstallUpdateOutcome {
      status: "installer_launched".to_string(),
      message: msg.to_string(),
    });
  }

  let swap_script = r#"pid="$1"
src_app="$2"
dst_app="$3"
extract_dir="$4"
i=0
while kill -0 "$pid" 2>/dev/null && [ "$i" -lt 100 ]; do
  sleep 0.1
  i=$((i + 1))
done
backup="${dst_app}.maam-backup.$$"
rm -rf "$backup" 2>/dev/null || true
if [ -e "$dst_app" ]; then
  mv "$dst_app" "$backup" || exit 1
fi
if mv "$src_app" "$dst_app"; then
  rm -rf "$backup" "$extract_dir" 2>/dev/null || true
  /usr/bin/xattr -dr com.apple.quarantine "$dst_app" 2>/dev/null || true
  /usr/bin/open -n "$dst_app"
else
  if [ -e "$backup" ] && [ ! -e "$dst_app" ]; then
    mv "$backup" "$dst_app" 2>/dev/null || true
  fi
  exit 1
fi"#;

  std::process::Command::new("/bin/sh")
    .arg("-c")
    .arg(swap_script)
    .arg("_")
    .arg(std::process::id().to_string())
    .arg(&extracted_app)
    .arg(&current_app)
    .arg(&extract_dir)
    .stdin(std::process::Stdio::null())
    .stdout(std::process::Stdio::null())
    .stderr(std::process::Stdio::null())
    .spawn()
    .map_err(|e| format!("启动覆盖更新进程失败: {e}"))?;

  let msg = "更新已就绪，正在重启应用...";
  emit_progress(app, "ready", downloaded, Some(downloaded), Some(100), msg);

  let app_clone = app.clone();
  tauri::async_runtime::spawn(async move {
    tokio::time::sleep(std::time::Duration::from_millis(350)).await;
    app_clone.exit(0);
  });

  Ok(InstallUpdateOutcome {
    status: "restarting".to_string(),
    message: msg.to_string(),
  })
}

#[cfg(windows)]
async fn install_windows_update(
  app: &AppHandle,
  dest_path: &std::path::Path,
  downloaded: u64,
) -> Result<InstallUpdateOutcome, String> {
  let lower_name = dest_path
    .file_name()
    .and_then(|n| n.to_str())
    .unwrap_or("")
    .to_ascii_lowercase();
  if !lower_name.ends_with(".exe") {
    return Err("不支持的 Windows 安装包格式".to_string());
  }

  emit_progress(
    app,
    "installing",
    downloaded,
    Some(downloaded),
    Some(100),
    "正在启动安装程序...",
  );

  std::process::Command::new(dest_path)
    .args(["/P", "/UPDATE", "/R"])
    .spawn()
    .map_err(|e| format!("启动安装程序失败: {e}"))?;

  let msg = "已启动安装程序，正在退出并完成替换...";
  emit_progress(app, "ready", downloaded, Some(downloaded), Some(100), msg);

  let app_clone = app.clone();
  tauri::async_runtime::spawn(async move {
    tokio::time::sleep(std::time::Duration::from_millis(350)).await;
    app_clone.exit(0);
  });

  Ok(InstallUpdateOutcome {
    status: "restarting".to_string(),
    message: msg.to_string(),
  })
}

pub async fn download_and_install_update(
  app: &AppHandle,
  req: InstallUpdateRequest,
) -> Result<InstallUpdateOutcome, String> {
  let validated_url = validate_download_url(&req.download_url)?;
  let _guard = UpdateLockGuard::try_acquire()?;
  let current_version = app.package_info().version.to_string();
  let user_agent = format!("multi-agent-account-manager/{current_version}");
  let fallback_name = default_filename_for_platform(&req.version, validated_url.as_str());
  let filename = sanitize_filename(req.filename.as_deref(), &fallback_name);

  #[cfg(target_os = "android")]
  {
    let sha = extract_sha256_digest(req.sha256.as_deref());
    let res = crate::platform::mobile::download_and_install_apk(
      app,
      validated_url.as_str(),
      sha.as_deref(),
      &filename,
      &user_agent,
    )
    .await?;
    Ok(InstallUpdateOutcome {
      status: res.status,
      message: res.message,
    })
  }

  #[cfg(target_os = "ios")]
  {
    let _ = (filename, user_agent);
    crate::platform::mobile::open_external_url(app, validated_url.as_str()).await?;
    Ok(InstallUpdateOutcome {
      status: "installer_launched".to_string(),
      message: "已在 Safari 打开新版 IPA 下载地址，下载后请通过自签工具覆盖安装".to_string(),
    })
  }

  #[cfg(desktop)]
  {
    let (dest_path, downloaded) = match download_to_updates_cache(
      app,
      validated_url.as_str(),
      req.sha256.as_deref(),
      &filename,
      &user_agent,
    )
    .await
    {
      Ok(pair) => pair,
      Err(err) => {
        emit_progress(app, "error", 0, None, None, &err);
        return Err(err);
      }
    };

    #[cfg(target_os = "macos")]
    {
      match install_macos_update(app, &dest_path, downloaded).await {
        Ok(outcome) => Ok(outcome),
        Err(err) => {
          emit_progress(app, "error", downloaded, Some(downloaded), None, &err);
          Err(err)
        }
      }
    }

    #[cfg(windows)]
    {
      match install_windows_update(app, &dest_path, downloaded).await {
        Ok(outcome) => Ok(outcome),
        Err(err) => {
          emit_progress(app, "error", downloaded, Some(downloaded), None, &err);
          Err(err)
        }
      }
    }

    #[cfg(all(not(target_os = "macos"), not(windows)))]
    {
      open::that_detached(&dest_path).map_err(|e| format!("打开更新文件失败: {e}"))?;
      let msg = "已下载更新包并打开文件";
      emit_progress(app, "ready", downloaded, Some(downloaded), Some(100), msg);
      Ok(InstallUpdateOutcome {
        status: "installer_launched".to_string(),
        message: msg.to_string(),
      })
    }
  }
}

pub async fn install_local_apk(app: &AppHandle) -> Result<InstallUpdateOutcome, String> {
  #[cfg(target_os = "android")]
  {
    let res = crate::platform::mobile::pick_and_install_apk(app).await?;
    Ok(InstallUpdateOutcome {
      status: res.status,
      message: res.message,
    })
  }

  #[cfg(not(target_os = "android"))]
  {
    let _ = app;
    Err("导入本地 APK 仅支持 Android".to_string())
  }
}

/// 前端在下载更新前预检安装授权。Android 上透传插件的
/// checkInstallPermission；非 Android 直接返回可以安装（走桌面安装流程）。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallPermissionStatus {
  pub can_install: bool,
}

pub async fn check_install_permission(
  app: &AppHandle,
) -> Result<InstallPermissionStatus, String> {
  #[cfg(target_os = "android")]
  {
    let res = crate::platform::mobile::check_install_permission(app).await?;
    Ok(InstallPermissionStatus {
      can_install: res.can_install,
    })
  }

  #[cfg(not(target_os = "android"))]
  {
    let _ = app;
    Ok(InstallPermissionStatus { can_install: true })
  }
}

/// 打开系统「允许安装未知来源应用」设置页（本应用）。仅 Android。
pub async fn open_install_permission_settings(app: &AppHandle) -> Result<(), String> {
  #[cfg(target_os = "android")]
  {
    crate::platform::mobile::open_install_permission_settings(app).await
  }

  #[cfg(not(target_os = "android"))]
  {
    let _ = app;
    Err("安装授权设置仅支持 Android".to_string())
  }
}

pub async fn open_external_url(app: &AppHandle, raw_url: &str) -> Result<(), String> {
  let parsed = Url::parse(raw_url.trim()).map_err(|_| "无效的链接地址".to_string())?;
  if parsed.scheme() != "https" {
    return Err("仅支持打开 HTTPS 链接".to_string());
  }

  #[cfg(desktop)]
  {
    let _ = app;
    crate::platform::open_external_browser(parsed.as_str())
  }

  #[cfg(mobile)]
  {
    crate::platform::mobile::open_external_url(app, parsed.as_str()).await
  }
}
