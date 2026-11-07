# Calor Scan — o que está esquentando meu celular?

App Android que monitora o celular enquanto você usa e aponta o que o faz esquentar.
O relatório gerado é lido pelo Claude para fazer o diagnóstico.

> **iPhone:** o iOS não permite ver o que outros apps fazem, então é só para Android.

## Instalar o app (Samsung Galaxy S24 e outros Android 10+)

1. **Samsung (One UI 6+):** *Configurações → Segurança e privacidade →* **Bloqueador automático** →
   desative por enquanto. Ele impede instalar apps de fora da Play Store.
2. No celular, abra este repositório no GitHub, entre na pasta [`apk/`](apk/) →
   `CalorScan.apk` → **Download** (ou "View raw").
3. Abra o arquivo baixado. Se pedir, permita que o navegador ou "Meus Arquivos" **instale apps
   desconhecidos**. O Play Protect pode avisar que o app é desconhecido → *Mais detalhes →
   Instalar assim mesmo*.

## Usar

1. Abra o **Calor Scan** e toque em **Permitir acesso aos dados de uso** → ative *Calor Scan*.
2. Toque em **Permitir rodar em segundo plano** (senão a Samsung fecha o app).
3. Escolha **30 min, 1 h, 3 h** ou **Até eu parar**.
4. Use o celular normalmente, **sem carregar** e do jeito que ele costuma esquentar. Uma notificação
   mostra a temperatura atual.
5. No fim, toque em **Enviar relatório**. Com o token configurado (abaixo), vai direto para o Claude; sem token, escolha *Drive*, WhatsApp, e-mail… Uma cópia também fica em
   *Downloads/CalorScan*.
6. Mande o arquivo `.json` para o Claude no chat (ou salve no Google Drive e avise). Se preferir,
   **Copiar resumo** e cole o texto no chat.

### Monitorar desde que o celular liga
Marque **"Monitorar sozinho toda vez que o celular ligar"** e reinicie o celular. Ao desbloquear,
o app começa sozinho a coletar (a cada 10 s, por 20 min) e, se o envio direto estiver configurado,
manda o relatório ao terminar, sem você abrir nada.

> Para ver **qual processo** gasta CPU ao ligar (atualizações, indexação, IA da Samsung), use o
> modo com cabo: `./scan.sh boot` (abaixo). O app comum não enxerga outros processos.

## Envio direto para o Claude (token do GitHub — uma vez só)

Os relatórios vão para a pasta `reports/` deste repositório, onde o Claude lê direto.
**Deixe o repositório privado**, porque os relatórios mostram quais apps você usa.

1. No GitHub: foto de perfil → *Settings → Developer settings → Personal access tokens*.
2. **Se o repositório é seu:** *Fine-grained tokens → Generate new token* → *Repository access:
   Only select repositories* → `App-mobile-performance` → *Permissions → Contents: **Read and write***
   → *Generate token*.
   **Se você é colaboradora no repositório de outra pessoa:** *Tokens (classic) → Generate new token*
   → marque **repo** → *Generate token*.
3. Copie o token (começa com `github_pat_` ou `ghp_`):
   - **App:** cole em *Envio direto para o Claude* → **Salvar e testar token**.
   - **Notebook (modo cabo):** salve num arquivo `.github_token` na pasta do projeto (ele não vai para o Git).

### O que o app consegue ver

| Dado | Para quê |
|---|---|
| Temperatura da bateria a cada 20 s, estado térmico e margem até o limite (throttling) | quando e quanto esquenta |
| App aberto na tela em cada momento | qual app esquenta e quantos °C por 10 min |
| Apps rodando em segundo plano (serviços ativos) | o que trabalha escondido |
| Internet por app (em primeiro e em segundo plano) | sincronizações e uploads ocultos |
| Câmera em uso, áudio tocando, localização, Wi-Fi, Bluetooth | sensores que esquentam |
| Brilho, taxa de atualização (60/120 Hz), carregando ou não, % de bateria | outros fatores |

O Android **não permite** que um app comum veja quanta CPU cada outro app usa. Para isso existe o
modo avançado com computador (abaixo), que lê os dados direto do sistema via ADB.

**Privacidade:** o app não lê mensagens, fotos, contatos ou localização, e não envia nada sozinho.
O relatório só sai do celular quando você toca em *Enviar relatório*.

O código do app fica em [`android/`](android/). Para recompilar, rode `cd android && ./gradlew assembleRelease`
(é preciso ter o Android SDK).

---

# Modo avançado: scanner via ADB (com computador)

Coleta muito mais detalhes: uso de CPU por processo, sensores internos de temperatura e serviços
térmicos da Samsung. Exige ativar a Depuração USB e ter um computador (ou o Termux).

## O que é coletado (scanner ADB)

A cada 15 s (10 s no modo boot), durante o tempo que você escolher:

| Dado | Para quê |
|---|---|
| Temperatura da bateria e de todos os sensores térmicos (CPU, GPU, modem, pele...) | ver onde e quando esquenta |
| Estado térmico do sistema (throttling) | saber se o Android está segurando o desempenho |
| Top 20 processos por uso de CPU | achar o app/serviço culpado |
| App na tela, brilho, tela ligada/desligada | relacionar o calor com o que você estava fazendo |
| Frequência de cada núcleo e uso da GPU | carga de processamento/gráfico |
| Carregando ou não, corrente da bateria | descartar o carregador como causa |

Também: CPU acumulada por processo desde que o celular ligou (início e fim), registros de
temperatura/otimização de apps/indexação do sistema, os serviços térmicos da Samsung (`sdhms`/`ssrm`) e configurações do One UI
(taxa de atualização 60/120 Hz, Proteção da bateria, Game Booster, busca Wi-Fi/Bluetooth em segundo plano), consumo de bateria estimado por app, *wakelocks* (apps que não deixam
o celular dormir), alarmes, sensores ligados e estatísticas de processos.

**Privacidade:** a coleta não pega localização, mensagens, fotos ou contatos. E-mails que
aparecem em nomes de sincronização são substituídos por `<email>`. A lista de apps
instalados aparece nos dados — mantenha este repositório **privado**.

## Como usar o scanner ADB

### 1. Prepare o celular (uma vez) — Samsung Galaxy (S24 e outros com One UI)
1. *Configurações → Segurança e privacidade → **Bloqueador automático*** → **desative**.
   (No One UI 6+ ele bloqueia comandos pelo cabo USB e o ADB não funciona.)
2. *Configurações → Sobre o telefone → Informações do software* → toque 7× em
   **Número de compilação** (digite o PIN se pedir).
3. *Configurações → **Opções do desenvolvedor*** (no fim da lista) → ative **Depuração USB**.
4. Use um cabo USB-C que transmita dados (o da caixa serve).

> Depois do diagnóstico, se quiser, desative a Depuração USB e reative o Bloqueador automático.

Outros Android: *Configurações → Sobre o telefone* → 7 toques em **Número da versão** →
*Sistema → Opções do desenvolvedor* → **Depuração USB**.

### 2. Instale o ADB no computador (uma vez)
- **Windows:** baixe o [Platform Tools](https://developer.android.com/tools/releases/platform-tools), descompacte e coloque a pasta no `PATH` (ou copie o `adb.exe` para esta pasta).
- **Mac:** `brew install android-platform-tools`
- **Linux:** `sudo apt install adb`

Conecte o cabo, aceite o aviso "Permitir depuração USB?" no celular e confira com `adb devices`.

### 3. Rode o scan

**Aquecimento logo depois de ligar** (o mais completo):

```bash
./scan.sh boot 15       # Windows: scan.bat boot 15
```

O celular **reinicia sozinho**. Desbloqueie quando pedir. A coleta começa uns 20 s depois de
ligar, a cada 10 s. Quando aparecer *"Coleta iniciada"*, **desconecte o cabo**. Depois de 15 min,
reconecte e rode `./scan.sh collect`. O relatório inclui quanto de CPU **cada processo** gastou
desde que o celular ligou, então dá para pegar até o que já terminou antes da coleta começar.

**Aquecimento durante o uso:**

```bash
./scan.sh start 20      # Windows: scan.bat start 20
```

1. **Desconecte o cabo** (carregar esquenta e atrapalha o diagnóstico).
2. Use o celular **do jeito que ele costuma esquentar** por 20 minutos
   (jogo, Instagram, GPS, vídeo, ou só deixar parado se esquenta no bolso).
3. Reconecte e baixe o relatório:

```bash
./scan.sh status        # mostra o progresso
./scan.sh collect       # salva em reports/<data>_<modelo>/
```

### 4. Envie para o Claude ler

Com o `.github_token` configurado, o `collect` **envia sozinho** para `reports/adb/` no GitHub. É só
avisar o Claude. Sem token, anexe a pasta do relatório no chat.

Se tiver Python, o `collect` também gera um `RESUMO.md` legível na pasta do relatório.

### Sem computador (Android 11+, via Termux)
1. Instale o [Termux](https://f-droid.org/packages/com.termux/) (pelo F-Droid).
2. `pkg install android-tools git python`
3. Em *Opções do desenvolvedor → Depuração sem fio*, toque em **Parear com código** e rode
   `adb pair 127.0.0.1:<porta> <código>` e depois `adb connect 127.0.0.1:<porta da tela principal>`.
4. Clone este repositório no Termux e use `./scan.sh run 20` (o celular continua livre para usar).

## Estrutura

```
apk/CalorScan.apk         app pronto para instalar
android/                  codigo do app
scan.sh / scan.bat        comandos no computador (start, status, collect, run)
coletor/thermal_scan.sh   roda dentro do celular e coleta os dados
analisar.py               gera RESUMO.md a partir de um relatório
reports/                  relatórios coletados (é aqui que o Claude lê)
```

Cada relatório contém `info.txt` (aparelho e configurações), `samples.txt` (amostras ao
longo do tempo), `batterystats.txt`, `cpuinfo_*.txt`, `thermal_*.txt`, `power.txt`,
`alarm.txt`, `procstats.txt` e `sensorservice.txt`.
