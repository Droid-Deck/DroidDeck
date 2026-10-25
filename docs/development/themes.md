# Themes

A theme is a `Palette` (`ui/Theme.kt`): a dark background scale (`background`, `surface`,
`surfaceVariant`, `line`, `line2`), two text colours, and the accent: `primary` / `signal`
(flat accent, also Material's primary), `primary2` (the darker or second accent) and
`onPrimary`. `PrimaryButton` and the launch flood already draw a gradient from `primary` to
`primary2`. The choice is stored as an id (`SessionPrefs.theme`) and picked in Setup › Launcher.

## Gradient theme

`Themes.GRADIENT` is the one theme whose colours the person picks: a primary and a secondary,
kept in `SessionPrefs.gradientColors` and in `Themes.gradientColors`, a Compose state, so a change
restyles the whole app at once. `App.onCreate` loads the saved colours; code outside a composition
uses `Themes.current(context)`.

`ThemeColors.palette` builds the full palette from the two colours: primary becomes
`primary` / `signal`, secondary becomes `primary2`, the background scale is a dark neutral tinted with
the primary's hue (plain grey for a grey primary), and `onPrimary` is dark or white by the
luminance of the pair. A colour too dark to show on the dark background is lifted
(`ThemeColors.readable`) rather than refused.

Setup shows Primary and Secondary under the theme when Gradient is chosen, each a small colour
square. It opens `ColorPickerDialog`: a wheel (hue and saturation, touch), hue / saturation /
brightness sliders (they work with a controller) and a hex box, with the old and new colour side by
side. OK applies it.

## Inner Gamer theme

`Themes.INNER_GAMER` is an RGB theme: the accent walks round the colour wheel. It sits just above
Gradient in the list, and Setup shows a Speed slider (1 to 10, `SessionPrefs.rgbSpeed`) under the
theme while it is chosen.

`Themes.innerGamer(hue)` builds the palette for one hue through `ThemeColors.palette`, so button text
keeps the same contrast along the gradient at every hue. `DroidDeckTheme` runs one
`rememberInfiniteTransition` while Inner Gamer is chosen, so the system's animation clock drives it.
Compose applies Android's animator scale to it, and the colour holds still when animations are off
in the app (`Motion.scale`) or in Android's own settings. The hue is written to `Themes.rgbHue` in 3° steps, which restyles the
app only when the colour visibly moves; a slow cycle costs few recompositions.

