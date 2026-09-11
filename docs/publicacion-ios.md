# Publicar KortexGames en la App Store

Guía del proceso de publicación en iOS: qué está resuelto, qué hay que configurar
fuera del repo, el ciclo de cada subida y el mantenimiento recurrente.

El equivalente de Android ya está en producción. Esto cubre solo iOS.

---

## 0. Datos de referencia

Ninguno de estos valores es un secreto: todos viajan dentro del IPA publicado y
cualquiera puede extraerlos. Se listan aquí porque hacen falta a menudo.

| Dato | Valor | Dónde se usa |
|------|-------|--------------|
| Bundle ID | `com.kortexgames.appXRVAAUV9DG` | App Store Connect, AdMob, OAuth de Google y Apple |
| Team ID | `XRVAAUV9DG` | Firma; `iss` del secreto de Apple |
| AdMob App ID (iOS) | `ca-app-pub-6114090257248455~5653728419` | `Info.plist` → `GADApplicationIdentifier` |
| Publisher AdMob | `pub-6114090257248455` | `app-ads.txt` (común a Android e iOS) |
| Sitio del desarrollador | `https://tomasmoro.github.io` | Verificación de `app-ads.txt` |

### El bundle ID lleva sufijo, y NO se arregla

`com.kortexgames.appXRVAAUV9DG` viene del sufijo `$(TEAM_ID)` que la plantilla de
KMP añade para que cada desarrollador tenga un id único mientras desarrolla. Es feo,
pero **el usuario no lo ve nunca** y es inmutable: Apple lo congela al crear la ficha
en App Store Connect y solo admite cambiarlo mientras no se haya subido ninguna
build (ya se subió). Recrear la ficha obligaría a liberar el nombre de la app en la
tienda —algo que sí se ve— para arreglar algo que no se ve. No compensa.

Está comentado en `iosApp/Configuration/Config.xcconfig` para que nadie lo "limpie"
por error: quitarlo haría que el Archive dejara de coincidir con la ficha y la
subida sería rechazada.

---

## 1. Lo que ya está resuelto en el repo

No hay que volver a tocarlo, pero conviene saber por qué está como está.

| Pieza | Dónde | Qué hace |
|-------|-------|----------|
| Bundle ID | `Config.xcconfig` | Coincide con la ficha (ver arriba) |
| Firma | `project.pbxproj` | Firma **automática**, `CODE_SIGN_IDENTITY = Apple Development` en ambas configuraciones. **Es lo correcto**: con firma automática, fijar `Apple Distribution` a mano hace que Xcode falle con *"conflicting provisioning settings"*. El archive se firma como desarrollo y el Organizer re-firma con distribución al exportar |
| Privacy manifest | `iosApp/iosApp/PrivacyInfo.xcprivacy` | Obligatorio desde 2024. Declara tracking (IDFA/ATT), datos recogidos y las APIs de razón requerida: `UserDefaults` (CA92.1) y `FileTimestamp` (C617.1). Salen de inspeccionar los símbolos del binario, no de suponer |
| Cifrado | `Info.plist` → `ITSAppUsesNonExemptEncryption = false` | Evita que App Store Connect pregunte por el cumplimiento de exportación en cada subida |
| AdMob | `IosAdUnits` (shared/iosMain) + `AdMobBridge.swift` | Unidad real solo si el binario **no** es de depuración y hay una configurada en `secrets.properties`; si no, la de prueba. Misma política que `AdMobConfig` en Android |
| SKAdNetwork | `Info.plist` → `SKAdNetworkItems` | 50 redes de la lista oficial de Google. Sin esto, quien rechaza el ATT no genera atribución y las redes dejan de pujar |
| Sign in with Apple | `AppleAuthClient` (expect/actual) | Exigido por la guideline 4.8 al ofrecer también login de Google. Kotlin/Native puro, sin CocoaPods ni SPM |
| Entitlement | `iosApp/Configuration/iosApp.entitlements` | Vive en `Configuration/` y no en `iosApp/` porque esa carpeta es un **grupo sincronizado** del target: todo lo que se meta ahí se copia dentro del `.app` como recurso |

> ⚠️ **No añadas la capability de Sign in with Apple desde la UI de Xcode.** Ya está
> declarada vía `CODE_SIGN_ENTITLEMENTS` en el `xcconfig`; hacerlo por la UI la
> duplicaría.

