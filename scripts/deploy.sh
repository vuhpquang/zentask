#!/usr/bin/env bash
# Deploys the ZenTask server to vps1: build locally, rsync, docker compose up, health check.
source "$(dirname "$0")/lib/env.sh"

REMOTE=vps1
REMOTE_DIR=/root/zentask
PUBLIC_URL=https://zentask-api.vuhpquang.com

step "Building server distribution"
./gradlew -q :server:test :server:installDist

step "Uploading to $REMOTE:$REMOTE_DIR"
ssh -o BatchMode=yes "$REMOTE" "mkdir -p $REMOTE_DIR/server/build/install"
rsync -az --delete -e "ssh -o BatchMode=yes" server/build/install/server/ "$REMOTE:$REMOTE_DIR/server/build/install/server/"
rsync -az -e "ssh -o BatchMode=yes" docker-compose.yml .dockerignore "$REMOTE:$REMOTE_DIR/"
rsync -az -e "ssh -o BatchMode=yes" server/Dockerfile "$REMOTE:$REMOTE_DIR/server/"

step "Starting container"
ssh -o BatchMode=yes "$REMOTE" "cd $REMOTE_DIR && docker compose up -d --build"

step "Health check"
for _ in $(seq 1 30); do
    if out="$(curl -fsS --max-time 5 "$PUBLIC_URL/health" 2>/dev/null)"; then
        echo "    $PUBLIC_URL/health → $out"
        exit 0
    fi
    sleep 2
done
# The public URL is the one clients use, so it must answer. Look at vps1's loopback only to say why not.
if out="$(ssh -o BatchMode=yes "$REMOTE" "curl -fsS --max-time 5 localhost:3100/health")"; then
    echo "    vps1 localhost:3100/health → $out (server is up)"
    die "$PUBLIC_URL/health does not answer: check DNS / Cloudflare Tunnel (docs/setup-guide.md §1)"
fi
die "server did not answer on vps1 (ssh $REMOTE 'cd $REMOTE_DIR && docker compose logs api')"
