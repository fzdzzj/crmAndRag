#!/usr/bin/env bash
# Self-test for scripts/check-test-baseline.sh (TASK-19 E1).
#
# What it locks down: xargs splits the report list into several awk batches as soon as the
# argument list exceeds the OS batch limit, and each batch used to print its own END tuple.
# The gate then read a glued / multi-line tuple and produced nonsense such as "Skipped=047 0 0 0"
# (TASK-17 saw 697 real tests counted as 650 on a Windows runner). measure() must now return
# exactly one "<reports> <tests> <failures> <errors> <skipped>" tuple however the batching works.
#
# Run it directly (no Docker, no network, no maven):
#   bash scripts/tests/check-test-baseline-selftest.sh
# Exit code 0 = every assertion held.
#
# The gate is sourced with BASELINE_LIB_ONLY=1 so the real measure()/aggregate_tuples()
# implementations are exercised, not a copy of them. BASELINE_XARGS_LIMIT=<n> is the test-only
# hook inside the gate that forces "xargs -0 -n <n>", which turns a 12-file fixture directory into
# 12 genuine awk batches and makes the split reproducible at a small scale.
set -uo pipefail

selftest_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
scripts_dir=$(CDPATH= cd -- "$selftest_dir/.." && pwd)
gate_path="$scripts_dir/check-test-baseline.sh"

failures=0
checks=0

pass() {
  checks=$((checks + 1))
  echo "ok - $*"
}

die_case() {
  checks=$((checks + 1))
  failures=$((failures + 1))
  echo "NOT OK - $*" >&2
}

expect_eq() { # <label> <expected> <actual>
  if [ "$2" = "$3" ]; then
    pass "$1"
  else
    die_case "$1: expected [$2] but got [$3]"
  fi
}

expect_ne() { # <label> <unexpected> <actual>
  if [ "$2" != "$3" ]; then
    pass "$1"
  else
    die_case "$1: value must NOT be [$2]"
  fi
}

expect_line() { # <label> <file> <expected line>
  local actual
  actual=$(sed -n "s/^$3=//p" "$2" | tail -1 | tr -d '[:space:]')
  if [ -n "$actual" ]; then
    pass "$1 ($3=$actual)"
  else
    die_case "$1: no $3= entry in $2"
  fi
}

if [ ! -f "$gate_path" ]; then
  echo "cannot find gate script: $gate_path" >&2
  exit 1
fi

# Load the gate functions without running the gate.
BASELINE_LIB_ONLY=1
# shellcheck source=/dev/null
. "$gate_path"

# ---------------------------------------------------------------------------
# 1) aggregate_tuples: the cross-batch summation itself
# ---------------------------------------------------------------------------
expect_eq "aggregate: one batch passes through" \
  "717 0 0 5" "$(printf '717 0 0 5\n' | aggregate_tuples)"

expect_eq "aggregate: three batches are summed field by field" \
  "700 1 3 6" "$(printf '300 1 2 3\n250 0 1 0\n150 0 0 3\n' | aggregate_tuples)"

expect_eq "aggregate: batch order does not matter" \
  "700 1 3 6" "$(printf '150 0 0 3\n300 1 2 3\n250 0 1 0\n' | aggregate_tuples)"

expect_eq "aggregate: no input at all yields zeros, not garbage" \
  "0 0 0 0" "$(printf '' | aggregate_tuples)"

agg_two_batches=$(printf '300 1 2 3\n250 0 1 0\n' | aggregate_tuples)
expect_eq "aggregate: two batches collapse into one line" "550 1 3 3" "$agg_two_batches"
expect_eq "aggregate: that line holds exactly four numbers" "4" \
  "$(printf '%s\n' "$agg_two_batches" | wc -w | tr -d ' ')"

