#!/usr/bin/env python3
"""Check pinyin configuration and text commits over IBus."""

import os
import time

try:
    import gi

    gi.require_version('IBus', '1.0')
    from gi.repository import GLib, IBus
except (ImportError, ValueError) as error:
    raise SystemExit(f'error: PyGObject with the IBus 1.0 typelib is required: {error}')

CLIENT = 'droiddeck-ime-test'
ENGINE = 'pinyin'
SECTION = 'engine/Pinyin'
BOOLEAN_KEYS = ('DoublePinyin', 'InitPinyin', 'GBK')
KEYCODES = {'a': 38, 'h': 43, 'i': 31, 'n': 57, 'o': 32, 'space': 65, '1': 10}


def pump(seconds, condition):
    deadline = time.monotonic() + seconds
    while True:
        GLib.MainContext.default().iteration(False)
        if condition():
            return True
        if time.monotonic() >= deadline:
            return False
        time.sleep(0.02)


def press(context, name):
    keyval = IBus.keyval_from_name(name)
    keycode = KEYCODES[name]
    context.process_key_event(keyval, keycode, 0)
    context.process_key_event(keyval, keycode, IBus.ModifierType.RELEASE_MASK)


def boolean(config, key):
    value = config.get_value(SECTION, key)
    if value is None or value.get_type_string() != 'b':
        return None
    return value.get_boolean()


def unchanged(value, original):
    if original is None:
        return value is None
    return value is not None and value.print_(True) == original.print_(True)


def configured(bus):
    config = bus.get_config()
    if config is None:
        raise SystemExit('error: the bus exposes no configuration service')
    saved = {key: config.get_value(SECTION, key) for key in BOOLEAN_KEYS}
    probed = 0
    try:
        for key in BOOLEAN_KEYS:
            original = saved[key]
            current = original is not None and original.get_type_string() == 'b' \
                and original.get_boolean()
            wanted = not current
            # The PyGObject override returns None on success, so verify writes by readback.
            config.set_value(SECTION, key, GLib.Variant.new_boolean(wanted))
            if not pump(3.0, lambda key=key, wanted=wanted: boolean(config, key) == wanted):
                raise SystemExit(f'error: {SECTION}/{key} did not read back as written')
            probed += 1
    finally:
        restore_failed = []
        for key in BOOLEAN_KEYS:
            original = saved[key]
            try:
                if original is None:
                    config.unset(SECTION, key)
                else:
                    config.set_value(SECTION, key, original)
                if not pump(3.0, lambda key=key, original=original:
                            unchanged(config.get_value(SECTION, key), original)):
                    restore_failed.append(key)
            except GLib.Error:
                restore_failed.append(key)
        if restore_failed:
            raise SystemExit('error: could not restore ' + SECTION + ' settings: '
                             + ', '.join(restore_failed))
    return probed


def main():
    if not os.environ.get('IBUS_ADDRESS'):
        raise SystemExit('error: IBUS_ADDRESS is not set; the smoke test needs the private ibus-daemon')
    bus = IBus.Bus()
    if not bus.is_connected():
        raise SystemExit('error: cannot connect to the IBus bus from IBUS_ADDRESS')
    engines = {engine.get_name() for engine in bus.list_engines() or []}
    if ENGINE not in engines:
        raise SystemExit('error: pinyin is not registered: ' + ', '.join(sorted(engines)))
    print('IBus bus connected; pinyin engine registered')

    keys = configured(bus)
    print(f'engine/Pinyin config write/readback ok ({keys} boolean settings)')

    if not bus.set_global_engine(ENGINE):
        raise SystemExit('error: the bus rejected the pinyin global engine')
    context = bus.create_input_context(CLIENT)
    state = {'candidates': 0, 'commit': ''}

    def lookup(_, table, visible):
        if table is not None:
            state['candidates'] = table.get_number_of_candidates()

    def commit(_, text):
        state['commit'] = text.get_text() or state['commit']

    context.connect('update-lookup-table', lookup)
    context.connect('commit-text', commit)
    context.set_capabilities(IBus.Capabilite.PREEDIT_TEXT | IBus.Capabilite.LOOKUP_TABLE
                             | IBus.Capabilite.FOCUS)
    context.focus_in()
    pump(1.0, lambda: False)
    context.set_engine(ENGINE)
    pump(1.0, lambda: False)

    for _ in range(3):
        for name in 'nihao':
            press(context, name)
        if pump(3.0, lambda: state['candidates'] > 0):
            break
    else:
        raise SystemExit('error: no pinyin candidates after typing the test syllable')
    print('lookup table ok')

    def chinese():
        return any('\u4e00' <= character <= '\u9fff' for character in state['commit'])

    for name in ('space', '1'):
        press(context, name)
        if pump(3.0, chinese):
            break
    if not chinese():
        raise SystemExit('error: pinyin committed no Chinese text for the test syllable')
    print('commit-text ok')
    context.focus_out()
    print('droiddeck IBus pinyin smoke test passed')


if __name__ == '__main__':
    try:
        main()
    except GLib.Error as error:
        raise SystemExit(f'error: IBus call failed: {error.message}')
