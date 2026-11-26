import json
import os
from pathlib import Path
import runpy
import tempfile
import unittest


BIN = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin'
MODULE = runpy.run_path(str(BIN / 'droiddeck-steam-lite'))
resolve = MODULE['resolve']
process_role = MODULE['process_role']
sample_processes = MODULE['sample_processes']
renderer_groups = MODULE['renderer_groups']
Actions = MODULE['Actions']
Client = MODULE['Client']
CdpError = MODULE['CdpError']
Supervisor = MODULE['Supervisor']
Pager = MODULE['Pager']
ZramHold = MODULE['ZramHold']
SETTLE = MODULE['SETTLE']
PRESSURE_HOLD = MODULE['PRESSURE_HOLD']
COOLDOWN = MODULE['COOLDOWN']
PASSES_PER_GAME = MODULE['PASSES_PER_GAME']

UI = 'https://steamloopback.host/routes/library/home'
SHARED = {'id': 's', 'title': 'SharedJSContext', 'type': 'page', 'url': 'https://steamloopback.host/index.html',
          'webSocketDebuggerUrl': 'ws://127.0.0.1:8080/devtools/page/s'}
BIG = {'id': 'b', 'title': 'Steam Big Picture Mode', 'type': 'page', 'url': UI,
       'webSocketDebuggerUrl': 'ws://127.0.0.1:8080/devtools/page/b'}
QAM = {'id': 'q', 'title': 'QuickAccess_uid2', 'type': 'page', 'url': 'https://steamloopback.host/routes/qam',
       'webSocketDebuggerUrl': 'ws://127.0.0.1:8080/devtools/page/q'}
STORE = {'id': 'w', 'title': 'Steam Store', 'type': 'page', 'url': 'https://store.steampowered.com/',
         'webSocketDebuggerUrl': 'ws://127.0.0.1:8080/devtools/page/w'}
TARGETS = [SHARED, BIG, QAM, STORE]


class AgentError(Exception):
    pass


class FakeAgent:
    AgentError = AgentError

    def __init__(self, targets=None, fail=None):
        self.list = list(TARGETS if targets is None else targets)
        self.fail = fail

    def cdp_targets(self, agent_dir, timeout=5):
        if self.fail:
            raise AgentError(self.fail)
        return self.list + [{'id': 'x', 'type': 'service_worker', 'webSocketDebuggerUrl': 'ws://127.0.0.1:8080/x'}]

    def cdp_port(self, agent_dir):
        return 8080


class FakeSession:
    def __init__(self, url, log, unsupported=(), broken=()):
        self.url = url
        self.log = log
        self.unsupported = unsupported
        self.broken = broken
        self.closed = False

    def call(self, method, params=None):
        self.log.append((self.url.rsplit('/', 1)[-1], method, params or {}))
        if method in self.unsupported:
            raise CdpError("%s: 'Memory.simulatePressureNotification' wasn't found" % method, -32601)
        if method in self.broken:
            raise OSError('DevTools closed the connection')
        return {}

    def close(self):
        self.closed = True


def client(log, agent=None, unsupported=(), broken=()):
    return Client(agent or FakeAgent(), '/agent', opener=lambda url: FakeSession(url, log, unsupported, broken))


class ResolveTest(unittest.TestCase):
    def test_stock_by_default(self):
        self.assertEqual(resolve('', ''), (0, set()))
        self.assertEqual(resolve('off', ''), (0, set()))

    def test_levels_are_cumulative(self):
        self.assertEqual(resolve('1', '')[1], {'gc', 'moderate'})
        self.assertEqual(resolve('balanced', '')[1], {'gc', 'moderate', 'pageout'})
        self.assertEqual(resolve('3', '')[1], {'gc', 'moderate', 'pageout', 'critical', 'freeze'})

    def test_switches_toggle_single_interventions(self):
        self.assertEqual(resolve('0', '+gc')[1], {'gc'})
        self.assertEqual(resolve('3', '-freeze,-pageout +pause')[1], {'gc', 'moderate', 'critical', 'pause'})
        self.assertEqual(resolve('3', '+motion')[1], resolve('3', '')[1])

    def test_unknown_values_fall_back_to_stock(self):
        self.assertEqual(resolve('turbo', '+warp'), (0, set()))


