#!/bin/bash
# Disable or restore the packages in a debloat list (one package per line, # comments).
#
#   tools/debloat.sh status  debloat/hollywood.txt
#   tools/debloat.sh apply   debloat/hollywood.txt
#   tools/debloat.sh restore debloat/hollywood.txt
#
# apply records each package's state before touching it, in debloat/state/<list>.state, and never
# overwrites a recorded state: restore always goes back to how the TV was before the first apply, for
# every package it recorded, even one later commented out of the list.
#
# apply refuses a list that names a package the TV or our apps depend on (PROTECTED below, the same as
# the NEVER DISABLE block of hollywood.txt), and refuses phase B (hollywood-launcher.txt) while our
# launcher is not installed: without it the TV would be left with no Home.
set -euo pipefail

PROTECTED=(
  com.tcl.tv com.tcl.tvinput com.tcl.suspension com.tcl.dashboard com.tcl.systemserver
  com.tcl.providers.config com.tcl.initsetup com.tcl.useragreement com.tcl.factory.view com.tvos
  com.tcl.autopair com.tcl.globalkeyoverlay com.tcl.inputmethod.international com.tcl.hearaid
  com.tcl.airplay2 com.realtek.airplay2
  com.google.android.tv.remote.service
  com.android.tv.settings com.android.vending com.google.android.gms com.google.android.gsf
  com.google.android.webview com.android.providers.tv
)
LAUNCHER=dev.cardozo.hollywood.launcher

protected() {
  [[ "$1" == com.realtek.* ]] && return 0
  local p
  for p in "${PROTECTED[@]}"; do [[ "$1" == "$p" ]] && return 0; done
  return 1
}

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
    bad=()
    for p in $packages; do protected "$p" && bad+=("$p"); done
    if (( ${#bad[@]} )); then
      echo "refusing: $list names protected packages (see NEVER DISABLE in debloat/hollywood.txt): ${bad[*]}" >&2
      exit 1
    fi
    if [[ "$(basename "$list")" == hollywood-launcher.txt ]] && ! grep -qx "$LAUNCHER" <<<"$all"; then
      echo "refusing: $LAUNCHER is not installed; phase B would leave the TV with no Home" >&2
      exit 1
    fi
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
    # From the recorded state, not the list: a package commented out of the list after an apply still
    # goes back. Read on fd 3: tools/tv runs ssh, which would swallow the rest of the file from stdin.
    while read -r p before <&3; do
      if [[ "$before" == enabled && "$(state_of "$p")" == disabled ]]; then
        printf '%-45s ' "$p"; $TV shell pm enable --user 0 "$p" | tr -d '\r'
      fi
    done 3< "$state"
    ;;
  *) usage ;;
esac
