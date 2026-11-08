# Sourced by every script: strict mode, arm64 JDK 17, repo root, helpers.
set -euo pipefail
JAVA_HOME="$(/usr/libexec/java_home -v 17 -a arm64 2>/dev/null || /usr/libexec/java_home -v 17)"
export JAVA_HOME
ZT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/../.." && pwd)"
export ZT_ROOT
cd "$ZT_ROOT"

die()  { echo "error: $*" >&2; exit 1; }
step() { echo; echo "==> $*"; }
