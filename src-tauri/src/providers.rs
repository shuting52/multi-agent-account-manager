use serde::{Deserialize, Serialize};
use url::Url;

#[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(transparent)]
pub struct ProviderId(String);

impl ProviderId {
  pub fn muse() -> Self {
    Self("muse".to_string())
  }

  pub fn hark() -> Self {
    Self("hark".to_string())
  }

  pub fn cue() -> Self {
    Self("cue".to_string())
  }

  pub fn chatgpt() -> Self {
    Self("chatgpt".to_string())
  }

  pub fn doubao() -> Self {
    Self("doubao".to_string())
  }

  pub fn jimeng() -> Self {
    Self("jimeng".to_string())
  }

  pub fn claude() -> Self {
    Self("claude".to_string())
  }

  pub fn dola() -> Self {
    Self("dola".to_string())
  }

  pub fn workbuddy() -> Self {
    Self("workbuddy".to_string())
  }

  pub fn wabi() -> Self {
    Self("wabi".to_string())
  }

  pub fn api() -> Self {
    Self("api".to_string())
  }

  pub fn cli() -> Self {
    Self("cli".to_string())
  }

  pub fn as_str(&self) -> &str {
    &self.0
  }

  pub fn is_muse(&self) -> bool {
    self.0 == "muse"
  }

  pub fn is_hark(&self) -> bool {
    self.0 == "hark"
  }

  pub fn is_cue(&self) -> bool {
    self.0 == "cue"
  }

  pub fn is_chatgpt(&self) -> bool {
    self.0 == "chatgpt"
  }

  pub fn is_doubao(&self) -> bool {
    self.0 == "doubao"
  }

  pub fn is_jimeng(&self) -> bool {
    self.0 == "jimeng"
  }

  pub fn is_claude(&self) -> bool {
    self.0 == "claude"
  }

  pub fn is_dola(&self) -> bool {
    self.0 == "dola"
  }

  pub fn is_workbuddy(&self) -> bool {
    self.0 == "workbuddy"
  }

  pub fn is_wabi(&self) -> bool {
    self.0 == "wabi"
  }

  pub fn is_api(&self) -> bool {
    self.0 == "api"
  }

  pub fn is_cli(&self) -> bool {
    self.0 == "cli"
  }

  #[cfg_attr(not(desktop), allow(dead_code))]
  pub fn is_external(&self) -> bool {
    self.is_api() || self.is_cli()
  }

  pub fn is_builtin(&self) -> bool {
    self.is_muse()
      || self.is_hark()
      || self.is_cue()
      || self.is_chatgpt()
      || self.is_doubao()
      || self.is_jimeng()
      || self.is_claude()
      || self.is_dola()
      || self.is_workbuddy()
      || self.is_wabi()
  }

