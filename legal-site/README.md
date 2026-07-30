# legal-site — Páginas legales públicas de KortexGames

Sitio estático con la política de privacidad de la App, en español e inglés.
Se publica en GitHub Pages y su URL se pega en Google Play Console y en
App Store Connect.

```
legal-site/
├── index.html          Índice con enlaces a ambos idiomas
├── styles.css          Tokens del sistema de diseño (§9 de CLAUDE.md)
├── privacidad/         Política de Privacidad (ES) → /privacidad/
└── privacy/            Privacy Policy (EN)        → /privacy/
```

## Antes de publicar: completar los marcadores

Los huecos pendientes están marcados en el HTML con `<span class="todo">[...]</span>`
y se ven resaltados en ámbar al abrir la página. Búscalos con:

```bash
grep -rn 'class="todo"' legal-site/
```

Hay tres, y aparecen en ambos idiomas:

1. **Nombre completo o razón social** del responsable del tratamiento.
2. **Dirección postal.** Es obligatoria en el RGPD y en COPPA — no basta el email.
   Si no quieres publicar tu domicilio particular, usa una dirección comercial o
   un apartado postal.
3. **Región de los servidores de Supabase.** Se ve en el dashboard del proyecto,
   en *Settings → General → Region*.

## Cómo publicarlo (GitHub Pages)

**No hace falta un repo aparte.** `tomasmoro/logic-games` es público, así que GitHub
Pages funciona desde aquí sin GitHub Pro. Lo despliega el workflow
`.github/workflows/deploy-legal-site.yml` en cada push a `main` que toque este
directorio.

Se usa Actions en vez de *Deploy from a branch* porque esa opción solo admite `/` o
`/docs` como raíz del sitio, y estas páginas viven en `legal-site/` (y `docs/` ya está
ocupado por documentación interna que no queremos servir como web).

Activación, una sola vez: **Settings → Pages → Source: GitHub Actions**. Después, el
primer despliegue se puede lanzar a mano desde la pestaña *Actions* → *Deploy legal
site* → *Run workflow*. Las URLs quedan:

- `https://tomasmoro.github.io/logic-games/privacidad/`
- `https://tomasmoro.github.io/logic-games/privacy/`

La segunda es la que se pega en las tiendas (usa la inglesa como principal; ambas
enlazan a la otra).

> La ventaja de publicar desde este mismo repo es que no hay copia que sincronizar: el
> texto vive versionado junto al código, así que cuando cambies de SDK o añadas un
> proveedor, la política se actualiza en el mismo commit y se despliega sola.

## Verlo en local antes de publicar

```bash
python3 -m http.server 8000 --directory legal-site
```

Y abrir `http://localhost:8000`.
