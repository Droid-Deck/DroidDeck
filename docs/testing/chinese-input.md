# Chinese input

The app ships a pinyin input method payload with the session files; it needs no network at run time.

## Android keyboard

1. Select your Android input method (for example Sogou or Gboard) in the system settings, then start a DroidDeck session and open its Android keyboard.
2. Type pinyin, pick a candidate, and commit it; confirm mixed Chinese/Latin and emoji text such as `中文` and `😀` arrives in the focused field, and that a backspace or Enter sent after a commit stays after it.

## Steam on-screen keyboard

Start a Steam session, open Steam's on-screen keyboard on a text field, switch it to Simplified Chinese (pinyin), and confirm committing a candidate puts the characters in the field. Physical keys, shortcuts, and Latin input keep working as before.

## X11 text-input fallback

For clients without native Wayland text input, committed Android IME text is delivered to the focused X11 display.

- **One focused display.** Text goes only to the focused X11 display.
- **Mappings stay stable.** Each keysym keeps its keycode and mapping until the helper exits, because clients may decode key events after they arrive.
- **Capacity is finite.** Only originally empty, non-modifier, unheld keycodes 8..255 can be reserved (at most 248 per display, in practice fewer); a frame that needs more distinct symbols than remain is refused before anything is injected, so restart the session to reclaim the slots.
- **Scope of the limitation.** Steam's own built-in IBus pinyin keyboard and native Wayland text input do not use this fallback and are not subject to it.
