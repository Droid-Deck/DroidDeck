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
