#!/bin/sh
set -eu

case "${PUBLIC_ORIGIN-}" in
  https://hirundo-team.space|https://hirundo-team.space/)
    set -- "$@" \
      --fsp.proctoring.enabled=true \
      --fsp.session-cookie-secure=true
    ;;
esac

exec java -jar /app/app.jar "$@"
