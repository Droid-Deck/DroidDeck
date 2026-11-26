# DroidDeck Launcher para Windows

Aplicación nativa de Windows para reunir el catálogo de juegos de DroidDeck y abrir juegos con sus clientes de tienda. No incluye emulación ni runtimes de Android.

## Importar tu biblioteca

1. En DroidDeck para Android, abrí **Biblioteca**, elegí **Todas las tiendas** y usá **Compartir** para guardar `DroidDeck-library.json`.
2. Transferí ese archivo a la PC.
3. En DroidDeck Launcher, elegí **Importar DroidDeck**.

La exportación contiene títulos, identificadores de tienda, estado de propiedad y carátulas. No exporta cookies, tokens ni contraseñas. Repetí la importación cuando quieras actualizar el catálogo.

El launcher detecta juegos instalados de Steam y Epic Games. También relaciona accesos directos del menú Inicio por nombre; para cualquier otro juego podés asociar un `.exe` o `.lnk` desde **Agregar juego**. Los juegos de Steam se abren con su App ID y los títulos no instalados llevan a la tienda o al flujo de instalación del cliente.

## Ejecutar y empaquetar

Necesita Node.js y npm en Windows.

```powershell
npm install
npm start
```

Para generar el instalador Windows x64:

```powershell
npm run dist
```

El instalador se genera en `release/`.
