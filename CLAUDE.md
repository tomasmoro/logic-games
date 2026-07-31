# CLAUDE.md — Guía del proyecto Logic Games

Instrucciones para cualquier sesión de Claude Code que trabaje en este repo.
Léelas antes de escribir o modificar código.

---

## 1. Qué es este proyecto

App móvil de **entrenamiento mental y juegos de lógica** estilo *Impulse* /
*Lumosity*. La propuesta es un producto **adictivo y de uso diario**: sesiones
cortas de minijuegos que ejercitan distintas capacidades cognitivas, con
progreso medible y fuerte gancho de retención.

### Visión de producto y funcionalidades

- **Contenido — 30 minijuegos** repartidos en **11 categorías cognitivas**:
  Memoria, Pensamiento Lógico, Resolución de Problemas, Reflejos, Velocidad
  Mental, Atención y Concentración, Visión Espacial, Cálculo Mental, Lenguaje y
  Vocabulario, Flexibilidad Cognitiva y Reconocimiento de Patrones. Además:
  sección de **Acertijos** independientes y **Desafíos Diarios** con leaderboard.
- **Gamificación y retención:** objetivo mental diario (p. ej. 5 ejercicios/día
  para la recompensa), sistema de **logros**, y **rachas** por inicio de sesión
  consecutivo.
- **Estadísticas:** gráficos de progreso (tiempo de finalización, efectividad) y
  **comparación contra el resto de jugadores al terminar cada partida**
  (percentiles: "Eres mejor que el X% de los jugadores").
- **Monetización:** plan **gratuito** con anuncios cada 3 minutos de juego activo
  y plan **Premium** sin anuncios.
- **Cuentas y sincronización:** login con **Google** y **Email/Password**; modo
  **invitado** 100% local/offline que, al autenticarse, sincroniza su progreso
  con la nube (bidireccional).
- **UX/UI:** diseño llamativo con colores vibrantes, animaciones fluidas en
  transiciones y **feedback inmediato** (visual, sonoro y háptico) al acertar o
  fallar.

> Estado actual: arquitectura, sistema de diseño, auth (Google + email en ambas
> plataformas), anuncios, logros, objetivo diario y **17 juegos** están
> implementados. Faltan ~13 juegos, los acertijos, el leaderboard de desafíos y
> la compra del plan Premium. Ver el detalle en la tabla de fases (§2).

### Stack técnico

- **Cliente:** Kotlin Multiplatform + **Compose Multiplatform** (Android e iOS,
  UI y lógica compartidas en `commonMain`).
- **Backend:** **Supabase** (PostgreSQL + Auth + Edge Functions). Proyecto real
  ya provisionado: ref `pfjsacrxtutrkcsybaxh`
  (`https://pfjsacrxtutrkcsybaxh.supabase.co`).
- **Idioma del proyecto:** el código, comentarios, KDoc y mensajes de commit se
  escriben en **español** (mantén la consistencia con lo existente).

---

## 2. Disciplina de fases (IMPORTANTE)

El proyecto avanza en **fases secuenciales estrictas**. No se salta a la fase
siguiente sin que el usuario lo confirme explícitamente.

| Fase | Contenido | Estado |
|------|-----------|--------|
| 1 | Esquema BD PostgreSQL (3FN, particionado) | ✅ aplicado |
| 2 | RLS + RPC percentiles + Edge Function | ✅ aplicado |
| 3 | Arquitectura app (MVI, local-first, AdManager, tema, gráficos, audio/háptica) | ✅ |
| 4 | `GameEngine` + 2 juegos ejemplo + Daily Goal | ✅ |
| 5 | Sistema de Diseño visual (tema, animaciones, navegación, pantallas Home/Games/Profile) | ✅ |
| 6 | Catálogo de juegos (los 30 minijuegos de las 11 categorías) | 🚧 en curso |
| 7 | Acertijos + leaderboard de Desafíos Diarios | ⬜ pendiente |
| 8 | Monetización real (compra del plan Premium) y publicación en tiendas | ⬜ pendiente |

Al terminar una fase: resume lo hecho y **espera confirmación** antes de seguir.

> El **Sistema de Diseño** (paleta, tipografía, principios de animación) está
> especificado en la §9. Es la referencia obligatoria para toda UI nueva.

