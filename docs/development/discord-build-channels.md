# Discord build channels

Use the same names as the app's Updates page:

| App | Discord | Builds | Existing webhook secret |
| --- | --- | --- | --- |
| Preview | [#preview-builds](https://discord.com/channels/1552707793247539220/1554202932965015583) | Signed builds of `main`, including merged fixes | `DISCORD_BUILDS_WEBHOOK` |
| Test builds | [#test-builds](https://discord.com/channels/1552707793247539220/1557605185365217340) | Signed builds of individual open pull requests | `DISCORD_PR_BUILDS_WEBHOOK` |

Stable contains tested releases published from version tags. Preview contains the newest
merged fixes before Stable; Test builds let users try an unmerged fix by its PR number.

## Renaming safely

Rename the existing Discord channels in place. Keep their IDs, permissions, and webhooks.
The webhook URLs used by `build.yml`, `pr-release-publish.yml`, and `pr-release-cleanup.yml`
route by ID, so changing a channel's name or topic needs no secret rotation or routing change.
Keep the existing GitHub secret names even though they use the old channel terminology.

PR publication saves `webhook_id` and `message_id` in the signed build report comment.
Republishing edits that message; closing or merging the PR retires it. Neither operation
looks up a Discord channel by name. Keep the existing webhook so those saved references
continue to work. Recreating a channel or webhook is a different operation and needs a
separate migration.

For future renames, check the channel and webhook IDs before and after, verify that the
existing webhook token can still read an earlier build message, and inspect the next
publish/update/retirement runs. Separately hosted bots may have their own configuration;
this repository only controls the GitHub Actions build announcements.
