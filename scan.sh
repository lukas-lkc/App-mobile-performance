#!/usr/bin/env bash
# Scanner de aquecimento para Android (via ADB).
#
#   ./scan.sh boot [minutos]    REINICIA o celular e coleta desde a inicializacao (padrao 15 min)
#   ./scan.sh start [minutos]   inicia o scan agora, em segundo plano (pode desconectar o cabo)
#   ./scan.sh status            mostra o progresso
#   ./scan.sh collect           baixa o relatorio e ENVIA para o GitHub (o Claude le de la)
#   ./scan.sh run [minutos]     faz tudo de uma vez com o celular conectado
#
# Envio automatico: coloque um token do GitHub no arquivo .github_token (veja o README).
set -euo pipefail
cd "$(dirname "$0")"

REMOTE=/data/local/tmp/thermal_scan
INTERVAL=${INTERVAL:-15}
GH_REPO=${GH_REPO:-lukas-lkc/App-mobile-performance}
GH_BRANCH=${GH_BRANCH:-ccr-e3a2eeb0-ekv3ov}

die() { echo "ERRO: $*" >&2; exit 1; }

need_device() {
  command -v adb >/dev/null || die "adb nao encontrado. Instale o Android Platform Tools (veja README)."
  local n
  n=$(adb devices | sed 1d | grep -cw device || true)
  [ "$n" -ge 1 ] || die "nenhum celular conectado. Ative a Depuracao USB e aceite o aviso no celular."
}

push_script() {
  adb shell mkdir -p "$REMOTE"
  adb push -q coletor/thermal_scan.sh "$REMOTE/thermal_scan.sh" >/dev/null
}

bg_launch() { # bg_launch <dur_s> <interval_s> <boot>
  adb shell "setsid nohup sh $REMOTE/thermal_scan.sh $1 $2 $REMOTE/out $3 < /dev/null > $REMOTE/log.txt 2>&1 &"
}

launch() { # launch <minutos> <background:0|1>
  local min=${1:-15} bg=$2
  local dur=$((min * 60))
  need_device
  push_script
  if [ "$bg" = 1 ]; then
    bg_launch "$dur" "$INTERVAL" 0
    sleep 2
    echo "Scan de $min min iniciado no celular."
    echo
    echo "  -> Agora DESCONECTE o cabo (carregar esquenta e mascara o resultado)"
    echo "  -> Use o celular do jeito que costuma esquentar"
    echo "  -> Depois de $min min, reconecte e rode:  ./scan.sh collect"
  else
    echo "Coletando por $min min (intervalo ${INTERVAL}s)... use o celular normalmente."
    adb shell "cd $REMOTE && sh thermal_scan.sh $dur $INTERVAL"
    collect
  fi
}

boot() {
  local min=${1:-15}
  local dur=$((min * 60))
  need_device
  push_script
  echo "Reiniciando o celular..."
  adb reboot
  sleep 5
  echo "Aguardando o celular voltar (pode levar 1 minuto; desbloqueie com o PIN quando pedir)..."
  adb wait-for-device
  # espera o shell e a pasta /data ficarem acessiveis
  for _ in $(seq 1 120); do
    adb shell "test -f $REMOTE/thermal_scan.sh" 2>/dev/null && break
    sleep 1
  done
  bg_launch "$dur" 10 1
  sleep 2
  local up
  up=$(adb shell "cut -d. -f1 /proc/uptime" | tr -d '\r')
  echo
  echo "Coleta iniciada ${up}s depois de ligar. Vai durar $min min (amostra a cada 10 s)."
  echo
  echo "  -> DESBLOQUEIE o celular e DESCONECTE o cabo agora (carregar tambem esquenta)"
  echo "  -> Use normalmente ou deixe parado, como costuma acontecer quando esquenta"
  echo "  -> Depois de $min min, reconecte e rode:  ./scan.sh collect"
}

status() {
  need_device
  adb shell "cat $REMOTE/state 2>/dev/null || echo 'nenhum scan encontrado'"
}

upload() { # upload <pasta>
  local dir=$1 token=${GITHUB_TOKEN:-}
  [ -z "$token" ] && [ -f .github_token ] && token=$(tr -d ' \r\n' < .github_token)
  if [ -z "$token" ]; then
    echo "Sem token do GitHub (.github_token): relatorio NAO enviado automaticamente."
    echo "Envie com:  git add reports && git commit -m scan && git push   (ou anexe no chat)"
    return 0
  fi
  local name tgz body code
  name=$(basename "$dir")
  tgz=$(mktemp -t scanXXXX).tar.gz
  tar -czf "$tgz" -C "$(dirname "$dir")" "$name"
  body=$(mktemp -t scanXXXX)
  { printf '{"message":"Relatorio ADB %s","branch":"%s","content":"' "$name" "$GH_BRANCH"
    base64 < "$tgz" | tr -d '\n'
    printf '"}'; } > "$body"
  code=$(curl -sS -o /dev/null -w '%{http_code}' -X PUT \
    -H "Authorization: Bearer $token" -H "Accept: application/vnd.github+json" -H "Content-Type: application/json" \
    --data @"$body" "https://api.github.com/repos/$GH_REPO/contents/reports/adb/$name.tar.gz")
  rm -f "$tgz" "$body"
  case "$code" in
    200|201) echo "✅ Enviado para o GitHub: reports/adb/$name.tar.gz — e so avisar o Claude." ;;
    *) echo "⚠️ Falha ao enviar para o GitHub (HTTP $code). Confira o token. Anexe a pasta no chat." ;;
  esac
}

collect() {
  need_device
  local st
  st=$(adb shell "cat $REMOTE/state 2>/dev/null" | tr -d '\r')
  case "$st" in
    done*) ;;
    running*) die "scan ainda em andamento ($st). Aguarde e tente de novo." ;;
    *) die "nenhum scan concluido no celular (estado: '${st:-vazio}')." ;;
  esac
  local model ts dest
  model=$(adb shell getprop ro.product.model | tr -d '\r' | tr -c 'A-Za-z0-9\n' '-' | tr -d '\n')
  ts=$(date +%Y%m%d-%H%M%S)
  dest="reports/${ts}_${model:-android}"
  mkdir -p reports
  adb pull "$REMOTE/out" "$dest" >/dev/null
  adb shell "rm -rf $REMOTE/out $REMOTE/state" >/dev/null
  echo "Relatorio salvo em: $dest"
  if command -v python3 >/dev/null; then
    python3 analisar.py "$dest" && echo "Resumo: $dest/RESUMO.md"
  fi
  upload "$dest"
}

case "${1:-}" in
  boot)    boot "${2:-15}" ;;
  start)   launch "${2:-15}" 1 ;;
  run)     launch "${2:-15}" 0 ;;
  status)  status ;;
  collect) collect ;;
  *) sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
