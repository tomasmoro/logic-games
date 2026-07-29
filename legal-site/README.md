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

Este repo es de la app y probablemente sea privado; GitHub Pages en repos privados
requiere GitHub Pro. Lo más simple es un **repo público aparte** que solo contenga
estas páginas:

```bash
cd legal-site && git init -b main && git add . && git commit -m "Añade política de privacidad" && gh repo create kortexgames-legal --public --source=. --push
```

Si no tienes `gh` instalado, crea el repo `kortexgames-legal` a mano en
github.com y luego:

```bash
cd legal-site && git init -b main && git add . && git commit -m "Añade política de privacidad" && git remote add origin https://github.com/tomasmoro/kortexgames-legal.git && git push -u origin main
```

Después, en el repo nuevo: **Settings → Pages → Source: Deploy from a branch →
Branch: `main` / `(root)` → Save**. En uno o dos minutos las URLs quedan vivas:

- `https://tomasmoro.github.io/kortexgames-legal/privacidad/`
- `https://tomasmoro.github.io/kortexgames-legal/privacy/`

La segunda es la que se pega en las tiendas (usa la inglesa como principal; ambas
enlazan a la otra).

> Mantén este directorio como fuente de verdad y copia los cambios al repo
> público, o añade el repo público como segundo remoto. Lo importante es que el
> texto viva versionado junto al código: cuando cambies de SDK o añadas un
> proveedor, la política tiene que actualizarse en el mismo commit.

## Verlo en local antes de publicar

```bash
python3 -m http.server 8000 --directory legal-site
```

Y abrir `http://localhost:8000`.
