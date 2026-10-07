# Compose previews

Select the **debug** build variant in Android Studio, then open a preview file under
`app/src/debug/java/com/droiddeck/launcher/` and choose **Split** or **Design**.
Build the project after syncing Gradle. Previews call the production composables;
there is no separate mock UI to keep in sync.

| File | Coverage |
| --- | --- |
| `ui/LauncherPreviews.kt` | Steam home, runtime missing/downloading, populated/empty library, game details, desktop, emulator, Android apps, Store, Updates, alternate theme |
| `ui/SettingsPreviews.kt` | All four Setup tabs, all six Steam settings tabs, desktop settings, controller/mapping, performance, shared settings widgets |
| `ui/RuntimePreviews.kt` | Proton installed/empty/downloading, components/loading, GPU drivers and driver downloads |
| `ui/SessionPreviews.kt` | Every session drawer page, loading/starting/error screens, PC keyboard, HUD, cursor |
| `ui/DialogPreviews.kt` | Add/edit app, confirmation, custom resolution, ROM folder, environment variables, Windows components, display chooser, wireless ADB, process-limit help |
| `files/FilePreviews.kt` | File manager grid/list/empty/favorites, file/folder pickers, properties |

`@DroidDeckPreviews` shows handheld landscape (960×540 dp), compact landscape
(480×360 dp), portrait (400×700 dp), and larger text. The Paper theme has a separate
launcher preview. Dimensions are Android layout units, not physical display pixels.

Edit the real UI components to adjust layouts. Edit `PreviewData.kt` for sample
screen state and callbacks, and `FilePreviews.kt` for file metadata. Add a preview
for each new screen or materially different state. Add its entry to
`PreviewCases.kt` or `filePreviewCases` so it is covered by the render check.

Preview fixtures and tooling are debug-only. `PreviewHost` supplies the production
theme, inspection mode, and inert navigation/file-picker owners. Inspection paths
skip automatic runtime, directory, device, and network operations and settle
entrance animations so content is visible immediately. File manager mutation
commands are disabled in inspection mode. Previews are for layout and local UI
interaction; they do not validate installation, pairing, launching, or controller
input latency.

The native game surface, Termux terminal, on-screen gamepad, and second-screen
View controls are outside this Compose preview set. Their surrounding Compose
menus are covered. These components still need device validation.

Run the preview render smoke checks:

```sh
./gradlew app:testDebugUnitTest --tests '*PreviewRenderTest'
```

The checks use Robolectric native graphics with an ordinary `Application`, render
the same 68 preview entry points at handheld, compact, and portrait sizes, reject
empty/solid-color output, and write PNGs to
`app/build/preview-renders/`. This catches composition/render errors without starting
the Linux runtime. Android Studio uses Layoutlib, so the smoke check complements
Studio preview validation rather than replacing it.
