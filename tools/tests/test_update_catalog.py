import unittest

from tools.release.update_catalog import (
    asset_for_suffix,
    ci_description,
    parse_gradle,
    parse_variants,
    published_millis,
    release_body_value,
    stable_app_release,
)


class UpdateCatalogTest(unittest.TestCase):
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
