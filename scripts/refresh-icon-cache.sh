#!/bin/zsh
# After changing the app icon: the Dock (and the ⌘-Tab switcher, which uses it) keeps its own icon cache
# and can show an old icon even when /Applications/ZenTask.app has the new one. This moves that cache
# aside (the Dock rebuilds it) and restarts the Dock. Nothing else is touched.
set -euo pipefail
APP=/Applications/ZenTask.app
CACHE="$(getconf DARWIN_USER_CACHE_DIR)com.apple.dock.iconcache"
touch "$APP"
/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f "$APP"
if [[ -f "$CACHE" ]]; then
  mv "$CACHE" "${TMPDIR:-/tmp}/dock.iconcache.$(date +%s).bak"
fi
killall Dock
echo "Dock restarted; ZenTask's icon is re-read."
