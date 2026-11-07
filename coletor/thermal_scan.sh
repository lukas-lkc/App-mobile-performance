#!/system/bin/sh
# Roda DENTRO do Android (via adb shell). Coleta dados de temperatura, CPU,
# GPU, app em primeiro plano, wakelocks e consumo de bateria por app.
#
# Uso: sh thermal_scan.sh [duracao_seg] [intervalo_seg] [pasta_saida]

DUR=${1:-900}
INT=${2:-15}
BASE=/data/local/tmp/thermal_scan
OUT=${3:-$BASE/out}
STATE=$BASE/state

mkdir -p "$BASE"
rm -rf "$OUT"
mkdir -p "$OUT" || { echo "nao consegui criar $OUT"; exit 1; }

snap() { # snap <arquivo> <comando...>
  f=$1; shift
  { echo "### $* ($(date '+%Y-%m-%d %H:%M:%S'))"; "$@" 2>&1; } > "$OUT/$f.txt"
}

# ---------- informacoes do aparelho ----------
{
  echo "scan_start: $(date '+%Y-%m-%d %H:%M:%S %z')"
  echo "duration_s: $DUR"
  echo "interval_s: $INT"
  for p in ro.product.manufacturer ro.product.model ro.product.device \
           ro.build.version.release ro.build.version.sdk ro.build.display.id \
           ro.board.platform ro.soc.manufacturer ro.soc.model ro.hardware; do
    echo "$p: $(getprop $p)"
  done
  echo "uptime: $(uptime)"
  echo "screen_brightness: $(settings get system screen_brightness)"
  echo "screen_brightness_mode: $(settings get system screen_brightness_mode)"
  echo "screen_off_timeout: $(settings get system screen_off_timeout)"
  echo "peak_refresh_rate: $(settings get system peak_refresh_rate)"
  echo "min_refresh_rate: $(settings get system min_refresh_rate)"
  echo "low_power (economia de bateria): $(settings get global low_power)"
  echo "wifi_on: $(settings get global wifi_on)"
  echo "bluetooth_on: $(settings get global bluetooth_on)"
  echo "mobile_data: $(settings get global mobile_data)"
  echo "location_mode: $(settings get secure location_mode)"
  if [ "$(getprop ro.product.manufacturer)" = "samsung" ]; then
    echo "--- samsung"
    echo "one_ui: $(getprop ro.build.version.oneui)"
    for k in "system refresh_rate_mode" "secure refresh_rate_mode" "global protect_battery" \
             "global adaptive_battery_management_enabled" "global game_home_enable" \
             "global gamesdk_version" "system intelligent_sleep_mode" \
             "global wifi_scan_always_enabled" "global ble_scan_always_enabled" \
             "secure location_providers_allowed" "system screen_resolution"; do
      echo "$k: $(settings get $k 2>/dev/null)"
    done
  fi
  echo "--- display"
  dumpsys display 2>/dev/null | grep -iE 'mScreenState|refreshRate|renderFrameRate' | head -15
  echo "--- cpu cores"
  for c in /sys/devices/system/cpu/cpu[0-9]*; do
    echo "$(basename $c): max=$(cat $c/cpufreq/cpuinfo_max_freq 2>/dev/null) min=$(cat $c/cpufreq/cpuinfo_min_freq 2>/dev/null)"
  done
} > "$OUT/info.txt" 2>&1

snap battery_start      dumpsys battery
snap thermal_start      dumpsys thermalservice
snap cpuinfo_start      dumpsys cpuinfo

echo "running 0" > "$STATE"

# ---------- amostragem periodica ----------
S="$OUT/samples.txt"
start=$(date +%s)
end=$((start + DUR))
i=0
while [ "$(date +%s)" -lt "$end" ]; do
  i=$((i + 1))
  t0=$(date +%s)
  {
    echo "=== SAMPLE $i t=$((t0 - start)) epoch=$t0 ==="
    echo "--- battery"
    dumpsys battery | grep -E 'powered|status:|level:|temperature:|voltage:|current'
    for f in current_now temp; do
      v=$(cat /sys/class/power_supply/battery/$f 2>/dev/null) && echo "sys_$f: $v"
    done
    echo "--- screen"
    echo "brightness: $(settings get system screen_brightness)"
    dumpsys power | grep -m1 -E 'mWakefulness='
    echo "--- foreground"
    dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'
    echo "--- thermal"
    dumpsys thermalservice | grep -E 'Thermal Status|mValue=' | head -40
    echo "--- zones"
    for z in /sys/class/thermal/thermal_zone*; do
      v=$(cat $z/temp 2>/dev/null) && echo "$(cat $z/type 2>/dev/null)=$v"
    done
    echo "--- cpufreq"
    for c in /sys/devices/system/cpu/cpu[0-9]*; do
      echo "$(basename $c)=$(cat $c/cpufreq/scaling_cur_freq 2>/dev/null) online=$(cat $c/online 2>/dev/null)"
    done
    echo "--- gpu"
    for g in /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage /sys/class/kgsl/kgsl-3d0/gpuclk \
             /sys/kernel/gpu/gpu_busy /sys/kernel/gpu/gpu_clock \
             /sys/class/misc/mali0/device/utilization /sys/class/misc/mali0/device/cur_freq; do
      v=$(cat $g 2>/dev/null) && echo "$g=$v"
    done
    echo "--- top"
    top -b -n 2 -d 2 -m 20 -s 3 -o PID,USER,%CPU,%MEM,ARGS 2>/dev/null || top -b -n 2 -d 2 -m 20
  } >> "$S" 2>&1
  echo "running $i $((t0 - start))/$DUR" > "$STATE"
  spent=$(( $(date +%s) - t0 ))
  [ "$spent" -lt "$INT" ] && sleep $((INT - spent))
done

# ---------- fotos finais (cobrem o periodo do scan) ----------
snap battery_end        dumpsys battery
snap thermal_end        dumpsys thermalservice
snap cpuinfo_end        dumpsys cpuinfo
snap power              dumpsys power
snap procstats          dumpsys procstats --hours 3
snap alarm              dumpsys alarm
snap sensorservice      dumpsys sensorservice
snap batterystats       dumpsys batterystats

# servicos proprios do fabricante (Samsung: sdhms/ssrm = gerenciamento termico e de bateria)
{
  for svc in $(dumpsys -l 2>/dev/null | grep -iE 'sdhms|ssrm|thermal|gamemanager|game_manager|batterystats_sec|DeviceHealth' | tr -d ' '); do
    case "$svc" in thermalservice) continue ;; esac
    echo "######## dumpsys $svc"
    dumpsys -t 15 "$svc" 2>&1 | head -800
  done
} > "$OUT/vendor_services.txt" 2>&1

# ---------- privacidade: remove e-mails (aparecem em nomes de sync) ----------
for f in "$OUT"/*.txt; do
  sed -E -i 's/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/<email>/g' "$f"
done

echo "scan_end: $(date '+%Y-%m-%d %H:%M:%S %z')" >> "$OUT/info.txt"
echo "done $i" > "$STATE"
echo "SCAN_DONE samples=$i out=$OUT"
