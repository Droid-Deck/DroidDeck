import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request


REPORT = "<!-- droiddeck-pr-release -->"
STATE = re.compile(r"\n<!-- droiddeck-discord: (\{[^\n]*\}) -->")


def github(path, method="GET", body=None):
    args = ["gh", "api", path, "--method", method]
    if body is not None:
        args += ["--input", "-"]
    result = subprocess.run(args, input=json.dumps(body) if body is not None else None,
                            capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError("GitHub request failed")
    return json.loads(result.stdout) if result.stdout.strip() else None


def report_comment(repo, number):
    page = 1
    while True:
        comments = github(f"repos/{repo}/issues/{number}/comments?per_page=100&page={page}")
        for comment in comments:
            if (comment.get("user", {}).get("login") == "github-actions[bot]"
                    and (comment.get("body") or "").startswith(REPORT)):
                return comment
        if len(comments) < 100:
            return None
        page += 1


def saved_message(body, webhook_id):
    match = STATE.search(body)
    if not match:
        return None
    try:
        state = json.loads(match.group(1))
    except ValueError:
        return None
    if (state.get("webhook_id") == webhook_id
            and re.fullmatch(r"[0-9]{15,22}", str(state.get("message_id", "")))):
        return state["message_id"]
    return None


class Discord:
    def __init__(self, webhook):
        match = re.fullmatch(r"https://discord\.com/api(?:/v[0-9]+)?/webhooks/([0-9]{15,22})/([A-Za-z0-9_-]+)", webhook)
        if not match:
            raise RuntimeError("Invalid Discord webhook configuration")
        self.url = webhook
        self.id = match.group(1)

    def request(self, method, suffix="", body=None):
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.url + suffix, data=data,
                                         headers={"Content-Type": "application/json",
                                                  "User-Agent": "DroidDeck-PR-builds"}, method=method)
        for attempt in range(3):
            try:
                with urllib.request.urlopen(request, timeout=30) as response:
                    return json.load(response)
            except urllib.error.HTTPError as error:
                if error.code == 404:
                    return None
                if method != "POST" and error.code in (429, 500, 502, 503, 504) and attempt < 2:
                    time.sleep(2 ** attempt)
                    continue
                raise RuntimeError(f"Discord request failed (HTTP {error.code})") from None
            except (OSError, ValueError):
                raise RuntimeError("Discord request failed") from None


def plain_title(title):
    title = " ".join(title.split())[:300]
    return re.sub(r"([\\`*_~|\[\]<>])", r"\\\1", title)


def build_content(repo, number, title, sha, apk, release):
    return (f"**Signed PR test build** `pr-{number}` · `{sha[:7]}`\n"
            f"[#{number}](https://github.com/{repo}/pull/{number}): {plain_title(title)}\n"
            "_Not merged — for testing._\n"
            f"Download: [standard APK](<{apk}>) · [all variants](<{release}>)")


def retired_content(content, merged):
    label = "Merged" if merged else "Closed"
    if content.startswith(("**Merged** —", "**Closed** —")):
        content = "\n".join(line[2:-2] if line.startswith("~~") and line.endswith("~~") else line
                            for line in content.splitlines()[2:])
    struck = "\n".join(f"~~{line}~~" if line else "" for line in content.splitlines())
    return f"**{label}** — this PR test build is no longer available.\n\n{struck}"


def sync(mode, repo, number, discord, sha="", apk="", release=""):
    pull = github(f"repos/{repo}/pulls/{number}")
    if mode == "publish":
        if (pull["state"] != "open" or pull.get("draft") or pull["base"]["ref"] != "main"
                or pull["head"]["sha"] != sha):
            print("PR changed; no Discord announcement published")
            return
    elif pull["state"] != "closed":
        print("PR is open; Discord announcement retained")
        return
    comment = report_comment(repo, number)
    if comment is None:
        if mode == "retire":
            return
        raise RuntimeError("Signed build report comment is missing")
    message_id = saved_message(comment["body"], discord.id)
    if mode == "retire":
        if message_id:
            message = discord.request("GET", f"/messages/{message_id}")
            if message:
                discord.request("PATCH", f"/messages/{message_id}",
                                {"content": retired_content(message["content"], pull.get("merged", False)),
                                 "allowed_mentions": {"parse": []}, "flags": 4})
        return
    content = build_content(repo, number, pull["title"], sha, apk, release)
    if len(content) > 1800:
        raise RuntimeError("Discord announcement is too long")
    payload = {"content": content, "allowed_mentions": {"parse": []}, "flags": 4}
    message = discord.request("PATCH", f"/messages/{message_id}", payload) if message_id else None
    if message is None:
        message = discord.request("POST", "?wait=true", payload)
        if message is None:
            raise RuntimeError("Discord webhook no longer exists")
    state = {"webhook_id": discord.id, "message_id": message["id"]}
    latest = github(f"repos/{repo}/issues/comments/{comment['id']}")
    body = STATE.sub("", latest["body"]).rstrip() + f"\n<!-- droiddeck-discord: {json.dumps(state)} -->"
    github(f"repos/{repo}/issues/comments/{comment['id']}", "PATCH", {"body": body})
    print("Discord PR announcement updated")


def main():
    webhook = os.environ.get("WEBHOOK", "")
    if not webhook:
        print("::notice::No DISCORD_PR_BUILDS_WEBHOOK secret; nothing posted")
        return
    repo = os.environ["GITHUB_REPOSITORY"]
    number = os.environ["PR_NUMBER"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo) or not re.fullmatch(r"[1-9][0-9]*", number):
        raise RuntimeError("Invalid PR configuration")
    mode = sys.argv[1]
    if mode not in ("publish", "retire"):
        raise RuntimeError("Invalid announcement mode")
    sha, apk, release = (os.environ.get(key, "") for key in ("HEAD_SHA", "APK", "RELEASE"))
    if mode == "publish":
        prefix = f"https://github.com/Droid-Deck/DroidDeck-CI/releases/"
        if (not re.fullmatch(r"[0-9a-f]{40}", sha)
                or not apk.startswith(prefix + f"download/pr-{number}/")
                or release != prefix + f"tag/pr-{number}"
                or re.search(r"[\s<>]", apk)):
            raise RuntimeError("Invalid published build configuration")
    sync(mode, repo, number, Discord(webhook), sha, apk, release)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"::error::{error}" if isinstance(error, RuntimeError) else "::error::Discord announcement failed", file=sys.stderr)
        sys.exit(1)
