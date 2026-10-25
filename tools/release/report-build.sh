#!/usr/bin/env bash
set -uo pipefail
MARK="<!-- droiddeck-pr-build -->"
{
  echo "$MARK"
  echo "## Build of this pull request"
  echo
  [ -f /tmp/pr-merge.md ] && cat /tmp/pr-merge.md
  echo
  case "$BUILD_OUTCOME:$UNIT_TEST_OUTCOME:$APK_OUTCOME" in
    success:success:success)
      echo "###  Build passed"
      echo
      echo "The \`droiddeck-apk\` artifact is [on this run]($RUN_URL), signed with the public test key."
      echo
      if [ "$SAME_REPO" = true ]; then
        echo "After the parallel regression tests pass, a release-signed tester build that installs over Stable or Preview will be published and will appear in DroidDeck's **Test builds** list. Label the PR \`no-test-build\` to skip that."
      else
        echo "**Maintainer:** add the \`publish-test\` label to publish a release-signed tester build that installs over Stable or Preview and appears in DroidDeck's **Test builds** list. The label is removed automatically after publishing."
      fi
      echo
      echo "Fallback: [open **PR Release Publish**]($GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/workflows/pr-release-publish.yml) on \`main\` and enter PR \`$PR\`."
      ;;
    success:success:*)
      echo "###  APK publication incomplete"
      echo
      echo "Compilation and unit tests passed, but APK publication did not complete. See [the run]($RUN_URL)."
      ;;
    success:failure:*)
      echo "### ERROR: Unit tests failed"
      echo
      echo "The APK compiled, but unit tests failed. No installable artifact was published."
      echo
      if [ -n "$TEST_DIAGNOSTICS_URL" ]; then
        echo "The [unit-test-results]($TEST_DIAGNOSTICS_URL) artifact contains the test log and any generated reports."
      else
        echo "The test diagnostics upload did not complete. See [the test log]($RUN_URL)."
      fi
      ;;
    success:*)
      echo "###  Build incomplete"
      echo
      echo "The APK compiled, but unit tests did not complete successfully. See [the run]($RUN_URL)."
      ;;
    failure:*)
      echo "### ERROR: Build failed"
      echo
      echo "The compiler's errors ([full log]($RUN_URL)):"
      echo
      echo '```'
      { grep -E '^e: |^w: .*error|error:|CMake Error|fatal error|ninja: error|FAILED|What went wrong|Execution failed for task|Unresolved reference|Type mismatch|Unresolved' /tmp/build.log 2>/dev/null \
        | sed -e 's#file:///home/runner/work/[^/]*/[^/]*/##' -e 's#/home/runner/work/[^/]*/[^/]*/##' \
        | grep -v '^w: ' | awk '!seen[$0]++' | head -80; } || true
      echo '```'
      echo
      echo "<details><summary>What went wrong (Gradle)</summary>"
      echo
      echo '```'
      awk '/^\* What went wrong/{p=1} /^\* Try:/{p=0} p{print}' /tmp/build.log 2>/dev/null | head -60
      echo '```'
      echo "</details>"
      ;;
    skipped:*|cancelled:*)
      echo "###  Not built"
      echo
      echo "The build did not run (see above, or [the run]($RUN_URL))."
      ;;
  esac
  echo
} > /tmp/pr-report.md
cat /tmp/pr-report.md >> "$GITHUB_STEP_SUMMARY"
ID=$(gh api "repos/${GITHUB_REPOSITORY}/issues/${PR}/comments" --paginate --jq ".[] | select(.user.login == \"github-actions[bot]\" and (.body | startswith(\"$MARK\"))) | .id" | head -1 || true)
if [ -n "$ID" ]; then
  gh api -X PATCH "repos/${GITHUB_REPOSITORY}/issues/comments/${ID}" -F body=@/tmp/pr-report.md >/dev/null
else
  gh api -X POST "repos/${GITHUB_REPOSITORY}/issues/${PR}/comments" -F body=@/tmp/pr-report.md >/dev/null
fi
