import json
from pathlib import Path
import random
import runpy
import tempfile
import unittest
from unittest import mock


BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
COMPAT = runpy.run_path(str(BIN / "steam-compatibility"))


def config_text(entries):
    return ('"Before" { "9" { "name" "outside" } } '
            '"InstallConfigStore" { "Software" { "Valve" { "Steam" { '
            '"CompatToolMapping" { ' + entries +
            ' } "After" { "9" { "name" "outside" } } } } } }')


class CountedTokens(list):
    def __init__(self, tokens):
        super().__init__(tokens)
        self.visited = 0

    def __getitem__(self, key):
        self.visited += len(range(*key.indices(len(self)))) if isinstance(key, slice) else 1
        return super().__getitem__(key)


class MappingBatchTest(unittest.TestCase):
    def assert_batch(self, entries, changes):
        original = COMPAT["tokenize"](config_text(entries))
        mapping = COMPAT["mapping_block"](original)
        expected, actual = list(original), list(original)
        for app, tool in changes.items():
            COMPAT["set_mapping"](expected, mapping, app, tool)
        COMPAT["set_mappings"](actual, mapping, changes)
        self.assertEqual(actual, expected)
        self.assertEqual(COMPAT["serialize"](actual), COMPAT["serialize"](expected))
        return actual, mapping

    def test_existing_fields_and_default_priority_are_preserved(self):
        entries = ('"0" { "name" "old" "priority" "123" "config" "custom" } '
                   '"1" { "name" "old" "priority" "17" "extra" { "keep" "yes" } }')
        actual, _ = self.assert_batch(entries, {"0": "New Default", "1": "New Game"})
        expected = entries.replace('"old"', '"New Default"', 1).replace('"old"', '"New Game"', 1)
        self.assertEqual(actual, COMPAT["tokenize"](config_text(expected)))

    def test_missing_names_are_inserted_without_replacing_other_fields(self):
        entries = '"1" { "config" "custom" "priority" "17" } "2" { "NAME" "kept" }'
        actual, _ = self.assert_batch(entries, {"1": "new", "2": ""})
        expected = ('"1" { "name" "new" "config" "custom" "priority" "17" } '
                    '"2" { "name" "" "NAME" "kept" }')
        self.assertEqual(actual, COMPAT["tokenize"](config_text(expected)))

    def test_new_entries_keep_reverse_insertion_order_and_priorities(self):
        actual, mapping = self.assert_batch('"1" { "name" "old" }',
                                            {"4": "four", "0": "default", "9": "nine", "8": None, "1": "one"})
        expected = COMPAT["tokenize"](
            '"9" { "name" "nine" "config" "" "priority" "250" } '
            '"0" { "name" "default" "config" "" "priority" "75" } '
            '"4" { "name" "four" "config" "" "priority" "250" }')
        self.assertEqual(actual[mapping + 1:mapping + 1 + len(expected)], expected)

    def test_deletion_and_absent_deletion_preserve_neighbor_sections(self):
        entries = '"layout" "keep" "1" { "name" "old" } "2" { "name" "kept" }'
        actual, _ = self.assert_batch(entries, {"1": None, "9": None})
        self.assertEqual(actual, COMPAT["tokenize"](config_text('"layout" "keep" "2" { "name" "kept" }')))

    def test_duplicate_app_entries_change_only_the_first_match(self):
        entries = '"1" { "name" "first" } "1" { "name" "second" } "2" { "name" "other" }'
        for tool in (None, "new"):
            with self.subTest(tool=tool):
                actual, _ = self.assert_batch(entries, {"1": tool, "2": "updated"})
                first = '' if tool is None else '"1" { "name" "new" } '
                expected = first + '"1" { "name" "second" } "2" { "name" "updated" }'
                self.assertEqual(actual, COMPAT["tokenize"](config_text(expected)))

    def test_nested_and_duplicate_name_fields_keep_first_match_semantics(self):
        entries = ('"1" { "extra" { "name" "nested" } "name" "outer" } '
                   '"2" { "name" "first" "name" "second" }')
        actual, _ = self.assert_batch(entries, {"1": "new", "2": "new"})
        expected = entries.replace('"nested"', '"new"').replace('"first"', '"new"')
        self.assertEqual(actual, COMPAT["tokenize"](config_text(expected)))

    def test_case_aliases_and_quoted_aliases_keep_sequential_semantics(self):
        for entries, changes in (
                ('"App" { "name" "first" } "app" { "name" "second" }', {"App": None, "app": "new"}),
                ('', {"App": "first", "app": "second"}),
                ('', {0: "first", "0": "second"})):
            with self.subTest(entries=entries, changes=changes):
                self.assert_batch(entries, changes)

    def test_an_empty_batch_does_not_read_or_mutate_tokens(self):
        tokens = CountedTokens(COMPAT["tokenize"](config_text('"1" { "name" "old" }')))
        mapping = COMPAT["mapping_block"](tokens)
        tokens.visited = 0
        before = list(tokens)
        COMPAT["set_mappings"](tokens, mapping, {})
        self.assertEqual(tokens, before)
        self.assertEqual(tokens.visited, 0)

    def test_non_string_keys_keep_the_single_writers_error_behavior(self):
        for changes in ({1: "new", "2": "new"}, {"2": "new", 1: "new"}):
            with self.subTest(changes=changes):
                tokens = COMPAT["tokenize"](config_text('"1" { "name" "old" } "2" { "name" "old" }'))
                mapping = COMPAT["mapping_block"](tokens)
                expected, actual = list(tokens), list(tokens)
                with self.assertRaises(AttributeError):
                    for app, tool in changes.items():
                        COMPAT["set_mapping"](expected, mapping, app, tool)
                with self.assertRaises(AttributeError):
                    COMPAT["set_mappings"](actual, mapping, changes)
                self.assertEqual(actual, expected)

    def test_single_changes_use_the_existing_writer(self):
        globals_ = COMPAT["set_mappings"].__globals__
        for changes in ({"1": "new"}, {"1": None}, {"2": "new"}):
            with self.subTest(changes=changes):
                tokens = COMPAT["tokenize"](config_text('"1" { "name" "old" }'))
                mapping = COMPAT["mapping_block"](tokens)
                with mock.patch.dict(globals_, set_mapping=mock.Mock(wraps=COMPAT["set_mapping"])):
                    COMPAT["set_mappings"](tokens, mapping, changes)
                    globals_["set_mapping"].assert_called_once_with(tokens, mapping, *next(iter(changes.items())))

    def test_bulk_work_is_linear_for_updates_insertions_and_deletions(self):
        for count in (128, 512):
            for kind in ("update", "insert", "delete", "missing-name"):
                with self.subTest(count=count, kind=kind):
                    field = '"config" "kept"' if kind == "missing-name" else '"name" "old"'
                    entries = "".join('"%s" { %s }' % (app, field) for app in range(count))
                    tokens = CountedTokens(COMPAT["tokenize"](config_text(entries)))
                    mapping = COMPAT["mapping_block"](tokens)
                    start = count if kind == "insert" else 0
                    changes = {str(app): None if kind == "delete" else "new" for app in range(start, start + count)}
                    tokens.visited = 0
                    size = len(tokens)
                    COMPAT["set_mappings"](tokens, mapping, changes)
                    self.assertLessEqual(tokens.visited, 8 * size + 8 * count)

    def test_a_batch_stops_after_its_last_matching_entry(self):
        entries = "".join('"%s" { "name" "old" }' % app for app in range(512))
        tokens = CountedTokens(COMPAT["tokenize"](config_text(entries)))
        mapping = COMPAT["mapping_block"](tokens)
        tokens.visited = 0
        COMPAT["set_mappings"](tokens, mapping, {"0": "new", "1": "new"})
        self.assertLess(tokens.visited, 100)

    def test_mixed_batches_match_the_single_writer(self):
        random_ = random.Random(1748)
        fields = (
            '"name" "old" "config" "" "priority" "250"',
            '"priority" "17"',
            '"name" "" "name" "second"',
            '"extra" { "name" "nested" } "name" "outer"',
            '"NAME" "keep" "extra" { "1" { "name" "nested" } }',
            '',
            r'"name" "old" "config" "quoted \"value\" and \\path"',
        )
        for case in range(512):
            entries = '"layout" "keep" ' + "".join(
                '"%s" { %s }' % (random_.randrange(8), random_.choice(fields))
                for _ in range(random_.randrange(12)))
            apps = random_.sample(range(12), random_.randrange(13))
            changes = {str(app): random_.choice((None, "", "New Tool", r"new\\tool")) for app in apps}
            with self.subTest(case=case):
                self.assert_batch(entries, changes)


