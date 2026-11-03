# Rules

- Comments should be rare, factual, and explain only non-obvious external constraints or business invariants.


## Google Play distribution

For `playstore` and all changes destined for Google Play, read and enforce [the mandatory distribution policy gate](tools/playstore/POLICY.md) before editing or integrating changes. Refuse known policy violations and hold uncertain changes for authoritative evidence. Review incoming main commits individually; never overwrite Play delivery, permission, updater, signing, versioning, catalog, or release protections. A successful build is not policy clearance.
