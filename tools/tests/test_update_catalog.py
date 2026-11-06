import base64
import contextlib
import io
import json
import unittest
from unittest.mock import Mock, patch

from tools.release.update_catalog import (
    ApiError,
    asset_for_suffix,
    ci_description,
    current_file,
    parse_gradle,
    parse_variants,
    published_millis,
    publish_catalog,
    preview_history_entry,
    recent_previews,
    release_body_value,
    stable_app_release,
)


class UpdateCatalogTest(unittest.TestCase):
    def test_current_file_uses_repository_default_branch_without_ref(self):
        ci = Mock()
        current_file(ci, "example/fork", "catalog.json")
        ci.get.assert_called_once_with("/repos/example/fork/contents/catalog.json")

    def test_current_file_encodes_explicit_branch_as_one_query_value(self):
        ci = Mock()
        current_file(ci, "example/fork", "catalog.json", "release/Updates+%2F")
        ci.get.assert_called_once_with(
            "/repos/example/fork/contents/catalog.json?ref=release%2FUpdates%2B%252F"
        )

    def test_publish_catalog_reads_and_writes_the_same_branch_selection(self):
        catalog = {"schema": 1, "generatedAt": 2, "preview": {"commit": "b" * 40}}
        old_catalog = {**catalog, "preview": {"commit": "a" * 40}}
        for branch in (None, "dev", "release/Updates+%2F"):
            with self.subTest(branch=branch):
                ci = Mock()
                ci.get.return_value = {
                    "sha": "old-catalog-blob",
                    "content": base64.b64encode(json.dumps(old_catalog).encode()).decode(),
                }
                ci.put.return_value = {
                    "content": {"html_url": "https://github.com/example/fork/blob/dev/catalog.json"}
                }
                with patch("tools.release.update_catalog.build_catalog", return_value=catalog), \
                        contextlib.redirect_stdout(io.StringIO()) as output:
                    self.assertEqual(catalog, publish_catalog(Mock(), ci, "example/app", "example/fork", "catalog.json", branch))
                endpoint, body = ci.put.call_args.args
                self.assertEqual("/repos/example/fork/contents/catalog.json", endpoint)
                self.assertEqual("old-catalog-blob", body["sha"])
                self.assertEqual(catalog, json.loads(base64.b64decode(body["content"])))
                if branch is None:
                    self.assertNotIn("branch", body)
                    ci.get.assert_called_once_with(endpoint)
                else:
                    self.assertEqual(branch, body["branch"])
                    self.assertIn("?ref=", ci.get.call_args.args[0])
                self.assertIn(ci.put.return_value["content"]["html_url"], output.getvalue())

    def test_missing_explicit_branch_never_falls_back_to_default(self):
        ci = Mock()
        ci.get.side_effect = ApiError(404, "branch not found")
        ci.put.side_effect = ApiError(404, "branch not found")
        with patch("tools.release.update_catalog.build_catalog", return_value={"schema": 1}):
            with self.assertRaises(ApiError):
                publish_catalog(Mock(), ci, "example/app", "example/fork", "catalog.json", "missing")
        ci.get.assert_called_once_with("/repos/example/fork/contents/catalog.json?ref=missing")
        self.assertEqual("missing", ci.put.call_args.args[1]["branch"])
        self.assertEqual(1, ci.put.call_count)

    def test_gradle_metadata(self):
        code, name = parse_gradle("""android {
    defaultConfig {
        versionCode 12
        versionName "0.3.1"
    }
}
""")
        self.assertEqual(12, code)
        self.assertEqual("0.3.1", name)

    def test_variants(self):
        rows = parse_variants("""
# name package suffix
standard com.droiddeck.launcher -
pubg com.tencent.ig -pubg
""")
        self.assertEqual(
            [("com.droiddeck.launcher", ""), ("com.tencent.ig", "-pubg")],
            rows,
        )

    def test_standard_asset_does_not_take_a_variant(self):
        assets = [
            {
                "name": "DroidDeck-main-deadbee-pubg.apk",
                "size": 10,
                "digest": "sha256:" + "a" * 64,
                "browser_download_url": "https://example/pubg",
            },
            {
                "name": "DroidDeck-main-deadbee.apk",
                "size": 11,
                "digest": "sha256:" + "b" * 64,
                "browser_download_url": "https://example/standard",
            },
        ]
        standard = asset_for_suffix(assets, "", ["", "-pubg"])
        pubg = asset_for_suffix(assets, "-pubg", ["", "-pubg"])
        self.assertEqual("DroidDeck-main-deadbee.apk", standard["name"])
        self.assertEqual("DroidDeck-main-deadbee-pubg.apk", pubg["name"])

    def test_ci_description(self):
        title, summary = ci_description(
            {
                "tag_name": "main-deadbee",
                "name": "main deadbee",
                "body": (
                    "**Merged to main** - [PR #9](https://github.com/Droid-Deck/DroidDeck/pull/9): "
                    "fix: keep Steam alive (merged 2026-09-30)\n\n"
                    "Steam no longer **exits** on resume.\n\n"
                    "Signed build of https://example"
                ),
            }
        )
        self.assertEqual("fix: keep Steam alive", title)
        self.assertEqual("Steam no longer exits on resume.", summary)

    def test_release_body_metadata(self):
        tick = chr(96)
        body = f"Commit: {tick}abcdef1234567{tick}\nBase: 1234567\nVersionCode: 42\n"
        self.assertEqual("abcdef1234567", release_body_value(body, "Commit"))
        self.assertEqual("1234567", release_body_value(body, "Base"))
        self.assertEqual("42", release_body_value(body, "VersionCode"))

    def test_republished_pr_uses_updated_time_but_other_channels_use_publish_time(self):
        release = {
            "published_at": "2026-09-30T10:00:00Z",
            "updated_at": "2026-09-30T12:00:00Z",
        }
        self.assertLess(
            published_millis(release),
            published_millis(release, prefer_updated=True),
        )

    def test_preview_history_keeps_only_complete_signed_main_releases(self):
        variants = [("com.droiddeck.launcher", ""), ("com.example.variant", "-variant")]
        commit = "a" * 40
        entry = {
            "commit": commit,
            "title": "Fix Steam resume",
            "summary": "Steam stays open after resuming.",
            "publishedAt": 123,
            "apks": {
                package: {"sha256": "a" * 64, "signerSha256": "b" * 64}
                for package, _ in variants
            },
        }
        release = {"tag_name": "main-aaaaaaa", "draft": False, "prerelease": True}
        self.assertEqual(
            {
                "commit": commit,
                "title": "Fix Steam resume",
                "summary": "Steam stays open after resuming.",
                "publishedAt": 123,
            },
            preview_history_entry(release, entry, variants),
        )

        for invalid_release in (
            {**release, "draft": True},
            {**release, "prerelease": False},
            {**release, "tag_name": "pr-42"},
        ):
            self.assertIsNone(preview_history_entry(invalid_release, entry, variants))
        self.assertIsNone(preview_history_entry(release, {**entry, "commit": "a" * 12}, variants))
        self.assertIsNone(preview_history_entry(release, {**entry, "apks": {"com.droiddeck.launcher": {}}}, variants))
        unsigned = {**entry, "apks": {package: {"sha256": "a" * 64} for package, _ in variants}}
        self.assertIsNone(preview_history_entry(release, unsigned, variants))

    def test_preview_history_is_newest_first_deduplicated_and_limited(self):
        entries = [
            {"commit": f"{i:040x}", "title": str(i), "summary": "", "publishedAt": i}
            for i in range(12)
        ]
        entries.append({**entries[-1], "title": "duplicate", "publishedAt": 99})
        recent = recent_previews(entries)
        self.assertEqual(10, len(recent))
        self.assertEqual("duplicate", recent[0]["title"])
        self.assertEqual("000000000000000000000000000000000000000a", recent[1]["commit"])
        self.assertEqual(1, len([item for item in recent if item["commit"].endswith("000b")]))

    def test_stable_is_the_newest_app_release_not_github_latest(self):
        def release(tag, at, *assets, **flags):
            return {"tag_name": tag, "published_at": at, "assets": [{"name": a} for a in assets], **flags}
        releases = [
            release("gamescope-3.16.29-p5", "2026-10-01T18:53:08Z", "gamescope.tzst"),
            release("0.3.0", "2026-09-30T00:00:00Z", "DroidDeck-0.3.0.apk", prerelease=True),
            release("0.2.1", "2026-09-29T00:00:00Z", "DroidDeck-0.2.1.apk", draft=True),
            release("0.2.0", "2026-09-28T03:20:01Z", "DroidDeck-0.2.0-pubg.apk", "DroidDeck-0.2.0.apk"),
            release("0.1.6", "2026-09-01T00:00:00Z", "DroidDeck-0.1.6.apk"),
        ]
        self.assertEqual("0.2.0", stable_app_release(releases)["tag_name"])
        self.assertIsNone(stable_app_release(releases[:1]))


if __name__ == "__main__":
    unittest.main()
