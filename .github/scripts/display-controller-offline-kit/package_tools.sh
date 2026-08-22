#!/usr/bin/env bash
set -euo pipefail
kit=${1:?usage: package_tools.sh KIT_DIR}
root="$kit/tools-rootfs"
mkdir -p "$root"

copy_path() {
  local path=$1
  if [[ -e "$path" || -L "$path" ]]; then
    cp -a --parents "$path" "$root"
  fi
}

for command in \
  iverilog vvp iverilog-vpi \
  verilator verilator_bin verilator_coverage verilator_gantt verilator_profcfunc \
  yosys yosys-config yosys-abc; do
  path=$(command -v "$command" || true)
  if [[ -n "$path" ]]; then
    copy_path "$path"
    copy_path "$(readlink -f "$path")"
  fi
done

for path in \
  /usr/lib/x86_64-linux-gnu/ivl /usr/local/lib/ivl \
  /usr/share/iverilog /usr/local/share/iverilog \
  /usr/share/verilator /usr/local/share/verilator \
  /usr/lib/verilator /usr/local/lib/verilator \
  /usr/share/yosys /usr/local/share/yosys \
  /usr/lib/yosys /usr/local/lib/yosys; do
  copy_path "$path"
done

while IFS= read -r elf; do
  while IFS= read -r lib; do
    [[ -n "$lib" ]] && copy_path "$lib"
  done < <(ldd "$elf" 2>/dev/null | awk '/=> \// {print $3} /^\// {print $1}')
done < <(find "$root" -type f -perm -0100 -exec file {} + | awk -F: '/ELF/ {print $1}')
