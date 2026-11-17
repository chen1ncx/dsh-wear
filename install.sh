#!/bin/bash
# Install dsh-watch-bridge into a DSH profile.
#
#   ./install.sh                 # install into the `web` profile
#   DSH_PROFILE=tui ./install.sh # another profile
#   WATCH_PORT=9000 ./install.sh  # another port
set -euo pipefail

SRC="$(cd "$(dirname "$0")" && pwd)"
PROFILE="${DSH_PROFILE:-web}"
PORT="${WATCH_PORT:-8787}"
HOST="${WATCH_HOST:-0.0.0.0}"
DEST="${WATCH_DEST:-$HOME/dsh-watch-bridge}"
PATCH="${DSH_HOME:-$HOME/.dsh}/profiles/$PROFILE/cordis.patch.yml"

command -v dsh >/dev/null 2>&1 || { echo "找不到 dsh 命令，先把 DSH 装上"; exit 1; }
[ -d "${DSH_HOME:-$HOME/.dsh}/profiles/$PROFILE" ] || { echo "没有 profile '$PROFILE'（${DSH_HOME:-$HOME/.dsh}/profiles/$PROFILE）"; exit 1; }

echo "[1/4] 拷贝到 $DEST"
if [ "$SRC" != "$DEST" ]; then
  mkdir -p "$DEST"
  for item in package.json lib cordis.patch.yml README.md dist android install.sh package.sh; do
    [ -e "$SRC/$item" ] && cp -r "$SRC/$item" "$DEST/"
  done
fi

echo "[2/4] dsh plugin add"
dsh plugin --profile "$PROFILE" add "$DEST" 2>&1 | tail -4

echo "[3/4] 写 profile 配置"
if grep -q "id: watch-bridge" "$PATCH" 2>/dev/null; then
  echo "  已经有一行 watch-bridge，跳过（要改端口请手动改 $PATCH）"
else
  cp "$PATCH" "$PATCH.bak-$(date +%s)" 2>/dev/null || true
  cat >> "$PATCH" <<YAML

- insert:
    - id: watch-bridge
      name: dsh-watch-bridge
      config:
        port: ${PORT}
        host: '${HOST}'
        relayApprovals: true
        relayQuestions: true
        approvalWaitMs: 120000
        questionWaitMs: 300000
        logLevel: info
YAML
  echo "  已追加到 $PATCH"
fi

echo "[4/4] 等插件起来"
for i in $(seq 1 20); do
  OUT=$(node -e "fetch('http://127.0.0.1:${PORT}/health').then(r=>r.text()).then(t=>console.log(t)).catch(()=>process.exit(1))" 2>/dev/null || true)
  if [ -n "$OUT" ]; then
    echo "  桥接已在 127.0.0.1:${PORT} 上跑着：$OUT"
    echo
    echo "  浏览器打开: http://<这台机器的IP>:${PORT}/watch"
    echo "  手表配对:   adb shell am start -n io.dsh.watch/.MainActivity --es url http://<IP>:${PORT} --es code ${CODE}"
    exit 0
  fi
  sleep 1
done
echo "  没等到插件启动。检查：引擎要不要重启？端口 ${PORT} 被占用？看 $DSH_HOME/dsh-app/diagnostics.md"
exit 1
