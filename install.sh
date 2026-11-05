#!/bin/sh
# Builds the Tether plugin and installs it into your local WebStorm or PyCharm.
#
# Usage: ./install.sh [--ide webstorm|pycharm] [--no-build] [--force] [--config-dir <dir>]
#   --ide           which IDE to install into (default: webstorm)
#   --no-build      install the existing build/distributions zip without rebuilding
#   --force         install even if the IDE is running (takes effect after a restart)
#   --config-dir    the IDE's config folder (default: newest ~/Library/Application Support/JetBrains/<IDE><version>)
set -eu

cd "$(dirname "$0")"

ide=webstorm
build=1
force=0
config_dir=""
while [ $# -gt 0 ]; do
  case "$1" in
    --ide) shift; ide="${1:?--ide needs webstorm or pycharm}" ;;
    --no-build) build=0 ;;
    --force) force=1 ;;
    --config-dir) shift; config_dir="${1:?--config-dir needs a folder}" ;;
    -h|--help) sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

case "$ide" in
  webstorm) ide_name=WebStorm ;;
  pycharm) ide_name=PyCharm ;;
  *) echo "Unknown IDE: $ide (use webstorm or pycharm)" >&2; exit 2 ;;
esac

if [ -z "$config_dir" ]; then
  config_dir=$(ls -d "$HOME/Library/Application Support/JetBrains/$ide_name"[0-9]* 2>/dev/null | sort -V | tail -n 1 || true)
fi
if [ -z "$config_dir" ] || [ ! -d "$config_dir" ]; then
  echo "Couldn't find a $ide_name config folder. Pass it with --config-dir." >&2
  exit 1
fi
plugins_dir="$config_dir/plugins"

# True when the installed IDE app is running; the `./gw runIde` sandbox (plain java) doesn't count.
ide_is_running() {
  ps -axo command= | grep "$ide_name[^/]*\.app/Contents/MacOS" | grep -v "intellijPlatform/sandbox" | grep -qv grep
}

if [ "$force" -eq 0 ] && ide_is_running; then
  echo "$ide_name is running. Quit it first, or rerun with --force and restart $ide_name afterwards." >&2
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
echo "Start (or restart) $ide_name to load it."
