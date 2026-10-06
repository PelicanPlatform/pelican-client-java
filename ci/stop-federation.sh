#!/usr/bin/env bash
# Stop a federation started by start-federation.sh, and print its log on request.
set -uo pipefail
PELICAN_WORKDIR="${PELICAN_WORKDIR:-/tmp/pelican-fed}"
PID_FILE="${PELICAN_WORKDIR}/server.pid"
if [ -f "${PID_FILE}" ]; then
  pid="$(cat "${PID_FILE}")"
  kill "${pid}" 2>/dev/null && echo "==> Stopped pelican-server (pid ${pid})" >&2
  rm -f "${PID_FILE}"
fi
