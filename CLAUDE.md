# CLAUDE.md — Guía del proyecto Logic Games

Instrucciones para cualquier sesión de Claude Code que trabaje en este repo.
Léelas antes de escribir o modificar código.

---

## 1. Qué es este proyecto

App de **entrenamiento mental y juegos de lógica** estilo *Impulse*.

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

Al terminar una fase: resume lo hecho y **espera confirmación** antes de seguir.

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

### Paquete base (en `shared`): `com.example.kortexgames`

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
