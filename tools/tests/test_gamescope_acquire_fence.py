import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
HEADER = 'src/Utils/AcquireFence.h'


def production_header():
    source = os.environ.get('GAMESCOPE_SOURCE')
    if source:
        return (Path(source) / HEADER).read_text()
    for patch in sorted((ROOT / 'tools/gamescope/patches').glob('*.patch')):
        lines = patch.read_text().splitlines()
        marker = '+++ b/' + HEADER
        if marker not in lines:
            continue
        added = []
        for line in lines[lines.index(marker) + 1:]:
            if line.startswith('diff --git '):
                break
            if line.startswith('+'):
                added.append(line[1:])
        return '\n'.join(added) + '\n'
    return (ROOT / 'build/gamescope-test9/src' / HEADER).read_text()


HARNESS = r'''
#include "AcquireFence.h"
#include <cassert>
#include <cerrno>
#include <fcntl.h>
#include <memory>
#include <poll.h>
#include <vector>

static int fresh() {
    int fd = open("/dev/null", O_RDONLY);
    assert(fd >= 0);
    return fd;
}

static bool open_fd(int fd) {
    return fcntl(fd, F_GETFD) >= 0;
}

static void closed_fd(int fd) {
    errno = 0;
    assert(fcntl(fd, F_GETFD) == -1 && errno == EBADF);
}

int main() {
    int old = fresh();
    auto pending = std::make_shared<GamescopeAcquireFence>(old);
    int replacement = fresh();
    pending = std::make_shared<GamescopeAcquireFence>(replacement);
    closed_fd(old);
    assert(open_fd(replacement));
    pending.reset();
    closed_fd(replacement);

    int committed_fd = fresh();
    pending = std::make_shared<GamescopeAcquireFence>(committed_fd);
    std::vector<std::shared_ptr<GamescopeAcquireFence>> commits;
    commits.emplace_back(std::move(pending));
    assert(!pending);
    pending.reset();
    assert(open_fd(committed_fd));
    auto retained = commits.front();
    commits.clear();
    assert(open_fd(committed_fd));
    retained.reset();
    closed_fd(committed_fd);

    int transferred = fresh();
    pending = std::make_shared<GamescopeAcquireFence>(transferred);
    assert(pending->Take() == transferred);
    assert(pending->Take() == -1);
    assert(open_fd(transferred));
    close(transferred);
    int reused = fresh();
    assert(reused == transferred);
    pending.reset();
    assert(open_fd(reused));
    close(reused);

    int pipe_fds[2];
    assert(pipe(pipe_fds) == 0);
    pending = std::make_shared<GamescopeAcquireFence>(pipe_fds[0]);
    auto committed = std::move(pending);
    int waiter = committed->Take();
    committed.reset();
    struct pollfd pfd = {waiter, POLLIN, 0};
    assert(poll(&pfd, 1, 0) == 0);
    char ready = 1;
    assert(write(pipe_fds[1], &ready, 1) == 1);
    assert(poll(&pfd, 1, 0) == 1 && (pfd.revents & POLLIN));
    close(waiter);
    close(pipe_fds[1]);
}
'''


@unittest.skipUnless(shutil.which('c++'), 'needs a C++ compiler')
class GamescopeAcquireFenceTest(unittest.TestCase):
    def test_descriptor_lifetime_across_commit_handoff(self):
        with tempfile.TemporaryDirectory() as folder:
            work = Path(folder)
            (work / 'AcquireFence.h').write_text(production_header())
            (work / 'check.cpp').write_text(HARNESS)
            binary = work / 'check'
            subprocess.run(['c++', '-std=c++17', '-Wall', '-Wextra', '-Werror',
                            str(work / 'check.cpp'), '-o', str(binary)],
                           check=True, capture_output=True, text=True)
            result = subprocess.run([str(binary)], capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