### Estado real a 29/07/2026

La Fase 5 se dio por cerrada: el tema (`core/theme`) y ~23 componentes de
`ui/components` están en producción, y las pantallas Home / Games / Profile /
Settings / Auth existen y navegan. Lo que queda de UI son detalles, no cimientos,
y vive en `BACKLOG.md`.

**Fase 6 (en curso) — juegos.** Hay **17 juegos jugables** (`playable = true`) en
`game/GameCatalog.kt`, de los cuales **14 están publicados**; 3 quedan ocultos
tras `published = false` (Palabras Conectadas, Tornillos Neón, Neon Starport
Escape) a la espera de pulido. Faltan ~13 para llegar a los 30 de la visión, y
las categorías **Flexibilidad Cognitiva** y **Reconocimiento de Patrones** aún no
tienen ningún juego implementado. Cada juego nuevo necesita su seed en Supabase
(`supabase/migrations/`, ya van 25) además del código.

**Transversales ya resueltos** (no son fase, pero conviene saber que existen para
no reimplementarlos): auth con Google en Android **e** iOS + email/password,
borrado de cuenta (`AccountViewModel` + Edge Function `delete-account`), AdMob
con consentimiento UMP, logros (`game/achievements`), objetivo diario y rachas
(`game/daily`), progresión y récords por nivel.

**Bloqueantes conocidos de la Fase 8** (publicación): el plan Premium está
modelado en el dominio pero **no se puede comprar** (no hay SDK de billing), y la
app se dirige a público mixto con menores de 13 sin la pantalla neutral de edad
ni el `tagForChildDirectedTreatment` que exigen COPPA y la Política de Familias
de Google Play. Las páginas legales (privacidad y condiciones, ES/EN) ya existen
en `site/` y se despliegan solas a GitHub Pages (`.github/workflows/deploy-site.yml`).

---

## 3. Estándar de documentación (REGLA OBLIGATORIA)

El usuario exige **código bien documentado** para facilitar el trabajo futuro.
Aplica esto a TODO código nuevo o modificado:

1. **KDoc en todo lo público**: cada `class`, `interface`, `object`, función y
   propiedad pública lleva KDoc (`/** ... */`) que explique su propósito.
2. **Explica el PORQUÉ, no el qué**: los comentarios justifican decisiones de
   diseño, trade-offs y "gotchas", no narran lo obvio del código.
   - ✅ `// SECURITY DEFINER: necesita leer scores de todos, pero solo devuelve agregado`
   - ❌ `// incrementa el contador en 1`
3. **Usa `@param`, `@return`, `@throws`** cuando aporten claridad (parámetros no
   evidentes, contratos de error).
4. **Documenta las fronteras `expect`/`actual`**: en el `expect` explica el
   contrato; en cada `actual` explica lo específico de la plataforma.
5. **Encabezado de archivo** en piezas de arquitectura no triviales (managers,
   repos, motores): un bloque que resuma responsabilidad y decisiones clave.
6. **SQL**: comenta tablas/columnas con propósito no evidente (`comment on ...`)
   e incluye el "porqué" de índices, particiones y `SECURITY DEFINER`.
7. Mantén el estilo y densidad de comentarios del código ya existente.

Sigue estos patrones incluso si el usuario no lo repite en cada petición.

---

## 4. Arquitectura y convenciones de código

### Módulos Gradle

- **`shared`** (plugin `com.android.kotlin.multiplatform.library`): TODO el
  código común y los `actual` de plataforma (`commonMain`/`androidMain`/`iosMain`).
  Es donde se trabaja el 95% del tiempo. Framework iOS: `Shared`.
- **`androidApp`** (`com.android.application`): lanzador Android fino
  (`MainActivity`, `LogicGamesApp`, manifest). Depende de `projects.shared`.
- **`iosApp`** (Xcode): lanzador iOS; llama a `MainViewControllerKt.MainViewController()`.

### Paquete base (en `shared`): `com.kortexgames.app`