# ---------------------------------------------------------------------------
# 2) measure(): real fixture directory, forced into many awk batches
# ---------------------------------------------------------------------------
tmp_root=$(mktemp -d 2>/dev/null) || {
  echo "mktemp -d failed" >&2
  exit 1
}
# shellcheck disable=SC2064
trap "rm -rf '$tmp_root'" EXIT

write_report() { # <dir> <name> <tests> <failures> <errors> <skipped> <duplicate-line:yes/no>
  {
    echo "-------------------------------------------------------------------------------"
    echo "Test set: $2"
    echo "-------------------------------------------------------------------------------"
    echo "Tests run: $3, Failures: $4, Errors: $5, Skipped: $6, Time elapsed: 0.066 s -- in $2"
    if [ "$7" = "yes" ]; then
      # Second Tests run line in the same file: measure() must only ever count the first one.
      echo "Tests run: 999, Failures: 9, Errors: 9, Skipped: 9, Time elapsed: 0.066 s -- in $2"
    fi
  } >"$1/$2.txt"
}

sf_dir="$tmp_root/surefire-reports"
fa_dir="$tmp_root/failsafe-reports"
mkdir -p "$sf_dir" "$fa_dir"

# surefire: tests 5 7 3 11 2 8 1 4 6 9 10 12 = 78, skipped 1 (r03) + 2 (r07) = 3, failures/errors 0.
sf_tests=(5 7 3 11 2 8 1 4 6 9 10 12)
sf_skipped=(0 0 1 0 0 0 2 0 0 0 0 0)
for i in "${!sf_tests[@]}"; do
  name=$(printf 'fixture.sf.%02d' "$((i + 1))")
  dup="no"
  if [ "$((i + 1))" -eq 5 ]; then
    dup="yes"
  fi
  write_report "$sf_dir" "$name" "${sf_tests[$i]}" 0 0 "${sf_skipped[$i]}" "$dup"
done

# failsafe: tests 3 4 2 1 = 10, skipped 1 1 0 0 = 2.
fa_tests=(3 4 2 1)
fa_skipped=(1 1 0 0)
for i in "${!fa_tests[@]}"; do
  name=$(printf 'fixture.fa.%02d' "$((i + 1))")
  write_report "$fa_dir" "$name" "${fa_tests[$i]}" 0 0 "${fa_skipped[$i]}" "no"
done

# 12 files, one of them carrying a duplicate Tests-run line that must stay ignored: 78 not 1077.
expect_eq "measure: default batching over 12 reports" \
  "12 78 0 0 3" "$(measure "$sf_dir")"

# One file per awk batch = 12 batches = the exact failure mode of E1.
expect_eq "measure: every file in its own batch (xargs -n 1)" \
  "12 78 0 0 3" "$(BASELINE_XARGS_LIMIT=1 measure "$sf_dir")"

expect_eq "measure: five files per batch (xargs -n 5)" \
  "12 78 0 0 3" "$(BASELINE_XARGS_LIMIT=5 measure "$sf_dir")"

expect_eq "measure: failsafe totals" \
  "4 10 0 0 2" "$(measure "$fa_dir")"

expect_eq "measure: failsafe totals under forced batching" \
  "4 10 0 0 2" "$(BASELINE_XARGS_LIMIT=1 measure "$fa_dir")"

# The test hook is a hook, not a licence to invent numbers: a non-numeric limit must fail closed.
if BASELINE_XARGS_LIMIT=abc measure "$sf_dir" >/dev/null 2>&1; then
  die_case "measure: a non-numeric BASELINE_XARGS_LIMIT must fail"
else
  pass "measure: a non-numeric BASELINE_XARGS_LIMIT fails closed"
fi

# Missing / empty directories must stay unmeasurable instead of reporting 0 tests.
if measure "$tmp_root/no-such-dir" >/dev/null 2>&1; then
  die_case "measure: missing report dir must not be measurable"
else
  pass "measure: missing report dir is unmeasurable"