### Sobre las carpetas sincronizadas (objectVersion 77)

El proyecto usa `PBXFileSystemSynchronizedRootGroup`: **todo archivo dentro de
`iosApp/iosApp/` entra automáticamente en el target**, sin tocar el `project.pbxproj`.
Por eso el `PrivacyInfo.xcprivacy` no necesitó ninguna referencia manual — y por eso
añadirlo con *Add Files* habría creado un duplicado.

La cara B: cualquier archivo que dejes ahí acaba **empaquetado en el `.app`**. Para
cosas que no son contenido de la app (entitlements, configuración) usa
`iosApp/Configuration/`, que no está sincronizada.

---

## 2. Configuración fuera del repo

Todo esto ya está hecho. Se documenta por si hay que rehacerlo o auditarlo.

**Apple Developer**
- App ID `com.kortexgames.appXRVAAUV9DG` con la capability **Sign In with Apple**.
- Una **Key** de Sign in with Apple (`AuthKey_<KEY_ID>.p8`), con el App ID como
  *Primary App ID*. El `.p8` solo se descarga una vez; guardarlo fuera del repo.
- Certificado **Apple Distribution**: lo crea la firma automática al distribuir.
  Límite de **2 por cuenta**, es el único recurso escaso del proceso.

**Supabase** → Auth → Providers → Apple
- Habilitado, con el bundle id en *Client IDs*.
- *Secret Key*: JWT generado con `scripts/apple-client-secret.js` (ver §5).
- *Allow users without an email*: **apagado**. `public.users.email` es `not null`,
  así que un alta sin email reventaría dentro del trigger `handle_new_user`; mejor
  que Supabase lo rechace antes y con un error claro.
- La *Callback URL* solo aplica al flujo web, que no se usa.

**AdMob**
- App iOS creada, con su App ID y dos ad units (intersticial y recompensado) en
  `secrets.properties` (`ADMOB_IOS_*`).
- Al publicar en la App Store, **vincular esa misma entrada** a la ficha de la
  tienda desde *App settings*. NO dar de alta una app nueva desde la tienda: es
  justo lo que duplicó la app de Android.

**app-ads.txt**
- Vive en el repo de usuario `tomasmoro/tomasmoro.github.io`, servido en
  `https://tomasmoro.github.io/app-ads.txt`. Tiene que estar en la **raíz** del
  dominio: un Pages de proyecto (`/logic-games/`) no sirve para la verificación.
- La fuente de verdad es `site/app-ads.txt` de este repo. **Están duplicados**: si
  se añade otra red publicitaria, hay que tocar los dos.
- Una única línea cubre Android e iOS, porque el publisher es el mismo.

---

## 3. El ciclo de cada subida

1. **Sube el build number.** `CURRENT_PROJECT_VERSION` en `Config.xcconfig`. App
   Store Connect **rechaza números repetidos** y un número quemado no se recicla, ni
   borrando la build. Es el despiste más caro: te enteras después de esperar todo el
   archive.
2. **Product → Archive** en Xcode, con el destino en *Any iOS Device (arm64)*.
3. En el Organizer, **`Validate App` → App Store Connect**. Hace las mismas
   comprobaciones que la subida real **sin subir nada**, y crea el certificado de
   distribución si falta. Úsalo siempre antes de la primera subida de una tanda.
4. **`Distribute App` → App Store Connect → Upload**, con las opciones por defecto.
5. Procesado: 5–15 minutos. Llega un email; hasta entonces figura como *Processing*
   en la pestaña TestFlight.
6. **TestFlight → Internal Testing**: grupo, testers, y asignar la build.

Archivar y validar **no tienen límite ninguno**: son operaciones locales/gratuitas.
Agrupar cambios ahorra esperas, no cupo.

> **La build de TestFlight es Release**, así que sirve **anuncios reales**. No pulses
> los tuyos más de lo justo para comprobar que se muestran: los clics propios
> repetidos son exactamente lo que Google penaliza.

### Avisos que son normales y no hay que arreglar

- **`Upload Symbols Failed`** para `GoogleMobileAds` y `UserMessagingPlatform`.
  Google distribuye sus XCFrameworks ya compilados y **sin dSYM**, así que no hay
  nada que subir. Aparece en cada subida. Única consecuencia: los stack traces
  dentro del código de Google salen sin nombres de función; el nuestro simboliza bien.
