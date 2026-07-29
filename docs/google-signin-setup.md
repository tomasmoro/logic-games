# Configurar Login con Google (Android + iOS)

Guía paso a paso para dejar operativo el inicio de sesión con Google. El **código
ya está completo** en ambas plataformas; esto es solo configuración de credenciales
(Google Cloud + Supabase) y rellenar dos valores en `secrets.properties`.

---

## 0. El panorama: por qué hace falta cada pieza

El login con Google **no** manda usuario/contraseña a nuestro backend. El flujo es:

```
App  ──(SDK nativo de Google)──►  Google  ──(ID token, JWT firmado)──►  App
App  ──(ID token)──►  Supabase  ──(valida la firma y el `aud`)──►  sesión propia
```

- La app obtiene de Google un **ID token** (un JWT que Google firma y que prueba
  "este usuario es quien dice ser"). Cómo se obtiene difiere por plataforma (de ahí
  el `expect/actual` `GoogleAuthClient`).
- Ese ID token se le pasa a **Supabase**, que verifica la firma de Google y el
  campo `aud` (audiencia) del token, y a cambio emite **su propia** sesión.

Para que ese baile funcione, Google y Supabase tienen que "conocerse" mediante unos
**OAuth Client IDs**. Cada variable de abajo existe para una parte concreta de la
verificación.

### Qué es cada variable y por qué

| Variable / credencial | Dónde vive | Por qué existe |
|---|---|---|
| **Web Client ID** | `secrets.properties` → `GOOGLE_WEB_CLIENT_ID`; y en Supabase | Android pide el ID token con este id como `serverClientId`; el token sale con `aud = Web Client ID`. Es además el id que registra a Google como proveedor en Supabase. |
| **Web Client Secret** | **solo** en Supabase | El secreto del cliente Web. Vive únicamente en Supabase (servidor); **nunca** en la app. Sirve para el intercambio servidor↔Google del proveedor. |
| **Android Client ID** | Google Cloud (no se pega en el código) | Ata el par *package name + SHA-1* a tu proyecto. Google lo usa para verificar que quien pide el token es realmente tu app Android. No se referencia en el código, pero **debe existir** o Credential Manager no devuelve token. Hace falta **uno por cada clave de firma** (debug / subida / Play): ver §1.3b. |
| **iOS Client ID** | `secrets.properties` → `GOOGLE_IOS_CLIENT_ID`; y en Supabase | iOS obtiene el ID token vía `ASWebAuthenticationSession` usando este id; el token sale con `aud = iOS Client ID`. De él se deriva el esquema de redirección (*reversed client id*). |
| **SHA-1** | Google Cloud (dentro del Android Client ID) | Huella de la clave con la que se firma el APK. Junto al package name, es la "identidad" criptográfica de la app Android ante Google. |
| **Bundle ID** | Google Cloud (dentro del iOS Client ID) | El identificador de la app iOS (`com.kortexgames.app…`). Identifica a la app iOS ante Google. |

> **Clave del `aud`:** el ID token de Android lleva `aud = Web Client ID` y el de iOS
> lleva `aud = iOS Client ID`. Por eso Supabase necesita **ambos** ids en su lista de
> *Authorized Client IDs*: valida el `aud` del token contra esa lista. Si falta uno,
> esa plataforma recibe "invalid audience" y el login falla.

Ninguno de estos IDs es un secreto criptográfico: viajan en el cliente, igual que la
publishable key de Supabase. El único secreto real (Web Client **Secret**) vive solo
en Supabase. Aun así externalizamos los IDs a `secrets.properties` (gitignored) para
rotarlos o usar valores por desarrollador sin tocar el código.

---

## 1. Google Cloud Console (paso a paso, campo por campo)

Todo en el **mismo** proyecto de Google Cloud (los tres client IDs deben convivir
en un único proyecto; si los creas en proyectos distintos, Supabase no podrá
autorizar los tres a la vez de forma coherente).

La consola está mayormente en inglés aunque tu cuenta esté en español — dejo los
nombres de campo en inglés tal como aparecen en pantalla.

### 1.1 Crear (o elegir) el proyecto

