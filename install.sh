#!/bin/sh
# Builds the Tether plugin and installs it into a local JetBrains IDE.
#
# Usage: ./install.sh [--ide <name>] [--no-build] [--force] [--config-dir <dir>]
#   --ide           which IDE to install into (default: webstorm), e.g. webstorm, pycharm, idea, goland,
#                   clion, rider, phpstorm, rubymine, datagrip, rustrover, or any config folder name without its version
#   --no-build      install the existing build/distributions zip without rebuilding
#   --force         install even if the IDE is running (takes effect after a restart)
#   --config-dir    the IDE's config folder (default: newest ~/Library/Application Support/JetBrains/<IDE><version>)
set -eu

cd "$(dirname "$0")"

jetbrains_dir="$HOME/Library/Application Support/JetBrains"

ide=webstorm
build=1
force=0
config_dir=""
while [ $# -gt 0 ]; do
  case "$1" in
    --ide) shift; ide="${1:?--ide needs an IDE name, e.g. webstorm, pycharm or idea}" ;;
    --no-build) build=0 ;;
    --force) force=1 ;;
    --config-dir) shift; config_dir="${1:?--config-dir needs a folder}" ;;
    -h|--help) sed -n '2,/^set -eu/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

lowercase() {
  printf '%s' "$1" | tr '[:upper:]' '[:lower:]'
}

ide=$(lowercase "$ide")
case "$ide" in
  idea|intellij) config_prefix=intellijidea ;;
  *) config_prefix="$ide" ;;
esac

# Prints the product part of a config folder path, e.g. PyCharmCE for .../PyCharmCE2024.2.
product_name() {
  basename "$1" | sed 's/[0-9.]*$//'
}

# Prints each versioned config folder in $jetbrains_dir, one per line.
config_dirs() {
  for dir in "$jetbrains_dir"/*[0-9]; do
    if [ -d "$dir" ]; then echo "$dir"; fi
  done
}

# Prints the newest config folder whose product name matches $config_prefix in any case.
newest_config_dir() {
  config_dirs | while IFS= read -r dir; do
    if [ "$(lowercase "$(product_name "$dir")")" = "$config_prefix" ]; then echo "$dir"; fi
  done | sort -V | tail -n 1
}

if [ -z "$config_dir" ]; then
  config_dir=$(newest_config_dir)
fi
if [ -z "$config_dir" ] || [ ! -d "$config_dir" ]; then
  echo "Couldn't find a config folder for '$ide' in $jetbrains_dir. Pass it with --config-dir." >&2
  found=$(config_dirs | xargs -I{} basename {} | tr '\n' ' ')
  echo "Config folders there: ${found:-none}" >&2
  exit 1
fi
plugins_dir="$config_dir/plugins"
ide_name=$(product_name "$config_dir")

# True when an IDE is running on $config_dir: the IDE keeps its PID in .lock there while it runs.
# The `./gw runIde` sandbox has its own config folder, so it doesn't count.
ide_is_running() {
  pid=$(cat "$config_dir/.lock" 2>/dev/null) && kill -0 "$pid" 2>/dev/null
}

if [ "$force" -eq 0 ] && ide_is_running; then
  echo "$ide_name is running. Quit it first, or rerun with --force and restart it afterwards." >&2
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
