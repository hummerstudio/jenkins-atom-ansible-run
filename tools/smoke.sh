#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

work="$(mktemp -d)"; mkdir -p "$work/bin"
cat >"$work/bin/ansible-playbook" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$SMOKE_ARGV"
if [ -n "${SMOKE_UNREACHABLE:-}" ]; then
  echo "host1 : ok=0 changed=0 unreachable=1 failed=0 skipped=0"
else
  echo "host1 : ok=3 changed=1 unreachable=0 failed=0 skipped=2"
fi
exit 0
SH
chmod +x "$work/bin/ansible-playbook"
export SMOKE_ARGV="$work/argv.txt"

dir="$work/run"; mkdir -p "$dir"; printf 'PLAY' >"$dir/site.yml"
printf '{"playbook": "site.yml", "inventory": "hosts", "limit": "web", "tags": "deploy"}' >"$dir/input.json"
PATH="$work/bin:$PATH" run "$dir" >/dev/null 2>&1 || fail "run failed: $(cat "$dir/output.json")"
[ "$(output "$dir" ok)" = "3" ] || fail "ok"
[ "$(output "$dir" changed)" = "1" ] || fail "changed"
grep -q -- "--limit web" "$work/argv.txt" || fail "limit"
grep -q -- "--tags deploy" "$work/argv.txt" || fail "tags"

dir="$work/unreachable"; mkdir -p "$dir"; printf 'PLAY' >"$dir/site.yml"
printf '{"playbook": "site.yml"}' >"$dir/input.json"
PATH="$work/bin:$PATH" SMOKE_UNREACHABLE=1 run "$dir" >/dev/null 2>&1 && fail "unreachable must fail"

dir="$work/none"; mkdir -p "$dir"; printf 'PLAY' >"$dir/site.yml"; printf '{"playbook": "site.yml"}' >"$dir/input.json"
PATH="/usr/bin:/bin" run "$dir" >/dev/null 2>&1 && fail "missing ansible must fail"
grep -q '"errorType":5' "$dir/output.json" || fail "ENVIRONMENT(5)"

echo "smoke ok: recap parsing, limit/tags, unreachable, missing ansible"