fi
mkdir -p "$tmp_root/empty-reports"
if measure "$tmp_root/empty-reports" >/dev/null 2>&1; then
  die_case "measure: empty report dir must not be measurable"
else
  pass "measure: empty report dir is unmeasurable"
fi

# ---------------------------------------------------------------------------
# 3) Prove this self-test CAN go red: the pre-fix pipeline, same fixtures.
# ---------------------------------------------------------------------------
legacy_measure() { # <dir> <xargs-opts> -- the shape of measure() before TASK-19
  local dir="$1" opts="$2"
  # shellcheck disable=SC2086
  find "$dir" -maxdepth 1 -name '*.txt' -type f -print0 \
    | xargs -0 $opts awk -F'[:,]' '
        /^Tests run:/ && !seen[FILENAME]++ {
          tests += $2; failures += $4; errors += $6; skipped += $8
        }
        END { printf "%d %d %d %d", tests, failures, errors, skipped }
      '
}

expect_eq "legacy pipeline was correct only while xargs used a single batch" \
  "78 0 0 3" "$(legacy_measure "$sf_dir" "")"

legacy_batched=$(legacy_measure "$sf_dir" "-n 1")
expect_ne "legacy pipeline breaks once xargs splits into batches (this is E1)" \
  "78 0 0 3" "$legacy_batched"

# 12 newline-less tuples weld together at every batch boundary: 4*12 numbers minus the 11 glued
# junctions = 37 fields. The junction is where "Skipped: 0" of one batch swallows the leading
# digit of the next batch's tests total -- the "Skipped=047 0 0 0" artefact in the TASK-19 spec.
expect_eq "legacy output is one glued blob of 37 fields, not 4 numbers" \
  "37" "$(printf '%s' "$legacy_batched" | wc -w | tr -d ' ')"
case " $legacy_batched " in
  *" 07 "*) pass "legacy: batch boundary welded 0 onto the next tests total (the 047 artefact)" ;;
  *) die_case "legacy: expected a welded token in [$legacy_batched]" ;;
esac

# What the old caller saw after `read`: the first five words of "<files> <sums>", i.e. a wildly
# under-counted suite (5 tests instead of 78) that still sailed through the gate.
legacy_reports=""
legacy_tests=""
legacy_failures=""
legacy_errors=""
legacy_skipped=""
IFS=' ' read -r legacy_reports legacy_tests legacy_failures legacy_errors legacy_skipped <<EOF
12 $legacy_batched
EOF
expect_eq "legacy: reports field still parsed" "12" "$legacy_reports"
expect_eq "legacy: tests field was silently wrong" "5" "$legacy_tests"
expect_eq "fixed: the same caller read now sees the true total" \
  "78" "$(measure "$sf_dir" | awk '{print $2}')"

