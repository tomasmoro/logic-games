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
| **Android Client ID** | Google Cloud (no se pega en el código) | Ata el par *package name + SHA-1* a tu proyecto. Google lo usa para verificar que quien pide el token es realmente tu app Android. No se referencia en el código, pero **debe existir** o Credential Manager no devuelve token. |
| **iOS Client ID** | `secrets.properties` → `GOOGLE_IOS_CLIENT_ID`; y en Supabase | iOS obtiene el ID token vía `ASWebAuthenticationSession` usando este id; el token sale con `aud = iOS Client ID`. De él se deriva el esquema de redirección (*reversed client id*). |
| **SHA-1** | Google Cloud (dentro del Android Client ID) | Huella de la clave con la que se firma el APK. Junto al package name, es la "identidad" criptográfica de la app Android ante Google. |
| **Bundle ID** | Google Cloud (dentro del iOS Client ID) | El identificador de la app iOS (`com.example.kortexgames.KortexGames…`). Identifica a la app iOS ante Google. |

> **Clave del `aud`:** el ID token de Android lleva `aud = Web Client ID` y el de iOS
> lleva `aud = iOS Client ID`. Por eso Supabase necesita **ambos** ids en su lista de
> *Authorized Client IDs*: valida el `aud` del token contra esa lista. Si falta uno,
> esa plataforma recibe "invalid audience" y el login falla.

Ninguno de estos IDs es un secreto criptográfico: viajan en el cliente, igual que la
publishable key de Supabase. El único secreto real (Web Client **Secret**) vive solo
en Supabase. Aun así externalizamos los IDs a `secrets.properties` (gitignored) para
rotarlos o usar valores por desarrollador sin tocar el código.

---

## 1. Google Cloud Console

Todo en el **mismo** proyecto de Google Cloud.

1. Ve a [console.cloud.google.com](https://console.cloud.google.com/) → crea (o elige)
   un proyecto.
2. **APIs & Services → OAuth consent screen**: configúrala (tipo *External*, nombre de
   app, correo de soporte). Añádete como *test user* mientras esté en modo prueba.
3. **APIs & Services → Credentials → Create Credentials → OAuth client ID**, y crea
   **tres** clients:

   **a) Web application** →
   - Anota su **Client ID** (`…apps.googleusercontent.com`) → irá a `GOOGLE_WEB_CLIENT_ID`.
   - Anota su **Client Secret** → irá **solo** a Supabase.

   **b) Android** →
   - *Package name*: `com.example.kortexgames`
   - *SHA-1*: ejecútalo con el script del repo:
     ```bash
     ./scripts/print-android-sha1.sh              # SHA-1 de DEBUG (para probar)
     # release: ./scripts/print-android-sha1.sh <keystore> <alias>
     ```
     (Este client id no se pega en ningún lado, pero debe existir.)

   **c) iOS** →
   - *Bundle ID*: el de la app iOS. Lo ves en `iosApp/Configuration/Config.xcconfig`
     (`PRODUCT_BUNDLE_IDENTIFIER`, hoy `com.example.kortexgames.KortexGames$(TEAM_ID)`).
   - Anota su **Client ID** → irá a `GOOGLE_IOS_CLIENT_ID`.

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
| Android: no aparece el selector / error 10 | Falta el **Android Client ID** o la SHA-1 no coincide con la clave de firma. |
| "invalid audience" / login rechazado por Supabase | El Client ID de esa plataforma no está en *Authorized Client IDs* de Supabase. |
| iOS: se abre y cierra sin sesión | El *bundle id* del iOS Client ID no coincide con el de la app. |