class RoleTest(unittest.TestCase):
    def test_web_helper_processes(self):
        helper = '/home/u/.local/share/Steam/ubuntu12_64/steamwebhelper'
        self.assertEqual(process_role([helper, '-lang=en']), 'browser')
        self.assertEqual(process_role([helper, '--type=renderer']), 'renderer')
        self.assertEqual(process_role([helper, '--type=utility', '--utility-sub-type=network.mojom.NetworkService']),
                         'utility:NetworkService')
        self.assertEqual(process_role(['/usr/bin/FEXInterpreter', helper, '--type=gpu-process']), 'gpu-process')
        self.assertEqual(process_role(['/home/u/.local/share/Steam/ubuntu12_32/steam', '-gamepadui']), 'client')

    def test_other_chromium_apps_are_not_counted(self):
        self.assertIsNone(process_role(['/opt/google/chrome/chrome', '--type=renderer']))
        self.assertIsNone(process_role(['/usr/lib/heroic/heroic', '--type=renderer']))
        self.assertIsNone(process_role([]))

    def test_the_supervisor_is_its_own_role(self):
        self.assertEqual(process_role(['/usr/bin/python3', '/usr/local/bin/droiddeck-steam-lite', 'run']), 'steam-lite')
        self.assertIsNone(process_role(['/usr/bin/python3', '/usr/local/bin/droiddeck-steam-lite', 'probe']))


class SampleTest(unittest.TestCase):
    def test_pss_from_smaps_rollup_and_unreadable_counters(self):
        with tempfile.TemporaryDirectory() as temp:
            proc = Path(temp)
            for pid, argv, smaps in (
                    (10, ['/s/steamwebhelper'], 'Rss: 300 kB\nPss: 200 kB\nPss_Anon: 150 kB\nSwapPss: 40 kB\n'),
                    (11, ['/s/steamwebhelper', '--type=renderer'], None),
                    (12, ['/usr/bin/bash'], 'Pss: 5 kB\n')):
                (proc / str(pid)).mkdir()
                (proc / str(pid) / 'cmdline').write_bytes(b'\0'.join(a.encode() for a in argv) + b'\0')
                (proc / str(pid) / 'stat').write_text(
                    '%d (x y) S 1 1 1 0 -1 0 0 0 0 0 7 3 0 0 20 0 12 0 0' % pid)
                (proc / str(pid) / 'status').write_text('VmRSS:\t300 kB\nVmSwap:\t50 kB\n')
                if smaps:
                    (proc / str(pid) / 'smaps_rollup').write_text(smaps)
            rows = {r['pid']: r for r in sample_processes(proc, me=-1)}
        self.assertEqual(set(rows), {10, 11})
        self.assertEqual((rows[10]['role'], rows[10]['Pss'], rows[10]['SwapPss'], rows[10]['ticks']),
                         ('browser', 200, 40, 10))
        self.assertEqual(rows[10]['threads'], 12)
        self.assertIsNone(rows[11]['Pss'])
        self.assertEqual(rows[11]['VmSwap'], 50)


