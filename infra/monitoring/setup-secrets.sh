#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
secret_dir="$repo_root/local/monitoring"
mkdir -p "$secret_dir"
chmod 700 "$secret_dir"

create_secret() {
    target=$1
    if [ ! -e "$target" ]; then
        umask 077
        openssl rand -hex 32 > "$target"
    fi
    chgrp "$(id -g)" "$target"
    chmod 640 "$target"
}

create_secret "$secret_dir/fsp_metrics_token"
create_secret "$secret_dir/grafana_admin_password"

cat > "$secret_dir/compose.env" <<EOF
FSP_METRICS_TOKEN_FILE=$secret_dir/fsp_metrics_token
FSP_GRAFANA_ADMIN_PASSWORD_FILE=$secret_dir/grafana_admin_password
FSP_MONITORING_SECRET_GID=$(id -g)
EOF
chmod 600 "$secret_dir/compose.env"

printf '%s\n' 'Monitoring secrets are ready in ignored local/monitoring/.'
