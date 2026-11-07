# CalorScan — o que está esquentando meu celular?

Duas peças, no mesmo visual de blocos:

| | O que faz |
|---|---|
| **App do celular** (`apk/CalorScan.apk`, ~160 KB) | Mede a temperatura sozinho desde que o celular liga, avisa quando esquenta, explica o porquê em português claro e aplica os ajustes que esfriam. |
| **CalorScan PC** (programa do notebook) | Com o cabo: instala e libera o app, aplica os ajustes e faz a medição completa do boot (com o consumo de cada processo). |

> Só Android. O iPhone não deixa nenhum app ver o que os outros fazem.

---

## 1. Instalar o app no celular

1. **Samsung:** *Configurações → Segurança e privacidade → Bloqueador automático* → desligue (ele impede instalar
   apps de fora da Play Store; dá para religar depois).
2. No celular, baixe: <https://github.com/lukas-lkc/App-mobile-performance/raw/ccr-e3a2eeb0-ekv3ov/apk/CalorScan.apk>
3. Abra o arquivo e permita "instalar apps desconhecidos". Se o Play Protect avisar → *Mais detalhes → Instalar assim mesmo*.

Na aba **Config** do app, toque nos botões de *Permissões básicas* (notificações, dados de uso, segundo plano).

## 2. Liberação completa (uma vez só, opcional)

Algumas coisas o Android só deixa liberar pelo modo desenvolvedor: **pausar o perfil de trabalho no horário**,
**restringir apps sozinho** e **mudar a busca de Wi-Fi/Bluetooth**. Depois de liberado, **desligue o modo
desenvolvedor**: as permissões continuam valendo (e apps de banco/governo voltam a abrir normalmente).

**Jeito A — com notebook:** use o CalorScan PC (abaixo) e clique em *Liberar app*.

**Jeito B — sem computador (Shizuku, precisa de Wi-Fi):** siga os passos na aba **Config → Jeito B** do app.
Resumo: instalar o *Shizuku* (Play Store) → ativar as Opções do desenvolvedor (7 toques em *Número de compilação*)
→ no Shizuku, *Parear* → em *Opções do desenvolvedor → Depuração sem fio*, *Parear dispositivo com código de
pareamento* → digitar o código na notificação do Shizuku → *Iniciar* → no CalorScan, *Conectar ao Shizuku* e
*Liberar agora*.

Sem a liberação o app funciona; a diferença está na tabela:

| Recurso | Sem liberação | Com liberação |
|---|---|---|
| Temperatura ao vivo, alertas, diagnóstico, resumo para IA | ✅ | ✅ |
| Restringir apps em segundo plano | à mão (o app abre a página de cada um) | sozinho |
| Desativar "Oi, Bixby" e Sugestões inteligentes | à mão | sozinho |
| Busca de Wi-Fi/Bluetooth | à mão | sozinho |
| Pausar/ligar o perfil de trabalho no horário | só lembrete | sozinho |

## 3. O app por dentro

- **Agora** — temperatura ao vivo (o mascote muda de cor), gráfico das últimas 3 horas, o que o app descobriu
  (pico depois de ligar, apps mais quentes, apps rodando escondidos, perfis extras, carregador) e o botão
  **Pedir ajuda a uma IA**, que gera um resumo `.md` com as medições e as instruções prontas para colar em
  qualquer IA.
- **Esfriar** — *Não deixar iniciar sozinho* (liga/desliga, com a lista de apps; os sugeridos vêm marcados e os que
  precisam avisar na hora, como Pix e mensagens, você desmarca), recursos da Samsung, busca de Wi-Fi/Bluetooth e
  **Desfazer tudo**.
- **Trabalho** — pausar/ligar agora e **agenda por dia da semana** (ex.: seg–sex, liga 08:00 e pausa 20:00). Ao
  ligar o celular fora do expediente, o perfil já fica pausado, o que alivia o aquecimento do boot.
- **Fotos** — chave *Fotos naturais* (liga/desliga): mostra o que mudar na câmera para ter menos
  pós-processamento (efeitos de rosto na selfie, Otimizador de cena, HDR automático, filtros) ou para voltar ao
  padrão Samsung, com atalho para abrir a câmera e lista para marcar. A Samsung não deixa nenhum outro app mudar
  essas opções, então é um guia, não um ajuste automático. Também indica a câmera *Expert RAW* (oficial da Samsung,
  com pouco processamento).
- **Config** — monitor sempre ligado, limite do alerta (41/43/45 °C), permissões e liberação.

O monitor é leve: reage aos avisos de temperatura do próprio Android e grava uma leitura a cada 10 s nos primeiros
15 min depois de ligar e a cada 1 min no resto do tempo. Nada sai do celular, a não ser o resumo quando você o
compartilha.

## 4. CalorScan PC (notebook Windows)

1. Baixe o projeto: <https://github.com/lukas-lkc/App-mobile-performance/archive/refs/heads/ccr-e3a2eeb0-ekv3ov.zip>
   e descompacte.
2. Precisa do Python (<https://www.python.org/downloads/>, marque *Add python.exe to PATH*).
3. Dê dois cliques em **`Abrir CalorScan PC.bat`**. Abre uma janela com os passos; cada botão acende quando o
   passo anterior está pronto:
   1. **Conectar o celular** (mostra como ativar a Depuração USB, o driver da Samsung e o Bloqueador automático;
      se faltar o ADB, ele baixa sozinho)
   2. **Instalar o app**
   3. **Liberar o app**
   4. **Aplicar ajustes** escolhidos na aba *Esfriar*
   5. **Medir o aquecimento** — *Reiniciar e medir* (boot) ou *Medir agora*; mostra o resultado com gráfico,
      conclusões e **Copiar resumo para IA**
   6. **Desligar o modo desenvolvedor**

As medições ficam na pasta `relatorios/`. O programa só executa no celular comandos de uma lista fechada
(restringir/liberar apps, liberar o próprio CalorScan, desativar/reativar o Bixby e as Sugestões, busca de Wi-Fi).

---

## Avançado

- `scan.sh` / `scan.bat` — o scanner de linha de comando que deu origem a tudo (`boot`, `start`, `status`, `collect`).
- `coletor/thermal_scan.sh` — roda dentro do celular (via ADB) e coleta temperatura, CPU por processo, estado
  térmico, batterystats, wakelocks e registros do sistema.
- `analisar.py` / `diagnostico.py` — leem uma medição e geram o resumo e as conclusões.
- `android/` — código do app (Kotlin, sem bibliotecas além do Shizuku). Para compilar: `cd android && ./gradlew assembleRelease`.
- `pc/` — código do CalorScan PC (Python, só biblioteca padrão; a interface é `pc/ui.html`).