```
core/mvi        Base MVI (MviViewModel, UiState/UiIntent/UiEffect)
core/theme      Tema Compose (LogicColors, LogicGamesTheme, tipografía)
core/ads        AdManager
core/audio      AudioAndHapticManager (interfaz), SoundEffect, expect PlatformContext
data/settings   SettingsRepository (DataStore → StateFlow), expect DataStore
data/local      SQLDelight (datasource + DatabaseDriverFactory expect)
data/remote     Cliente Supabase + RemoteProgressDataSource (RPC FASE 2)
data/repository Implementaciones de repositorios (local-first)
domain/model    Modelos de dominio puros (sin dependencias de framework)
domain/repository  Interfaces de repositorio
di              AppGraph (DI manual)
ui              App, pantallas, components (AnimatedGameButton, LineChart)
```

### MVI (patrón obligatorio para pantallas)
- Extiende `MviViewModel<Intent, State, Effect>` (`core/mvi/Mvi.kt`).
- **Estado** = `StateFlow` inmutable (`data class` que implementa `UiState`).
- **Intents** = `sealed interface : UiIntent`; único punto de entrada `onIntent`.
- **Effects** (one-shot: navegación, snackbar, sonido) = `Channel`→`Flow`, NO
  estado. Nunca metas eventos one-shot en el `State`.
- La UI observa con `collectAsStateWithLifecycle()`.
- Referencia canónica: `ui/settings/SettingsViewModel.kt`.

### Local-first (datos)
- La **fuente de verdad de la UI es siempre local** (SQLDelight). La UI observa
  local, nunca la red directamente.
- Al escribir: local primero → si hay sesión, sincroniza a Supabase.
- Modo invitado (`AuthState.Guest`) funciona 100% offline; al autenticarse se
  llama `syncPending()`.
- Referencia: `data/repository/ProgressRepositoryImpl.kt`.

### expect/actual
- Todo lo dependiente de plataforma se declara `expect` en `commonMain` y se
  implementa en `androidMain` / `iosMain`.
- `PlatformContext` abstrae el contexto (Android = `Context` vía `actual
  typealias`; iOS = `actual class` vacía).
- Nombra los archivos actual con sufijo de plataforma: `Foo.android.kt`,
  `Foo.ios.kt`.

### Compose
- Colores SIEMPRE desde `MaterialTheme`/`LogicColors`, nunca hardcodeados sueltos.
- Feedback inmediato (sonido + háptica) en interacciones de juego.
- Componentes reutilizables en `ui/components`, con `Modifier` como primer
  parámetro opcional y valores por defecto.

### Estilo Kotlin
- `kotlin.code.style=official`. Inmutabilidad por defecto (`val`, `data class`,
  `List` sobre `MutableList` en APIs públicas).
- Coroutines: `Dispatchers.Default` para CPU en común (no hay `Dispatchers.IO`
  en `commonMain`). Nada de trabajo bloqueante en el hilo principal.
- Modela dominios cerrados con `enum`/`sealed`, no strings.

---

## 5. Backend (Supabase)

- Migraciones en `supabase/migrations/` (numeradas `000N_nombre.sql`). Toda
  nueva migración es un archivo nuevo, **nunca edites una ya aplicada**.
- Edge Functions en `supabase/functions/`.
- `public.users.id` es FK 1:1 a `auth.users(id)` — no dupliques identidades.
- RLS: el usuario solo accede a lo suyo (`auth.uid() = user_id`). Catálogos son
  de solo lectura para `authenticated`; escritura vía `service_role`.
- Los cálculos de agregados globales (percentiles) van en RPC `SECURITY DEFINER`
  que devuelven SOLO estadística, nunca filas ajenas.
- Al tocar la BD, ejecuta el **security advisor** de Supabase y resuelve los
  hallazgos reales.
- Claves: la publishable/anon key es pública (va en el cliente). La
  `service_role` y la contraseña de BD **nunca** se commitean ni se pegan en chat.

### Herramientas MCP de Supabase
Hay un MCP de Supabase conectado. Úsalo para `apply_migration`,
`deploy_edge_function`, `get_advisors`, `execute_sql` (tests con `ROLLBACK`),
en vez de pedir credenciales.

---

## 6. Build / Run

> Requiere JDK 17, Android SDK y (para iOS) Xcode. El `gradle-wrapper.jar` no
> está versionado: genera el wrapper con `gradle wrapper --gradle-version 8.11.1`
> o abre el proyecto en Android Studio (lo regenera al sincronizar).

