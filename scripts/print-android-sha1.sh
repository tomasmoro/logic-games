#!/usr/bin/env bash
#
# print-android-sha1.sh — imprime la huella SHA-1 de un keystore de firma Android.
#
# ¿PARA QUÉ? El login con Google en Android (Credential Manager) exige registrar en
# Google Cloud un OAuth client id de tipo "Android" con el package name de la app y
# la SHA-1 de la clave con la que se firma el APK. Google usa ese par
# (package + SHA-1) para verificar que quien pide el token es de verdad tu app.
#
# - DEBUG: la clave `~/.android/debug.keystore` (contraseña conocida "android") que
#   usa Android Studio al instalar en el emulador/dispositivo durante el desarrollo.
#   Registra su SHA-1 para poder PROBAR el login en debug.
# - RELEASE: tu keystore de publicación (o, si usas Play App Signing, la SHA-1 que
#   aparece en Play Console → App integrity). Registra ESA para producción.
#
# Uso:
#   ./scripts/print-android-sha1.sh                 # keystore debug por defecto
#   ./scripts/print-android-sha1.sh <keystore> <alias>   # release u otro
#
set -euo pipefail

KEYSTORE="${1:-$HOME/.android/debug.keystore}"

if [[ "$KEYSTORE" == "$HOME/.android/debug.keystore" ]]; then
  ALIAS="${2:-androiddebugkey}"
  STOREPASS="android"
  KEYPASS="android"
  IS_DEBUG=1
  echo "→ Keystore DEBUG: $KEYSTORE (alias=$ALIAS)"
else
  IS_DEBUG=0
  ALIAS="${2:?Debes indicar el alias del keystore de release}"
  # No hardcodeamos la contraseña de release: keytool la pedirá de forma interactiva.
  STOREPASS=""
  KEYPASS=""
  echo "→ Keystore RELEASE: $KEYSTORE (alias=$ALIAS)"
fi

if [[ ! -f "$KEYSTORE" ]]; then
  echo "ERROR: no existe el keystore '$KEYSTORE'." >&2
  if [[ "$KEYSTORE" == "$HOME/.android/debug.keystore" ]]; then
    echo "       Se crea solo al instalar una app debug desde Android Studio una vez." >&2
  fi
  exit 1
fi

if [[ -n "$STOREPASS" ]]; then
  keytool -list -v -keystore "$KEYSTORE" -alias "$ALIAS" \
    -storepass "$STOREPASS" -keypass "$KEYPASS" | grep -E "SHA1:|SHA-1:" || {
      echo "No se encontró SHA-1 (¿alias incorrecto?)." >&2; exit 1; }
else
  keytool -list -v -keystore "$KEYSTORE" -alias "$ALIAS" | grep -E "SHA1:|SHA-1:" || {
      echo "No se encontró SHA-1 (¿alias/contraseña incorrectos?)." >&2; exit 1; }
fi

echo
if [[ "$IS_DEBUG" == "1" ]]; then
  echo "Esta es la huella de DEBUG: sirve para PROBAR el login con Google en desarrollo."
  echo "NO es la de producción. Para la de tu keystore de subida:"
  echo "    ./scripts/print-android-sha1.sh kortexgames-upload.jks upload"
else
  echo "Esta es la huella de tu keystore de SUBIDA."
  echo "Si usas Play App Signing, Play re-firma la app con OTRA clave: registra también"
  echo "la SHA-1 de Play Console → Integridad de la app → Firma de apps, o el login con"
  echo "Google fallará para los usuarios que instalen desde la tienda."
fi
echo
echo "Copia el valor tras 'SHA1:' (formato AA:BB:CC:...) al OAuth client id de tipo"
echo "Android en Google Cloud, junto al package name 'com.kortexgames.app'."
