#!/usr/bin/env python3
import json
import os
import subprocess
import sys
import time
from urllib.parse import urlencode

from check_authorship import check_revision, event_violations


RATE_LIMIT_WAIT = 1800


def rate_limit_wait():
    try:
        reset = json.loads(subprocess.check_output(["gh", "api", "rate_limit"], text=True))["resources"]["core"]["reset"]
    except (subprocess.CalledProcessError, KeyError, ValueError):
        return 60
    return max(5, min(RATE_LIMIT_WAIT, reset - time.time() + 5))


def api(path, *args, attempts=3):
    for attempt in range(attempts):
        done = subprocess.run(["gh", "api", path, *args], text=True, capture_output=True)
        if done.returncode == 0:
            return json.loads(done.stdout)
        err = done.stderr.lower()
        if "--method" in args or "rate limit" not in err or attempt == attempts - 1:
            sys.stderr.write(done.stderr)
            raise subprocess.CalledProcessError(done.returncode, done.args, done.stdout, done.stderr)
        wait = rate_limit_wait()
        if "secondary rate limit" in err:
            wait = max(wait, 60)
        time.sleep(wait)


def compilation_state(run, jobs):
    for job in jobs:
        if job["name"] in ("Compile and test APK", "Build APK"):
            if job["status"] != "completed":
                return "pending"
            return "success" if job["conclusion"] == "success" else "failure"
    return "failure" if run["status"] == "completed" else "pending"


def fetch_source(ref, sha):
    subprocess.run(["git", "fetch", "--no-tags", "origin", ref], check=True)
    fetched = subprocess.check_output(["git", "rev-parse", "FETCH_HEAD"], text=True).strip()
    if fetched != sha:
        raise RuntimeError("Pull request changed while validating")


def documentation_only(sha):
    base = subprocess.check_output(["git", "merge-base", "origin/main", sha], text=True).strip()
    paths = subprocess.check_output(["git", "diff", "--no-renames", "--name-only", "-z", base, sha]).split(b"\0")
    return all(path.endswith(b".md") or path in (b"LICENSE", b".gitignore") for path in paths if path)


def trusted_list_violation(repo, pr, sha):
    changed = subprocess.run(
        ["git", "diff", "--no-renames", "--quiet", f"origin/main...{sha}", "--", ".github/trusted-test-builds.txt"],
        check=False,
    )
    if changed.returncode not in (0, 1):
        raise RuntimeError("Could not check trusted contributor changes")
    if changed.returncode == 0:
        return []
    opener = pr["user"]["login"]
    permission = api(f"repos/{repo}/collaborators/{opener}/permission")
    if permission["permission"] != "admin" and permission.get("role_name") != "maintain":
        return [f"Only a maintainer may open a PR changing .github/trusted-test-builds.txt (opened by @{opener})"]
    return []


def main():
    with open(os.environ["GITHUB_EVENT_PATH"], encoding="utf-8") as source:
        event = json.load(source)
    repo = os.environ["GITHUB_REPOSITORY"]
    event_name = os.environ["GITHUB_EVENT_NAME"]
    pr_number = event.get("number") if event_name == "pull_request_target" else None
    if event_name == "workflow_dispatch":
        pr_number = int(event.get("inputs", {}).get("pr_number") or 0)
    run = event.get("workflow_run", {})
    expected = event.get("pull_request", {}).get("head", {}).get("sha")
    if event_name == "workflow_run":
        expected = run["head_sha"]
        if run["event"] == "pull_request":
            prs = api(f"repos/{repo}/commits/{expected}/pulls")
            owner = (run.get("head_repository") or {}).get("owner", {}).get("login")
            if not prs and owner and run.get("head_branch"):
                query = urlencode({"state": "open", "head": f"{owner}:{run['head_branch']}"})
                prs = api(f"repos/{repo}/pulls?{query}")
            pr_number = next((pr["number"] for pr in prs
                              if pr["state"] == "open" and pr["head"]["sha"] == expected), None)
            if pr_number is None:
                print("Ignoring a superseded or closed pull request build")
                return
        elif run["head_branch"] != event["repository"]["default_branch"]:
            return
    if pr_number:
        pr = api(f"repos/{repo}/pulls/{pr_number}")
        sha = pr["head"]["sha"]
        if pr["state"] != "open" or (expected and sha != expected):
            print("Ignoring a superseded or closed pull request event")
            return
        ref = f"refs/pull/{pr_number}/head"
        source_event = "pull_request"
    else:
        pr = None
        sha = expected or event.get("after") or os.environ["GITHUB_SHA"]
        ref = sha
        source_event = "push"
    state = "failure"
    description = "APK source validation failed"
    target = f"https://github.com/{repo}/actions/runs/{os.environ['GITHUB_RUN_ID']}"
    superseded = False

    def refresh_pr():
        nonlocal pr, state, description, target, superseded
        if not pr_number:
            return True
        pr = api(f"repos/{repo}/pulls/{pr_number}")
        if pr["state"] != "open" or pr["head"]["sha"] != sha:
            superseded = True
            return False
        violations = event_violations({"pull_request": pr})
        if violations:
            state = "failure"
            description = "APK source validation failed"
            target = f"https://github.com/{repo}/actions/runs/{os.environ['GITHUB_RUN_ID']}"
        return True

    try:
        fetch_source(ref, sha)
        commits, errors = check_revision(sha)
        if pr:
            errors.extend(event_violations({"pull_request": pr}))
            errors.extend(trusted_list_violation(repo, pr, sha))
        if errors:
            raise RuntimeError("\n".join(errors))
        print(f"Source and metadata validated for {len(commits)} commits")
        if pr and documentation_only(sha):
            state = "success"
            description = "Source validated; no APK source changes"
            if pr_number and not refresh_pr():
                print("Ignoring a superseded or closed pull request event")
                return
            if state == "failure":
                raise RuntimeError("\n".join(event_violations({"pull_request": pr})))
            return
        query = urlencode({"head_sha": sha, "event": source_event, "per_page": 20})
        runs = api(f"repos/{repo}/actions/workflows/build.yml/runs?{query}")["workflow_runs"]
        runs = [item for item in runs if item["head_sha"] == sha]
        if runs:
            latest = max(runs, key=lambda item: item["id"])
            jobs = api(f"repos/{repo}/actions/runs/{latest['id']}/jobs?per_page=100")["jobs"]
            state = compilation_state(latest, jobs)
            target = latest["html_url"]
        else:
            state = "pending"
        description = {"pending": "Source validated; awaiting APK compilation",
                       "success": "Source validation and APK compilation passed",
                       "failure": "APK compilation failed"}[state]
        if pr_number and not refresh_pr():
            print("Ignoring a superseded or closed pull request event")
            return
        if state == "failure" and description == "APK source validation failed":
            raise RuntimeError("\n".join(event_violations({"pull_request": pr})))
    finally:
        if not superseded and not (pr_number and state == "failure" and not refresh_pr()):
            for attempt in range(3):
                if attempt > 0 and pr_number and not refresh_pr():
                    break
                post_args = ["--method", "POST", "-f", "context=Build APK", "-f", f"state={state}",
                             "-f", f"description={description}", "-f", f"target_url={target}"]
                try:
                    api(f"repos/{repo}/statuses/{sha}", *post_args)
                    break
                except subprocess.CalledProcessError as error:
                    err = (error.stderr or "").lower()
                    if "rate limit" not in err or attempt == 2:
                        raise
                    wait = rate_limit_wait()
                    if "secondary rate limit" in err:
                        wait = max(wait, 60)
                    time.sleep(wait)


if __name__ == "__main__":
    main()