class MappingBatchIntegrationTest(unittest.TestCase):
    def register(self, replace):
        old = {"name": "DroidDeck-Proton-11-11.2", "dir": "DroidDeck-Proton 11-11.2", "valve": False}
        new = {"name": "DroidDeck-Proton-11-11.4", "dir": "DroidDeck-Proton 11-11.4", "valve": False}
        valve = {"name": COMPAT["TOOL"], "dir": "Proton - Experimental (ARM64)", "valve": True}
        before = old if replace else valve
        apps = [str(app) for app in range(1, 65)]
        with tempfile.TemporaryDirectory() as tmp:
            state = Path(tmp) / "state"
            state.mkdir()
            (state / "state.json").write_text(json.dumps({
                "default": before["name"], "own_proton": True, "tools": [valve, before],
                "auto": {app: before["name"] for app in apps},
            }))
            config = Path(tmp) / "Steam/config/config.vdf"
            config.parent.mkdir(parents=True)
            config.write_text(config_text("".join(
                '"%s" { "name" "%s" "priority" "%s" }' %
                (app, before["name"], "75" if app == "0" else "250") for app in ["0", *apps])))
            globals_ = COMPAT["register_default"].__globals__
            single = mock.Mock(wraps=COMPAT["set_mapping"])
            with mock.patch.dict(globals_, set_mapping=single, steam_running=lambda: False):
                result = COMPAT["register_default"](
                    str(config), apps, catalog=[valve, new], directory=str(state), settle=True,
                    default=None if replace else new["name"])
            self.assertEqual(result, new["name"])
            self.assertEqual(COMPAT["read_config_mapping"](str(config)), {app: new["name"] for app in ["0", *apps]})
            self.assertLessEqual(single.call_count, 1 if replace else 0)

    def test_retired_proton_replacements_are_batched(self):
        self.register(replace=True)

    def test_planned_default_changes_are_batched(self):
        self.register(replace=False)

    def test_x64_repoint_batches_default_and_games_and_keeps_pending_order(self):
        apps = [str(app) for app in range(1, 65)]
        with tempfile.TemporaryDirectory() as tmp:
            steam = Path(tmp) / "Steam"
            config = steam / "config/config.vdf"
            config.parent.mkdir(parents=True)
            config.write_text(config_text("".join('"%s" { "name" "proton_experimental" }' % app for app in apps)))
            pending = Path(tmp) / ".bl-compat-pending"
            pending.write_text("before\n")
            globals_ = COMPAT["x64_repoint"].__globals__
            single = mock.Mock(wraps=COMPAT["set_mapping"])
            with mock.patch.dict(globals_, set_mapping=single), mock.patch.dict("os.environ", HOME=tmp):
                moved = COMPAT["x64_repoint"](str(steam), "new-a64", live=True)
            self.assertEqual(moved, apps)
            self.assertEqual(COMPAT["read_config_mapping"](str(config)), {app: "new-a64" for app in ["0", *apps]})
            self.assertEqual(pending.read_text(), "before\n" + "".join(app + "\n" for app in apps))
            single.assert_not_called()


if __name__ == "__main__":
    unittest.main()
