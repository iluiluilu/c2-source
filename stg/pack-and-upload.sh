#!/usr/bin/env bash
# Nen NOI DUNG thu muc stg/ (khong wrap them thu muc stg/ ben trong zip)
# thanh zip roi upload thang vao REMOTE_PATH tren server. KHONG tu giai nen
# tren server - tu lam buoc do theo y muon.
#
# Usage:
#   ./pack-and-upload.sh                 # linhhost, /opt/aismartstore-stg
#   ./pack-and-upload.sh my-host
#   REMOTE_HOST=my-host REMOTE_PATH=/opt/khac ./pack-and-upload.sh
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"   # stg/

REMOTE_HOST="${1:-${REMOTE_HOST:-linhhost}}"
REMOTE_PATH="${REMOTE_PATH:-/opt/aismartstore-stg}"
ZIP_NAME="stg-$(date +%Y%m%d-%H%M%S).zip"

command -v zip >/dev/null || {
  echo "Can lenh 'zip' (macOS/Linux thuong co san; neu thieu: brew install zip)" >&2
  exit 1
}

[ -f .env ] || echo "CANH BAO: stg/.env khong ton tai - docker compose up tren server se thieu bien." >&2

echo "==> Nen noi dung stg/ -> $ZIP_NAME (khong wrap thu muc stg/)"
# Zip tu ben trong stg/, dung "." de file nam ngay tai goc zip - giai nen tai
# REMOTE_PATH se ra docker-compose.yml/.env/... nam thang o do, khong bi
# long them 1 lop thu muc stg/ nua.
zip -rq "../$ZIP_NAME" . -x '*.DS_Store' -x '*/node_modules/*'
cd ..

echo "==> Kiem tra SSH toi $REMOTE_HOST"
ssh -o BatchMode=yes -o ConnectTimeout=10 "$REMOTE_HOST" true || {
  echo "Khong SSH duoc toi '$REMOTE_HOST' - kiem tra lai ~/.ssh/config / key." >&2
  rm -f "$ZIP_NAME"
  exit 1
}

echo "==> Dam bao $REMOTE_PATH ton tai tren $REMOTE_HOST"
ssh "$REMOTE_HOST" "mkdir -p '$REMOTE_PATH'" || {
  echo "Khong tao/ghi duoc '$REMOTE_PATH' tren $REMOTE_HOST - co the can sudo, tu tao thu muc nay va chown cho user SSH truoc." >&2
  rm -f "$ZIP_NAME"
  exit 1
}

echo "==> Upload len $REMOTE_HOST:$REMOTE_PATH/$ZIP_NAME"
scp "$ZIP_NAME" "$REMOTE_HOST:$REMOTE_PATH/"

rm -f "$ZIP_NAME"

cat <<EOF

Xong. Da upload len $REMOTE_HOST:$REMOTE_PATH/$ZIP_NAME

Tren server, vi du de giai nen thang vao $REMOTE_PATH:
  ssh $REMOTE_HOST
  cd $REMOTE_PATH
  unzip -o $ZIP_NAME       # ghi de tai cho, file nam thang tai day (khong co lop stg/ long ben trong)
  rm $ZIP_NAME              # file zip chua .env (secret that) - xoa sau khi giai nen xong
  docker compose up -d

Luu y: neu $REMOTE_PATH dang chay ban stg cu (docker compose dang up),
"unzip -o" se ghi de file cau hinh tai cho (docker-compose.yml, nginx/,
config/, .env, ...) nhung KHONG dung container dang chay - sau khi giai nen
xong chay lai "docker compose up -d" de ap dung thay doi.
EOF
