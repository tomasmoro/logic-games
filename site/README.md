# site — Sitio web público de KortexGames

Sitio estático con la **página principal** de la App y su **política de privacidad**
(español e inglés). Se publica en GitHub Pages. Sus URLs se pegan en tres sitios:
Google Play Console, App Store Connect y el *Branding* del OAuth consent screen de
Google Cloud (que exige homepage + política para sacar la app de modo Testing).

```
site/
├── index.html          Página principal (landing)   → /
├── styles.css          Tokens del sistema de diseño (§9 de CLAUDE.md)
├── assets/icon.png     Icono de la app, reutilizado como logo
├── privacidad/         Política de Privacidad (ES)  → /privacidad/
└── privacy/            Privacy Policy (EN)          → /privacy/
```

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

La segunda es la que se pega en las tiendas (usa la inglesa como principal; ambas
enlazan a la otra).

> La ventaja de publicar desde este mismo repo es que no hay copia que sincronizar: el
> texto vive versionado junto al código, así que cuando cambies de SDK o añadas un
> proveedor, la política se actualiza en el mismo commit y se despliega sola.

## Verlo en local antes de publicar

```bash
python3 -m http.server 8000 --directory site
```

Y abrir `http://localhost:8000`.
