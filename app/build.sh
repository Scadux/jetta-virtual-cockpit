#!/usr/bin/env bash
# Build manual do APK debug SEM Gradle (aapt2 + kotlinc + d8 + apksigner).
#
# Uso:
#   ANDROID_HOME=~/android-sdk JAVA_HOME=~/jdk17 KOTLIN_HOME=~/kotlin-compiler ./build.sh
#
# Saída: build/manual/app-debug.apk
#
# O app não tem dependências externas (só framework Android + stdlib Kotlin),
# por isso o build manual é possível. No Android Studio / PC com Gradle
# funcionando, prefira: ./gradlew assembleDebug
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
JAVA_HOME="${JAVA_HOME:-$HOME/jdk17}"
KOTLIN_HOME="${KOTLIN_HOME:-$HOME/kotlin-compiler}"
BUILD_TOOLS="$ANDROID_HOME/build-tools/34.0.0"
PLATFORM_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
# Os scripts do build-tools (d8, apksigner) procuram `java` no PATH.
export PATH="$JAVA_HOME/bin:$PATH"
BUILD="$ROOT/build/manual"

AAPT2="$BUILD_TOOLS/aapt2"
D8="$BUILD_TOOLS/d8"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"
KOTLINC="$KOTLIN_HOME/bin/kotlinc"
KEYTOOL="$JAVA_HOME/bin/keytool"
JAR="$JAVA_HOME/bin/jar"

for tool in "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER" "$KOTLINC" "$KEYTOOL" "$JAR"; do
  [ -x "$tool" ] || { echo "Falta ferramenta: $tool"; exit 1; }
done
[ -f "$PLATFORM_JAR" ] || { echo "Falta: $PLATFORM_JAR"; exit 1; }

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

echo "== 1/6 Compilando recursos (aapt2 compile) =="
"$AAPT2" compile --dir "$ROOT/app/src/main/res" -o "$BUILD/res.zip"

echo "== 2/6 Ligando APK base (aapt2 link) =="
"$AAPT2" link -o "$BUILD/app-unaligned.apk" \
  --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
  -I "$PLATFORM_JAR" \
  --auto-add-overlay \
  --java "$BUILD/gen" \
  -A "$ROOT/app/src/main/assets" \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code 3 --version-name 0.4.0 \
  "$BUILD/res.zip"

echo "== 3/6 Compilando Kotlin (kotlinc) =="
# shellcheck disable=SC2046
"$KOTLINC" $(find "$ROOT/app/src/main/java" -name '*.kt') \
  "$BUILD/gen/com/jetta/virtualcockpit/R.java" \
  -cp "$PLATFORM_JAR" -jvm-target 11 -d "$BUILD/classes"

echo "== 4/6 Gerando DEX (d8) =="
# O d8 não aceita diretório de .class: empacotar em JAR primeiro.
(cd "$BUILD/classes" && "$JAR" cf "$BUILD/classes.jar" .)
"$D8" --min-api 24 --lib "$PLATFORM_JAR" \
  --output "$BUILD/dex" "$BUILD/classes.jar" "$KOTLIN_HOME/lib/kotlin-stdlib.jar"
(cd "$BUILD/dex" && "$JAR" uf "$BUILD/app-unaligned.apk" ./*.dex)

echo "== 5/6 Alinhando (zipalign) =="
"$ZIPALIGN" -f 4 "$BUILD/app-unaligned.apk" "$BUILD/app-aligned.apk"

echo "== 6/6 Assinando (apksigner) =="
# ATENÇÃO: a chave de assinatura NUNCA pode mudar entre versões — o Android
# bloqueia a atualização de um app assinado com chave diferente. Por isso o
# keystore fica fora de $BUILD (que é apagado a cada build) e é compartilhado
# por todas as versões a partir da v0.4. As chaves da v0.2 e da v0.3 foram
# perdidas quando o keystore ainda vivia dentro de build/; a v0.4 é a linha
# de base permanente. Nunca apague ~/workspace/jetta-virtual-cockpit/keystore/.
KEYSTORE="$ROOT/../keystore/jetta.keystore"
mkdir -p "$(dirname "$KEYSTORE")"
if [ ! -f "$KEYSTORE" ]; then
  "$KEYTOOL" -genkeypair -keystore "$KEYSTORE" -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" -noprompt
fi
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$BUILD/app-debug.apk" "$BUILD/app-aligned.apk"

echo "== Verificando =="
"$APKSIGNER" verify --verbose "$BUILD/app-debug.apk"
"$AAPT2" dump badging "$BUILD/app-debug.apk" | head -8 || true
ls -la "$BUILD/app-debug.apk"