class ActionsTest(unittest.TestCase):
    def setUp(self):
        self.log = []
        self.client = client(self.log)
        self.actions = Actions(self.client, clock=lambda: 0.0)

    def test_one_gc_for_the_client_renderer_and_one_per_other_page(self):
        self.assertEqual([t['id'] for t in renderer_groups(TARGETS)], ['s', 'w'])
        self.actions.gc(TARGETS)
        self.assertEqual(self.log, [('s', 'HeapProfiler.collectGarbage', {}),
                                    ('w', 'HeapProfiler.collectGarbage', {})])

    def test_pause_leaves_the_shared_context_alone_and_resume_undoes_it(self):
        self.actions.pause(TARGETS)
        paused = [(t, p) for t, m, p in self.log if m == 'Animation.setPlaybackRate']
        self.assertEqual(paused, [('b', {'playbackRate': 0}), ('q', {'playbackRate': 0})])
        self.assertTrue(self.actions.holding())
        self.log.clear()
        self.actions.restore(TARGETS)
        self.assertIn(('b', 'Animation.setPlaybackRate', {'playbackRate': 1}), self.log)
        self.assertFalse(self.actions.holding())

    def test_freeze_only_touches_pages_outside_the_client(self):
        self.actions.freeze(TARGETS)
        self.assertEqual(self.log, [('w', 'Page.setWebLifecycleState', {'state': 'frozen'})])
        self.log.clear()
        self.actions.restore(TARGETS)
        self.assertEqual(self.log, [('w', 'Page.setWebLifecycleState', {'state': 'active'})])

    def test_missing_method_is_remembered_and_not_retried(self):
        log = []
        c = client(log, unsupported=('Memory.simulatePressureNotification',))
        actions = Actions(c, clock=lambda: 0.0)
        first = actions.pressure(TARGETS, 'moderate')
        self.assertTrue(all(str(v).startswith('error') for v in first.values()))
        self.assertIn('Memory.simulatePressureNotification', c.unsupported)
        log.clear()
        actions.pressure(TARGETS, 'moderate')
        self.assertEqual(log, [])

    def test_a_dropped_connection_is_reopened_next_call(self):
        log = []
        c = client(log, broken=('HeapProfiler.collectGarbage',))
        actions = Actions(c, clock=lambda: 0.0)
        out = actions.gc([SHARED])
        self.assertTrue(out['SharedJSContext'].startswith('error'))
        self.assertNotIn('s', c.sessions)
        self.assertNotIn('HeapProfiler.collectGarbage', c.unsupported)

    def test_browser_pressure_goes_to_the_browser_target(self):
        browser = {'id': 'browser', 'title': 'browser', 'webSocketDebuggerUrl': 'ws://127.0.0.1:8080/devtools/browser/z'}
        self.client.browser = lambda: browser
        self.actions.browser_pressure('critical')
        self.assertEqual(self.log, [('z', 'Memory.simulatePressureNotification', {'level': 'critical'})])

    def test_browser_pressure_without_a_browser_target_changes_nothing(self):
        def missing():
            raise CdpError('no browser target')
        self.client.browser = missing
        self.assertTrue(self.actions.browser_pressure('critical')['browser'].startswith('error'))
        self.assertEqual(self.log, [])

    def test_about_blank_popups_are_client_windows(self):
        popup = dict(QAM, url='about:blank?browserviewpopup=1&requestid=2&parentpopup=2')
        self.actions.freeze([SHARED, popup, STORE])
        self.assertEqual([t for t, _, _ in self.log], ['w'])

    def test_a_failed_thaw_stays_held_for_the_next_restore(self):
        log = []
        c = client(log, broken=('Page.setWebLifecycleState',))
        actions = Actions(c, clock=lambda: 0.0)
        actions.frozen = {'w'}
        actions.thaw(TARGETS)
        self.assertEqual(actions.frozen, {'w'})
        self.assertTrue(actions.holding())

    def test_a_closed_target_is_no_longer_held(self):
        self.actions.frozen = {'gone'}
        self.actions.thaw(TARGETS)
        self.assertEqual(self.actions.frozen, set())

    def test_calls_past_the_deadline_are_skipped(self):
        self.actions.deadline = -1.0
        out = self.actions.gc(TARGETS)
        self.assertEqual(self.log, [])
        self.assertTrue(all(str(v).startswith('skipped') for v in out.values()))

    def test_only_page_targets_are_listed(self):
        self.assertEqual([t['id'] for t in self.client.targets()], ['s', 'b', 'q', 'w'])


class FakeHold:
    entered = 0

    def __enter__(self):
        FakeHold.entered += 1
        return self

    def __exit__(self, *_):
        pass


class FakePager:
    def __init__(self):
        self.out_kb = 0
        self.calls = []

    def pageout(self, budget, timeout=None):
        self.calls.append(('pageout', budget))
        self.out_kb = 100 * 1024
        return {'kB': self.out_kb}

    def readback(self):
        self.calls.append(('readback',))
        self.out_kb = 0
        return {'after kB': 0}


class SupervisorTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.dir = Path(temp.name)
        self.now = 100.0
        self.available = 4 << 20
        self.swapped = 0
        self.log = []
        self.client = client(self.log)
        self.actions = Actions(self.client, clock=lambda: self.now)
        self.targets = lambda: TARGETS
        self.seq = 0
        self.pager = FakePager()
        FakeHold.entered = 0

    def make(self, level='3', switches=''):
        return Supervisor(self.dir, self.actions, resolve(level, switches)[1], clock=lambda: self.now,
                          memory=lambda: {'MemAvailable': self.available, 'MemTotal': 8 << 20},
                          client_swap=lambda: self.swapped, hold=FakeHold, pager=self.pager, nap=lambda s: None)

    def focus(self, app, apps=()):
        self.seq += 1
        path = self.dir / 'focus.json'
        path.write_text(json.dumps({'focusedApp': app, 'focusableApps': list(apps), 'seq': self.seq}))
        os.utime(path, ns=(self.seq * 10 ** 9, self.seq * 10 ** 9))

    def methods(self):
        return [m for _, m, _ in self.log]

    def test_nothing_runs_while_the_client_is_in_front(self):
        sup = self.make()
        self.focus(769, [769])
        for _ in range(5):
            sup.tick(self.targets)
            self.now += 10
        self.assertEqual(sup.state, 'ui')
        self.assertEqual(self.log, [])

    def test_one_enter_pass_after_the_game_settles(self):
        sup = self.make('2', '+pause')
        self.focus(769, [769, 1234])
        sup.tick(self.targets)
        self.assertEqual(sup.state, 'launching')
        self.focus(1234, [769, 1234])
        sup.tick(self.targets)
        self.assertEqual((sup.state, self.log), ('game', []))
        self.now += SETTLE
        sup.tick(self.targets)
        self.assertEqual(self.methods().count('HeapProfiler.collectGarbage'), 2)
        self.assertIn('Animation.setPlaybackRate', self.methods())
        self.assertNotIn('Page.setWebLifecycleState', self.methods())
        self.assertEqual(FakeHold.entered, 1)
        self.log.clear()
        for _ in range(10):
            self.now += 10
            sup.tick(self.targets)
        self.assertEqual(self.log, [])

    def test_the_overlay_restores_at_once(self):
        sup = self.make('3', '+pause')
        self.focus(1234, [769, 1234])
        sup.tick(self.targets)
        self.now += SETTLE
        sup.tick(self.targets)
        self.assertTrue(self.actions.holding())
        self.log.clear()
        self.focus(769, [769, 1234])
        sup.tick(self.targets)
        self.assertEqual(sup.state, 'back')
        self.assertFalse(self.actions.holding())
        self.assertIn(('w', 'Page.setWebLifecycleState', {'state': 'active'}), self.log)
        self.assertIn(('b', 'Animation.setPlaybackRate', {'playbackRate': 1}), self.log)
        self.focus(1234, [769, 1234])
        sup.tick(self.targets)
        self.now += SETTLE
        self.log.clear()
        sup.tick(self.targets)
        self.assertIn('Animation.setPlaybackRate', self.methods())

    def test_a_pass_stops_when_the_client_comes_to_the_front(self):
        sup = self.make('3')
        self.focus(1234, [769, 1234])
        sup.tick(self.targets)
        self.now += SETTLE

        def gc_then_overlay(targets):
            self.focus(769, [769, 1234])
            return {}
        self.actions.gc = gc_then_overlay
        sup.tick(self.targets)
        self.assertIn('cancelled', sup.history[-1]['outcome'])
        self.assertFalse(self.actions.holding())
        self.assertNotIn('Memory.simulatePressureNotification', self.methods())

    def test_sustained_pressure_repeats_with_cooldown_and_a_cap(self):
        sup = self.make('1')
        self.focus(1234, [769, 1234])
        sup.tick(self.targets)
        self.now += SETTLE
        sup.tick(self.targets)
        self.log.clear()
        self.available = 300 << 10
        passes = 0
        for _ in range(int((COOLDOWN * (PASSES_PER_GAME + 3)) // 5)):
            self.now += 5
            sup.tick(self.targets)
        passes = self.methods().count('HeapProfiler.collectGarbage') // 2
        self.assertEqual(passes, PASSES_PER_GAME - 1)
        self.assertIn(('s', 'Memory.simulatePressureNotification', {'level': 'moderate'}), self.log)

    def test_brief_dips_do_not_trigger(self):
        sup = self.make('1')
        self.focus(1234, [769, 1234])
        sup.tick(self.targets)
        self.now += SETTLE
        sup.tick(self.targets)
        self.log.clear()
        for _ in range(20):
            self.available = 300 << 10
            self.now += PRESSURE_HOLD / 2
            sup.tick(self.targets)
            self.available = 4 << 20
            self.now += 1
            sup.tick(self.targets)
        self.assertEqual(self.log, [])

    def test_no_reclaim_once_the_client_is_in_zram(self):
        sup = self.make('3', '+pause')
        self.swapped = 200 * 1024
        self.focus(1234, [769, 1234])
        sup.tick(self.targets)
        self.now += SETTLE
        sup.tick(self.targets)
        self.assertNotIn('HeapProfiler.collectGarbage', self.methods())
        self.assertNotIn('Memory.simulatePressureNotification', self.methods())
        self.assertIn('Animation.setPlaybackRate', self.methods())

    def test_runtime_control_file_switches_to_stock_and_restores(self):
        sup = self.make('3')
        self.focus(1234, [769, 1234])
        sup.tick(self.targets)
        self.now += SETTLE
        sup.tick(self.targets)
        self.assertTrue(self.actions.holding())
        (self.dir / 'steam-lite.json').write_text(json.dumps({'level': 'stock'}))
        sup.tick(self.targets)
        self.assertEqual(sup.on, set())
        self.assertFalse(self.actions.holding())

    def test_no_devtools_keeps_stock(self):
        log = []
        dead = client(log, agent=FakeAgent(fail='DevTools unreachable'))
        actions = Actions(dead, clock=lambda: self.now)
        sup = Supervisor(self.dir, actions, resolve('3')[1], clock=lambda: self.now,
                         memory=lambda: {}, client_swap=lambda: 0, hold=FakeHold, pager=self.pager, nap=lambda s: None)
        self.focus(1234, [769, 1234])
        sup.tick(dead.targets)
        self.now += SETTLE
        sup.tick(dead.targets)
        self.assertIn('error', sup.history[-1]['outcome'])
        self.assertEqual(log, [])
        self.assertEqual(json.loads((self.dir / 'steam-lite-status.json').read_text())['state'], 'game')

    def test_pageout_follows_the_gc_and_reads_back_when_the_client_returns(self):
        sup = self.make('2')
        self.focus(1234, [769, 1234])
        sup.tick(self.targets)
        self.now += SETTLE
        sup.tick(self.targets)
        self.assertEqual(self.pager.calls, [('pageout', MODULE['PAGEOUT_BUDGET_KB'])])
        self.assertIn('pageout', sup.history[-1]['outcome'])
        self.focus(769, [769, 1234])
        sup.tick(self.targets)
        self.assertEqual(self.pager.calls[-1], ('readback',))
        self.assertEqual(self.pager.out_kb, 0)

    def test_closing_the_overlay_does_not_repeat_reclaim_within_the_cooldown(self):
        sup = self.make('2', '+pause')
        self.focus(1234, [769, 1234])
        sup.tick(self.targets)
        self.now += SETTLE
        sup.tick(self.targets)
        for _ in range(3):
            self.focus(769, [769, 1234])
            sup.tick(self.targets)
            self.focus(1234, [769, 1234])
            sup.tick(self.targets)
            self.now += SETTLE
            sup.tick(self.targets)
        self.assertEqual(self.methods().count('HeapProfiler.collectGarbage'), 2)
        self.assertEqual([c[0] for c in self.pager.calls], ['pageout', 'readback'])
        self.assertEqual(sum(1 for t, m, p in self.log if m == 'Animation.setPlaybackRate' and p == {'playbackRate': 0}),
                         4 * 2)
        self.now += COOLDOWN
        self.focus(769, [769, 1234])
        sup.tick(self.targets)
        self.focus(1234, [769, 1234])
        sup.tick(self.targets)
        self.now += SETTLE
        sup.tick(self.targets)
        self.assertEqual(self.methods().count('HeapProfiler.collectGarbage'), 4)

    def test_no_pageout_at_level_one(self):
        sup = self.make('1')
        self.focus(1234, [769, 1234])
        sup.tick(self.targets)
        self.now += SETTLE
        sup.tick(self.targets)
        self.assertEqual(self.pager.calls, [])

    def test_unreadable_focus_cancels_a_pass(self):
        sup = self.make('3', '+pause')
        self.focus(1234, [769, 1234])
        sup.tick(self.targets)
        self.now += SETTLE

        def gc_then_lose_focus(targets):
            (self.dir / 'focus.json').unlink()
            return {}
        self.actions.gc = gc_then_lose_focus
        sup.tick(self.targets)
        self.assertIn('cancelled', sup.history[-1]['outcome'])
        self.assertNotIn('Animation.setPlaybackRate', self.methods())

    def test_game_exit_returns_to_ui(self):
        sup = self.make('2')
        self.focus(1234, [769, 1234])
        sup.tick(self.targets)
        self.now += SETTLE
        sup.tick(self.targets)
        self.focus(769, [769])
        sup.tick(self.targets)
        self.assertEqual(sup.state, 'ui')
        self.assertFalse(self.actions.holding())


class PagerTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.dir = Path(temp.name)
        self.hold = self.dir / '.dd-zram.hold'
        self.proc = self.dir / 'proc'
        for pid in (10, 11):
            (self.proc / str(pid)).mkdir(parents=True)
            (self.proc / str(pid) / 'status').write_text('VmSwap:\t0 kB\n')
        self.signalled = []
        self.seen = []

    def pager(self, answer):
        def signal_pid(pid):
            self.signalled.append(pid)
            self.seen.append(self.hold.read_text())
            answer(pid)
        return Pager(self.hold, self.proc, nap=lambda s: None, signal_pid=signal_pid, pids=lambda: [10, 11])

    def test_pageout_sends_a_cold_request_and_sums_the_answers(self):
        pager = self.pager(lambda pid: (self.dir / ('.dd-zram.trim.%d' % pid)).write_text(str(pid * 1000)))
        out = pager.pageout(4096)
        self.assertEqual(out, {'kB': 21000, 'processes': 2, 'answered': 2})
        self.assertEqual(self.seen, ['2 4096', '2 4096'])
        self.assertFalse(self.hold.exists())
        self.assertEqual(list(self.dir.glob('.dd-zram.trim.*')), [])
        self.assertEqual(pager.out_kb, 21000)

    def test_pageout_gives_up_on_silent_processes(self):
        clock = iter(range(100))
        pager = self.pager(lambda pid: None)
        pager.clock = lambda: next(clock)
        out = pager.pageout(4096, timeout=3)
        self.assertEqual((out['kB'], out['answered']), (0, 0))
        self.assertFalse(self.hold.exists())

    def test_pageout_waits_for_a_hold_someone_else_has(self):
        self.hold.write_text('1')
        out = self.pager(lambda pid: None).pageout(4096)
        self.assertIn('skipped', out)
        self.assertEqual((self.signalled, self.hold.read_text()), ([], '1'))

    def test_an_incomplete_readback_stays_pending_until_the_retry_cap(self):
        for pid in (10, 11):
            (self.proc / str(pid) / 'status').write_text('VmSwap:\t8192 kB\n')
        pager = self.pager(lambda pid: None)
        pager.out_kb = 16384
        for attempt in range(MODULE['READBACK_ATTEMPTS'] - 1):
            pager.readback()
            self.assertEqual(pager.out_kb, 16384)
        pager.readback()
        self.assertEqual(pager.out_kb, 0)

    def test_readback_asks_for_everything_back(self):
        def swapped_then_back(pid):
            (self.proc / str(pid) / 'status').write_text('VmSwap:\t0 kB\n')
        for pid in (10, 11):
            (self.proc / str(pid) / 'status').write_text('VmSwap:\t8192 kB\n')
        pager = self.pager(swapped_then_back)
        pager.out_kb = 16384
        out = pager.readback()
        self.assertEqual(out, {'before kB': 16384, 'after kB': 0})
        self.assertEqual(self.seen, ['0', '0'])
        self.assertEqual(pager.out_kb, 0)
        self.assertFalse(self.hold.exists())


class ZramHoldTest(unittest.TestCase):
    def test_hold_is_removed_only_when_it_is_ours(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / '.dd-zram.hold'
            with ZramHold(path) as hold:
                self.assertTrue(hold.mine and path.exists())
            self.assertFalse(path.exists())
            path.write_text('someone else')
            with ZramHold(path) as hold:
                self.assertFalse(hold.mine)
            self.assertTrue(path.exists())
            path.unlink()

    def test_a_suspend_request_written_over_our_hold_is_left_to_the_app(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / '.dd-zram.hold'
            with ZramHold(path) as hold:
                self.assertEqual(path.read_bytes(), b'')
                path.write_text('1')
            self.assertEqual(path.read_text(), '1')


if __name__ == '__main__':
    unittest.main()
