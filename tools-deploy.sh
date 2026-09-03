#!/usr/bin/env bash
#
# Deploy the Go backend to the EC2 box, safely and verifiably.
#
# Everything here was previously done by hand from the notes in V2-HANDOFF.md
# section 6. Doing it by hand is how the box ended up in states nobody could
# describe, so this is the recipe as a script: it backs up before it changes
# anything, records the database before and after, and refuses to call the
# deploy a success unless the row counts survived it.
#
# Usage:
#   ./tools-deploy.sh                 # deploy HEAD to the default host
#   HOST=1.2.3.4 ./tools-deploy.sh    # somewhere else
#   DRY_RUN=1 ./tools-deploy.sh       # check reachability and stop
#
# Requires: ssh, scp, and the EC2 key at $KEY. Nothing is committed that
# contains a secret; the key is referenced by path and stays outside the repo.
set -euo pipefail

HOST="${HOST:-task-flow-babak.duckdns.org}"
USER_AT="${USER_AT:-ubuntu}"
KEY="${KEY:-$HOME/.ssh/taskflow-key.pem}"
SRC_DIR="task-manager-backend-GO"
REMOTE_SRC="~/taskflow-src"
REMOTE_DB="/var/lib/taskflow/taskmanager.db"
BIN="/opt/taskflow/taskflow"
SERVICE="taskflow"
# Every table, so "nothing was disturbed" means all of it rather than the two
# tables somebody happened to think of.
TABLES="users groups group_members invites tasks chores chore_rotation occurrences away_periods activity_events"

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
ssh_() { ssh -i "$KEY" -o StrictHostKeyChecking=accept-new -o ConnectTimeout=15 "$USER_AT@$HOST" "$@"; }

# ── 0. The local build must be good before the box is touched ──────────────
say "0. Building locally first"
(
  cd "$SRC_DIR"
  export PATH="/c/msys64/ucrt64/bin:$PATH"
  export CGO_ENABLED=1
  go build ./...
  go test ./... >/dev/null
)
echo "   local build and tests pass"

say "1. Reachability"
if ! ssh_ 'echo ok' >/dev/null 2>&1; then
  cat <<EOF
   Cannot reach $USER_AT@$HOST over SSH.

   Port 443 may well be answering while 22 is not: the API is behind Caddy and
   only 443 needs to be public. Check that the security group's SSH rule allows
   the address this script is running from, which is not necessarily your
   laptop's:

     curl -s https://api.ipify.org

   Nothing has been changed on the box.
EOF
  exit 1
fi
echo "   ssh ok: $(ssh_ 'hostname')"
[ -n "${DRY_RUN:-}" ] && { echo "   DRY_RUN set, stopping here."; exit 0; }

# ── 2. Back up before anything moves ──────────────────────────────────────
say "2. Backing up the database and the running binary"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
ssh_ "set -e
  mkdir -p ~/backups
  # .backup, not cp: the database runs in WAL mode, so a plain copy can miss
  # everything still sitting in the -wal file.
  sudo sqlite3 '$REMOTE_DB' \".backup '/tmp/db-$STAMP.db'\"
  sudo mv /tmp/db-$STAMP.db ~/backups/taskmanager-$STAMP.db
  sudo chown \$USER ~/backups/taskmanager-$STAMP.db
  sudo cp '$BIN' ~/backups/taskflow-$STAMP
  ls -la ~/backups | tail -4"
echo "   stamped $STAMP"

say "3. Row counts before"
BEFORE="$(ssh_ "for t in $TABLES; do printf '%s=%s\n' \"\$t\" \"\$(sudo sqlite3 '$REMOTE_DB' \"select count(*) from \$t\" 2>/dev/null || echo NA)\"; done")"
echo "$BEFORE" | sed 's/^/   /'

# ── 4. Ship and build ─────────────────────────────────────────────────────
say "4. Copying source and building on the box"
ssh_ "mkdir -p $REMOTE_SRC"
scp -i "$KEY" -o StrictHostKeyChecking=accept-new -q -r \
  "$SRC_DIR/cmd" "$SRC_DIR/internal" "$SRC_DIR/deploy" \
  "$SRC_DIR/go.mod" "$SRC_DIR/go.sum" \
  "$USER_AT@$HOST:$REMOTE_SRC/"
# go is genuinely absent from the non-interactive PATH, hence the prefix.
ssh_ "cd $REMOTE_SRC && PATH=\$PATH:/usr/local/go/bin CGO_ENABLED=1 go build -o /tmp/taskflow-new ./cmd/server && ls -la /tmp/taskflow-new"

# ── 5. Swap it in ─────────────────────────────────────────────────────────
say "5. Restarting the service"
# install -o taskflow, not cp: a plain copy leaves the binary owned by root and
# the unit still starts, which hides the mistake until something needs to write.
ssh_ "set -e
  sudo systemctl stop $SERVICE
  sudo install -o taskflow -g taskflow -m 0755 /tmp/taskflow-new '$BIN'
  sudo systemctl start $SERVICE
  sleep 3
  sudo systemctl is-active $SERVICE"

say "6. Migrations and row counts after"
AFTER="$(ssh_ "for t in $TABLES; do printf '%s=%s\n' \"\$t\" \"\$(sudo sqlite3 '$REMOTE_DB' \"select count(*) from \$t\" 2>/dev/null || echo NA)\"; done")"
echo "$AFTER" | sed 's/^/   /'

if [ "$BEFORE" != "$AFTER" ]; then
  say "STOP: the row counts changed across the restart"
  diff <(echo "$BEFORE") <(echo "$AFTER") || true
  cat <<EOF

  A migration should add columns, never rows. The backup from this run is at
  ~/backups/taskmanager-$STAMP.db on the box, and restoring means putting BOTH
  it and ~/backups/taskflow-$STAMP back: the old binary re-runs its migrations
  against whatever database it finds, so a database rolled back under a new
  binary is not a rollback.
EOF
  exit 1
fi
echo "   unchanged across the restart"

say "7. New columns present"
ssh_ "sudo sqlite3 '$REMOTE_DB' \"select name from pragma_table_info('occurrences') where name in ('covered_by','due_before_pass','passed_chain','pending_debts');\"" | sed 's/^/   /'

say "8. Smoke test through Caddy"
for path in /auth/login /groups /health; do
  code="$(curl -s -m 15 -o /dev/null -w '%{http_code}' "https://$HOST$path" || echo 000)"
  printf '   %-16s %s\n' "$path" "$code"
done
cat <<EOF

   401 on a guarded path and 400 on /auth/login with no body are both correct:
   the auth middleware wraps every route, so 401 means "reachable and refusing
   me", which is the most an unauthenticated smoke test can prove.

Deployed $(git rev-parse --short HEAD) to $HOST.
EOF
