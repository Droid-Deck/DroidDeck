"""Check the engine before modifying build assets; never rebuild for a daemon error."""
from pathlib import Path
import subprocess
import sys


def run(*args):
    try:
        return subprocess.run(['docker', *args], capture_output=True, text=True, timeout=15)
    except subprocess.TimeoutExpired:
        sys.exit('Docker is not responding. Restart Docker Desktop, then rerun tools/build_local.sh.')


if __name__ == '__main__':
    engine = run('info', '--format', '{{.ServerVersion}}')
    if engine.returncode:
        sys.exit('Docker is unavailable: ' + engine.stderr.strip())
    image = run('image', 'inspect', sys.argv[1])
    if image.returncode:
        if 'No such image' not in image.stderr:
            sys.exit('Cannot inspect the build image: ' + image.stderr.strip())
        repo = Path(__file__).resolve().parents[1]
        subprocess.run(['docker', 'build', '--platform', 'linux/amd64', '-t', sys.argv[1],
                        '-f', str(repo / 'tools/local-cross.Dockerfile'), str(repo)], check=True)
