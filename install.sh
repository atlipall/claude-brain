#!/bin/sh
# Builds the Tether plugin and installs it into your local WebStorm.
#
# Usage: ./install.sh [--no-build] [--force] [--config-dir <dir>]
#   --no-build      install the existing build/distributions zip without rebuilding
#   --force         install even if WebStorm is running (takes effect after a restart)
#   --config-dir    WebStorm config folder (default: newest ~/Library/Application Support/JetBrains/WebStorm*)
set -eu

cd "$(dirname "$0")"

build=1
force=0
config_dir=""
while [ $# -gt 0 ]; do
  case "$1" in
    --no-build) build=0 ;;
    --force) force=1 ;;
    --config-dir) shift; config_dir="${1:?--config-dir needs a folder}" ;;
    -h|--help) sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

if [ -z "$config_dir" ]; then
  config_dir=$(ls -d "$HOME/Library/Application Support/JetBrains"/WebStorm* 2>/dev/null | sort -V | tail -n 1 || true)
fi
if [ -z "$config_dir" ] || [ ! -d "$config_dir" ]; then
  echo "Couldn't find a WebStorm config folder. Pass it with --config-dir." >&2
  exit 1
fi
plugins_dir="$config_dir/plugins"

# The real IDE runs as WebStorm.app/Contents/MacOS/webstorm; the `./gw runIde` sandbox runs plain java.
if [ "$force" -eq 0 ] && ps -axo command= | grep "WebStorm.app/Contents/MacOS" | grep -v "intellijPlatform/sandbox" | grep -qv grep; then
  echo "WebStorm is running. Quit it first, or rerun with --force and restart WebStorm afterwards." >&2
  exit 1
fi

if [ "$build" -eq 1 ]; then
  echo "Building…"
  ./gw buildPlugin -q
fi

zip=$(ls build/distributions/tether-*.zip 2>/dev/null | sort -V | tail -n 1 || true)
if [ -z "$zip" ]; then
  echo "No plugin zip in build/distributions. Run without --no-build." >&2
  exit 1
fi

mkdir -p "$plugins_dir"
rm -rf "$plugins_dir/tether" "$plugins_dir/claude-chat" # claude-chat: the folder before the rename
unzip -q -o "$zip" -d "$plugins_dir"
echo "Installed $(basename "$zip") into $plugins_dir/tether"
echo "Start (or restart) WebStorm to load it."
