#!/usr/bin/env bash
# Build image web + api tu source hien tai, roi push len server bang
# `docker save | ssh | docker load` (khong can registry rieng).
#
# KHONG tu dong `docker compose up` lai tren server - script chi dua image
# moi vao docker cache cua server, ap dung image can chay lenh in ra o cuoi.
#
# Usage:
#   ./build-and-push.sh              # dung REMOTE_HOST mac dinh (linhhost)
#   ./build-and-push.sh my-other-host
#   REMOTE_HOST=my-other-host ./build-and-push.sh
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"   # luon chay tu thu muc stg/, du goi tu dau

REMOTE_HOST="${1:-${REMOTE_HOST:-linhhost}}"
REMOTE_DIR="${REMOTE_DIR:-/opt/aismartstore-stg}"

[ -f .env ] || {
  echo "Khong thay stg/.env - chay: cp .env.example .env roi dien gia tri that" >&2
  exit 1
}
set -a; source .env; set +a

for v in WEB_IMAGE API_IMAGE AUTH_DOMAIN API_DOMAIN KEYCLOAK_REALM; do
  [ -n "${!v:-}" ] || { echo "Thieu bien $v trong stg/.env" >&2; exit 1; }
done

REPO_ROOT="$(cd .. && pwd)"

echo "==> Kiem tra SSH toi $REMOTE_HOST"
ssh -o BatchMode=yes -o ConnectTimeout=10 "$REMOTE_HOST" true || {
  echo "Khong SSH duoc toi '$REMOTE_HOST' - kiem tra lai ~/.ssh/config / key." >&2
  exit 1
}

# May build (vd Mac Apple Silicon = arm64) co the khac kien truc voi server
# (vd VPS = amd64) - docker build mac dinh build theo kien truc MAY BUILD, tu
# do image se khong chay duoc tren server (exec format error). Doi kien truc
# tu server qua SSH thay vi hardcode, roi build cho dung platform do.
TARGET_PLATFORM="${TARGET_PLATFORM:-linux/$(ssh "$REMOTE_HOST" 'docker version --format "{{.Server.Arch}}"')}"
echo "==> Build target platform: $TARGET_PLATFORM (theo kien truc cua $REMOTE_HOST)"

echo "==> Build web ($WEB_IMAGE)"
# NEXT_PUBLIC_* bi bake cung vao bundle luc build - domain lay tu .env de
# khop voi domain that se chay tren server, khong hardcode tay.
docker build --platform "$TARGET_PLATFORM" -t "$WEB_IMAGE" \
  --build-arg NEXT_PUBLIC_KEYCLOAK_ISSUER="http://${AUTH_DOMAIN}/realms/${KEYCLOAK_REALM}" \
  --build-arg NEXT_PUBLIC_API_BASE_URL="https://${API_DOMAIN}" \
  "$REPO_ROOT/web"

echo "==> Verify domain da bake dung vao bundle web"
if ! docker run --rm "$WEB_IMAGE" sh -c "grep -rl \"$API_DOMAIN\" .next/static 2>/dev/null | head -1" | grep -q .; then
  echo "CANH BAO: khong thay \"$API_DOMAIN\" trong .next/static cua image $WEB_IMAGE." >&2
  echo "Build-arg co the sai, hoac dinh cache lop build cu - thu lai voi --no-cache." >&2
  exit 1
fi

echo "==> Build api ($API_IMAGE)"
docker build --platform "$TARGET_PLATFORM" -t "$API_IMAGE" "$REPO_ROOT/backend"

echo "==> Push image len $REMOTE_HOST (docker save | ssh | docker load)"
docker save "$WEB_IMAGE" "$API_IMAGE" | gzip | ssh "$REMOTE_HOST" 'gunzip | docker load'

cat <<EOF

Xong. Image da nam trong docker cache cua $REMOTE_HOST:
  $WEB_IMAGE
  $API_IMAGE

Ap dung image moi (chay tay, script khong tu lam):
  ssh $REMOTE_HOST 'cd $REMOTE_DIR && docker compose up -d web backend'
EOF
