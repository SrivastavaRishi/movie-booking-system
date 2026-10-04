#!/usr/bin/env bash
# Build the image on this machine, ship it to the EC2 host and (re)start the stack.
#   ./deploy.sh <HOST_IP>                 e.g. ./deploy.sh 16.178.54.244
#   SSH_KEY=~/.ssh/other.pem ./deploy.sh <HOST_IP>
# The host only needs Docker; it never sees the source code or runs Maven.
set -euo pipefail
if [ $# -lt 1 ]; then
  echo "usage: $0 <HOST_IP>" >&2
  exit 2
fi
HOST="$1"
KEY="${SSH_KEY:-$HOME/.ssh/seat-key.pem}"
IMAGE="seat-reservation:latest"
remote() { ssh -i "$KEY" "ubuntu@$HOST" "$@"; }

cd "$(dirname "$0")"

echo "==> building $IMAGE (linux/arm64)"
docker build --platform linux/arm64 -t "$IMAGE" .

echo "==> uploading image to $HOST"
docker save "$IMAGE" | gzip | remote 'gunzip | docker load'

echo "==> uploading docker-compose.yml"
remote 'mkdir -p ~/app'
scp -i "$KEY" docker-compose.yml "ubuntu@$HOST:app/docker-compose.yml"

# First deploy only: generate real secrets on the server. They never leave it.
remote 'cd ~/app && [ -f .env ] || {
  umask 077
  printf "DB_PASSWORD=%s\nJWT_SECRET=%s\n" "$(openssl rand -hex 24)" "$(openssl rand -hex 32)" > .env
  echo "created ~/app/.env with new secrets"
}'

echo "==> starting containers"
remote 'cd ~/app && docker compose up -d --no-build && docker image prune -f >/dev/null'

echo "==> waiting for http://$HOST:8080/health/ready"
for _ in $(seq 1 60); do
  if curl -fsS --max-time 3 "http://$HOST:8080/health/ready" >/dev/null 2>&1; then
    echo "==> up: http://$HOST:8080"
    exit 0
  fi
  sleep 2
done
echo "==> not ready after 2 minutes; last app logs:" >&2
remote 'cd ~/app && docker compose logs --tail 50 app' >&2
exit 1
