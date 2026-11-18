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
- `drivers.py` (aba Drivers): lista drivers de outros fabricantes (`Win32_PnPSignedDriver`), marca "antigo"
  (vídeo > 1 ano; som/rede/Bluetooth/câmera/discos > 3 anos; virtuais ignorados; data 1968 da Intel = sem data),
  busca no Windows Update via COM (`Microsoft.Update.Session`, ~20 s, sem admin) e instala por UpdateID (admin).
  O WU às vezes oferece driver **mais velho** que o instalado → `kind="velho"`, fica desmarcado; BIOS = `firmware`.
  Versão NVIDIA amigável: `nvidia_version("32.0.15.7283") == "572.83"`. Botão "Site" só abre URLs da lista `SITES`/`MAKER_SITES`.
- Segurança: "Procurar ameaças" (`SEC.quick_scan`) junta processos/inicialização/tarefas/serviços/apps com risco
  alto/médio + `Start-MpScan CustomScan` nos .exe suspeitos. "Remover" (`remove_threat`) encerra, desliga (entra no
  Desfazer), desinstala o app dono ou manda o arquivo para a Lixeira. "Ignorar" grava em `%LOCALAPPDATA%\CalorScan\sec_ignore.json`.
  Chave do Defender: `Set-MpPreference -DisableRealtimeMonitoring` como admin; com Proteção contra adulteração ligada
  o Windows ignora → abre `windowsdefender://threatsettings`.
- `virustotal.py`: segunda opinião por clique (botão "VirusTotal" em cada ameaça, ou "Conferir todos"). Só o SHA-256
  sai do PC, nunca o arquivo; arquivo nunca visto = "desconhecido" (não enviamos). Chave pessoal grátis em
  `%LOCALAPPDATA%\CalorScan\vt_key.txt` (nunca no repositório); cache 3 dias em `vt_cache.json`; conta grátis = 4/min
  (`GAP` 15,5 s; "Conferir todos" espera sozinho). Para apps, `main_exe()` acha o .exe principal da pasta.
  Veredito: ≥5 antivírus = vírus; 2–4 = suspeito; 0–1 = limpo.
- `assistentes.py` (topo da aba Drivers): assistentes dos fabricantes que combinam com o PC (processador, placas de
  vídeo, marca). Os sites da Intel/NVIDIA/AMD **bloqueiam iframe** (`frame-ancestors`), então: programa instalado abre
  por `explorer shell:AppsFolder\<AppID>` (AppID vem de `Get-StartApps`); senão o site oficial abre em `msedge --app=`
  (janela sem abas/barra). Só URLs da lista `ASSIST`. Intel DSA prefere o site (ele conversa com o serviço local da Intel).
- `backup.py` (aba Backup): ponto de restauração (`Checkpoint-Computer`, liga a Proteção do Sistema no C: se preciso,
  reserva 8 GB, zera `SystemRestorePointCreationFrequency` só durante a criação e devolve o valor antigo) e imagem do
  sistema (`wbadmin start backup -allCritical`, nunca no C:). Ler/criar pede admin; a lista fica em
  `%LOCALAPPDATA%\CalorScan\backup_state.json`. Restaurar só abre telas do Windows (`rstrui.exe` ou `ms-settings:recovery`)
  depois de 3 caixinhas + digitar RESTAURAR; o servidor também confere a palavra (`bk_restore`/`bk_recovery`).
  Instalar drivers oferece "criar ponto antes" (marcado); se o ponto falhar, os drivers NÃO são instalados.
- Python do gerenciador da Microsoft (pymanager) roda com **registro virtualizado**: o que ele grava em HKCU pode não
  aparecer para o PowerShell. Nunca rodar testes com `LOCALAPPDATA` trocado (o gerenciador baixa outro Python).
- **Regra de tela**: marcar caixinhas nunca chama `renderDisks()`/re-render; só atualiza o botão (`updSelBtn`, `selClean`, `drvSel`).
  Na aba Segurança/Drivers o polling só redesenha quando um trabalho começa ou termina.
- Testar no navegador embutido: `.claude/launch.json` (na pasta acima) sobe com `CALORSCAN_NO_WINDOW=1`, porta 8765.
- Tudo leve: polling pausa com a janela escondida; animação do mascote curta.

## Histórico das últimas versões do app

- 2.10 — cartões azuis; aba Trabalho não volta ao topo ao tocar nos dias; quadros do Ao vivo com tamanho fixo.
- 2.11 — quadro "Bateria" virou "Celular" (temperatura do corpo); Consumo mostra "bateria X °C"; topo mostra a
  temperatura do celular ("Celular fresquinho"); a palavra "carcaça" não aparece mais para a pessoa.
- 2.12 — Config: cartão "Instalar em outro celular" com QR code do link do APK + botão "Mandar o link".
  O QR é um desenho fixo em `Qr.kt` (`Qr.ROWS`, versão 6, correção M), sem biblioteca. Se o link mudar: num venv
  temporário `pip install segno`, `segno.make_qr(LINK, error='m', boost_error=False).matrix` → linhas com `#`/`.`.
  Compatível com Android 10+ (minSdk 29): Galaxy S22 ok (saiu com Android 12).
  Config: cartão "Atualizar o app" (`Updater.kt`): só ao tocar, lê `apk/VERSION_CODE` em raw.githubusercontent.com,
  baixa o APK para `cacheDir`, confere pacote e versão (`getPackageArchiveInfo`) e entrega ao `PackageInstaller`
  (o Android pede "Atualizar?"; permissão `REQUEST_INSTALL_PACKAGES` + "Permitir desta fonte" na 1ª vez).
  Testado no emulador: build de teste com código 14 → atualizou para a 2.11 do GitHub. Celulares na 2.11 ainda não
  têm o botão: a 1ª atualização para a 2.12 vem pelo CalorScan PC ou pelo QR.

## Ideias/pendências

- Medir de novo o peso da aba Agora e do tema Ao vivo **fora do carregador** (`dumpsys gfxinfo br.calorscan`).
- Textos do CalorScan PC ainda não passaram por revisão completa de português.
