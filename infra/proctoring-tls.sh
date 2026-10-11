#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -f "$repo_root/.env" ]]; then
  while IFS= read -r line; do
    [[ "$line" =~ ^(TLS_HOST|TLS_EMAIL|TLS_LETSENCRYPT_DIR|TLS_ACME_WEBROOT)= ]] || continue
    name="${line%%=*}"
    [[ ${!name+x} ]] && continue
    value="${line#*=}"
    if [[ "$value" == \"*\" || "$value" == \'*\' ]]; then value="${value:1:${#value}-2}"; fi
    printf -v "$name" '%s' "$value"
    export "$name"
  done < "$repo_root/.env"
fi
tls_host="${TLS_HOST:-158.160.223.177}"
tls_email="${TLS_EMAIL:-}"
tls_dir="${TLS_LETSENCRYPT_DIR:-$repo_root/local/tls/letsencrypt}"
webroot="${TLS_ACME_WEBROOT:-$repo_root/local/tls/webroot}"
[[ "$tls_dir" = /* ]] || tls_dir="$repo_root/$tls_dir"
[[ "$webroot" = /* ]] || webroot="$repo_root/$webroot"

usage() {
  cat <<'EOF'
Usage: infra/proctoring-tls.sh check|issue|renew

check  verifies local Certbot version, directories and public IP certificate files
issue  requests a trusted short-lived IP certificate from Let's Encrypt
renew  runs Certbot renewal; successful renewal validates and reloads active nginx

Set TLS_HOST, TLS_EMAIL, TLS_LETSENCRYPT_DIR, and TLS_ACME_WEBROOT as needed.
The `issue` action contacts the public certificate authority.
EOF
}

require_certbot() {
  command -v certbot >/dev/null 2>&1 || { echo "certbot is required on this host (version 5.4 or newer)." >&2; exit 1; }
  local version major minor
  version="$(certbot --version 2>&1 | sed -E 's/.* ([0-9]+\.[0-9]+(\.[0-9]+)?).*/\1/')"
  major="${version%%.*}"
  minor="${version#*.}"
  minor="${minor%%.*}"
  if [[ ! "$major" =~ ^[0-9]+$ || ! "$minor" =~ ^[0-9]+$ ]] || (( major < 5 || (major == 5 && minor < 4) )); then
    echo "Certbot 5.4 or newer is required for IP-address certificates with webroot." >&2
    exit 1
  fi
}

require_email() {
  if [[ -z "$tls_email" || "$tls_email" == *example.invalid ]]; then
    echo "Set TLS_EMAIL to an operator-controlled email address before requesting a certificate." >&2
    exit 1
  fi
}

action="${1:-}"
case "$action" in
  check)
    require_certbot
    [[ "$tls_host" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || { echo "TLS_HOST must be an IPv4 address." >&2; exit 1; }
    mkdir -p "$webroot" "$tls_dir/work" "$tls_dir/logs"
    if [[ -s "$tls_dir/live/$tls_host/fullchain.pem" && -s "$tls_dir/live/$tls_host/privkey.pem" ]]; then
      echo "Certificate and private key exist for $tls_host."
    else
      echo "No certificate exists for $tls_host yet; the default HTTP compose remains available."
    fi
    ;;
  issue)
    require_certbot
    require_email
    [[ "$tls_host" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || { echo "TLS_HOST must be an IPv4 address." >&2; exit 1; }
    mkdir -p "$webroot" "$tls_dir/work" "$tls_dir/logs"
    chmod 755 "$webroot"
    certbot certonly --non-interactive --agree-tos --email "$tls_email" \
      --config-dir "$tls_dir" --work-dir "$tls_dir/work" --logs-dir "$tls_dir/logs" \
      --preferred-profile shortlived --webroot --webroot-path "$webroot" \
      --ip-address "$tls_host"
    ;;
  renew)
    require_certbot
    certbot renew --non-interactive --config-dir "$tls_dir" \
      --work-dir "$tls_dir/work" --logs-dir "$tls_dir/logs" \
      --deploy-hook "$repo_root/infra/proctoring-tls-reload.sh"
    ;;
  *) usage; exit 2 ;;
esac
