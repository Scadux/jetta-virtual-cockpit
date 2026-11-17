# JETTA VIRTUAL COCKPIT — v0.4 (base do app Android)

Aplicativo Android independente para a multimídia K200IN do Volkswagen Jetta
2.0 TSI 2011/2012. Exibe dados do veículo em tempo real via adaptador OBD2
Bluetooth, com interface inspirada no Audi Virtual Cockpit (protótipo v0.1).

> **O painel original continua sendo a referência.** Este app é auxiliar.
> Não consulte a tela durante a condução.

## O que esta versão já faz

- **Home estilo Drive Lab**: carro em destaque (ilustração SVG), relógio,
  velocidade, RPM, turbo e mini-medidores, atalhos para Waze, Maps,
  Spotify e YouTube Music (abrem o app instalado).
- **Meu Carro**: escolha o modelo (Jetta MK6, Golf GTI, Tiguan, Amarok,
  Opala) e a cor (8 cores). A cor muda o carro e o tema da interface.
  A escolha fica salva no app.
- **Cockpit**: interface idêntica ao protótipo v0.1, agora dentro do app
  (WebView + `app/src/main/assets/index.html`), com modos
  Street / Sport / Engine, mostradores e métricas.
- **Diagnóstico**: scanner de códigos de falha (DTC, Modo 03) com botão
  de leitura, e painel de sistemas com valores ao vivo.
  O app **não apaga códigos** nem executa codificação/adaptação.
- **Gráficos**: histórico ao vivo (últimos ~2 min) de RPM, velocidade,
  temperatura da água e MAP.
- Serviço OBD2 em primeiro plano com conexão Bluetooth clássico (SPP),
  cliente ELM327 e reconexão automática.
- Leitura ao vivo (Modo 01): RPM, velocidade, temperatura do líquido de
  arrefecimento, MAP, temperatura do ar de admissão, carga do motor,
  posição do acelerador e tensão.
- Pressão do turbo **estimada** a partir do MAP (marcada com `*`).
- Escolha do adaptador entre os dispositivos pareados, com conexão
  automática na abertura do app.
- Modo demonstração com valores simulados, sempre sinalizado como
  "MODO DEMONSTRAÇÃO" (nunca apresentado como real).
- Inicialização automática com a multimídia (opcional; depende do Android
  da central permitir iniciar Activity no boot).
- Parâmetros indisponíveis aparecem como `--`, nunca com valor inventado.
  Marcha e consumo ainda não têm fonte real nesta versão e ficam em `--`.
- A barra de mídia mostra apenas texto por enquanto; mostrar a música
  real tocando exige uma permissão extra (serviço de notificações) e
  fica para uma próxima versão.
- **Atualização dentro do app** (`update/UpdateChecker.kt`): ao abrir,
  o app verifica silenciosamente um arquivo `version.json` (endereço
  configurável nas configurações do app, botão ⚙). Se houver versão
  nova, oferece baixar e instalar por cima — as configurações são
  preservadas. O APK é entregue ao instalador por um ContentProvider
  próprio (`update/UpdateFileProvider.kt`, sem AndroidX). Na primeira
  vez, o Android pode pedir para autorizar "instalar apps
  desconhecidos" para este app.

## Hospedando as atualizações (configuração única)

O arquivo `version.json` (na raiz deste projeto) é o modelo. Para
ativar a atualização automática:

1. Crie um repositório no GitHub (ex.: `jetta-virtual-cockpit`).
2. Publique o APK como asset de uma Release (ex.: tag `v0.4.0`).
3. Coloque o `version.json` na raiz do repositório, com `versionCode`,
   `versionName` e o `apkUrl` apontando para o asset da Release.
4. No app: ⚙ → "Endereço de atualizações" → cole o endereço bruto do
   arquivo, ex.:
   `https://raw.githubusercontent.com/<usuario>/jetta-virtual-cockpit/main/version.json`

A cada versão nova, basta subir o APK na Release e atualizar o
`version.json` — o app passa a se atualizar sozinho. Alternativa
futura: publicar na Play Store (conta de desenvolvedor, US$ 25),
com atualização automática total.

## Arquitetura

```
MainActivity (Kotlin)
  └─ WebView → assets/index.html (visual v0.1)
  │    ↑ updateLiveData(json) / setConnectionState(state)  [Kotlin → JS]
  │    ↓ AndroidObd.onModeChanged() / openSettings()       [JS → Kotlin]
  └─ ObdService (foreground service)
       └─ Elm327Client (Bluetooth SPP + protocolo ELM327/OBD2)
       └─ DemoDataGenerator (simulação, somente modo demo)
BootReceiver → abre o app no boot (se ativado)
```

- `minSdk 24` / `targetSdk 34`, Kotlin, sem Compose (leve para 2 GB de RAM).
- **Zero dependências externas**: só framework Android + stdlib Kotlin.
  Isso mantém o APK leve (~730 KB) e permite compilar sem Gradle.
- RPM e velocidade a ~4 Hz; demais parâmetros a ~1 Hz.
- Serviço separado da tela: a leitura continua mesmo se a interface
  reiniciar.

## Como compilar

### Opção A — Gradle (recomendado no PC / Android Studio)

Pré-requisitos: JDK 17, Android SDK (API 34, build-tools 34.0.0).

```bash
./gradlew assembleDebug
# Saída: app/build/outputs/apk/debug/app-debug.apk
```

No Android Studio: *File → Open* nesta pasta e *Run* / *Build APK*.

### Opção B — Build manual sem Gradle (`build.sh`)

Usa direto as ferramentas do SDK (`aapt2`, `d8`, `apksigner`) + o
compilador Kotlin standalone. Útil em ambientes onde o Gradle não roda.

Pré-requisitos: JDK 17, Android SDK (API 34, build-tools 34.0.0) e o
compilador Kotlin 2.0.x descompactado em algum lugar.

```bash
ANDROID_HOME=~/android-sdk JAVA_HOME=~/jdk17 KOTLIN_HOME=~/kotlin-compiler ./build.sh
# Saída: build/manual/app-debug.apk
```

## Como instalar na K200IN

1. Copie o `app-debug.apk` para um pendrive ou baixe-o na própria central.
2. Na central, permita "instalar apps desconhecidos" para o app usado
   (gerenciador de arquivos ou navegador).
3. Instale o APK e abra o **Jetta Virtual Cockpit**.
4. Pareie o adaptador OBD2 no Bluetooth da multimídia **antes** de abrir
   o app (o app lista apenas dispositivos já pareados).
5. No app, toque no botão de configurações (canto inferior direito) e
   escolha o adaptador. A conexão é automática nas próximas aberturas.

## Testando sem o carro

Ative o **Modo demonstração** no menu de configurações: a interface passa
a exibir valores simulados com o selo "MODO DEMONSTRAÇÃO".

## Próximos passos

1. Validar na K200IN: resolução real, API Android verdadeira, instalação
   de APK e Bluetooth SPP.
2. Comprar o adaptador (avaliar Vgate vLinker MC+, após validar o item 1).
3. Testar a leitura real no Jetta (começar com RPM, velocidade, água, MAP).
4. Estimativa de consumo, histórico de viagens e alertas.
5. Avaliar acesso a parâmetros específicos VW (temperatura/pressão do
   óleo, marcha) conforme suporte confirmado — sem prometer antes de testar.
