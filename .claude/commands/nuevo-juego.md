---
description: Scaffolding de un minijuego nuevo (Fase 6) siguiendo la arquitectura MVI + local-first del proyecto
---

# Nuevo juego: $ARGUMENTS

Vas a crear el esqueleto de un minijuego nuevo para el catálogo de Logic Games,
siguiendo exactamente el patrón de los juegos ya existentes en
`shared/src/commonMain/kotlin/com/kortexgames/app/game/<paquete>/`. No inventes
una arquitectura distinta: copia la forma de un juego similar ya publicado
(mirá `game/blockgrid/`, `game/energyflow/` o `game/neonpulse/` según se parezca
más en mecánica al nuevo) y adaptá.

## Antes de escribir código

1. Preguntá o confirmá (si no está claro en el pedido del usuario): nombre del
   juego, categoría cognitiva (una de las 11 de la §1 del `CLAUDE.md` — priorizá
   **Flexibilidad Cognitiva** o **Reconocimiento de Patrones** si no se especifica,
   porque hoy no tienen ningún juego), mecánica básica, y si tiene niveles de
   dificultad.
2. Leé `shared/src/commonMain/kotlin/com/kortexgames/app/game/GameCatalog.kt`
   completo para ver el patrón de `GameInfo`, `GameIds`, `GameMotif` y cómo se
   registran los juegos existentes (`playable`, `published`).
3. Leé un juego de referencia comparable completo (contract + viewmodel +
   screen) antes de escribir nada nuevo.

## Checklist de implementación

- [ ] **Contract** (`<Juego>Contract.kt`): `State` (implementa `UiState`),
      `Intent` (`sealed interface : UiIntent`), `Effect` — seguí `core/mvi/Mvi.kt`
      y la referencia canónica `ui/settings/SettingsViewModel.kt` para la forma
      del patrón MVI. Efectos one-shot (sonido, navegación) van en `Effect`,
      nunca en el `State`.
- [ ] **ViewModel** (`<Juego>ViewModel.kt`): extiende `MviViewModel<Intent, State, Effect>`.
      Lógica pura de juego, sin dependencias de plataforma.
- [ ] **Screen** (`<Juego>Screen.kt`): Compose. Colores SIEMPRE de `MaterialTheme`/
      `LogicColors`, nunca hardcodeados. Feedback inmediato (sonido + háptica) en
      cada interacción relevante vía `AudioAndHapticManager`. Usá `bounceClick()`
      en lo tocable. Si necesita bordes de neón en celdas/tablero, reusá
      `drawNeonTile`/`NeonFrame` (§9.7 del `CLAUDE.md`) — no dibujes un borde ad-hoc.
- [ ] **Entrada en `GameCatalog.kt`**: agregá el `GameInfo` con `playable = true`.
      Si el juego todavía no está pulido para salir a producción, arrancá con
      `published = false` (como `Palabras Conectadas`/`Tornillos Neón` hoy) y
      avisá al usuario que hace falta flipearlo cuando esté listo.
- [ ] **KDoc en todo lo público** (regla §3 del `CLAUDE.md`): explicá el PORQUÉ
      de decisiones no evidentes, no narres lo obvio.
- [ ] **Textos**: cualquier string de UI nuevo va a
      `shared/src/commonMain/composeResources/values/strings.xml` con clave
      `<área>_<pantalla>_<detalle>` — nunca hardcodeado en el Composable
      (regla §10). El **nombre del juego** en cambio SÍ va hardcodeado en
      `GameCatalog.kt` (es contenido de catálogo, no UI genérica).
- [ ] **Migración Supabase**: creá un archivo nuevo en `supabase/migrations/`
      (numeración siguiente a la última, nunca edites una ya aplicada) para el
      seed del juego en el catálogo remoto. Mirá una migración de seed de un
      juego reciente como referencia de forma.
- [ ] **Puntuación**: si el juego tiene niveles/tiempo, la puntuación debe
      penalizar ayudas (pistas, deshacer) — nivel + tiempo + eficiencia, no solo
      "completado sí/no". Ver memoria del proyecto sobre este criterio.

## Al terminar

- Compilá con `./gradlew :shared:compileKotlinIosSimulatorArm64` (ya está en el
  allowlist del proyecto) como señal de que el código es correcto — **no
  levantes emulador/simulador** para verificar (regla del `CLAUDE.md` §6).
- Resumí qué falta para publicarlo (`published = true`) y esperá confirmación
  antes de tocar más juegos — el proyecto avanza en fases, no satures Fase 6
  con múltiples juegos a la vez sin que el usuario lo pida.
