# Rules

- Comments should be rare, factual, and explain only non-obvious external constraints or business invariants.
- Commits must use the configured human author's identity. Never set an AI or bot identity as author, and never add an AI or bot `Co-authored-by` trailer. Preserve the original human contributors when repairing or squashing their work.
- Before pushing or merging, run `python3 tools/release/check_authorship.py <base>..<head>` and inspect the final squash message. A green build does not excuse invalid authorship.
- Install the shared commit and push guards with `python3 tools/release/install_authorship_hooks.py`. Do not disable these hooks or bypass the required Human authorship check.