```bash
./gradlew :androidApp:assembleDebug         # APK Android debug
./gradlew :shared:compileKotlinIosArm64 # compila target iOS
./gradlew build                             # build completo + tests
```

- Versiones centralizadas en `gradle/libs.versions.toml` (version catalog). Añade
  dependencias ahí, no con coordenadas sueltas en los `build.gradle.kts`.
- iOS se integra vía el framework `ComposeApp` y `MainViewController()`.

### Verificación de cambios (NO uses emulador/simulador)

- **No arranques el emulador Android ni el simulador iOS para verificar cambios**
  (gasta demasiados tokens/tiempo en este entorno). Verifica con `./gradlew
  :shared:compileKotlinIosSimulatorArm64` (o el target que aplique) y, si hace
  falta, `:androidApp:assembleDebug`/tests — eso basta como señal de que el
  código compila y es correcto.
- Si el usuario quiere ver el resultado en pantalla, dile que lo arranque él
  mismo (o pídeselo explícitamente) en vez de lanzarlo tú por defecto.

---

## 7. Git

- Rama principal: `main`. Trabaja en ramas de feature y no hagas commit/push
  salvo que el usuario lo pida.
- Mensajes de commit en español, descriptivos.
- No versiones: `local.properties`, `secrets.properties`, keystores,
  `.claude/settings.local.json` (ver `.gitignore`).

---

## 8. Cosas a NO hacer

- No saltar de fase sin confirmación.
- No commitear secretos (service_role, keystores, contraseñas).
- No editar migraciones SQL ya aplicadas (crea una nueva).
- No poner colores/strings hardcodeados donde exista tema/recurso.
- No dejar código público sin KDoc (ver sección 3).
- No introducir dependencias de plataforma en `commonMain` sin `expect/actual`.

---

## 9. Sistema de Diseño (Design System)

> **Filosofía rectora: "Los detalles hacen grande a cualquier app; sé detallista
> siempre."** Diseño moderno, amigable y estético. Cada interacción responde con
> feedback visual. La app debe sentirse *viva* sin ser ruidosa.

La fuente de verdad en código vive en `core/theme` (`LogicColors`,
`LogicGradients`, `LogicTypography`, `LogicGamesTheme`) y en los modificadores de
`ui/components/ModifierExt.kt`. **Esta sección manda sobre cualquier valor
hardcodeado**: si necesitas un color, sale de `LogicColors`; si un color de
categoría, de `CategoryPalette`.

### 9.1 Concepto: "Lógica + Juego"

La identidad nace de una tensión deliberada entre dos mundos:

- **Lógica** → fondos oscuros y profundos (**azul noche**, `#0A0E1A`). Transmiten
  concentración, elegancia y foco. Es el "lienzo" sobre el que el jugador piensa.
- **Juego** → acentos vibrantes y saturados (neón). Transmiten energía,
  gamificación y recompensa. Son los "premios" que puntúan la pantalla.

Regla de oro de proporción: **superficie mayormente oscura, acento escaso**. El
neón vale porque es raro; si todo brilla, nada resalta.

### 9.2 Paleta de colores

**Base / "Lógica" (superficies azul-noche, tema por defecto):**

| Rol | Token | Hex | Uso |
|-----|-------|-----|-----|
| Fondo profundo | `BackgroundDark` | `#0A0E1A` | Fondo raíz de la app |
| Superficie | `SurfaceDark` | `#141B2E` | Tarjetas, sheets, barra inferior |
| Superficie elevada | `SurfaceVariantDark` | `#1E2740` | Tracks, chips, placeholders |
| Texto principal | `OnDark` | `#F2F5FF` | Titulares y cuerpo |
| Texto atenuado | `OnDarkMuted` | `#9AA3BE` | Subtítulos, metadatos |

**Acentos / "Juego" (neón saturado con halo):**

