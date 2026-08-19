---
description: Verificación estándar de compilación (Android + iOS) sin levantar emulador/simulador
---

# Verificación estándar

Corré la batería de verificación que usa este proyecto para confirmar que un
cambio compila, **sin** levantar el emulador Android ni el simulador iOS
(regla §6 del `CLAUDE.md` — gasta demasiados tokens/tiempo en este entorno):

1. `./gradlew :shared:compileKotlinIosSimulatorArm64` — compila el target iOS
   compartido. Es la señal principal de que `commonMain` es válido.
2. Si el cambio toca algo Android-específico (`androidMain`, `androidApp`), sumá
   `./gradlew :androidApp:assembleDebug`.
3. Si el cambio toca lógica de juego con tests existentes, corré el test
   relevante en vez de la suite completa, ej.
   `./gradlew :shared:testAndroidHostTest --tests "com.kortexgames.app.game.<paquete>.*"`.

Reportá el resultado tal cual salió (si falló, pegá el error real; no digas
"debería andar" sin haber corrido esto). Si el usuario quiere ver el resultado
en pantalla (UI, animación, layout), decile que lo arranque él mismo en vez de
levantar vos el emulador/simulador por defecto.
