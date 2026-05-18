#!/usr/bin/env bash
# bin/dev/sections/preflight.sh — extracted 2026-04-22 from stability-check.sh (Phase B-3)
# Functions: section_preflight, section_healthcheck, section_hooks_installed, section_dep_cache_size, section_env_drift, section_manual_health
# Sourced by ../stability-check.sh; relies on globals (SVC_DIR, UI_DIR,
# STAMP, BLOCKING[], ATTENTION[], NICE[], INFO[]) + helpers (finding, silent).

# ── Section 1: pre-flight (git, branch, docker) ─────────────────────────────
section_preflight() {
  echo "▸ Pre-flight…"
  for repo in "$SVC_DIR" "$UI_DIR"; do
    local name=$(basename "$repo")
    if ! (cd "$repo" && git diff --quiet); then
      finding warn "$name: uncommitted changes — \`cd $repo && git status\`"
    fi
    if ! (cd "$repo" && silent git fetch --all); then
      finding block "$name: \`git fetch\` failed — network or auth issue"
      continue
    fi
    local ahead behind
    ahead=$(cd "$repo" && git log --oneline origin/dev..HEAD 2>/dev/null | wc -l | tr -d ' ')
    behind=$(cd "$repo" && git log --oneline HEAD..origin/dev 2>/dev/null | wc -l | tr -d ' ')
    if [[ "$behind" -gt 0 ]]; then
      finding warn "$name: branch is $behind commits behind origin/dev — \`git pull --rebase\`"
    fi
    if [[ "$ahead" -gt 0 ]]; then
      finding info "$name: $ahead commits ahead of origin/dev (push pending?)"
    fi
  done

  # GitHub mirror drift check (per ADR-0069 double-CI + 2026-05-14 audit).
  # iris-7 mirrors GitLab to GitHub ; drift accumulates silently on force-
  # pushes and manual main pushes. `github-mirror-sync.sh --check` exits 1
  # if any of the 5 repos has drift — surface as a stability finding so
  # the next tag has a clean mirror baseline.
  if [[ -x "$SVC_DIR/infra/common/bin/ship/github-mirror-sync.sh" ]]; then
    if "$SVC_DIR/infra/common/bin/ship/github-mirror-sync.sh" --check >/dev/null 2>&1; then
      finding info "GitHub mirrors: all 5 in sync with GitLab"
    else
      finding warn "GitHub mirror drift — run \`infra/common/bin/ship/github-mirror-sync.sh\` to resync"
    fi
  fi

  # Docker disk pressure (per CLAUDE.md "Regular Docker cleanup").
  local docker_gb
  docker_gb=$(docker system df --format json 2>/dev/null \
    | python3 -c "import json,sys; total=0
for line in sys.stdin:
    if not line.strip(): continue
    d = json.loads(line)
    sz = d.get('Size','0B').replace('GB','').replace('MB','')
    try: total += float(sz) if 'GB' in d.get('Size','') else float(sz)/1024
    except: pass
print(int(total))" 2>/dev/null || echo "0")
  if [[ "$docker_gb" -gt 80 ]]; then
    finding warn "Docker disk: ${docker_gb} GB used — run \`docker system prune\` (per CLAUDE.md)"
  else
    finding info "Docker disk: ${docker_gb} GB (under 80 GB cap)"
  fi
}

# ── Section 2: local services healthcheck ───────────────────────────────────
section_healthcheck() {
  echo "▸ Local services healthcheck (delegates to bin/dev/healthcheck-all.sh)…"
  if [[ -x "$SVC_DIR/bin/dev/healthcheck-all.sh" ]]; then
    if "$SVC_DIR/bin/dev/healthcheck-all.sh" --json 2>/dev/null > /tmp/hc.json; then
      local down
      down=$(python3 -c "
import json
d = json.load(open('/tmp/hc.json'))
down = [s['name'] for s in d.get('services', []) if s.get('status') != 'UP' and s.get('required')]
print(','.join(down))" 2>/dev/null || echo "")
      if [[ -n "$down" ]]; then
        finding warn "Local services DOWN: $down (run \`./run.sh all\`)"
      else
        finding info "Local services: all UP"
      fi
    else
      finding info "healthcheck-all returned non-zero (services likely not running — OK if not needed)"
    fi
  else
    finding warn "bin/dev/healthcheck-all.sh missing — restore from git"
  fi
}

# ── Section 1b: Pre-commit hooks installed (lefthook) ──────────────────────
# A repo with a lefthook config but no .git/hooks/pre-commit silently
# bypasses every lint/format/secret-scan we configured. Easy to forget
# after a fresh `git clone` (lefthook needs an explicit `lefthook
# install`). As of 2026-04-20 the config can live at either ./lefthook.yml
# (legacy) or ./.config/lefthook.yml (root-hygiene move); both probed.
section_hooks_installed() {
  echo "▸ Pre-commit hooks installed…"
  for repo in "$SVC_DIR" "$UI_DIR"; do
    local name=$(basename "$repo")
    if [[ ! -f "$repo/lefthook.yml" && ! -f "$repo/.config/lefthook.yml" ]]; then continue; fi
    if [[ ! -f "$repo/.git/hooks/pre-commit" ]]; then
      finding warn "$name: lefthook config present but .git/hooks/pre-commit missing — \`lefthook install\`"
    fi
  done
}

# ── Section 1c: node_modules / .m2 size sanity ─────────────────────────────
# Bloated dependency caches are usually safe to ignore but >2 GB indicates
# either a recent breaking dep bump (clean install would help) or
# accumulated cruft from `npm ci` race conditions across CI variants.
section_dep_cache_size() {
  echo "▸ Dependency cache sizes…"
  if [[ -d "$UI_DIR/node_modules" ]]; then
    local nm_mb
    nm_mb=$(du -sm "$UI_DIR/node_modules" 2>/dev/null | awk '{print $1}')
    if [[ "$nm_mb" -gt 2048 ]]; then
      finding warn "UI node_modules: ${nm_mb} MB — consider \`rm -rf node_modules && npm ci\`"
    fi
  fi
  if [[ -d "$SVC_DIR/.m2" ]]; then
    local m2_mb
    m2_mb=$(du -sm "$SVC_DIR/.m2" 2>/dev/null | awk '{print $1}')
    if [[ "$m2_mb" -gt 5120 ]]; then
      finding warn "svc local .m2: ${m2_mb} MB — consider \`rm -rf .m2 && mvn package -DskipTests\`"
    fi
  fi
}

# ── Section 7c: .env key drift vs .env.example (onboarding hygiene) ────────
# If .env has keys that .env.example doesn't document, a new contributor
# can't `cp .env.example .env` and get a working local stack — they'll hit
# a runtime error later when the code reads an undocumented env var.
section_env_drift() {
  echo "▸ .env / .env.example drift…"
  for repo in "$SVC_DIR" "$UI_DIR"; do
    local name=$(basename "$repo")
    [[ ! -f "$repo/.env.example" || ! -f "$repo/.env" ]] && continue
    local missing
    missing=$(comm -23 \
      <(grep -oE "^[A-Z_]+" "$repo/.env" 2>/dev/null | sort -u) \
      <(grep -oE "^[A-Z_]+" "$repo/.env.example" 2>/dev/null | sort -u) \
      | tr '\n' ' ')
    if [[ -n "$missing" ]]; then
      finding warn "$name .env keys missing from .env.example: $missing"
    fi
  done
}

# ── Section 8b: Manual job health (svc only — they're scheduled-or-manual) ──
section_manual_health() {
  echo "▸ Manual job health (last run on svc main)…"
  local pid
  pid=$( (cd "$SVC_DIR" && glab api 'projects/iris-7%2Firis-service/pipelines?ref=main&per_page=1' 2>/dev/null) \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print(d[0]['id'])" 2>/dev/null || echo "")
  if [[ -z "$pid" ]]; then
    finding info "manual-health: couldn't fetch latest svc main pipeline"
    return
  fi
  # Manual jobs we care about + their last-run status.
  local report
  report=$( (cd "$SVC_DIR" && glab api "projects/iris-7%2Firis-service/pipelines/$pid/jobs?per_page=50" 2>/dev/null) \
    | python3 -c "
import json, sys
jobs = json.load(sys.stdin)
watched = ['compat-sb3-java17', 'compat-sb3-java21', 'compat-sb4-java17',
           'compat-sb4-java21', 'mutation-test', 'semgrep', 'build-native',
           'smoke-test']
fails, manuals, ok = [], [], []
for j in jobs:
    if j['name'] not in watched: continue
    if j['status'] == 'failed':  fails.append(j['name'])
    elif j['status'] == 'manual': manuals.append(j['name'])
    elif j['status'] == 'success': ok.append(j['name'])
print(f\"FAIL:{','.join(fails)}|MANUAL:{','.join(manuals)}|OK:{','.join(ok)}\")" 2>/dev/null || echo "")
  local fails manuals ok
  fails=$(echo "$report" | grep -oE 'FAIL:[^|]*' | sed 's/FAIL://')
  manuals=$(echo "$report" | grep -oE 'MANUAL:[^|]*' | sed 's/MANUAL://')
  ok=$(echo "$report" | grep -oE 'OK:[^|]*' | sed 's/OK://')
  if [[ -n "$fails" ]]; then
    finding warn "Manual jobs FAILED last run: $fails — re-trigger via \`glab ci trigger <id>\` or run script with --trigger-manual"
  fi
  if [[ -n "$manuals" ]]; then
    finding info "Manual jobs never run on this pipeline: $manuals"
  fi
  if [[ -n "$ok" ]]; then
    finding info "Manual jobs OK: $ok"
  fi
}