1. Entra a [console.cloud.google.com](https://console.cloud.google.com/).
2. Arriba a la izquierda, junto al logo de Google Cloud, hay un selector de
   proyecto. Si ya tienes uno para esta app, selecciónalo. Si no:
   **New Project** → dale un nombre (p. ej. `KortexGames`) → **Create**.
3. Espera a que la notificación (icono de campana) confirme que el proyecto está
   creado, y selecciónalo en el desplegable si no quedó activo solo.

> No hace falta habilitar ninguna API adicional (Google People API, etc.) para
> este flujo: el login con ID token usa Google Identity Services, que ya viene
> disponible sin activarlo explícitamente.

### 1.2 Configurar la pantalla de consentimiento (OAuth consent screen)

Es obligatoria antes de poder crear ningún Client ID: es lo que ve el usuario
("Kortex Games quiere acceder a tu cuenta de Google...").

> Google rediseñó esta parte de la consola durante 2025 (**"Google Auth
> Platform"**). Según cuándo la abras verás uno de estos dos flujos — mira cuál
> te aparece a ti (si no ves "User Type" al entrar, es el nuevo) y sigue esa
> variante:

**Si ves un botón "Get Started" (flujo nuevo, Google Auth Platform):**

1. Menú ☰ → **APIs & Services → OAuth consent screen** (puede aparecer también
   como **"Google Auth Platform"** en el menú, o buscándolo en la barra superior).
2. Pulsa **Get Started**. Es un asistente de varios pasos:
   - **App Information**: *App name* (p. ej. `Kortex Games`) y *User support
     email* (desplegable, tu cuenta).
   - **Audience** (esto reemplaza al viejo "User Type"): elige **External** —
     a menos que uses Google Workspace y quieras restringir el login a tu
     organización.
   - **Contact Information**: tu email como *Developer contact information*
     (obligatorio, es donde Google te avisa de cambios de política).
   - **Finish**: marca la casilla *"I agree to the Google API Services User
     Data Policy"* → **Continue** → **Create**.
3. Aterrizas en el dashboard de **Google Auth Platform**, con pestañas a la
   izquierda: *Overview*, *Branding*, **Audience**, *Clients*, *Data Access*,
   *Verification Center*. Dos de estas requieren un paso más:
   - **Audience** → sección **Test users** → **+ Add users**: mientras la app
     esté en *Publishing status: Testing* (el estado inicial), **solo las
     cuentas que agregues aquí podrán iniciar sesión** — si pruebas con tu
     cuenta y no la sumaste, Google devuelve *"Access blocked: KortexGames has
     not completed the Google verification process"*. Agrega tu email (y el de
     cualquier tester).
   - **Data Access** → no hace falta tocar nada: `openid`, `email` y `profile`
     (los que pide nuestro código) son *scopes no sensibles* que Google Identity
     Services añade por defecto al flujo de login, sin que los declares a mano.

**Si ves "User Type: External/Internal" directo (flujo clásico, aún posible en
algunos proyectos):**

1. **User Type**: **External** → **Create**.
2. **App information**: *App name*, *User support email*, *App logo* (opcional).
3. **App domain** (opcional sin web pública) → **Developer contact information**
   (tu email) → **Save and Continue**.
4. **Scopes**: no añadas nada a mano, `openid`/`email`/`profile` van por
   defecto → **Save and Continue**.
5. **Test users** → **+ Add users** → tu email (mismo motivo que arriba) →
   **Save and Continue**.
6. Revisa el resumen → **Back to Dashboard**.

> **Testing vs. Production (aplica a ambos flujos):** para desarrollar y probar
> te alcanza con **Testing** + la lista de *test users* (hasta 100). Publicar a
> producción (*Publish App*, en *Audience* o en el dashboard según el flujo)
> permite que cualquier cuenta de Google inicie sesión; como usamos solo scopes
> no sensibles no dispara el proceso largo de verificación, aunque puede seguir
> mostrando un aviso de "app no verificada" hasta que Google la revise — no
> bloquea el login, solo añade un clic de "Continuar" para el usuario.

### 1.3 Crear los tres OAuth Client IDs

Dos caminos posibles, según el flujo que te tocó — llegan al mismo lugar:

- **Flujo nuevo (Google Auth Platform)**: dashboard → pestaña **Clients** →
  **+ Create Client**.
- **Flujo clásico, o siempre disponible**: Menú ☰ → **APIs & Services →
  Credentials** → **+ Create Credentials** → **OAuth client ID**.

Repite el paso tres veces, uno por tipo:

#### a) Web application

1. *Application type*: **Web application**.
2. *Name*: uno identificable, p. ej. `KortexGames – Web (Supabase)`. Es solo una
   etiqueta interna, no afecta al funcionamiento.
3. *Authorized JavaScript origins* / *Authorized redirect URIs*: **déjalos
   vacíos**. Este client no sirve para que un navegador inicie el flujo; existe
   únicamente como **audiencia** (`aud`) del ID token que emite Android y como
   client id/secret que Supabase usa para verificar Google como proveedor.
4. **Create**. Se abre un modal **"OAuth client created"** con:
   - **Client ID** — cadena `NNNNNNNNNN-xxxxxxxx.apps.googleusercontent.com`.
     → esto es `GOOGLE_WEB_CLIENT_ID`.
   - **Client Secret** — cadena corta tipo `GOCSPX-xxxxxxxx`.
     → esto va **solo** a Supabase, nunca al repo.
   - Copia ambos ya (o descarga el JSON con **Download JSON**); si cierras el
     modal, el Client ID lo puedes volver a ver en la tabla de Credentials, pero
     el **Secret no se vuelve a mostrar completo** — tendrías que resetearlo
     (**Reset Secret**) si lo pierdes.

#### b) Android

1. *Application type*: **Android**.
2. *Name*: p. ej. `KortexGames – Android`.
3. *Package name*: `com.kortexgames.app` (coincide con `applicationId` en
   `androidApp/build.gradle.kts`).
4. *SHA-1 certificate fingerprint*: pégala aquí. Para obtenerla:
   ```bash
   ./scripts/print-android-sha1.sh                          # SHA-1 de DEBUG
   ./scripts/print-android-sha1.sh kortexgames-upload.jks upload   # la de subida
   ```
   El script imprime una línea `SHA1: AA:BB:CC:...` — pega exactamente ese valor
   (con los dos puntos) en el campo.
5. **Create**. Los client IDs de tipo Android **no tienen secret** (una app
   instalada no puede guardar uno de forma segura) y este Client ID en particular
   **no se pega en ningún archivo del repo**: su única función es quedar
   registrado junto al SHA-1 para que Google confíe en las peticiones que
   Credential Manager hace desde tu APK firmado con esa clave.

##### Una SHA-1 por client: hacen falta TRES

El formulario de Android en Google Cloud tiene **un solo campo de huella**, y no se
pueden añadir más a un client ya creado (eso lo permite la consola de *Firebase*, que
no es la que se usa aquí). Como la restricción de unicidad de Google se define sobre
el **par** *package name + SHA-1*, cada huella necesita su propio client — los tres con
el mismo package `com.kortexgames.app`, en el mismo proyecto, conviviendo sin conflicto:
Google resuelve por el par de quien hace la petición, así que cada build encuentra el suyo.

| Client | SHA-1 | Cuándo se crea |
|--------|-------|----------------|
| `KortexGames – Android (debug)` | `~/.android/debug.keystore` | Al empezar, para probar en dev |
| `KortexGames – Android (upload)` | `kortexgames-upload.jks` | Al preparar la publicación |
| `KortexGames – Android (Play)` | Play Console → *Integridad de la app → Firma de apps* | Tras subir el primer AAB |

> ⚠️ La tercera es la que de verdad importa y **solo existe después de subir la primera
> build**: con Play App Signing, Play re-firma la app con su propia clave, así que lo que
> instalan los usuarios NO lleva la huella de tu keystore. El síntoma clásico es un login
> que te funciona a ti en release local y falla a todo el que instala desde la tienda.
>
> Si al crear un client sale *"An OAuth2 client already exists for this package name and
> SHA-1"*, ese par ya está registrado en otro proyecto de Cloud o Firebase: el par debe
> ser único a nivel global, no solo dentro de tu proyecto.

#### c) iOS

1. *Application type*: **iOS**.
2. *Name*: p. ej. `KortexGames – iOS`.
3. *Bundle ID*: tiene que ser exactamente el de la app. Lo ves en
   `iosApp/Configuration/Config.xcconfig`:
   ```
   PRODUCT_BUNDLE_IDENTIFIER=com.kortexgames.app$(TEAM_ID)
   ```
   `$(TEAM_ID)` se resuelve al valor de la línea `TEAM_ID=XRVAAUV9DG` del mismo
   archivo, así que el bundle id real a pegar es
   `com.kortexgames.appXRVAAUV9DG`. Si tienes dudas, ábrelo en
   Xcode: proyecto `iosApp` → target `iosApp` → pestaña *General* → campo
   *Bundle Identifier* muestra el valor ya resuelto.
4. *App Store ID* / *Team ID* (campos opcionales que aparecen debajo): puedes
   dejarlos vacíos — solo hacen falta para *Universal Links*, que este flujo no
   usa (`ASWebAuthenticationSession` no depende de ellos).
5. **Create**. Igual que Android, no genera secret. El **Client ID**
   (`…apps.googleusercontent.com`) → esto es `GOOGLE_IOS_CLIENT_ID`.

### 1.4 Dónde volver a ver estos valores después

**APIs & Services → Credentials** lista los tres bajo *OAuth 2.0 Client IDs*.
Haz clic en el nombre de cualquiera para reabrir su detalle y volver a copiar el
**Client ID** cuando lo necesites. El **Client Secret** del Web client es la
única excepción: tras cerrar el modal inicial solo se ve enmascarado
(`••••••••`); si lo perdiste, usa **Reset Secret** en esa misma pantalla (esto
invalida el secret anterior — habrá que actualizarlo también en Supabase).

---

## 2. Supabase

Panel del proyecto (`pfjsacrxtutrkcsybaxh`) → **Authentication → Providers → Google**:

1. Actívalo (*Enable*).
2. **Client ID (for OAuth)**: pega el **Web Client ID**.
3. **Client Secret**: pega el **Web Client Secret**.
4. **Authorized Client IDs**: pega el **Web Client ID** *y* el **iOS Client ID**
   (separados por coma). Esto es lo que valida el `aud` de los tokens de Android y iOS.
5. Guarda.

---

## 3. El repositorio

1. Crea tu `secrets.properties` a partir de la plantilla:
   ```bash
   cp secrets.properties.example secrets.properties
   ```
2. Rellena los dos valores (los IDs de Google Cloud del paso 1):
   ```properties
   GOOGLE_WEB_CLIENT_ID=xxxx-web.apps.googleusercontent.com
   GOOGLE_IOS_CLIENT_ID=xxxx-ios.apps.googleusercontent.com
   ```
3. `secrets.properties` está en `.gitignore`: no se commitea. En el build, la tarea
   Gradle `generateSecrets` lo lee e inyecta los valores en el objeto `Secrets`, que
   `SupabaseConfig` reexporta. No hay que hacer nada más: la próxima compilación los
   toma.

> **iOS:** no hace falta tocar `Info.plist`. `ASWebAuthenticationSession` intercepta
> el esquema de callback (*reversed client id*) por sí mismo.

---

## 4. Probar

- **Android:** instala en emulador/dispositivo (firma debug) y pulsa "Continuar con
  Google". Requiere que la SHA-1 **debug** esté registrada (paso 1b).
- **iOS:** ejecuta en el Simulador (ver memoria del proyecto: el dispositivo físico
  está bloqueado por MDM) y pulsa el mismo botón; se abre el navegador seguro.
- Si algo falla, la app degrada de forma controlada y ofrece email; revisa el mensaje
  de error (indica si falta el Client ID, si el `aud` no está autorizado, etc.).

### Errores típicos

| Síntoma | Causa probable |
|---|---|
| "Falta …CLIENT_ID" | `secrets.properties` vacío o no creado. |
| Android: no aparece el selector / error 10 | Falta el **Android Client ID** o la SHA-1 no coincide con la clave de firma. Si falla **solo en release** (o solo a quien instala desde Play), falta el client de esa huella concreta: ver la tabla de §1.3b. |
| "invalid audience" / login rechazado por Supabase | El Client ID de esa plataforma no está en *Authorized Client IDs* de Supabase. |
| iOS: se abre y cierra sin sesión | El *bundle id* del iOS Client ID no coincide con el de la app. |