| Rol | Token | Hex | Personalidad |
|-----|-------|-----|--------------|
| Verde neón (acción) | `NeonGreen` | `#25E17E` | PLAY NOW, progreso, acierto |
| Cian eléctrico (foco) | `NeonCyan`/`Electric` | `#00E5FF` | Navegación activa, anillo |
| Morado neón | `Violet` | `#9D4EDD` | Categoría Memoria, marca 2ª |
| Azul neón | `Blue` | `#3D9BFF` | Categoría Lógica |
| Naranja coral | `Coral` | `#FF7A2F` | Categoría Reflejos |
| Fuego (racha) | `StreakOrange` | `#FF7A3D` | Icono de racha |
| Amarillo eléctrico | `Amber` | `#FFC24B` | Recompensa, monedas |
| Magenta | `Magenta` | `#FF3D8B` | Categoría Lenguaje |

El anillo de progreso y el CTA usan degradados neón (`LogicGradients.ring` cian→verde,
`LogicGradients.play` verde). El halo de los iconos lo da `NeonIcon` (resplandor radial).

**Feedback de juego (semántico, no decorativo):**

| Rol | Token | Hex | Cuándo |
|-----|-------|-----|--------|
| Acierto | `Success` | `#00E676` | Respuesta correcta |
| Error | `Error` | `#FF1744` | Fallo, tiempo agotado |

**Degradados** (`LogicGradients`) para dar volumen a botones y fondos:
`primary` (Violet→Magenta), `energy` (Electric→Violet), `reward` (Amber→Magenta),
`success` (Lime→Success).

**Color por categoría** (`CategoryPalette`): cada categoría cognitiva tiene un
color representativo estable; las tarjetas del catálogo lo usan como identidad
visual. No inventes colores por categoría fuera de ese mapa.

### 9.3 Tipografía

Escala geométrica de pesos marcados: **titulares gruesos** (impacto, "gamey") y
**cuerpo legible** (nunca por debajo de 14sp). Amigable pero clara.

| Estilo | Peso | Tamaño | Uso |
|--------|------|--------|-----|
| `displayLarge` | Black | 40sp | Números grandes, hero |
| `headlineLarge` | ExtraBold | 30sp | Títulos de pantalla |
| `headlineMedium` | Bold | 24sp | Saludo, secciones fuertes |
| `titleLarge` | Bold | 20sp | Encabezados de sección |
| `titleMedium` | SemiBold | 17sp | Títulos de tarjeta |
| `bodyLarge` | Normal | 16sp | Cuerpo |
| `bodyMedium` | Normal | 14sp | Texto secundario |
| `labelLarge` | SemiBold | 15sp | Botones, chips |

> Hoy se usa la fuente del sistema por pragmatismo multiplataforma. Para adoptar
> una geométrica/redondeada propia (p. ej. *Poppins*, *Nunito*, *Baloo*), enlázala
> vía `compose.components.resources` y aplícala como `FontFamily` en
> `LogicTypography` — el resto de la app la hereda sin cambios.

### 9.4 Principios de animación

**Toda interacción tiene feedback visual.** Reglas:

1. **Fluidas, rápidas y NO invasivas.** La animación sirve al usuario, no lo
   entretiene: nunca bloquea la interacción ni retrasa la lectura.
2. **Física de resorte (`spring`) para lo táctil** (botones, selección, aparición
   de tarjetas). Da sensación orgánica y "con peso". Preferimos `spring` sobre
   `tween` en todo lo que el usuario *toca*.
3. **Transiciones sutiles de opacidad + escala para navegar** (fade/scale corto),
   no deslizamientos largos que mareen.
4. **Duraciones:** micro-feedback ~100–250 ms; revelados/entradas ~300–600 ms;
   ambientes en bucle (brillo, latido) lentos (~1.2–2 s) y de baja amplitud.
5. **Bucles con propósito:** el "pulse/glow" solo en el CTA principal para guiar
   la acción; jamás en varios elementos a la vez (competirían por la atención).
6. **Respeta el estado:** animaciones dirigidas por estado (`animate*AsState`,
   `AnimatedVisibility`), reactivas, no imperativas.

**Modificadores reutilizables** (`ui/components/ModifierExt.kt`):

- `Modifier.bounceClick { }` — escala al 95 % al presionar y rebota con `spring`
  al soltar. **Interacción táctil por defecto** de la app.
- `Modifier.pulse()` — latido suave y continuo (escala 1↔1.04). Solo para el CTA
  principal ("JUGAR AHORA").
