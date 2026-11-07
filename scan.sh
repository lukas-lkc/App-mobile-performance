#!/usr/bin/env bash
# Scanner de aquecimento para Android (via ADB).
#
#   ./scan.sh start [minutos]   inicia o scan em segundo plano no celular
#                               (pode desconectar o cabo e usar o celular normalmente)
#   ./scan.sh status            mostra o progresso
#   ./scan.sh collect           baixa o relatorio para reports/
#   ./scan.sh run [minutos]     faz tudo de uma vez, com o celular conectado
#                               (bom para depuracao sem fio / Termux)
set -euo pipefail
cd "$(dirname "$0")"

REMOTE=/data/local/tmp/thermal_scan
INTERVAL=${INTERVAL:-15}

die() { echo "ERRO: $*" >&2; exit 1; }

need_device() {
  command -v adb >/dev/null || die "adb nao encontrado. Instale o Android Platform Tools (veja README)."
  local n
  n=$(adb devices | sed 1d | grep -cw device || true)
  [ "$n" -ge 1 ] || die "nenhum celular conectado. Ative a Depuracao USB e aceite o aviso no celular."
}

launch() { # launch <minutos> <background:0|1>
  local min=${1:-15} bg=$2
  local dur=$((min * 60))
  need_device
  adb shell mkdir -p "$REMOTE"
  adb push -q coletor/thermal_scan.sh "$REMOTE/thermal_scan.sh" >/dev/null
  if [ "$bg" = 1 ]; then
    adb shell "cd $REMOTE && setsid nohup sh thermal_scan.sh $dur $INTERVAL > $REMOTE/log.txt 2>&1 < /dev/null &"
    sleep 2
    echo "Scan de $min min iniciado no celular."
    echo
    echo "  -> Agora DESCONECTE o cabo (carregar esquenta e mascara o resultado)"
    echo "  -> Use o celular do jeito que costuma esquentar (jogos, redes sociais, video...)"
    echo "  -> Depois de $min min, reconecte e rode:  ./scan.sh collect"
  else
    echo "Coletando por $min min (intervalo ${INTERVAL}s)... use o celular normalmente."
    adb shell "cd $REMOTE && sh thermal_scan.sh $dur $INTERVAL"
    collect
  fi
}

status() {
  need_device
  adb shell "cat $REMOTE/state 2>/dev/null || echo 'nenhum scan encontrado'"
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
  adb shell "rm -rf $REMOTE" >/dev/null
  echo "Relatorio salvo em: $dest"
  if command -v python3 >/dev/null; then
    python3 analisar.py "$dest" && echo "Resumo: $dest/RESUMO.md"
  fi
  echo
  echo "Para o Claude ler:  git add reports && git commit -m 'scan' && git push"
}

case "${1:-}" in
  start)   launch "${2:-15}" 1 ;;
  run)     launch "${2:-15}" 0 ;;
  status)  status ;;
  collect) collect ;;
  *) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
