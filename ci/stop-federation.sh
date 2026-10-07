#!/usr/bin/env bash
# Stop a federation started by start-federation.sh.
set -uo pipefail
PELICAN_WORKDIR="${PELICAN_WORKDIR:-/tmp/pelican-fed}"
for role in cache origin fed; do
  pid_file="${PELICAN_WORKDIR}/${role}.pid"
  if [ -f "${pid_file}" ]; then
    pid="$(cat "${pid_file}")"
    if kill "${pid}" 2>/dev/null; then
      echo "==> Stopped ${role} (pid ${pid})" >&2
    fi
    rm -f "${pid_file}"
  fi
done
