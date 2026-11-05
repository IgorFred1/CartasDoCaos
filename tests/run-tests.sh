#!/usr/bin/env bash
# Testes automáticos da lógica do jogo, da rede Wi-Fi e do jogo online (rodam no PC, sem Android).
# Requer: kotlinc no PATH (ou KOTLINC=/caminho/para/kotlinc) e Java 17+. Com Node 22+, testa também o relay.
set -euo pipefail
cd "$(dirname "$0")/.."
KOTLINC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
SRC=app/src/main/java/com/cartasdocaos/game
"$KOTLINC" $SRC/core $SRC/net/LineConnection.kt $SRC/net/Lan.kt $SRC/net/Sessions.kt $SRC/net/Discovery.kt \
  $SRC/net/WebSocket.kt $SRC/net/Online.kt -d "$OUT/main"
"$KOTLINC" tests/EngineTest.kt -cp "$OUT/main" -d "$OUT/test"
STDLIB="$(dirname "$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")")/lib/kotlin-stdlib.jar"
RELAY_PID=""
if command -v node >/dev/null 2>&1; then
  (cd relay && node test.js)
  PORT=18080 node relay/server.js >/dev/null 2>&1 &
  RELAY_PID=$!
  export RELAY_URL="http://127.0.0.1:18080"
  sleep 1
fi
trap '[ -n "$RELAY_PID" ] && kill $RELAY_PID 2>/dev/null || true' EXIT
java -Dfile.encoding=UTF-8 -cp "$OUT/main:$OUT/test:$STDLIB" EngineTestKt
