import builtins
import contextlib
import hashlib
import importlib.machinery
import importlib.util
import io
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1]
BIN = TOOLS / 'linuxfs/overlay/usr/local/bin'
FIXTURES = Path(__file__).resolve().parent / 'fixtures/steam-accounts'
SESSION = BIN / 'droiddeck-session'


def load(name):
    loader = importlib.machinery.SourceFileLoader(name.replace('-', '_'), str(BIN / name))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


account = load('droiddeck-steam-account')
shortcuts = load('droiddeck-steam-shortcuts')

A, B, C = '76561198000000001', '76561198000000002', '76561198000000003'
NAMES = ['alpha_acct', 'bravo_acct', 'charlie_acct', 'Alpha Persona', 'Bravo Persona', 'Charlie Persona']
CREDENTIALS = re.compile(r'(^|/)(config\.vdf|local\.vdf|ssfn[^/]*|[^/]*ConnectCache[^/]*)$')


def fixture(name):
    return (FIXTURES / name).read_bytes()


def recent_ids(text):
    return [uid for uid, f in account.parse_users(text) if f.get('mostrecent') == '1']


class Session(unittest.TestCase):
    """A guest home and Steam root laid out as the session sees them."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name) / 'root'
        self.steam = self.home / '.local/share/Steam'
        (self.steam / 'config').mkdir(parents=True)
        (self.home / '.steam').mkdir()
        (self.home / '.config/droiddeck').mkdir(parents=True)
        self.users = self.steam / 'config/loginusers.vdf'
        self.registry = self.home / '.steam/registry.vdf'
        self.config = self.steam / 'config/config.vdf'
        self.config.write_bytes(fixture('config.vdf'))
        self.config_sha = hashlib.sha256(self.config.read_bytes()).hexdigest()
        self.request = self.home / '.config/droiddeck/steam-account-next'
        self.last = self.home / '.config/droiddeck/steam-account-last'
        self.opened = []
        self.out = ''

    def tearDown(self):
        self.assertEqual(self.config_sha, hashlib.sha256(self.config.read_bytes()).hexdigest())
        for path in self.opened:
            self.assertIsNone(CREDENTIALS.search(str(path)), path)
        for leak in NAMES + [A, B, C]:
            self.assertNotIn(leak, self.out)

    def lay(self, users='two.vdf', registry='registry.vdf'):
        if users:
            self.users.write_bytes(fixture(users))
        if registry:
            self.registry.write_bytes(fixture(registry))

    def ask(self, text):
        self.request.write_text(text)

    def run_helper(self, verb='apply'):
        real_open, real_os_open = builtins.open, os.open

        def record_open(file, *a, **k):
            self.opened.append(file)
            return real_open(file, *a, **k)

        def record_os_open(path, *a, **k):
            self.opened.append(path)
            return real_os_open(path, *a, **k)
        out, err = io.StringIO(), io.StringIO()
        with patch('builtins.open', record_open), patch('os.open', record_os_open), \
                patch.dict(os.environ, {'HOME': str(self.home)}), \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            rc = account.main(['droiddeck-steam-account', verb, str(self.steam)])
        self.out += out.getvalue() + err.getvalue()
        self.assertEqual(0, rc)
        return out.getvalue()

    def snapshot(self):
        files = [self.users, self.registry]
        return {p: (p.read_bytes(), p.stat().st_mtime_ns) if p.exists() else None for p in files}

    def backups(self):
        return sorted(p.name for p in self.home.rglob('*.droiddeck-prev'))

    def leftovers(self):
        return sorted(p.name for p in self.home.rglob('*.tmp'))


class ParseTest(unittest.TestCase):
    def test_accounts_in_file_order(self):
        users = account.parse_users(fixture('three.vdf').decode())
        self.assertEqual([A, B, C], [uid for uid, _ in users])
        self.assertEqual('bravo_acct', users[1][1]['accountname'])

    def test_crlf_bom_comments_and_escapes_parse(self):
        for name in ('two-crlf.vdf', 'two-bom.vdf', 'comments.vdf', 'escaped-persona.vdf'):
            users = account.parse_users(fixture(name).decode())
            self.assertEqual([A, B], [uid for uid, _ in users], name)
        persona = account.parse_users(fixture('escaped-persona.vdf').decode())[1][1]['personaname']
        self.assertEqual('Bra\\"vo {x} // y', persona)

    def test_malformed_files_are_refused(self):
        for name in ('unbalanced.vdf', 'no-root.vdf', 'duplicate-id.vdf', 'bad-id.vdf',
                     'no-accountname.vdf', 'unterminated.vdf'):
            self.assertIsNone(account.parse_users(fixture(name).decode()), name)
        self.assertIsNone(account.parse_users(''))
        self.assertIsNone(account.parse_users('"users" { "76561198000000001" { "a" } }'))

    def test_tokens_keep_their_offsets(self):
        text = '"k" // note\n{ "v\\"x" "y" }'
        toks = account.tokenize(text)
        self.assertEqual([('s', 'k'), ('{', '{'), ('s', 'v\\"x'), ('s', 'y'), ('}', '}')],
                         [(t[0], t[1]) for t in toks])
        for kind, raw, start, end in toks:
            self.assertEqual(raw, text[start:end])
        self.assertIsNone(account.tokenize('"k" bare'))


class SelectTest(unittest.TestCase):
    def changed_lines(self, old, new):
        a, b = old.splitlines(True), new.splitlines(True)
        self.assertEqual(len(a), len(b))
        return [(x, y) for x, y in zip(a, b) if x != y]

    def test_only_most_recent_digits_change(self):
        for name, target in (('two.vdf', B), ('three.vdf', C), ('three.vdf', B), ('two-crlf.vdf', B),
                             ('two-bom.vdf', B), ('comments.vdf', B), ('escaped-persona.vdf', B)):
            old = fixture(name).decode()
            new = account.select(old, target)
            changed = self.changed_lines(old, new)
            self.assertEqual(2, len(changed), name)
            for x, y in changed:
                self.assertIn('"MostRecent"', x)
                self.assertEqual(re.sub(r'"[01]"', '""', x), re.sub(r'"[01]"', '""', y))
            self.assertEqual([target], recent_ids(new), name)
            self.assertTrue(account.validate(old, new, 'users', id64=target), name)

    def test_crlf_and_bom_survive(self):
        new = account.select(fixture('two-crlf.vdf').decode(), B)
        self.assertEqual(new.count('\r\n'), new.count('\n'))
        self.assertTrue(account.select(fixture('two-bom.vdf').decode(), B).startswith('\ufeff'))

    def test_active_account_is_same(self):
        self.assertIs(account.SAME, account.select(fixture('two.vdf').decode(), A))

    def test_several_flagged_end_with_one(self):
        old = fixture('several-recent.vdf').decode()
        for target in (A, B, C):
            new = account.select(old, target)
            self.assertEqual([target], recent_ids(new))
            self.assertTrue(account.validate(old, new, 'users', id64=target))

    def test_refusals(self):
        self.assertIsNone(account.select(fixture('two.vdf').decode(), C))
        self.assertIsNone(account.select(fixture('not-remembered.vdf').decode(), B))
        self.assertIsNone(account.select(fixture('no-mostrecent.vdf').decode(), B))
        self.assertIsNone(account.select(fixture('unbalanced.vdf').decode(), B))

    def test_block_without_most_recent_is_left_alone(self):
        old = fixture('no-mostrecent.vdf').decode()
        self.assertIs(account.SAME, account.select(old, A))

    def test_registry_value_changes_and_nothing_else(self):
        for name in ('registry.vdf', 'registry-lowercase.vdf'):
            old = fixture(name).decode()
            new = account.select_registry(old, 'bravo_acct')
            changed = self.changed_lines(old, new)
            self.assertEqual(1, len(changed))
            self.assertEqual(changed[0][0].replace('alpha_acct', 'bravo_acct'), changed[0][1])
            self.assertTrue(account.validate(old, new, 'registry', account='bravo_acct'))
        self.assertIs(account.SAME, account.select_registry(fixture('registry.vdf').decode(), 'alpha_acct'))

    def test_registry_absent_and_refused(self):
        self.assertEqual(account.ABSENT, account.select_registry(fixture('registry-no-key.vdf').decode(), 'bravo_acct'))
        self.assertEqual(account.ABSENT, account.select_registry(fixture('registry-other-path.vdf').decode(), 'bravo_acct'))
        self.assertIsNone(account.select_registry(fixture('registry-malformed.vdf').decode(), 'bravo_acct'))
        self.assertIsNone(account.select_registry(fixture('registry-ambiguous.vdf').decode(), 'bravo_acct'))
        for bad in ('', '  ', 'a"b', 'a\\b', 'a\nb'):
            self.assertIsNone(account.select_registry(fixture('registry.vdf').decode(), bad), repr(bad))

    def test_validate_rejects_other_changes(self):
        old = fixture('two.vdf').decode()
        new = account.select(old, B)
        self.assertFalse(account.validate(old, new.replace('"Bravo Persona"', '"Bravo"'), 'users', id64=B))
        self.assertFalse(account.validate(old, new.replace('"RememberPassword"\t\t"1"', '"RememberPassword"\t\t"0"', 1), 'users', id64=B))
        self.assertFalse(account.validate(old, new + '\n"x" "y"\n', 'users', id64=B))
        self.assertFalse(account.validate(old, new, 'users', id64=A))
        self.assertFalse(account.validate(old, old, 'users', id64=B))
        reg = fixture('registry.vdf').decode()
        self.assertFalse(account.validate(reg, reg.replace('english', 'french'), 'registry', account='alpha_acct'))


class ApplyTest(Session):
    def test_no_request_touches_nothing(self):
        self.lay()
        before = self.snapshot()
        self.opened = []
        self.assertEqual('', self.run_helper())
        self.assertEqual(before, self.snapshot())
        self.assertEqual([], self.backups())
        self.assertEqual([], self.opened)
        self.assertFalse(self.last.exists())

    def test_request_for_active_account(self):
        self.lay()
        before = self.snapshot()
        self.ask(A + '\n')
        self.assertIn('already selected', self.run_helper())
        self.assertEqual(before, self.snapshot())
        self.assertEqual([], self.backups())
        self.assertFalse(self.request.exists())
        self.last.unlink()
        self.assertEqual('', self.run_helper())
        self.assertEqual(before, self.snapshot())

    def test_switch_two_and_three(self):
        for users, target, place in (('two.vdf', B, '2 of 2'), ('three.vdf', C, '3 of 3'), ('three.vdf', B, '2 of 3')):
            with self.subTest(users=users, target=target):
                self.lay(users)
                old = self.users.read_text()
                self.ask(target)
                self.assertIn('== steam account: starting as account %s\n' % place, self.run_helper())
                new = self.users.read_text()
                self.assertTrue(account.validate(old, new, 'users', id64=target))
                self.assertEqual([target], recent_ids(new))
                acct = str(int(target) - 76561197960265728)
                self.assertEqual(os.path.join(str(self.steam), 'userdata', acct), shortcuts.account_dir(str(self.steam)))
                reg = account.parse_users(new)
                name = dict(reg)[target]['accountname']
                self.assertIn('"AutoLoginUser"\t\t"%s"' % name, self.registry.read_text())
                self.assertEqual(fixture(users), (self.steam / 'config/loginusers.vdf.droiddeck-prev').read_bytes())
                self.assertEqual(fixture('registry.vdf'), (self.home / '.steam/registry.vdf.droiddeck-prev').read_bytes())
                self.assertFalse(self.request.exists())
                self.assertEqual(target, self.last.read_text().splitlines()[0])
                self.assertEqual([], self.leftovers())

    def test_several_flagged_end_with_one(self):
        self.lay('several-recent.vdf')
        self.ask(B)
        self.run_helper()
        self.assertEqual([B], recent_ids(self.users.read_text()))

    def test_bad_accounts_lists_write_nothing(self):
        for users in ('unbalanced.vdf', 'no-root.vdf', 'duplicate-id.vdf', 'binary.vdf', 'unterminated.vdf', 'empty', None):
            with self.subTest(users=users):
                if users == 'empty':
                    self.lay(None)
                    self.users.write_bytes(b'')
                elif users is None:
                    self.lay(None)
                    if self.users.exists():
                        self.users.unlink()
                else:
                    self.lay(users)
                before = self.snapshot()
                self.ask(B)
                self.assertIn('nothing changed', self.run_helper())
                self.assertEqual(before, self.snapshot())
                self.assertFalse(self.request.exists())
                self.assertEqual([], self.backups())

    def test_unusable_requests_write_nothing(self):
        for users, text in (('two.vdf', C), ('not-remembered.vdf', B), ('two.vdf', 'bravo_acct'),
                            ('two.vdf', '1234'), ('two.vdf', B + 'x'), ('two.vdf', ''), ('no-mostrecent.vdf', B)):
            with self.subTest(users=users, text=text):
                self.lay(users)
                before = self.snapshot()
                self.ask(text)
                self.assertIn('nothing changed', self.run_helper())
                self.assertEqual(before, self.snapshot())
                self.assertFalse(self.request.exists())
                self.assertEqual([], self.backups())
                self.assertFalse(self.last.exists())

    def test_binary_request_is_dropped(self):
        self.lay()
        before = self.snapshot()
        self.request.write_bytes(b'\xff\x00' * 100)
        self.run_helper()
        self.assertEqual(before, self.snapshot())
        self.assertFalse(self.request.exists())

    def test_registry_other_path_untouched_and_list_switched(self):
        self.lay(registry='registry-other-path.vdf')
        self.ask(B)
        self.run_helper()
        self.assertEqual(fixture('registry-other-path.vdf'), self.registry.read_bytes())
        self.assertEqual([B], recent_ids(self.users.read_text()))
        self.assertEqual(['loginusers.vdf.droiddeck-prev'], self.backups())

    def test_registry_missing_file_or_key(self):
        for registry in (None, 'registry-no-key.vdf'):
            with self.subTest(registry=registry):
                self.lay(registry=registry)
                if registry is None and self.registry.exists():
                    self.registry.unlink()
                self.ask(B)
                self.run_helper()
                self.assertEqual([B], recent_ids(self.users.read_text()))
                if registry is None:
                    self.assertFalse(self.registry.exists())
                else:
                    self.assertEqual(fixture(registry), self.registry.read_bytes())

    def test_registry_brought_in_line_with_the_list(self):
        self.lay()
        self.registry.write_text(fixture('registry.vdf').decode().replace('alpha_acct', 'bravo_acct'))
        self.ask(A)
        self.assertIn('starting as account 1 of 2\n', self.run_helper())
        self.assertEqual(fixture('two.vdf'), self.users.read_bytes())
        self.assertEqual(fixture('registry.vdf'), self.registry.read_bytes())
        self.assertEqual(['registry.vdf.droiddeck-prev'], self.backups())

    def test_registry_already_right(self):
        self.lay()
        self.registry.write_text(fixture('registry.vdf').decode().replace('alpha_acct', 'bravo_acct'))
        before = self.registry.read_bytes()
        self.ask(B)
        self.run_helper()
        self.assertEqual(before, self.registry.read_bytes())
        self.assertEqual([B], recent_ids(self.users.read_text()))
        self.assertEqual(['loginusers.vdf.droiddeck-prev'], self.backups())

    def test_bad_registry_writes_neither(self):
        for registry in ('registry-malformed.vdf', 'registry-ambiguous.vdf', 'binary.vdf'):
            with self.subTest(registry=registry):
                self.lay(registry=registry)
                before = self.snapshot()
                self.ask(B)
                self.assertIn('nothing changed', self.run_helper())
                self.assertEqual(before, self.snapshot())
                self.assertEqual([], self.backups())
                self.assertFalse(self.request.exists())

    def test_failed_list_write_puts_registry_back(self):
        self.lay()
        before = self.snapshot()
        real = os.replace

        def failing(src, dst, *a, **k):
            if str(dst).endswith('loginusers.vdf'):
                raise OSError('disk full')
            return real(src, dst, *a, **k)
        self.ask(B)
        with patch('os.replace', failing):
            self.assertIn('nothing changed', self.run_helper())
        self.assertEqual(before[self.users][0], self.users.read_bytes())
        self.assertEqual(before[self.registry][0], self.registry.read_bytes())
        self.assertEqual([], self.leftovers())
        self.assertFalse(self.last.exists())

    def test_symlinked_registry_keeps_its_link(self):
        self.lay(registry=None)
        real = self.home / 'registry-real.vdf'
        real.write_bytes(fixture('registry.vdf'))
        self.registry.symlink_to(real)
        self.ask(B)
        self.run_helper()
        self.assertTrue(self.registry.is_symlink())
        self.assertIn('bravo_acct', real.read_text())

    def test_internal_error_still_exits_zero(self):
        self.lay()
        self.ask(B)
        with patch.object(account, 'select', side_effect=RuntimeError('boom')):
            self.assertIn('skipped (RuntimeError)', self.run_helper())
        self.assertFalse(self.request.exists())


class CheckTest(Session):
    def switched(self, target=B):
        self.lay()
        self.ask(target)
        self.run_helper()
        self.out = ''

    def client_exit(self, recent, stamp=True):
        text = self.users.read_text()
        for uid in (A, B):
            flag = '1' if uid == recent else '0'
            text = re.sub(r'("%s"\s*\{[^}]*"MostRecent"\s*")[01]' % uid, r'\g<1>' + flag, text)
            if stamp and uid == recent:
                text = text.replace('"17000000%s"' % uid[-2:], '"1800000000"')
        self.users.write_text(text)

    def test_yes(self):
        self.switched()
        self.client_exit(B)
        self.assertIn('chosen account: yes', self.run_helper('check'))
        self.assertFalse(self.last.exists())

    def test_no(self):
        self.switched()
        self.client_exit(A)
        self.assertIn('chosen account: no', self.run_helper('check'))

    def test_unknown_when_client_did_not_rewrite(self):
        self.switched()
        self.assertIn('chosen account: unknown', self.run_helper('check'))

    def test_unknown_on_bad_file(self):
        self.switched()
        self.users.write_text('garbage {')
        self.assertIn('chosen account: unknown', self.run_helper('check'))

    def test_without_last_says_nothing(self):
        self.lay()
        self.assertEqual('', self.run_helper('check'))

    def test_check_writes_nothing(self):
        self.switched()
        self.client_exit(B)
        before = self.snapshot()
        listing = sorted(p for p in self.home.rglob('*'))
        self.run_helper('check')
        self.assertEqual(before, self.snapshot())
        self.assertEqual([p for p in listing if p != self.last], sorted(p for p in self.home.rglob('*')))


class SessionScriptTest(unittest.TestCase):
    def setUp(self):
        self.text = SESSION.read_text()
        self.lines = self.text.splitlines()

    def steam_branch(self):
        start = self.lines.index('  steam)')
        end = next(i for i in range(start + 1, len(self.lines)) if self.lines[i] == '    ;;')
        return start, end

    def test_apply_is_guarded_and_before_shortcuts(self):
        start, end = self.steam_branch()
        apply_at = next(i for i, l in enumerate(self.lines) if 'droiddeck-steam-account apply' in l)
        shortcuts_at = next(i for i, l in enumerate(self.lines) if 'droiddeck-steam-shortcuts' in l)
        self.assertTrue(start < apply_at < shortcuts_at < end)
        guard = ' '.join(self.lines[apply_at - 3:apply_at])
        self.assertIn('pgrep -f', guard)
        self.assertIn('[ -f "$HOME/.config/droiddeck/steam-account-next" ]', guard)
        self.assertIn('[ -x /usr/local/bin/droiddeck-steam-account ]', guard)
        self.assertIn('[ -z "${BL_DESKTOP:-}" ]', guard)
        self.assertTrue(self.lines[apply_at].rstrip().endswith('|| true'))
        self.assertEqual(1, self.text.count('droiddeck-steam-account apply'))

    def test_check_after_client_exit(self):
        exited = next(i for i, l in enumerate(self.lines) if 'echo "== steam exited rc=$rc"' in l)
        check_at = next(i for i, l in enumerate(self.lines) if 'droiddeck-steam-account check' in l)
        self.assertTrue(exited < check_at < exited + 6)
        self.assertIn('[ -z "${BL_DESKTOP:-}" ]', ' '.join(self.lines[exited:check_at]))
        self.assertTrue(self.lines[check_at].rstrip().endswith('|| true'))
        self.assertEqual(1, self.text.count('droiddeck-steam-account check'))

    def test_logs_never_collect_account_files(self):
        start = self.text.index('collect_steam_logs() {')
        body = self.text[start:self.text.index('\n}\n', start)]
        code = '\n'.join(l for l in body.splitlines() if not l.strip().startswith('#'))
        for name in ('loginusers', 'registry', 'droiddeck-prev', 'steam-account'):
            self.assertNotIn(name, code)

    def test_app_stages_the_helper(self):
        files = (TOOLS.parent / 'app/src/main/java/com/droiddeck/launcher/session/SessionFiles.kt').read_text()
        self.assertIn('"usr/local/bin/droiddeck-steam-account" to "usr/local/bin/droiddeck-steam-account"', files)
        self.assertTrue(os.access(BIN / 'droiddeck-steam-account', os.X_OK))

    def guarded_block(self):
        apply_at = next(i for i, l in enumerate(self.lines) if 'droiddeck-steam-account apply' in l)
        start = apply_at - 3
        self.assertTrue(self.lines[start].strip().startswith('if [ -z "${BL_DESKTOP:-}" ]'))
        return '\n'.join(self.lines[start:apply_at + 2]).replace('/usr/local/bin/droiddeck-steam-account', '"$HELPER"')

    def run_block(self, client_running, desktop=False):
        with tempfile.TemporaryDirectory() as temp:
            temp = Path(temp)
            stubs = temp / 'bin'
            stubs.mkdir()
            ran = temp / 'ran'
            helper = stubs / 'droiddeck-steam-account'
            helper.write_text('#!/bin/sh\necho "$@" > "%s"\n' % ran)
            pgrep = stubs / 'pgrep'
            pgrep.write_text('#!/bin/sh\nexit %d\n' % (0 if client_running else 1))
            for f in (helper, pgrep):
                f.chmod(0o755)
            home = temp / 'home'
            (home / '.config/droiddeck').mkdir(parents=True)
            (home / '.config/droiddeck/steam-account-next').write_text(B)
            env = {'PATH': '%s:/usr/bin:/bin' % stubs, 'HOME': str(home), 'HELPER': str(helper)}
            if desktop:
                env['BL_DESKTOP'] = '1'
            script = 'set -u\nsteam_root=/x\n' + self.guarded_block() + '\n'
            subprocess.run(['bash', '-c', script], env=env, check=True)
            return ran.read_text().strip() if ran.exists() else None

    def test_guard_skips_when_client_runs(self):
        self.assertIsNone(self.run_block(client_running=True))
        self.assertIsNone(self.run_block(client_running=False, desktop=True))
        self.assertEqual('apply /x', self.run_block(client_running=False))

    def test_helper_runs_as_a_script(self):
        with tempfile.TemporaryDirectory() as temp:
            home = Path(temp)
            steam = home / '.local/share/Steam'
            (steam / 'config').mkdir(parents=True)
            shutil.copy(FIXTURES / 'two.vdf', steam / 'config/loginusers.vdf')
            (home / '.config/droiddeck').mkdir(parents=True)
            (home / '.config/droiddeck/steam-account-next').write_text(B)
            done = subprocess.run([str(BIN / 'droiddeck-steam-account'), 'apply', str(steam)],
                                  env={'HOME': str(home), 'PATH': '/usr/bin:/bin'}, capture_output=True, text=True)
            self.assertEqual(0, done.returncode)
            self.assertEqual('== steam account: starting as account 2 of 2\n', done.stdout)
            self.assertEqual([B], recent_ids((steam / 'config/loginusers.vdf').read_text()))
            bad = subprocess.run([str(BIN / 'droiddeck-steam-account'), 'bogus'], capture_output=True)
            self.assertEqual(0, bad.returncode)


class KotlinCopiesTest(unittest.TestCase):
    def test_kotlin_fixtures_match_these(self):
        kt = (TOOLS.parent / 'app/src/test/java/com/droiddeck/launcher/session/SteamAccountsTest.kt').read_text()
        copies = dict(re.findall(r'"([\w.-]+\.vdf)" to """(.*?)"""', kt, re.S))
        self.assertGreaterEqual(len(copies), 10)
        for name, text in copies.items():
            self.assertEqual(fixture(name).decode(), text, name)

if __name__ == '__main__':
    unittest.main()
