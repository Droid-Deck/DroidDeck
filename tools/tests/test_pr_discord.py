import json
import unittest
from unittest.mock import Mock, patch

from tools.release import pr_discord as module


class PrDiscordTest(unittest.TestCase):
    def setUp(self):
        self.pull = {"state": "open", "draft": False, "base": {"ref": "main"},
                     "head": {"sha": "a" * 40}, "title": "Fix **input** @everyone", "merged": False}
        self.comment = {"id": 10, "body": module.REPORT + "\nBuild download"}
        self.discord = Mock(id="1554202953248940172")
        self.discord.request.return_value = {"id": "1557547293375332465"}
        self.github = patch.object(module, "github").start()
        self.addCleanup(patch.stopall)
        self.github.side_effect = lambda path, method="GET", body=None: self.pull if "/pulls/" in path else self.comment
        patch.object(module, "report_comment", return_value=self.comment).start()

    def publish(self):
        module.sync("publish", "Droid-Deck/DroidDeck", "410", self.discord, "a" * 40,
                    "https://github.com/Droid-Deck/DroidDeck-CI/releases/download/pr-410/build.apk",
                    "https://github.com/Droid-Deck/DroidDeck-CI/releases/tag/pr-410")

    def save_state(self):
        self.comment["body"] += '\n<!-- droiddeck-discord: ' + json.dumps({
            "webhook_id": self.discord.id, "message_id": "1557547293375332465"}) + ' -->'

    def test_create_confirmed_message_and_persist_reference(self):
        self.publish()
        args = self.discord.request.call_args.args
        self.assertEqual(("POST", "?wait=true"), args[:2])
        self.assertEqual({"parse": []}, args[2]["allowed_mentions"])
        self.assertIn(r"Fix \*\*input\*\*", args[2]["content"])
        saved = self.github.call_args.args[2]["body"]
        self.assertEqual("1557547293375332465", module.saved_message(saved, self.discord.id))
        self.assertIn("Build download", saved)

    def test_new_build_updates_existing_message(self):
        self.save_state()
        self.publish()
        self.assertEqual("PATCH", self.discord.request.call_args.args[0])
        saved = self.github.call_args.args[2]["body"]
        self.assertEqual(1, len(module.STATE.findall(saved)))

    def test_deleted_message_is_recreated(self):
        self.save_state()
        self.discord.request.side_effect = [None, {"id": "1557547293375332466"}]
        self.publish()
        self.assertEqual(["PATCH", "POST"], [c.args[0] for c in self.discord.request.call_args_list])

    def test_changed_pr_does_not_publish(self):
        for change in ({"state": "closed"}, {"draft": True}, {"base": {"ref": "other"}},
                       {"head": {"sha": "b" * 40}}):
            original = self.pull.copy()
            self.pull.update(change)
            self.publish()
            self.discord.request.assert_not_called()
            self.pull = original

    def test_retirement_strikes_original_and_distinguishes_merge(self):
        self.save_state()
        original = module.build_content("Droid-Deck/DroidDeck", "410", self.pull["title"], "a" * 40, "apk", "release")
        for merged, label in ((True, "Merged"), (False, "Closed")):
            self.pull.update(state="closed", merged=merged)
            self.discord.request.side_effect = [{"content": original}, {"id": "1557547293375332465"}]
            module.sync("retire", "Droid-Deck/DroidDeck", "410", self.discord)
            payload = self.discord.request.call_args.args[2]
            self.assertTrue(payload["content"].startswith(f"**{label}** —"))
            self.assertIn("~~Download:", payload["content"])
            self.assertEqual({"parse": []}, payload["allowed_mentions"])
            self.assertEqual(4, payload["flags"])

    def test_retirement_is_idempotent_and_reopen_skips_it(self):
        once = module.retired_content("first\n\nlast", True)
        self.assertEqual(once, module.retired_content(once, True))
        self.save_state()
        module.sync("retire", "Droid-Deck/DroidDeck", "410", self.discord)
        self.discord.request.assert_not_called()

    def test_old_build_without_saved_id_is_not_guessed(self):
        self.pull["state"] = "closed"
        module.sync("retire", "Droid-Deck/DroidDeck", "410", self.discord)
        self.discord.request.assert_not_called()

    def test_replaced_webhook_cannot_edit_old_webhook_message(self):
        self.save_state()
        self.discord.id = "1554202953248940173"
        self.publish()
        self.assertEqual("POST", self.discord.request.call_args.args[0])

    def test_only_github_actions_report_is_trusted(self):
        with patch.object(module, "report_comment", wraps=original_report):
            forged = {"user": {"login": "contributor"}, "body": module.REPORT, "id": 1}
            real = {"user": {"login": "github-actions[bot]"}, "body": module.REPORT, "id": 2}
            self.github.side_effect = [[forged, real]]
            self.assertEqual(2, module.report_comment("Droid-Deck/DroidDeck", "410")["id"])

    def test_invalid_webhook_never_echoes_secret(self):
        with self.assertRaisesRegex(RuntimeError, "^Invalid Discord webhook configuration$"):
            module.Discord("https://attacker.invalid/webhooks/secret")

    def test_network_error_redacts_webhook_and_does_not_retry_creation(self):
        webhook = "https://discord.com/api/webhooks/1554202953248940172/private_token"
        with patch.object(module.urllib.request, "urlopen", side_effect=module.urllib.error.URLError(webhook)) as request:
            with self.assertRaisesRegex(RuntimeError, "^Discord request failed$"):
                module.Discord(webhook).request("POST", "?wait=true", {"content": "build"})
            self.assertEqual(1, request.call_count)

    def test_edit_failure_does_not_create_duplicate_announcement(self):
        self.save_state()
        self.discord.request.side_effect = RuntimeError("Discord request failed")
        with self.assertRaises(RuntimeError):
            self.publish()
        self.assertEqual(1, self.discord.request.call_count)
        self.assertEqual("PATCH", self.discord.request.call_args.args[0])

    def test_missing_build_report_prevents_untracked_message(self):
        module.report_comment.return_value = None
        with self.assertRaisesRegex(RuntimeError, "Signed build report comment is missing"):
            self.publish()
        self.discord.request.assert_not_called()

    def test_title_cannot_insert_status_or_strikethrough(self):
        content = module.build_content("Droid-Deck/DroidDeck", "410", "~~Retired~~\n**Merged**", "a" * 40, "apk", "release")
        self.assertNotIn("\n**Merged**", content)
        self.assertIn(r"\~\~Retired\~\~", content)
        self.assertLessEqual(len(module.retired_content(content, True)), 2000)

    def test_description_uses_first_useful_paragraph(self):
        body = "## Summary\n\n<!-- private template guidance -->\n\nFixes the [Store import](https://example.com).\n\nMore implementation detail."
        content = module.build_content("Droid-Deck/DroidDeck", "400", "Store import", "a" * 40, "apk", "release", body)
        self.assertIn("\n\nFixes the Store import.\n\n", content)
        self.assertNotIn("private template", content)
        self.assertNotIn("More implementation", content)

    def test_empty_and_template_descriptions_are_omitted(self):
        for body in (None, "", "## Summary\n\n<!-- fill this out -->\n\n- [ ] Tests\n- [x] Code review", "N/A"):
            self.assertEqual("", module.description_excerpt(body))

    def test_description_cannot_override_status_or_ping(self):
        self.pull["body"] = "~~Closed~~ @everyone\n\n```\nsecret template code\n```"
        self.publish()
        payload = self.discord.request.call_args.args[2]
        self.assertIn(r"\~\~Closed\~\~ @everyone", payload["content"])
        self.assertNotIn("secret template", payload["content"])
        self.assertEqual({"parse": []}, payload["allowed_mentions"])

    def test_long_description_leaves_room_for_retirement(self):
        content = module.build_content("Droid-Deck/DroidDeck", "410", "*" * 300, "a" * 40,
                                       "https://github.com/Droid-Deck/DroidDeck-CI/releases/download/pr-410/build.apk",
                                       "https://github.com/Droid-Deck/DroidDeck-CI/releases/tag/pr-410", "~" * 10000)
        self.assertLessEqual(len(content), 1800)
        self.assertLessEqual(len(module.retired_content(content, True)), 2000)


original_report = module.report_comment


if __name__ == "__main__":
    unittest.main()
