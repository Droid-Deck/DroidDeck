# DroidDeck Launcher para Windows

Launcher nativo de Windows escrito en Rust. Reúne bibliotecas de distintas tiendas y abre los clientes correspondientes. No incluye emulación ni runtimes de Android. La interfaz usa eframe/egui; la aplicación no necesita Node.js ni Electron.

## Biblioteca

1. En DroidDeck para Android, abrí **Biblioteca** y elegí **Compartir** para guardar `DroidDeck-library.json`.
2. Transferí el archivo a la PC.
3. En DroidDeck Launcher, seleccioná **Importar DroidDeck**.

El catálogo de DroidDeck incluye juegos de Steam vinculados a la cuenta aunque no estén instalados. El launcher conserva esos títulos y actualiza el estado local al revisar Steam, Epic Games y los accesos directos del menú Inicio. Volvé a importar el JSON para actualizar los catálogos de las tiendas. La exportación no contiene cookies, tokens ni contraseñas, y el launcher no lee credenciales de Steam.

La vista **No instalados** aparece primero para que puedas revisar tu colección antes de comprar. **Instalar** abre el flujo de instalación de Steam o la página de la tienda. Se pueden agregar ejecutables o accesos directos locales desde **Agregar juego**.

## Compilar

Instalá Rust estable para Windows y compilá desde esta carpeta:

```powershell
cargo run --release
```

El ejecutable portable queda en `target/release/droideck-launcher.exe`. Los datos se guardan en `%LOCALAPPDATA%\DroidDeckLauncher\`; en el primer inicio se importa la biblioteca de la versión Electron anterior si está en `%APPDATA%\droideck-windows-launcher\library.json`.
