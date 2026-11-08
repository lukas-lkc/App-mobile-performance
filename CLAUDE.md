# CalorScan — guia para quem continuar o projeto (pessoa ou agente de IA)

Projeto em português do Brasil, para leigos. Visual de **blocos/pixel** (bordas grossas escuras, sombra "3D" embaixo,
mascote termômetro). Toda a interface e os textos são em PT-BR simples, sem jargão; revisar concordância
("bateria fresquinha", "celular fresquinho"). Regra de privacidade: **nada sai do celular/PC sem a pessoa pedir**.

Repositório: <https://github.com/lukas-lkc/App-mobile-performance> (público), branch `ccr-e3a2eeb0-ekv3ov`.
Download do app: <https://github.com/lukas-lkc/App-mobile-performance/raw/ccr-e3a2eeb0-ekv3ov/apk/CalorScan.apk>

## Peças

| Pasta | O quê |
|---|---|
| `android/` | App Android (Kotlin, **sem AndroidX**, UI montada em código, sem XML de layout). Pacote `br.calorscan`. |
| `apk/` | APK pronto + `VERSION` (nome, ex. `2.11`) + `VERSION_CODE` (número, ex. `15`). O PC compara o número para oferecer atualização. |
| `pc/` | **CalorScan PC**: servidor Python só com stdlib (`ThreadingHTTPServer`) + janela do Edge `--app`. Abre por `Abrir CalorScan PC.bat`. |
| `diagnostico.py`, `analisar.py`, `coletor/`, `scan.*` | Diagnóstico do celular via ADB (nomes amigáveis de apps em `NAMES`, `profile_name`). |
| `relatorios/` | Saídas locais (ignorado no git — tem dados pessoais). |

## App Android (`android/app/src/main/java/br/calorscan/`)

- `MainActivity.kt` — tudo da tela. Abas: 0 Agora, 1 Esfriar, 2 Trabalho (só se houver perfil de trabalho), 5 Pasta
  (só se houver Pasta Segura), 3 Fotos, 4 Config. `show(t)` mantém a rolagem quando re-renderiza a mesma aba.
  Agora: cartão do topo (temperatura do **corpo do celular** = sensor de carcaça, com fallback para a bateria; texto
  "Celular fresquinho/morno/quente"), card **Ao vivo** com 4 quadros de tamanho fixo (Celular, Processador, Memória,
  Consumo — este mostra "bateria X °C" na 2ª linha), "Usando o processador agora", "Quem mais esquentou".
  Atualiza a cada 3 s (`live` runnable) só com a tela visível.
- `Live.kt` — leitura ao vivo: bateria, corrente×tensão (W), memória, status térmico, sensores (`dumpsys thermalservice`:
  tipo 0 Processador, 1 Vídeo, 3 Carcaça), CPU por app via delta de `dumpsys batterystats --checkin` (linhas
  `9,uid,l,cpu,...`). **No carregador o batterystats congela** → o app mostra "não dá para medir no carregador".
  `CpuRank` guarda ranking do boot (15 min) e do dia.
- `Sky.kt` + `Weather.kt` — tema **Ao vivo** (claro / escuro / ao vivo, preferência `theme_mode`). Céu por hora +
  tempo do open-meteo.com (localização arredondada ~10 km, no máx. a cada 30 min, só nesse tema). Lua pela fase
  calculada. Animação leve: só a faixa do topo redesenha, 8–15 fps, para com economia de bateria, calor ≥ moderado,
  app fora da tela. Cuidado com `scheduleSelf` duplicado (sempre `unscheduleSelf` antes) e com lerp sem fim.
- `Ui.kt` — paleta (variáveis temáticas claro/escuro; cartões de destaque em **azul**: `HILITE`, `GOOD` — a dona não
  quer cartões verdes/amarelos; botões não mudam), componentes de bloco, `ThemeToggle`, `MascotView` (animação curta,
  nunca infinita — pesa no processador).
- `Work.kt` — perfil de trabalho (tipo MANAGED) e Pasta Segura (tipo PRIVATE, Android 17 Samsung). Pausar =
  `UserManager.requestQuietModeEnabled`. Agenda por dia da semana. Não confundir os dois perfis.
- `Cold.kt` — "Esfriar": restringir apps, recursos Samsung, desfazer. `GuardService.kt` — monitor em segundo plano.
  `Diagnosis.kt` — achados e resumo `.md` para IA. `Store.kt` — preferências/histórico.
- Permissões de desenvolvedor concedidas por ADB (o PC faz em "Liberar app"): WRITE_SECURE_SETTINGS,
  MODIFY_QUIET_MODE, DUMP, INTERACT_ACROSS_USERS. SELinux bloqueia `dumpsys cpuinfo` para apps.

### Compilar (Windows, ferramentas em `D:\CalorScanDev`)

```bash
cd android
export JAVA_HOME="D:/CalorScanDev/jdk21" GRADLE_USER_HOME="D:/CalorScanDev/gradle-home" ANDROID_HOME="D:/CalorScanDev/android-sdk"
./gradlew.bat assembleRelease -q
cp app/build/outputs/apk/release/app-release.apk ../apk/CalorScan.apk
```

Antes: subir `versionCode`/`versionName` em `android/app/build.gradle.kts` e os arquivos `apk/VERSION*`.
Assinatura: `android/app/calorscan.keystore` (a mesma desde a 2.0; trocar quebra a atualização por cima).
Emulador: AVD `calorscan35`. ADB a usar: `%LOCALAPPDATA%\CalorScan\platform-tools\adb.exe` (o do BlueStacks é velho e
briga). Com mais de um aparelho, sempre `-s <serial>`.

## CalorScan PC (`pc/`)

- `calorscan_pc.py` — servidor, classe `Adb` (serial, `recover()` reconecta após reboot), instalar/atualizar/
  reinstalar o app (detecta assinatura diferente → oferece "Reinstalar"), liberar, medir o boot, checa
  atualização no GitHub (`apk/VERSION_CODE`). API protegida por token `X-Token` + checagem de Host.
- `ui.html` (abas Computador/Celular, painel "Celular ao vivo") + `ui_pc.js` (Painel, Processos, Apps, Inicialização,
  Esfriar, Discos, Segurança, Relatório).
- `celular_live.py` (painel ao vivo do celular por ADB), `winapi.py`/`ps.py` (ctypes/PowerShell), `monitor.py`,
  `inventario.py` (desinstalar, inicialização, com desfazer em `pc_undo.json`), `suspeitos.py`, `seguranca.py`,
  `discos.py` (pesados, duplicados, por data, limpeza), `relatorio.py` (.md para IA), `pc_api.py`.
- Tudo leve: polling pausa com a janela escondida; animação do mascote curta.

## Histórico das últimas versões do app

- 2.10 — cartões azuis; aba Trabalho não volta ao topo ao tocar nos dias; quadros do Ao vivo com tamanho fixo.
- 2.11 — quadro "Bateria" virou "Celular" (temperatura do corpo); Consumo mostra "bateria X °C"; topo mostra a
  temperatura do celular ("Celular fresquinho"); a palavra "carcaça" não aparece mais para a pessoa.

## Ideias/pendências

- Medir de novo o peso da aba Agora e do tema Ao vivo **fora do carregador** (`dumpsys gfxinfo br.calorscan`).
- Textos do CalorScan PC ainda não passaram por revisão completa de português.
