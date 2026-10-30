#!/usr/bin/env sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
# LANMONITORING_JAVA_OPTS is operator-controlled; keep secrets outside this distribution.
# shellcheck disable=SC2086
exec java ${LANMONITORING_JAVA_OPTS:-} -jar "$APP_HOME/LANMonitoringServer.jar" --server
