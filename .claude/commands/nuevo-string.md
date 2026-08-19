---
description: Agrega/edita claves en strings.xml siguiendo la convención del proyecto
---

# Nuevo string: $ARGUMENTS

Vas a agregar (o editar) entradas en
`shared/src/commonMain/composeResources/values/strings.xml` (regla §10 del
`CLAUDE.md`, obligatoria para todo texto de UI nuevo o modificado).

## Pasos

1. Si el pedido no trae la clave explícita, proponé una siguiendo la convención
   `<área>_<pantalla/concepto>_<detalle>` (ej. `settings_notifications_section`).
   Si es una variante de un mensaje ya existente, terminá en `_1`, `_2`, `_3`
   en vez de inventar una clave nueva.
2. Buscá si ya existe una clave parecida en el archivo antes de crear una
   duplicada (`grep` por el área/concepto).
3. Agregá la entrada en `strings.xml` en la sección que corresponda (el archivo
   ya está agrupado por área/pantalla — respetá ese agrupamiento, no la pongas
   suelta al final).
4. Si el string lleva argumentos, usá `%1$s` (nunca `%1$d`, incluso para
   números — el valor se pasa como `String` desde Kotlin).
5. Reemplazá el texto embebido en el `@Composable` (o donde esté) por
   `stringResource(Res.string.<clave>)`, o `getString(Res.string.<clave>, ...)`
   si es código `suspend` fuera de Compose (ej. notificaciones).
6. **Qué NO va acá**: nombres de juegos (viven en `GameCatalog.kt`, contenido de
   catálogo) ni datos que vienen del backend.

## Al terminar

Compilá (`./gradlew :shared:compileKotlinIosSimulatorArm64`) para confirmar que
el accesor `Res.string.<clave>` generado por el plugin de Compose Resources
resuelve bien.