- `Modifier.softGlow(color)` — halo/sombra que respira. Refuerza el CTA sin ruido.

Componentes canónicos con animación: `AnimatedGameButton` (spring press),
`CircularProgressRing` (llenado animado), `LineChart` (trazado revelado),
`AnimatedBottomBar` (ícono que crece + color + indicador), `RandomGameFab` (dado
flotante: balanceo en bucle + brillo + "tirada" con giro al pulsar),
`CategoryMotifSurface` + `CategoryTexture` (fondo de tarjeta **temático por
categoría** —símbolos matemáticos, piezas de rompecabezas, red neuronal, o el
icono repetido— con animación de expansión radial al pulsar que ilumina la card).

### 9.5 Iconografía (REGLA: nada de emojis como iconos)

Los iconos son **siempre vectoriales** (`ImageVector`), nunca emojis. Los emojis
renderizan distinto por plataforma, no se pueden teñir con el color de acento ni
llevar halo neón, y rompen la estética. Regla para todo icono de UI (navegación,
categorías, racha, ajustes, botones):

- Fuente: **Material Icons** (`org.jetbrains.compose.material:material-icons-extended`,
  fijado en **1.7.3** — la última versión publicada; el artefacto no existe para la
  versión de CMP actual). Variante **`Rounded`** (coherente con el lenguaje amigable).
- Centraliza los iconos de navegación/acción en `ui/components/KortexIcons`; el
  icono de cada categoría vive en la enum `GameCategory`.
- Píntalos con [NeonIcon] para darles el **halo neón** del color de acento. Para
  "encender/apagar" un icono (activo/inactivo) usa `glow = true/false` + color.
- `contentDescription` significativo en iconos con carga semántica (accesibilidad).

### 9.6 Forma y elevación

- **Bordes muy redondeados** (moderno): tarjetas `24dp`, botones `20dp`, chips
  `12dp`. Escala en `LogicShapes` (`small`/`medium`/`large`).
- **Elevación sutil** por color y sombra corta; en tema oscuro la jerarquía se da
  sobre todo por el escalón de superficie (`Surface` < `SurfaceVariant`), no por
  sombras duras.
- Espaciado base múltiplos de `4dp`; ritmo vertical cómodo (`16–20dp` entre
  bloques).

### 9.7 Borde neón (REGLA: fuente única, no bordes ad-hoc)

Todo tablero/tecla/celda de un minijuego que necesite un borde "de neón" (tubo
de luz con halo) **reutiliza los componentes compartidos de `ui/components`**
en vez de dibujar su propio `drawRoundRect`/`Stroke` suelto:

- **[`drawNeonTile`](shared/src/commonMain/kotlin/com/kortexgames/app/ui/components/NeonTile.kt)**
  (`DrawScope`): el "tubo hueco" que usan las teclas de Memoria de Secuencias y
  las celdas de Crucigrama Neón — halo ancho → halo intermedio → trazo nítido →
  núcleo blanco al encender (`activeAmt`), con chispas opcionales. Úsalo para
  celdas/teclas individuales; si un trazo no es un contorno de tile (p. ej. los
  cables de Circuito Neón, dibujados sobre un `Path` arbitrario), replica la
  MISMA proporción de capas (halo ancho → intermedio → nítido → núcleo) en vez
  de inventar un halo distinto — no llames a `drawNeonTile` sobre el fondo
  entero del tablero solo por reutilizar la función: en tableros grandes con
  mucho contenido encima (rejilla, cables, nodos) un marco así de intenso
  compite con el resto y se ve sobrecargado; ahí basta un contorno sutil.
- **[`NeonFrame`](shared/src/commonMain/kotlin/com/kortexgames/app/ui/components/NeonFrame.kt)**
  (`@Composable`): el "bezel" de consola arcade con borde giratorio, para
  envolver un panel/pantalla completa (no una celda suelta).

Si un juego nuevo necesita un borde de neón que estos componentes no cubren,
amplía `drawNeonTile`/`NeonFrame` (parámetros o nueva variante) en vez de
copiar su lógica: así todos los tableros comparten idéntica estética y un
ajuste de "look" se hace en un solo sitio.
