#!/usr/bin/env bash
# Runs the CI checks the fast deploy loop skips, limited to what this branch changes.
set -euo pipefail
repo_root=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "${repo_root}"
base_ref=${1:-origin/main}

git fetch -q origin main 2>/dev/null || echo "Could not fetch origin/main; comparing with the local copy." >&2
base=$(git merge-base "${base_ref}" HEAD)
changed=()
while IFS= read -r path; do changed+=("${path}"); done \
    < <({ git diff --name-only "${base}"; git ls-files --others --exclude-standard; } | sort -u)
failed=()
run() {
    local name=$1
    shift
    echo "== ${name}"
    if "$@"; then echo "ok: ${name}"; else failed+=("${name}"); echo "FAILED: ${name}"; fi
}

run "human authorship" python3 tools/release/check_authorship.py "${base}..HEAD"
if [[ ${#changed[@]} -eq 0 ]]; then
    [[ ${#failed[@]} -eq 0 ]] || exit 1
    echo "No files differ from ${base_ref}."
    exit 0
fi

behind=$(git rev-list --count "HEAD..${base_ref}")
if [[ "${behind}" != 0 ]]; then
    echo "== merge onto ${base_ref}"
    if git merge-tree --write-tree HEAD "${base_ref}" >/dev/null 2>&1; then
        echo "${behind} commits behind ${base_ref}. CI compiles the merge, so rebase before pushing if they touch code you use."
    else
        failed+=("merge onto ${base_ref}")
        echo "FAILED: the branch conflicts with ${base_ref}; CI cannot merge it."
    fi
fi

app=0 settings=0
tests=() python_tests=()
for path in "${changed[@]}"; do
    case "${path}" in
        app/src/main/*|app/build.gradle|gradle.properties|gradle/*) app=1 ;;
        app/src/test/*Test.kt) app=1; [[ ! -f "${path}" ]] || tests+=("$(basename "${path}" .kt)") ;;
    esac
    case "${path}" in
        */SettingsIndex.kt) settings=1 ;;
    esac
    if [[ "${path}" == app/src/main/java/*.kt && -f "${path}" ]]; then
        while IFS= read -r test; do tests+=("$(basename "${test}" .kt)"); done \
            < <(find app/src/test -name "$(basename "${path}" .kt)*Test.kt")
    fi
    if [[ "${path}" == tools/tests/test_*.py ]]; then
        [[ ! -f "${path}" ]] || python_tests+=("${path}")
    elif [[ "${path}" == tools/* && "${path}" != tools/tests/* ]]; then
        while IFS= read -r test; do python_tests+=("${test}"); done \
            < <(grep -lF "${path#tools/}" tools/tests/test_*.py 2>/dev/null || true)
    fi
done
[[ "${settings}" == 0 ]] || tests+=(SettingsMapTest)

if [[ "${app}" == 1 ]]; then
    gradle_tasks=(:app:compileReleaseKotlin :app:lintVitalRelease)
    test_args=()
    if [[ ${#tests[@]} -gt 0 ]]; then
        gradle_tasks+=(:app:testReleaseUnitTest)
        for test in $(printf '%s\n' "${tests[@]}" | sort -u); do test_args+=(--tests "*.${test}"); done
    fi
    run "Kotlin, lint and $((${#test_args[@]} / 2)) unit test classes" \
        ./gradlew -q -PskipRust=true -PallowStaleRuntime=true "${gradle_tasks[@]}" ${test_args[@]+"${test_args[@]}"}
fi

if [[ ${#python_tests[@]} -gt 0 ]]; then
    modules=()
    while IFS= read -r test; do modules+=("tools.tests.$(basename "${test}" .py)"); done \
        < <(printf '%s\n' "${python_tests[@]}" | sort -u)
    run "${#modules[@]} Python test modules" python3 -m unittest "${modules[@]}"
fi

if [[ ${#failed[@]} -gt 0 ]]; then
    printf 'Failed: %s\n' "${failed[@]}" >&2
    exit 1
fi
echo "Pre-PR checks passed. CI still runs the full suites."
