#!/bin/bash
# Disable or restore the packages in a debloat list (one package per line, # comments).
#
#   tools/debloat.sh status  debloat/hollywood.txt
#   tools/debloat.sh apply   debloat/hollywood.txt
#   tools/debloat.sh restore debloat/hollywood.txt
#
# apply records each package's state before touching it, in debloat/state/<list>.state, and never
# overwrites a recorded state: restore always goes back to how the TV was before the first apply.
set -euo pipefail

cd "$(dirname "$0")/.."
TV=tools/tv

usage() { echo "usage: $0 status|apply|restore <list>" >&2; exit 2; }
[[ $# -eq 2 ]] || usage
cmd="$1"; list="$2"
[[ -f "$list" ]] || { echo "no such list: $list" >&2; exit 2; }

state_dir=debloat/state
state="$state_dir/$(basename "$list" .txt).state"
packages=$(sed -e 's/#.*//' -e 's/[[:space:]]//g' "$list" | grep -v '^$')

all=$($TV shell pm list packages | sed 's/^package://' | tr -d '\r')
disabled=$($TV shell pm list packages -d | sed 's/^package://' | tr -d '\r')

state_of() {
  if ! grep -qx "$1" <<<"$all"; then echo missing
  elif grep -qx "$1" <<<"$disabled"; then echo disabled
  else echo enabled; fi
}

case "$cmd" in
  status)
    for p in $packages; do printf '%-45s %s\n' "$p" "$(state_of "$p")"; done
    ;;
  apply)
    mkdir -p "$state_dir"; touch "$state"
    for p in $packages; do
      s=$(state_of "$p")
      grep -q "^$p " "$state" || echo "$p $s" >> "$state"
      if [[ "$s" == enabled ]]; then
        printf '%-45s ' "$p"; $TV shell pm disable-user --user 0 "$p" | tr -d '\r'
      else
        printf '%-45s %s, skipped\n' "$p" "$s"
      fi
    done
    echo "Wait 10 s before any reboot: PackageManager persists the new states after a delay."
    ;;
  restore)
    [[ -f "$state" ]] || { echo "no recorded state in $state; nothing to restore" >&2; exit 1; }
    for p in $packages; do
      before=$(awk -v p="$p" '$1 == p { print $2 }' "$state")
      if [[ "$before" == enabled && "$(state_of "$p")" == disabled ]]; then
        printf '%-45s ' "$p"; $TV shell pm enable --user 0 "$p" | tr -d '\r'
      fi
    done
    ;;
  *) usage ;;
esac
