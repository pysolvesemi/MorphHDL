#!/usr/bin/env bash
set -uo pipefail

readonly artifact_root="/tmp/p1-sanity-validation-artifact"
readonly log_root="${artifact_root}/logs"
readonly full_log="/tmp/p1-sanity-validation-full.log"

rm -rf "${artifact_root}"
mkdir -p "${log_root}"

set +e
bash validation/display-controller-p1-sanity-00/run_validation.sh \
  2>&1 | tee "${full_log}"
validation_rc=${PIPESTATUS[0]}
set -e

cp "${full_log}" "${log_root}/"
for candidate in /tmp/p1-sanity-*.log /tmp/p1-sanity-yosys.json; do
  if [[ -f "${candidate}" ]]; then
    cp "${candidate}" "${log_root}/"
  fi
done

{
  echo "validation_exit_code=${validation_rc}"
  echo "validation_commit=$(git rev-parse HEAD)"
  echo "morphhdl_base=81abd25518551b8a452ecf038e409331e646f726"
  echo "rtl_gate_sha256=1c637be71c7f686943aa8ee41bb439071b7e60bf676ac47beac08ea96890ea90"
  echo "dv_gate_sha256=461468459b48b6bc1a5d4a12f606933551df1381dd776656a699051ee43c0db2"
} > "${artifact_root}/diagnostic-summary.txt"

exit "${validation_rc}"
