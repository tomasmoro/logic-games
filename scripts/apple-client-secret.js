#!/usr/bin/env node
//
// apple-client-secret.js — genera el "client secret" del proveedor Apple de Supabase.
//
// ¿PARA QUÉ? Supabase → Auth → Providers → Apple exige un campo *Secret Key (for
// OAuth)* que debe ser un JWT firmado con ES256. El dashboard NO deja guardar el
// proveedor sin él, aunque nuestro login sea nativo y no lo use para autenticar:
// el flujo nativo valida el ID token contra las claves públicas de Apple y comprueba
// el `aud` contra la lista de *Client IDs*. Sin secreto de por medio.
//
// Aun así el secreto sirve para algo real: Apple exige (guideline 5.1.1(v)) que al
// borrar la cuenta se REVOQUE el token de Sign in with Apple, y Supabase solo puede
// hacer esa llamada al endpoint de Apple si tiene este secreto configurado.
//
// CADUCA A LOS 6 MESES. Apple no admite un `exp` mayor. Cuando expire:
//   - El login NATIVO de la app sigue funcionando (no usa el secreto).
//   - Deja de funcionar el flujo web y la revocación de tokens.
// Es decir: no es una urgencia de producción, pero hay que regenerarlo. Por eso este
// script está versionado y no fue un apaño de una sola vez.
//
// La clave PRIVADA (.p8) nunca se guarda aquí ni se commitea: se pasa por argumento
// y solo se lee para firmar. Descárgala en Apple Developer → Keys (marcando "Sign in
// with Apple" y eligiendo el App ID de la app como *Primary App ID*); OJO, Apple
// solo permite descargarla UNA vez. El nombre del archivo contiene el Key ID:
// `AuthKey_<KEY_ID>.p8`.
//
// No usa dependencias: Node firma ES256 de forma nativa.
//
// Uso:
//   node scripts/apple-client-secret.js <ruta.p8> <TEAM_ID> <KEY_ID> <CLIENT_ID>
//
// Ejemplo (con `pbcopy` el JWT va al portapapeles y no queda en el historial del
// terminal — es una credencial):
//   node scripts/apple-client-secret.js ~/Documents/claves-apple/AuthKey_ABC123.p8 \
//     XRVAAUV9DG ABC123 com.kortexgames.appXRVAAUV9DG | pbcopy
//
// Los valores de KortexGames están en `docs/publicacion-ios.md`.

const fs = require('fs');
const crypto = require('crypto');

const [, , p8Path, teamId, keyId, clientId] = process.argv;

if (!p8Path || !teamId || !keyId || !clientId) {
  console.error('Uso: node scripts/apple-client-secret.js <ruta.p8> <TEAM_ID> <KEY_ID> <CLIENT_ID>');
  console.error('Ver el encabezado del archivo para el detalle de cada valor.');
  process.exit(1);
}

if (!fs.existsSync(p8Path)) {
  console.error(`No existe el archivo de clave: ${p8Path}`);
  console.error('Pista: Apple la descarga como AuthKey_<KEY_ID>.p8, normalmente en ~/Downloads.');
  process.exit(1);
}

const b64url = (v) => Buffer.from(v).toString('base64url');

const ahora = Math.floor(Date.now() / 1000);
// Apple rechaza cualquier `exp` a más de 6 meses vista. 180 días deja margen.
const caducidad = ahora + 60 * 60 * 24 * 180;

const header = { alg: 'ES256', kid: keyId };
const payload = {
  iss: teamId,                      // Team ID de la cuenta de desarrollador
  iat: ahora,
  exp: caducidad,
  aud: 'https://appleid.apple.com', // Fijo: lo exige Apple
  sub: clientId,                    // Bundle id de la app
};

const firmable = `${b64url(JSON.stringify(header))}.${b64url(JSON.stringify(payload))}`;

let firma;
try {
  const clave = crypto.createPrivateKey(fs.readFileSync(p8Path));
  // `ieee-p1363` produce la firma R||S en crudo que exige JWS. Con el valor por
  // defecto Node devuelve DER y el token lo rechaza cualquier validador de JWT:
  // es el error más difícil de diagnosticar de todo este script, porque el JWT
  // "parece" bien formado.
  firma = crypto.sign('sha256', Buffer.from(firmable), { key: clave, dsaEncoding: 'ieee-p1363' });
} catch (e) {
  console.error(`No se pudo firmar con esa clave: ${e.message}`);
  console.error('¿Es el .p8 descargado de Apple Developer → Keys, sin modificar?');
  process.exit(1);
}

// El JWT va por stdout (para poder encadenar `| pbcopy`); los mensajes por stderr.
console.log(`${firmable}.${b64url(firma)}`);
console.error(`\n✅ Secreto generado. Caduca el ${new Date(caducidad * 1000).toISOString().slice(0, 10)}.`);
console.error('   Pégalo en Supabase → Auth → Providers → Apple → "Secret Key (for OAuth)".');
console.error('   NO lo pegues en un chat ni lo commitees: es una credencial.');