  pub fn parse(value: &str) -> Option<Self> {
    let clean = value.trim().to_ascii_lowercase();
    if clean == "muse"
      || clean == "hark"
      || clean == "cue"
      || clean == "chatgpt"
      || clean == "doubao"
      || clean == "jimeng"
      || clean == "claude"
      || clean == "dola"
      || clean == "workbuddy"
      || clean == "wabi"
      || clean == "api"
      || clean == "cli"
    {
      Some(Self(clean))
    } else {
      None
    }
  }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProviderInfo {
  pub id: ProviderId,
  pub name: String,
  pub short_name: String,
  pub enabled: bool,
  pub status_text: String,
  pub entry_url: String,
  pub homepage_url: String,
  pub callback_origins: Vec<String>,
  pub verified_methods: Vec<String>,
}

#[derive(Debug, Clone)]
pub struct ProviderSpec {
  pub info: ProviderInfo,
  pub allowed_hosts: Vec<String>,
}

pub fn builtin_provider_specs() -> Vec<ProviderSpec> {
  vec![
    ProviderSpec {
      info: ProviderInfo {
        id: ProviderId::muse(),
        name: "Muse · Meta".to_string(),
        short_name: "Muse".to_string(),
        enabled: true,
        status_text: "已启用".to_string(),
        entry_url: "https://muse.ai/".to_string(),
        homepage_url: "https://muse.ai/".to_string(),
        callback_origins: vec![
          "https://muse.ai".to_string(),
          "https://www.muse.ai".to_string(),
        ],
        verified_methods: vec!["email".to_string(), "phone".to_string()],
      },
      allowed_hosts: vec![
        "muse.ai".to_string(),
        "meta.com".to_string(),
        "meta.ai".to_string(),
        "metaaiusercontent.com".to_string(),
        "ecto1usercontent.com".to_string(),
        "meta-agents-apps.workers.dev".to_string(),
        "facebook.com".to_string(),
        "instagram.com".to_string(),
        "fb.com".to_string(),
        "appleid.apple.com".to_string(),
        "idmsa.apple.com".to_string(),
      ],
    },
    ProviderSpec {
      info: ProviderInfo {
        id: ProviderId::hark(),
        name: "Hark".to_string(),
        short_name: "Hark".to_string(),
        enabled: true,
        status_text: "已启用".to_string(),
        entry_url: "https://app.hark.com/".to_string(),
        homepage_url: "https://hark.com/".to_string(),
        callback_origins: vec![
          "https://app.hark.com".to_string(),
          "https://hark.com".to_string(),
        ],
        verified_methods: vec![
          "email".to_string(),
          "phone".to_string(),
          "apple".to_string(),
        ],
      },
      allowed_hosts: vec![
        "app.hark.com".to_string(),
        "hark.com".to_string(),
        "appleid.apple.com".to_string(),
        "idmsa.apple.com".to_string(),
      ],
    },
    ProviderSpec {
      info: ProviderInfo {
        id: ProviderId::cue(),
        name: "Cue".to_string(),
        short_name: "Cue".to_string(),
        enabled: true,
        status_text: "已启用".to_string(),
        entry_url: "https://cue.im/login".to_string(),
        homepage_url: "https://cue.im/".to_string(),
        callback_origins: vec!["https://cue.im".to_string()],
        verified_methods: vec![
          "email".to_string(),
          "phone".to_string(),
          "apple".to_string(),
          "google".to_string(),
        ],
      },
      allowed_hosts: vec![
        "cue.im".to_string(),
        "manus.im".to_string(),
        "manuscdn.com".to_string(),
        "appleid.apple.com".to_string(),
        "idmsa.apple.com".to_string(),
      ],
    },
    ProviderSpec {
      info: ProviderInfo {
        id: ProviderId::chatgpt(),
        name: "ChatGPT".to_string(),
        short_name: "ChatGPT".to_string(),
        enabled: true,
        status_text: "已启用".to_string(),
        entry_url: "https://chatgpt.com/".to_string(),
        homepage_url: "https://chatgpt.com/".to_string(),
        callback_origins: vec!["https://chatgpt.com".to_string()],
        verified_methods: vec![
          "email".to_string(),
          "google".to_string(),
          "apple".to_string(),
        ],
      },
      allowed_hosts: vec![
        "chatgpt.com".to_string(),
        "openai.com".to_string(),
        "auth.openai.com".to_string(),
        "appleid.apple.com".to_string(),
        "idmsa.apple.com".to_string(),
      ],
    },
    ProviderSpec {
      info: ProviderInfo {
        id: ProviderId::doubao(),
        name: "豆包".to_string(),
        short_name: "豆包".to_string(),
        enabled: true,
        status_text: "已启用".to_string(),
        entry_url: "https://www.doubao.com/".to_string(),
        homepage_url: "https://www.doubao.com/".to_string(),
        callback_origins: vec!["https://www.doubao.com".to_string()],
        verified_methods: vec!["phone".to_string(), "email".to_string()],
      },
      allowed_hosts: vec![
        "doubao.com".to_string(),
        "bytedance.com".to_string(),
        "appleid.apple.com".to_string(),
        "idmsa.apple.com".to_string(),
      ],
    },
    ProviderSpec {
      info: ProviderInfo {
        id: ProviderId::jimeng(),
        name: "即梦".to_string(),
        short_name: "即梦".to_string(),
        enabled: true,
        status_text: "已启用".to_string(),
        entry_url: "https://jimeng.jianying.com/".to_string(),
        homepage_url: "https://jimeng.jianying.com/".to_string(),
        callback_origins: vec!["https://jimeng.jianying.com".to_string()],
        verified_methods: vec!["phone".to_string()],
      },
      allowed_hosts: vec![
        "jimeng.jianying.com".to_string(),
        "jianying.com".to_string(),
        "bytedance.com".to_string(),
      ],
    },
    ProviderSpec {
      info: ProviderInfo {
        id: ProviderId::claude(),
        name: "Claude".to_string(),
        short_name: "Claude".to_string(),
        enabled: true,
        status_text: "已启用".to_string(),
        entry_url: "https://claude.ai/".to_string(),
        homepage_url: "https://claude.ai/".to_string(),
        callback_origins: vec!["https://claude.ai".to_string()],
        verified_methods: vec![
          "email".to_string(),
          "google".to_string(),
          "apple".to_string(),
        ],
      },
      allowed_hosts: vec![
        "claude.ai".to_string(),
        "anthropic.com".to_string(),
        "appleid.apple.com".to_string(),
        "idmsa.apple.com".to_string(),
      ],
    },
    ProviderSpec {
      info: ProviderInfo {
        id: ProviderId::dola(),
        name: "Dola".to_string(),
        short_name: "Dola".to_string(),
        enabled: true,
        status_text: "已启用".to_string(),
        entry_url: "https://dola.ai/".to_string(),
        homepage_url: "https://dola.ai/".to_string(),
        callback_origins: vec!["https://dola.ai".to_string()],
        verified_methods: vec!["google".to_string(), "email".to_string()],
      },
      allowed_hosts: vec![
        "dola.ai".to_string(),
        "appleid.apple.com".to_string(),
        "idmsa.apple.com".to_string(),
      ],
    },
    ProviderSpec {
      info: ProviderInfo {
        id: ProviderId::workbuddy(),
        name: "WorkBuddy".to_string(),
        short_name: "WorkBuddy".to_string(),
        enabled: true,
        status_text: "已启用".to_string(),
        entry_url: "https://www.workbuddy.cn/".to_string(),
        homepage_url: "https://www.workbuddy.cn/".to_string(),
        callback_origins: vec!["https://www.workbuddy.cn".to_string()],
        verified_methods: vec!["phone".to_string(), "email".to_string()],
      },
      allowed_hosts: vec![
        "workbuddy.cn".to_string(),
        "workbuddy.ai".to_string(),
        "wx.qq.com".to_string(),
        "ssl.ptlogin2.qq.com".to_string(),
      ],
    },
    ProviderSpec {
      info: ProviderInfo {
        id: ProviderId::wabi(),
        name: "Wabi".to_string(),
        short_name: "Wabi".to_string(),
        enabled: true,
        status_text: "已启用".to_string(),
        entry_url: "https://wabi.ai/".to_string(),
        homepage_url: "https://wabi.ai/".to_string(),
        callback_origins: vec!["https://wabi.ai".to_string()],
        verified_methods: vec![
          "email".to_string(),
          "google".to_string(),
          "apple".to_string(),
        ],
      },
      allowed_hosts: vec![
        "wabi.ai".to_string(),
        "appleid.apple.com".to_string(),
        "idmsa.apple.com".to_string(),
      ],
    },
  ]
}

pub fn resolve_provider(id: &ProviderId) -> Option<ProviderSpec> {
  if !id.is_builtin() {
    return None;
  }
  builtin_provider_specs()
    .into_iter()
    .find(|p| &p.info.id == id)
}

pub fn all_provider_infos() -> Vec<ProviderInfo> {
  builtin_provider_specs()
    .into_iter()
    .map(|p| p.info)
    .collect()
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NavigationDecision {
  AllowBlank,
  AllowHttps,
  ExternalGoogleOAuth,
  Deny,
}

pub fn host_matches(host: &str, domain: &str) -> bool {
  if host.eq_ignore_ascii_case(domain) {
    return true;
  }
  let suffix = format!(".{}", domain.to_ascii_lowercase());
  host.to_ascii_lowercase().ends_with(&suffix)
}

const EMBEDDED_WIDGET_HOSTS: &[&str] = &[
  "challenges.cloudflare.com",
  "turnstile.cloudflare.com",
  "hcaptcha.com",
  "recaptcha.net",
  "gstatic.com",
  "arkoselabs.com",
  "funcaptcha.com",
  "friendlycaptcha.com",
  "geetest.com",
  "captcha-delivery.com",
  "firebaseapp.com",
  "stripe.com",
  "stripecdn.com",
];

fn is_embedded_widget_url(url: &Url, host: &str) -> bool {
  if EMBEDDED_WIDGET_HOSTS.iter().any(|allowed| host_matches(host, allowed)) {
    return true;
  }
  (host.eq_ignore_ascii_case("www.google.com") || host.eq_ignore_ascii_case("google.com"))
    && url.path().starts_with("/recaptcha")
}

pub fn classify_top_level_url(spec: &ProviderSpec, url: &Url, allow_blank: bool) -> NavigationDecision {
  let raw = url.as_str();
  if raw == "about:srcdoc"
    || raw.starts_with("blob:https://")
    || raw.starts_with("blob:null/")
  {
    return NavigationDecision::AllowBlank;
  }
  if allow_blank && raw == "about:blank" {
    return NavigationDecision::AllowBlank;
  }
  if url.scheme() != "https" {
    return NavigationDecision::Deny;
  }
  let Some(host) = url.host_str() else {
    return NavigationDecision::Deny;
  };
  if host_matches(host, "accounts.google.com") {
    return NavigationDecision::ExternalGoogleOAuth;
  }
  if spec.allowed_hosts.iter().any(|allowed| host_matches(host, allowed))
    || is_embedded_widget_url(url, host)
  {
    NavigationDecision::AllowHttps
  } else {
    NavigationDecision::Deny
  }
}

pub fn is_verified_callback_origin(spec: &ProviderSpec, url: &Url) -> bool {
  if url.scheme() != "https" {
    return false;
  }
  let origin = url.origin().ascii_serialization();
  spec
    .info
    .callback_origins
    .iter()
    .any(|allowed| allowed.eq_ignore_ascii_case(&origin))
}
