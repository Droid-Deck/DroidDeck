# Compose previews

Previews show production composables with sample data, without a device, the Linux runtime or a network. Use them in two
ways:

- **Android Studio:** select the `debug` build variant and open `app/src/debug/java/com/droiddeck/launcher/ui/LauncherPreviews.kt`
  in Split or Design view.
- **Rendered to PNG files**, with no Studio or device needed (also how an agent can look at a UI change):

  ```sh
  ./gradlew -PskipRust=true :app:testDebugUnitTest --tests '*PreviewRenderTest'
  ```

  Each preview is drawn with Robolectric's native graphics at handheld (960x540 dp at xhdpi, the Duo's top screen,
  1920x1080 px) and portrait (540x960 dp) sizes, into `app/build/preview-renders/{handheld,portrait}/<case>.png`. The test
  fails if a preview throws, or draws a single flat colour.

## Files

| File | What |
| --- | --- |
| `app/src/debug/.../ui/PreviewSupport.kt` | `PreviewHost` (the production theme), `@DroidDeckPreviews` (the two sizes), sample `FrontEndState`, no-op `FrontEndActions` and `DrawerActions`, sample games |
| `app/src/debug/.../ui/LauncherPreviews.kt` | The previews, and `previewCases`, the list the render test draws |
| `app/src/testDebug/.../ui/PreviewRenderTest.kt` | The render test |

Previews cover the launcher on each tab (Steam, Library, Library with no games, Stores, Downloads, Settings, Settings with
no runtime) and the in-game menu's pages (Controller, Display, HUD, Session, and the Desktop menu).

## Adding one

Write the preview in `LauncherPreviews.kt` (or a new file beside it) with `@DroidDeckPreviews`, wrapping the content in
`PreviewHost`. Add a `PreviewCase` to `previewCases` so it is rendered and checked too. The launcher opens on a tab via
`sampleState(navigate = "<tab key>")`.

Everything here is in the debug source sets, so none of it ships in release builds. Sample art is left out, so cards use
their fallback gradients. Previews check layout and look; they do not exercise launching, input or the runtime.
