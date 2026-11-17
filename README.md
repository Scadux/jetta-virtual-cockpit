# JETTA VIRTUAL COCKPIT

App Android independente para a multimídia K200IN de um Volkswagen Jetta
2.0 TSI 2011/2012, com dados em tempo real via adaptador OBD2 Bluetooth
e visual inspirado no Audi Virtual Cockpit. Roda direto na central, sem
Android Auto e sem celular.

## Estrutura

- `app/` — projeto Android (Kotlin, zero dependências externas).
  Veja `app/README.md` para arquitetura, build e funcionalidades.
- `version.json` — manifesto de versão lido pelo atualizador dentro do
  app. Cada release atualiza este arquivo com o novo `versionCode`,
  `versionName` e o `apkUrl` do APK da release.

## Atualização dentro do app

O app verifica o `version.json` deste repositório ao abrir
(endereço bruto:
`https://raw.githubusercontent.com/Scadux/jetta-virtual-cockpit/main/version.json`).
Se houver versão nova, baixa o APK da Release correspondente e instala
por cima, preservando as configurações.

Para publicar uma versão nova:

1. Suba o código e atualize o `version.json` na branch `main`.
2. Crie uma Release com a tag `vX.Y.Z` e anexe o APK com o mesmo nome
   usado no `apkUrl` do `version.json`.

## Assinatura (importante)

Todas as versões a partir da v0.4 usam a mesma chave de assinatura.
O keystore **nunca** é commitado neste repositório — ele vive fora do
projeto e é compartilhado por todos os builds. Sem ele, o Android
bloqueia a atualização por cima da versão instalada.

## Build

No PC, com Gradle funcionando: `./gradlew assembleDebug` (é preciso
baixar o `gradle-wrapper.jar` separadamente — arquivos binários não
são versionados aqui — ou abrir o projeto no Android Studio, que
resolve o Gradle sozinho). Também existe o build manual
`app/build.sh` (aapt2 + kotlinc + d8 + apksigner), usado no ambiente
de desenvolvimento deste projeto.
