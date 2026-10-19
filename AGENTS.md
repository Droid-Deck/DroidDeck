# Contributing

`origin` is `Droid-Deck/DroidDeck` and is pull-only. Push the `fork` remote. Open the pull request with `gh pr create --repo Droid-Deck/DroidDeck --head <fork-owner>:<branch> --base main`.

Stop if `git status --porcelain` is not empty, or if this branch was not just created from fetched `origin/main`. Do not edit those files and do not stack another fix on them.

Search open issues, then open pull requests by filename. If one already changes the same files, stop and link it.

Comments should be rare, factual, and explain only non-obvious external constraints or business invariants.

Unit-only changes run `./gradlew -PskipRust=true :app:testDebugUnitTest`. Native sources or staged assets run `tools/build_local.sh` with the toolchain already installed. Do not install another JDK, NDK, or GCC. A change that does not affect the app does not build an APK.

Commit only the fix. Do not commit notes, images, logs, `.gradle/`, `app/build/`, `local.properties`, or an agent instruction file other than the ones already in the tree. Do not edit `docs/progress-log.md`. Do not add a file under `docs/development/`. Do not add a `Co-authored-by` trailer.

Write the pull request body as UTF-8 with no BOM. `Set-Content -Encoding utf8` on Windows writes a BOM and the check fails. Fill What, Tested, and Checklist. Mark every box `[x]`. `Depends on:` is `#N` or `none`. `AI-assisted:` is `yes` or `no`.

Run `python tools/check_conventions.py --base origin/main --title "..." --body <file>` and require it to pass before `gh pr create`.