- **`Cannot infer a bundle ID ... use the bundle name instead: Shared`**. Inofensivo:
  `Shared` es estático (`isStatic = true`), se enlaza dentro del binario y su bundle
  id nunca llega al `.app`.
- **`expect/actual classes are in Beta`**. Warning perpetuo de KMP.

---

## 4. Ficha de App Store Connect

- **Capturas**: solo iPhone 6.9" (1290×2796 o 1320×2868), mínimo 3. El target es
  `TARGETED_DEVICE_FAMILY = 1` (solo iPhone), así que no hacen falta las de iPad.
- **URLs**: privacidad → `https://tomasmoro.github.io/logic-games/privacy/`,
  soporte → la landing del mismo sitio.
- **App Privacy** (etiqueta nutricional): tiene que **cuadrar con el
  `PrivacyInfo.xcprivacy`** o salta la alerta. Declara *Device ID* (IDFA) usado para
  seguimiento, *Usage Data*, *Email* y *Product Interaction*.
- **Eliminación de cuenta** (guideline 5.1.1(v)): ya se cumple, vía
  `Perfil → Ajustes → Cuenta → Eliminar cuenta`. Menciónalo en las notas.

### Notas para el revisor (importan más de lo que parece)

- «La app se puede usar completa en **modo invitado**, sin crear cuenta.» Evita que
  el revisor se atasque en la pantalla de login.
- «El prompt de **seguimiento (ATT)** y el formulario de consentimiento **no salen al
  arrancar**: aparecen al terminar el tercer juego de la bienvenida inicial.» Sin
  esto, el revisor no los ve y rechaza por "no muestra el prompt de ATT".
- Una cuenta demo con email/contraseña, por si acaso.

---

## 5. Mantenimiento recurrente

| Cada cuánto | Qué | Cómo |
|-------------|-----|------|
| Cada subida | Subir `CURRENT_PROJECT_VERSION` | `Config.xcconfig` |
| 6 meses | Regenerar el secreto de Apple | `node scripts/apple-client-secret.js <ruta.p8> XRVAAUV9DG <KEY_ID> com.kortexgames.appXRVAAUV9DG \| pbcopy` → pegar en Supabase |
| Antes de un envío importante | Refrescar `SKAdNetworkItems` | Recopiar de [la lista de Google](https://developers.google.com/admob/ios/3p-skadnetworks) al `Info.plist` |
| Al añadir dependencias | Revisar el privacy manifest | Inspeccionar los símbolos del binario nuevo antes de subir |
| Al cambiar de red publicitaria | Sincronizar `app-ads.txt` | Los **dos** sitios (§2) |

Que el secreto de Apple caduque **no rompe el login nativo** de la app: solo el flujo
web y la revocación de tokens. No es una urgencia de producción.

---

## 6. Errores típicos

| Síntoma | Causa | Solución |
|---------|-------|----------|
| `conflicting provisioning settings` al archivar | Se fijó `Apple Distribution` a mano con firma automática | Volver a `Apple Development`; el Organizer re-firma al exportar |
| `provisioning profile doesn't include the com.apple.developer.applesignin entitlement` | La capability no está activada en el App ID | Activarla en developer.apple.com → Identifiers y volver a archivar |
| `ITMS-91053: Missing API declaration` | Falta una API de razón requerida en el privacy manifest | El email de Apple dice cuál; añadir su código de razón |
| `invalid audience` al entrar con Apple o Google | El bundle id / client id no está en *Authorized Client IDs* de Supabase | Añadirlo en el proveedor correspondiente |
| El login con Apple falla sin más detalle | Cancelación del usuario o error de configuración | Buscar `KORTEX apple_sign_in FALLÓ:` en la consola de Xcode |
| AdMob no monetiza en release | `ADMOB_IOS_*` vacías en `secrets.properties` | Rellenarlas; `IosAdUnits` cae a las de prueba si faltan |

---

## 7. Pendiente

- Probar en dispositivo real vía TestFlight: hoja de Apple, formulario UMP y prompt
  de ATT tras la bienvenida jugable.
- Botón oficial de Sign in with Apple (hoy usa el genérico compartido con Google).
  Riesgo bajo; detalle en `BACKLOG.md`.
- `IPHONEOS_DEPLOYMENT_TARGET = 18.2` recorta parque de dispositivos sin ganar nada.
  Bajarlo a 16.0 y verificar que Compose MP y Google Mobile Ads lo aceptan.
