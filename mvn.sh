#!/bin/sh
# Run Maven in a container so no JDK/Maven install is needed on the host.
# The local repository lives in the named volume `pcj-m2` so dependency
# resolution is paid for once.
set -e
docker volume create pcj-m2 >/dev/null
exec docker run --rm \
  -v "$(cd "$(dirname "$0")" && pwd)":/work \
  -v pcj-m2:/root/.m2 \
  -w /work \
  maven:3.9-eclipse-temurin-21 mvn "$@"
