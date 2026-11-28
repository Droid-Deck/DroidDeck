# DroidDeck Launcher para Windows

Launcher nativo escrito en Rust para reunir juegos de PC de las tiendas conectadas. No emula Android ni instala un runtime de Linux.

## Conectar tiendas

Abrí **Cuentas de tiendas**, elegí **Conectar cuenta** y completá el login en la ventana integrada. La mayoría de las bibliotecas se sincronizan al terminar y al iniciar el launcher. Steam y Game Jolt requieren una confirmación dentro de su ventana web: el inicio automático y **Actualizar todas** abren esas ventanas, pero no importan la biblioteca hasta que selecciones **Sincronizar biblioteca con DroidDeck**. Repetí ese paso cuando quieras incorporar compras nuevas.

Steam usa el formulario oficial dentro de esa ventana. Si el perfil aislado ya tiene una sesión abierta, no aparece el QR: pulsá **Sincronizar biblioteca con DroidDeck**. Si no hay sesión, iniciá sesión o escaneá el QR que muestra Steam y luego sincronizá. El launcher detecta la sesión y agrega los juegos instalados y no instalados.

La integración de bibliotecas de cuenta está disponible para:

- Steam, Epic Games, EA app, GOG, Ubisoft Connect, Amazon Games, Rockstar Games y Game Jolt.
- itch.io mediante una clave API personal.

Xbox todavía usa el helper Linux que DroidDeck distribuye para Android, así que no se reutiliza en Windows. Battle.net en DroidDeck sincroniza los juegos instalados desde su cliente y no ofrece su biblioteca de compras no instaladas. Estas dos tiendas se muestran en la lista, pero no como cuentas conectables completas.

Las sesiones se guardan por tienda en `%LOCALAPPDATA%\DroidDeckLauncher\accounts` cifradas con AES-256-GCM. La clave de cifrado queda en el Administrador de credenciales de Windows. Los perfiles del navegador integrado son independientes por tienda y se eliminan al desconectarla. El catálogo local está en `%LOCALAPPDATA%\DroidDeckLauncher\library.json` y no contiene tokens.

Los juegos no instalados aparecen primero y los instalados se detectan con los manifiestos locales de Steam y Epic, además de accesos directos reconocidos. En tiendas sin instalador local expuesto, el botón abre la página del juego o su protocolo de tienda cuando está disponible. **Agregar juego** admite ejecutables y accesos directos.

## Compilar

Instalá Rust estable, el WebView2 Runtime de Windows y compilá desde esta carpeta:

```powershell
cargo run --release
```

El ejecutable portable queda en `target/release/droideck-launcher.exe`.
