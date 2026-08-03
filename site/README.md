# site — Sitio web público de KortexGames

Sitio estático con la **página principal** de la App, su **política de privacidad**, sus
**condiciones del servicio** y la página de **eliminación de cuenta** (español e inglés).
Se publica en GitHub Pages. Sus URLs se pegan en varios sitios: Google Play Console (Data
safety pide una URL de eliminación de cuenta además de la de privacidad), App Store Connect
y el *Branding* del OAuth consent screen de Google Cloud (que exige homepage + política para
sacar la app de modo Testing; el enlace a condiciones es opcional ahí, pero conviene
rellenarlo).

```
site/
├── index.html          Página principal (landing)   → /
├── styles.css          Tokens del sistema de diseño (§9 de CLAUDE.md)
├── assets/icon.png     Icono de la app, reutilizado como logo
├── privacidad/         Política de Privacidad (ES)  → /privacidad/
├── privacy/            Privacy Policy (EN)          → /privacy/
├── condiciones/        Condiciones del Servicio (ES) → /condiciones/
├── terms/               Terms of Service (EN)        → /terms/
├── eliminar-cuenta/     Eliminar cuenta y datos (ES) → /eliminar-cuenta/
└── delete-account/      Delete account and data (EN) → /delete-account/
```

> **Google Play — "Eliminación de cuenta" en Data safety.** Pega ahí la URL de
> `delete-account/` (inglesa, igual que con privacidad). La página describe el borrado
> desde la App (`Perfil → Ajustes → Cuenta → Eliminar cuenta`, inmediato vía la Edge
> Function `delete-account`) y la alternativa por email si el usuario no puede abrir la
> App. Si cambia el texto o la ubicación de ese botón en la UI, actualiza ambos idiomas de
> esta página para que sigan describiendo los pasos reales.

> Las condiciones declaran en su §9 que **la App no tiene compras dentro de la
> aplicación**, y en su §2 que **no es un producto sanitario ni promete beneficios
> cognitivos** (la cláusula que evita el problema por el que la FTC sancionó a Lumosity).
> Si algún día se integra Google Play Billing, hay que actualizar la §9 de ambos idiomas
> **antes** de activar la compra.

> El contenido de la landing (nº de juegos, categorías, lista de títulos) sale del
> catálogo real: `GameCatalog.kt`, contando solo los `playable = true` que no estén
> marcados `published = false`. Si añades o publicas un juego, actualiza también aquí
> las cifras y la lista — es lo que ve un revisor de tienda.

## Datos ya completados

Los tres huecos que traía la plantilla están rellenos: responsable, dirección postal y
región de los servidores (`us-east-1`). El mecanismo sigue disponible por si se añade
contenido nuevo: cualquier `<span class="todo">[...]</span>` se resalta en ámbar al abrir
la página, y se localiza con `grep -rn 'class="todo"' site/`. Ahora mismo no hay ninguno.

Si cambias la **región de Supabase**, actualiza también el párrafo de transferencias
internacionales: hoy dice explícitamente que los datos de usuarios del EEE salen del EEE
porque el servidor está en Estados Unidos. Es una afirmación que debe seguir siendo cierta.

## Cómo publicarlo (GitHub Pages)

**No hace falta un repo aparte.** `tomasmoro/logic-games` es público, así que GitHub
Pages funciona desde aquí sin GitHub Pro. Lo despliega el workflow
`.github/workflows/deploy-site.yml` en cada push a `main` que toque este
directorio.

Se usa Actions en vez de *Deploy from a branch* porque esa opción solo admite `/` o
`/docs` como raíz del sitio, y estas páginas viven en `site/` (y `docs/` ya está
ocupado por documentación interna que no queremos servir como web).

Activación, una sola vez: **Settings → Pages → Source: GitHub Actions**. Después, el
primer despliegue se puede lanzar a mano desde la pestaña *Actions* → *Deploy site* →
*Run workflow*. Las URLs quedan:

- `https://tomasmoro.github.io/logic-games/` — **homepage** (la que pide el Branding
  del OAuth consent screen y la ficha de Play)
- `https://tomasmoro.github.io/logic-games/privacidad/`
- `https://tomasmoro.github.io/logic-games/privacy/`
- `https://tomasmoro.github.io/logic-games/eliminar-cuenta/`
- `https://tomasmoro.github.io/logic-games/delete-account/`

La versión inglesa es la que se pega en las tiendas en cada caso (privacidad y
eliminación de cuenta; ambas enlazan a su equivalente en español).

> La ventaja de publicar desde este mismo repo es que no hay copia que sincronizar: el
> texto vive versionado junto al código, así que cuando cambies de SDK o añadas un
> proveedor, la política se actualiza en el mismo commit y se despliega sola.

## Verlo en local antes de publicar

```bash
python3 -m http.server 8000 --directory site
```

Y abrir `http://localhost:8000`.
