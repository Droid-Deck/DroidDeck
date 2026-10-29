import contextlib
from pathlib import Path
import runpy
import subprocess
import tempfile
import unittest
from unittest import mock


BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
EA = runpy.run_path(str(BIN / "droiddeck-ea"))
EA_GLOBALS = EA["ensure_launcher"].__globals__
GAME_ENV = runpy.run_path(str(BIN / "droiddeck-game-env"))
START_GLOBALS = GAME_ENV["start"].__globals__

SCRIPT = r'''"evaluatorscript"
{
    // "Run Process" { "Commented" { "process 99" "EAappInstaller.exe" } }
    "0"
    {
        "appid" "1237970"
        "Run Process"
        {
            "EADesktopSetup"
            {
                "process 1" "%INSTALLDIR%\\__Installer\\Origin\\redist\\internal\\EAappInstaller.exe"
                "command 1" "EAX_LAUNCH_CLIENT=0 IGNORE_INSTALLED=1"
                "HasRunStringKey" "HKEY_LOCAL_MACHINE\\SOFTWARE\\Electronic Arts\\EA Desktop\\InstallSuccessful"
                "HasRunStringValue" "true"
                "RunType" "1"
            }
            "Unrelated"
            {
                "process 1" "%INSTALLDIR%\\not-EAappInstaller.exe"
                "command 1" "/passive EAX_LAUNCH_CLIENT=1"
            }
        }
        "Run Process On Uninstall"
        {
            "Cleanup" { "process 1" "%INSTALLDIR%\\uninstall\\EAappInstaller.exe" }
        }
        "kvsignatures" { "InstallScript" "original-signature" }
    }
}
'''


def installed_files(prefix, versioned=False):
    base = prefix / "drive_c/Program Files/Electronic Arts/EA Desktop"
    directory = base / "13.813.1.6327/EA Desktop" if versioned else base / "EA Desktop"
    directory.mkdir(parents=True, exist_ok=True)
    for name in ("EADesktop.exe", "EASteamLauncher.exe"):
        (directory / name).write_bytes(b"MZ")
    return directory


class EaProcessParserTest(unittest.TestCase):
    def test_only_exact_ea_processes_under_run_process_are_selected(self):
        self.assertEqual(EA["ea_processes"](SCRIPT), [
            r"%INSTALLDIR%\__Installer\Origin\redist\internal\EAappInstaller.exe",
        ])

    def test_nested_blocks_multiple_processes_and_case_are_supported(self):
        script = r'''"evaluator" { "0" { "InstallScript" {
            "rUn PrOcEsS" { "Setup" {
                "process 1" "other.exe"
                "PrOcEsS 2" "C:\\Install Dir\\EAAPPINSTALLER.EXE"
                "command 2" "/passive"
                "process 3" "EAappInstaller.exe"
            } }
        } } }'''
        self.assertEqual(EA["ea_processes"](script), [
            r"C:\Install Dir\EAAPPINSTALLER.EXE", "EAappInstaller.exe",
        ])

    def test_similar_names_commands_comments_and_uninstall_do_not_trigger_installation(self):
        script = r'''"process 1" "EAappInstaller.exe"
            "Run Process On Uninstall" { "Setup" { "process 1" "EAappInstaller.exe" } }
            "Other" { "process 1" "EAappInstaller.exe" }
            "Run Process" { "Setup" {
                // "process 1" "EAappInstaller.exe"
                "process 2" "EAappInstaller.exe.backup"
                "process 3" "not-EAappInstaller.exe"
                "process 4" "installer.exe" "command 4" "EAappInstaller.exe"
            } }'''
        self.assertEqual(EA["ea_processes"](script), [])


class EaLauncherTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.game = self.root / "game with spaces"
        self.compat = self.root / "compatdata/1237970"
        self.prefix = self.compat / "pfx"
        self.installer = self.game / "__Installer/Origin/redist/internal/EAappInstaller.exe"
        self.installer.parent.mkdir(parents=True)
        self.installer.write_bytes(b"MZ")
        self.env = {"STEAM_COMPAT_DATA_PATH": str(self.compat), "STEAM_COMPAT_INSTALL_PATH": str(self.game),
                    "STEAM_COMPAT_CLIENT_INSTALL_PATH": str(self.root),
                    "DISPLAY": ":0", "STEAM_GAME_DISPLAY_0": ":7", "CUSTOM": "retained"}
        self.proton = str(self.root / "chosen Proton/proton")
        self.uri = [self.proton, "waitforexitandrun", "steam2ea://launchgame/1237970"]
        self.source = self.root / "legacycompat/evaluatorscript_1237970.vdf"
        self.source.parent.mkdir()
        self.original = SCRIPT.replace("\n", "\r\n").encode("utf-8")
        self.source.write_bytes(self.original)
        self.evaluator = [self.proton, "run", str(self.source.parent / "iscriptevaluator.exe"),
                          r"legacycompat\evaluatorscript_1237970.vdf"]
        self.lock = mock.patch.dict(EA_GLOBALS, {"installation_lock": lambda prefix: contextlib.nullcontext()})
        self.lock.start()
        self.addCleanup(self.lock.stop)

    def install(self, command, **kwargs):
        installed_files(self.prefix)
        return subprocess.CompletedProcess(command, 0)

    def assert_installer_call(self, call, installer=None):
        args, kwargs = call
        self.assertEqual(args[0], [self.proton, "run", str(installer or self.installer),
                                  "/quiet", "/norestart", "EAX_LAUNCH_CLIENT=0", "IGNORE_INSTALLED=1"])
        self.assertEqual(kwargs["env"]["STEAM_COMPAT_DATA_PATH"], str(self.compat))
        self.assertEqual(kwargs["env"]["DISPLAY"], ":7")
        self.assertEqual(kwargs["env"]["CUSTOM"], "retained")
        self.assertFalse(kwargs["check"])

    def test_prepare_keeps_signed_evaluator_bytes_arguments_and_environment_unchanged(self):
        original_command, original_env = list(self.evaluator), dict(self.env)
        with mock.patch.object(EA_GLOBALS["subprocess"], "run", side_effect=self.install) as run, \
                mock.patch.object(tempfile, "NamedTemporaryFile") as temporary:
            with EA["prepare"](self.evaluator, self.env) as (command, env):
                self.assertIs(command, self.evaluator)
                self.assertIs(env, self.env)
                self.assertTrue(EA["launcher_installed"](self.prefix))
                self.assertEqual(self.source.read_bytes(), self.original)
            temporary.assert_not_called()
        self.assertEqual(run.call_count, 1)
        self.assert_installer_call(run.call_args)
        self.assertEqual(self.evaluator, original_command)
        self.assertEqual(self.env, original_env)
        self.assertEqual(self.source.read_bytes(), self.original)
        self.assertEqual(list(self.source.parent.iterdir()), [self.source])

    def test_installer_completes_before_original_evaluator_runs(self):
        record = mock.Mock()
        calls = []

        def launch(command, **kwargs):
            record.assert_not_called()
            calls.append(command)
            if len(calls) == 1:
                self.assertFalse(EA["launcher_installed"](self.prefix))
                return self.install(command, **kwargs)
            self.assertIs(command, self.evaluator)
            self.assertIs(kwargs["env"], self.env)
            self.assertTrue(EA["launcher_installed"](self.prefix))
            self.assertEqual(self.source.read_bytes(), self.original)
            return subprocess.CompletedProcess(command, 0)

        with mock.patch.object(START_GLOBALS["runpy"], "run_path", return_value=EA), \
                mock.patch.object(START_GLOBALS["subprocess"], "run", side_effect=launch) as run, \
                mock.patch.dict(START_GLOBALS, {"record_install_scripts": record}):
            self.assertEqual(GAME_ENV["start"](self.evaluator, self.env, "original-digest"), 0)
        self.assertEqual(run.call_count, 2)
        self.assert_installer_call(run.call_args_list[0])
        record.assert_called_once_with("original-digest", self.env)

    def test_only_successful_evaluator_records_the_original_digest(self):
        installed_files(self.prefix)
        for code in (0, 1):
            with self.subTest(code=code):
                record = mock.Mock()
                with mock.patch.object(START_GLOBALS["runpy"], "run_path", return_value=EA), \
                        mock.patch.object(START_GLOBALS["subprocess"], "run", return_value=
                                          subprocess.CompletedProcess(self.evaluator, code)) as run, \
                        mock.patch.dict(START_GLOBALS, {"record_install_scripts": record}):
                    self.assertEqual(GAME_ENV["start"](self.evaluator, self.env, "original-digest"), code)
                run.assert_called_once_with(self.evaluator, env=self.env, check=False)
                if code == 0:
                    record.assert_called_once_with("original-digest", self.env)
                else:
                    record.assert_not_called()
                self.assertEqual(self.source.read_bytes(), self.original)

    def test_evaluator_without_digest_executes_original_command_after_installation(self):
        def execute(executable, command, env):
            self.assertTrue(EA["launcher_installed"](self.prefix))
            self.assertEqual(executable, self.proton)
            self.assertIs(command, self.evaluator)
            self.assertIs(env, self.env)
            return 0

        record = mock.Mock()
        with mock.patch.object(START_GLOBALS["runpy"], "run_path", return_value=EA), \
                mock.patch.object(START_GLOBALS["subprocess"], "run", side_effect=self.install) as run, \
                mock.patch.object(START_GLOBALS["os"], "execvpe", side_effect=execute) as execvpe, \
                mock.patch.dict(START_GLOBALS, {"record_install_scripts": record}):
            self.assertEqual(GAME_ENV["start"](self.evaluator, self.env, None), 0)
        self.assertEqual(run.call_count, 1)
        self.assertEqual(execvpe.call_count, 1)
        record.assert_not_called()

    def test_installer_uses_game_display_only_when_available(self):
        for display in (None, "", ":7"):
            env = {"DISPLAY": ":0", "KEEP": "original"}
            if display is not None:
                env["STEAM_GAME_DISPLAY_0"] = display
            updated = EA["installer_environment"](env)
            self.assertEqual(updated["DISPLAY"], display or ":0")
            self.assertEqual(updated["KEEP"], "original")
            self.assertEqual(env["DISPLAY"], ":0")

    def test_normal_games_and_evaluators_without_ea_have_no_side_effects(self):
        script = b'"Run Process" { "Setup" { "process 1" "vcredist.exe" "command 1" "/q" } }'
        self.source.write_bytes(script)
        env = {key: value for key, value in self.env.items() if key != "STEAM_COMPAT_DATA_PATH"}
        commands = [[self.proton, "waitforexitandrun", "/game/Game.exe"], self.evaluator]
        with mock.patch.object(EA_GLOBALS["subprocess"], "run") as run, \
                mock.patch.object(tempfile, "NamedTemporaryFile") as temporary:
            for command in commands:
                with EA["prepare"](command, env) as (prepared, prepared_env):
                    self.assertIs(prepared, command)
                    self.assertIs(prepared_env, env)
            run.assert_not_called()
            temporary.assert_not_called()
        self.assertEqual(self.source.read_bytes(), script)
        self.assertFalse(self.compat.exists())

    def test_normal_game_start_does_not_load_ea_support(self):
        command = [self.proton, "waitforexitandrun", "/game/Game.exe"]
        with mock.patch.object(START_GLOBALS["runpy"], "run_path") as load, \
                mock.patch.object(START_GLOBALS["os"], "execvpe", return_value=0) as execute:
            self.assertEqual(GAME_ENV["start"](command, self.env, None), 0)
        load.assert_not_called()
        execute.assert_called_once_with(command[0], command, self.env)

    def test_stable_and_versioned_ea_installations_require_both_executables(self):
        for versioned in (False, True):
            with self.subTest(versioned=versioned):
                prefix = self.root / ("versioned" if versioned else "stable")
                directory = installed_files(prefix, versioned)
                self.assertTrue(EA["launcher_installed"](prefix))
                (directory / "EASteamLauncher.exe").unlink()
                self.assertFalse(EA["launcher_installed"](prefix))

    def test_already_installed_launcher_needs_no_installer_and_is_not_reinstalled(self):
        installed_files(self.prefix)
        self.installer.unlink()
        with mock.patch.object(EA_GLOBALS["subprocess"], "run") as run:
            for command in (self.uri, self.evaluator):
                with EA["prepare"](command, self.env) as (prepared, env):
                    self.assertIs(prepared, command)
                    self.assertIs(env, self.env)
            run.assert_not_called()

    def test_installation_is_rechecked_after_acquiring_lock(self):
        @contextlib.contextmanager
        def lock(prefix):
            self.assertEqual(prefix, self.prefix)
            installed_files(prefix)
            yield

        with mock.patch.dict(EA_GLOBALS, {"installation_lock": lock}), \
                mock.patch.object(EA_GLOBALS["subprocess"], "run") as run:
            EA["ensure_launcher"](self.uri, self.env)
        run.assert_not_called()

    def test_uri_recovery_uses_selected_proton_original_prefix_and_bundled_installer(self):
        with mock.patch.object(EA_GLOBALS["subprocess"], "run", side_effect=self.install) as run:
            with EA["prepare"](self.uri, self.env) as (command, env):
                self.assertIs(command, self.uri)
                self.assertIs(env, self.env)
        self.assert_installer_call(run.call_args)
        self.assertFalse((self.prefix / ".droiddeck-install-scripts").exists())

    def test_parsed_installdir_path_is_preferred_to_observed_bundled_path(self):
        installer = self.game / "alternate redist/EAappInstaller.exe"
        installer.parent.mkdir()
        installer.write_bytes(b"MZ")
        with mock.patch.object(EA_GLOBALS["subprocess"], "run", side_effect=self.install) as run:
            EA["ensure_launcher"](self.evaluator, self.env, [r"%INSTALLDIR%\alternate redist\EAappInstaller.exe"])
        self.assert_installer_call(run.call_args, installer)

    def test_parsed_exact_path_is_supported(self):
        installer = self.root / "alternate redist/EAappInstaller.exe"
        installer.parent.mkdir()
        installer.write_bytes(b"MZ")
        with mock.patch.object(EA_GLOBALS["subprocess"], "run", side_effect=self.install) as run:
            EA["ensure_launcher"](self.evaluator, self.env, [str(installer)])
        self.assert_installer_call(run.call_args, installer)

    def test_parsed_installdir_path_can_resolve_against_game_working_directory(self):
        env = {key: value for key, value in self.env.items() if key != "STEAM_COMPAT_INSTALL_PATH"}
        installer = self.game / "alternate redist/EAappInstaller.exe"
        installer.parent.mkdir()
        installer.write_bytes(b"MZ")
        with mock.patch.object(Path, "cwd", return_value=self.game), \
                mock.patch.object(EA_GLOBALS["subprocess"], "run", side_effect=self.install) as run:
            EA["ensure_launcher"](self.evaluator, env, [r"%INSTALLDIR%\alternate redist\EAappInstaller.exe"])
        self.assert_installer_call(run.call_args, installer)

    def test_uri_recovery_can_use_working_directory_and_accept_reboot_required(self):
        env = {key: value for key, value in self.env.items() if key != "STEAM_COMPAT_INSTALL_PATH"}

        def install(command, **kwargs):
            installed_files(self.prefix)
            return subprocess.CompletedProcess(command, 3010)

        with mock.patch.object(Path, "cwd", return_value=self.game), \
                mock.patch.object(EA_GLOBALS["subprocess"], "run", side_effect=install) as run:
            EA["ensure_launcher"](self.uri, env)
        self.assert_installer_call(run.call_args)

    def test_missing_installer_fails_without_launching_a_process(self):
        self.installer.unlink()
        with mock.patch.object(EA_GLOBALS["subprocess"], "run") as run:
            with self.assertRaisesRegex(RuntimeError, "EAappInstaller.exe was not found"):
                EA["ensure_launcher"](self.uri, self.env)
            run.assert_not_called()

    def test_failed_or_partial_install_blocks_evaluator_and_uri_without_success_record(self):
        for command in (self.evaluator, self.uri):
            for code, partial in ((1, False), (0, True)):
                with self.subTest(command=command, code=code, partial=partial):
                    def install(command, **kwargs):
                        if partial:
                            path = self.prefix / "drive_c/Program Files/Electronic Arts/EA Desktop/EA Desktop"
                            path.mkdir(parents=True, exist_ok=True)
                            (path / "EADesktop.exe").write_bytes(b"MZ")
                        return subprocess.CompletedProcess(command, code)

                    record = mock.Mock()
                    with mock.patch.object(START_GLOBALS["runpy"], "run_path", return_value=EA), \
                            mock.patch.object(EA_GLOBALS["subprocess"], "run", side_effect=install) as run, \
                            mock.patch.object(START_GLOBALS["os"], "execvpe") as execute, \
                            mock.patch.dict(START_GLOBALS, {"record_install_scripts": record}):
                        self.assertEqual(GAME_ENV["start"](command, self.env, "not-successful"), 1)
                    self.assertEqual(run.call_count, 1)
                    execute.assert_not_called()
                    record.assert_not_called()
                    self.assertFalse((self.prefix / ".droiddeck-install-scripts").exists())
                    self.assertEqual(self.source.read_bytes(), self.original)


if __name__ == "__main__":
    unittest.main()
