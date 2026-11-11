import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-desktop-clipboard'
SIGNAL = 'signal sender=:1.35 -> destination=(null destination) path=/klipper; interface=org.kde.klipper.klipper; member=clipboardHistoryUpdated'


class DesktopClipboardTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.helper_args = self.root / 'helper-args'
        helper = self.bin / 'helper'
        helper.write_text(f'#!/bin/bash\nprintf "%s\\n" "$@" > {self.helper_args}\n')
        helper.chmod(0o755)
        script = self.bin / 'droiddeck-desktop-clipboard'
        script.write_text(SCRIPT.read_text().replace('/usr/local/bin/droiddeck-clipboard', str(helper)))
        script.chmod(0o755)
        self.script = script
        self.android = self.root / 'android-clipboard'
        self.guest = self.root / 'guest-clipboard'

    def tool(self, name, body):
        path = self.bin / name
        path.write_text('#!/bin/bash\n' + body)
        path.chmod(0o755)

    def run_script(self, klipper_texts, guest=True):
        if isinstance(klipper_texts, str):
            klipper_texts = [klipper_texts]
        for index, text in enumerate(klipper_texts, 1):
            (self.root / f'klipper-text-{index}').write_text(text)
        signals = '\n'.join(f'echo "{SIGNAL}"' for _ in klipper_texts)
        self.tool('dbus-monitor', signals + '\necho "signal other member=NameAcquired"\n')
        self.tool('qdbus6', f'''count_file="{self.root}/count"
count=0
[ ! -f "$count_file" ] || count=$(cat "$count_file")
count=$((count + 1))
printf %s "$count" > "$count_file"
cat "{self.root}/klipper-text-$count"
echo
''')
        env = dict(os.environ, PATH=f'{self.bin}:{os.environ["PATH"]}', DISPLAY=':0',
                   BL_ANDROID_CLIPBOARD=str(self.android))
        env.pop('BL_GUEST_CLIPBOARD', None)
        if guest:
            env['BL_GUEST_CLIPBOARD'] = str(self.guest)
        subprocess.run(['bash', str(self.script)], env=env, check=True, timeout=20)

    def guest_updates(self):
        return sorted(self.root.glob('guest-clipboard.queue.*.ready'))

    def test_guest_copy_reaches_android_file_with_exact_text(self):
        self.run_script('line one\n\nline two\n')
        updates = self.guest_updates()
        self.assertEqual(len(updates), 1)
        self.assertEqual(updates[0].read_text(), 'line one\n\nline two\n')
        self.assertFalse(list(self.root.glob('guest-clipboard.queue.*.pending')))

    def test_trailing_newline_of_copied_text_is_kept(self):
        self.run_script('text\n')
        self.assertEqual([path.read_text() for path in self.guest_updates()], ['text\n'])

    def test_empty_clipboard_is_not_forwarded(self):
        self.run_script('')
        self.assertFalse(self.guest_updates())

    def test_oversized_text_is_not_forwarded(self):
        self.run_script('a' * 300000)
        self.assertFalse(self.guest_updates())

    def test_rapid_copies_are_queued_in_order(self):
        self.run_script(['first copy', 'second copy'])
        self.assertEqual([path.read_text() for path in self.guest_updates()], ['first copy', 'second copy'])

    def test_android_helper_runs_with_android_file_only(self):
        self.run_script('x')
        self.assertEqual(self.helper_args.read_text().split(), [str(self.android)])

    def test_without_guest_path_nothing_is_forwarded(self):
        self.run_script('x', guest=False)
        self.assertFalse(self.guest_updates())
        self.assertEqual(self.helper_args.read_text().split(), [str(self.android)])


if __name__ == '__main__':
    unittest.main()