# ---------------------------------------------------------------------------
# 4) End-to-end: --update then check against a throwaway repo root, fully batched.
# ---------------------------------------------------------------------------
e2e_root="$tmp_root/e2e"
mkdir -p "$e2e_root/scripts" "$e2e_root/target/surefire-reports" "$e2e_root/target/failsafe-reports"
cp "$sf_dir"/*.txt "$e2e_root/target/surefire-reports/"
cp "$fa_dir"/*.txt "$e2e_root/target/failsafe-reports/"
cp "$gate_path" "$e2e_root/scripts/check-test-baseline.sh"

if BASELINE_XARGS_LIMIT=1 bash "$e2e_root/scripts/check-test-baseline.sh" --update >"$tmp_root/update.log" 2>&1; then
  pass "e2e: --update accepts a fully batched report set"
else
  die_case "e2e: --update failed: $(cat "$tmp_root/update.log")"
fi
baseline_copy="$e2e_root/scripts/test-baseline.txt"
expect_eq "e2e: --update wrote the summed surefire total" "78" \
  "$(sed -n 's/^surefire.tests=//p' "$baseline_copy" | tr -d '[:space:]')"
expect_eq "e2e: --update wrote the summed surefire skipped total" "3" \
  "$(sed -n 's/^surefire.skipped=//p' "$baseline_copy" | tr -d '[:space:]')"
expect_eq "e2e: --update wrote the summed failsafe total" "10" \
  "$(sed -n 's/^failsafe.tests=//p' "$baseline_copy" | tr -d '[:space:]')"
expect_eq "e2e: --update wrote the summed failsafe skipped total" "2" \
  "$(sed -n 's/^failsafe.skipped=//p' "$baseline_copy" | tr -d '[:space:]')"

check_rc=0
check_out=$(bash "$e2e_root/scripts/check-test-baseline.sh" 2>&1) || check_rc=$?
echo "$check_out" | sed 's/^/    | /'
expect_eq "e2e: check right after --update exits 0" "0" "$check_rc"
case "$check_out" in
  *"Tests run=78"*"Skipped=3"*) pass "e2e: check prints the batched totals verbatim" ;;
  *) die_case "e2e: expected Tests run=78 and Skipped=3 in: $check_out" ;;
esac
# Same number the gate prints, re-parsed on its own so a truncated total cannot hide in a
# substring match ("5" is a substring of nothing here, but the exact value is what matters).
parsed_sf_tests=$(printf '%s\n' "$check_out" \
  | sed -n 's/.*Tests run=\([0-9][0-9]*\).*/\1/p' | head -1 | tr -d '[:space:]')
expect_eq "e2e: the surefire total the gate compared is the summed one" "78" "$parsed_sf_tests"

# The gate must still bite after the refactor: an inflated baseline has to go red.
sed 's/^surefire.tests=.*/surefire.tests=999/' "$baseline_copy" >"$baseline_copy.tmp" &&
  mv "$baseline_copy.tmp" "$baseline_copy"
inflated_rc=0
bash "$e2e_root/scripts/check-test-baseline.sh" >/dev/null 2>&1 || inflated_rc=$?
if [ "$inflated_rc" -ne 0 ]; then
  pass "e2e: baseline above the measured total still fails the gate (rc=$inflated_rc)"
else
  die_case "e2e: threshold judgement stopped working (rc=0 with a tests=999 baseline)"
fi

# And a silently skipped class must still be caught (restore tests, drop the skipped allowance).
sed -e 's/^surefire.tests=.*/surefire.tests=78/' -e 's/^surefire.skipped=.*/surefire.skipped=0/' \
  "$baseline_copy" >"$baseline_copy.tmp" && mv "$baseline_copy.tmp" "$baseline_copy"
skipped_rc=0
bash "$e2e_root/scripts/check-test-baseline.sh" >/dev/null 2>&1 || skipped_rc=$?
if [ "$skipped_rc" -ne 0 ]; then
  pass "e2e: skipped above baseline still fails the gate (rc=$skipped_rc)"
else
  die_case "e2e: skipped-over-baseline went unnoticed"
fi

# ---------------------------------------------------------------------------
# 5) Sourcing the gate must not write anything into the repository.
# ---------------------------------------------------------------------------
if [ -e "$scripts_dir/tests/test-baseline.txt" ]; then
  die_case "sourcing the gate leaked its paths: a baseline appeared under scripts/tests/"
else
  pass "sourcing the gate wrote nothing into the repository"
fi
if [ ! -f "$scripts_dir/test-baseline.txt" ]; then
  die_case "the committed baseline file vanished"
else
  expect_line "the committed baseline keeps its entries" "$scripts_dir/test-baseline.txt" "surefire.tests"
  expect_line "the committed baseline keeps its entries" "$scripts_dir/test-baseline.txt" "failsafe.skipped"
fi

echo
if [ "$failures" -eq 0 ]; then
  echo "SELFTEST PASSED: $checks assertions"
  exit 0
fi
echo "SELFTEST FAILED: $failures of $checks assertions" >&2
exit 1
