# App Mobile Performance — o que está esquentando meu celular?

Ferramenta que varre um celular **Android** durante o uso normal e registra tudo que
costuma causar aquecimento. Os relatórios ficam em `reports/` para o Claude ler e
diagnosticar.

> **iPhone:** o iOS não permite que nenhum app ou ferramenta veja o consumo de
> outros apps, então este scanner é só para Android.

## O que é coletado

A cada 15 s, durante o tempo que você escolher:

| Dado | Para quê |
|---|---|
| Temperatura da bateria e de todos os sensores térmicos (CPU, GPU, modem, pele...) | ver onde e quando esquenta |
| Estado térmico do sistema (throttling) | saber se o Android está segurando o desempenho |
| Top 20 processos por uso de CPU | achar o app/serviço culpado |
| App na tela, brilho, tela ligada/desligada | relacionar o calor com o que você estava fazendo |
| Frequência de cada núcleo e uso da GPU | carga de processamento/gráfico |
| Carregando ou não, corrente da bateria | descartar o carregador como causa |

No fim, também: consumo de bateria estimado por app, *wakelocks* (apps que não deixam
o celular dormir), alarmes, sensores ligados e estatísticas de processos.

**Privacidade:** a coleta não pega localização, mensagens, fotos ou contatos. E-mails que
aparecem em nomes de sincronização são substituídos por `<email>`. A lista de apps
instalados aparece nos dados — mantenha este repositório **privado**.

## Como usar

### 1. Prepare o celular (uma vez)
1. *Configurações → Sobre o telefone* → toque 7× em **Número da versão**.
2. *Configurações → Sistema → Opções do desenvolvedor* → ative **Depuração USB**.

### 2. Instale o ADB no computador (uma vez)
- **Windows:** baixe o [Platform Tools](https://developer.android.com/tools/releases/platform-tools), descompacte e coloque a pasta no `PATH` (ou copie o `adb.exe` para esta pasta).
- **Mac:** `brew install android-platform-tools`
- **Linux:** `sudo apt install adb`

Conecte o cabo, aceite o aviso "Permitir depuração USB?" no celular e confira com `adb devices`.

### 3. Rode o scan

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

```bash
git add reports && git commit -m "scan" && git push
```

e peça: *"lê o último relatório em reports/ e me diz o que está esquentando o celular"*.

Se tiver Python, o `collect` também gera um `RESUMO.md` legível na pasta do relatório.

### Sem computador (Android 11+, via Termux)
1. Instale o [Termux](https://f-droid.org/packages/com.termux/) (pelo F-Droid).
2. `pkg install android-tools git python`
3. Em *Opções do desenvolvedor → Depuração sem fio*, toque em **Parear com código** e rode
   `adb pair 127.0.0.1:<porta> <código>` e depois `adb connect 127.0.0.1:<porta da tela principal>`.
4. Clone este repositório no Termux e use `./scan.sh run 20` (o celular continua livre para usar).

## Estrutura

```
scan.sh / scan.bat        comandos no computador (start, status, collect, run)
coletor/thermal_scan.sh   roda dentro do celular e coleta os dados
analisar.py               gera RESUMO.md a partir de um relatório
reports/                  relatórios coletados (é aqui que o Claude lê)
```

Cada relatório contém `info.txt` (aparelho e configurações), `samples.txt` (amostras ao
longo do tempo), `batterystats.txt`, `cpuinfo_*.txt`, `thermal_*.txt`, `power.txt`,
`alarm.txt`, `procstats.txt` e `sensorservice.txt`.
