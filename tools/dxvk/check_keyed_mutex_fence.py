#!/usr/bin/env python3
import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile


def function(text, signature):
    start = text.index(signature)
    opening = text.index('{', start)
    stripped = re.sub(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
                      lambda m: ' ' * len(m.group()), text, flags=re.S)
    depth = 0
    for index in range(opening, len(stripped)):
        depth += (stripped[index] == '{') - (stripped[index] == '}')
        if depth == 0:
            return text[start:index + 1]
    raise ValueError('Unterminated source function: ' + signature)


HARNESS = r'''
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <vector>

using UINT64 = uint64_t;
using DWORD = uint32_t;
using HRESULT = int32_t;
using NTSTATUS = int32_t;
using D3DKMT_HANDLE = uint32_t;
using VkSemaphore = uint64_t;
using VkResult = int;

constexpr HRESULT S_OK = 0;
constexpr HRESULT DXGI_ERROR_INVALID_CALL = 0x887A0001;
constexpr HRESULT WAIT_TIMEOUT = 258;
constexpr NTSTATUS STATUS_TIMEOUT = 0x102;
constexpr DWORD INFINITE = 0xFFFFFFFF;
constexpr int VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO = 1;
constexpr int VK_STRUCTURE_TYPE_SEMAPHORE_SIGNAL_INFO = 2;

struct LARGE_INTEGER { int64_t QuadPart; };
struct D3DKMT_ACQUIREKEYEDMUTEX { D3DKMT_HANDLE hKeyedMutex; UINT64 Key; LARGE_INTEGER* pTimeout; UINT64 FenceValue; };
struct D3DKMT_RELEASEKEYEDMUTEX { D3DKMT_HANDLE hKeyedMutex; UINT64 Key; UINT64 FenceValue; };
struct VkSemaphoreWaitInfo { int sType; uint32_t semaphoreCount; const VkSemaphore* pSemaphores; const uint64_t* pValues; };
struct VkSemaphoreSignalInfo { int sType; VkSemaphore semaphore; uint64_t value; };

struct Logger { template<typename... A> static void warn(const char*, A...) { std::puts("warn"); } };

struct Recorder {
  NTSTATUS acquireStatus = 0;
  uint64_t acquireFenceValue = 0;
  bool timeoutPassed = false;
  int64_t timeout = 0;
  uint64_t releasedFenceValue = ~0ull;
  std::vector<uint64_t> waited, signalled;
} rec;

NTSTATUS D3DKMTAcquireKeyedMutex(D3DKMT_ACQUIREKEYEDMUTEX* a) {
  rec.timeoutPassed = a->pTimeout != nullptr;
  rec.timeout = a->pTimeout ? a->pTimeout->QuadPart : 0;
  a->FenceValue = rec.acquireFenceValue;
  return rec.acquireStatus;
}
NTSTATUS D3DKMTReleaseKeyedMutex(D3DKMT_RELEASEKEYEDMUTEX* r) {
  rec.releasedFenceValue = r->FenceValue;
  return 0;
}

struct Fence { VkSemaphore handle() const { return 7; } };
struct FenceRef {
  Fence* ptr = nullptr;
  Fence* operator->() const { return ptr; }
  explicit operator bool() const { return ptr != nullptr; }
};
struct Vkd {
  int device() const { return 0; }
  VkResult vkWaitSemaphores(int, const VkSemaphoreWaitInfo* i, int) { rec.waited.push_back(*i->pValues); return 0; }
  VkResult vkSignalSemaphore(int, const VkSemaphoreSignalInfo* i) { rec.signalled.push_back(i->value); return 0; }
};
struct VkdRef { Vkd* operator->() const { static Vkd v; return &v; } };

struct DxvkKeyedMutex {
  HRESULT AcquireSync(UINT64 key, DWORD milliseconds);
  HRESULT ReleaseSync(UINT64 key);
  VkdRef m_vkd;
  FenceRef m_fence;
  D3DKMT_HANDLE m_kmtLocal = 1;
  uint64_t m_fenceValue = 0;
  std::atomic<bool> m_owned = false;
};

@ACQUIRE_SYNC@
@RELEASE_SYNC@
'''

MAIN = r'''
#define CHECK(cond) do { if (!(cond)) { std::fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #cond); return 2; } } while (0)

int main() {
  Fence fence;
  {
    rec = Recorder{}; rec.acquireFenceValue = 41;
    DxvkKeyedMutex m;
    CHECK(m.AcquireSync(0, 5) == S_OK);
    CHECK(rec.timeoutPassed && rec.timeout == -50000);
    CHECK(rec.waited.empty());
    CHECK(m.ReleaseSync(0) == S_OK);
    CHECK(rec.signalled.empty());
    CHECK(rec.releasedFenceValue == 41);
  }
  {
    rec = Recorder{}; rec.acquireFenceValue = 41;
    DxvkKeyedMutex m; m.m_fence.ptr = &fence;
    CHECK(m.AcquireSync(3, 5) == S_OK);
    CHECK(rec.waited.size() == 1 && rec.waited[0] == 41);
    CHECK(m.ReleaseSync(3) == S_OK);
    CHECK(rec.signalled.size() == 1 && rec.signalled[0] == 42);
    CHECK(rec.releasedFenceValue == 42);
  }
  {
    rec = Recorder{};
    DxvkKeyedMutex m;
    CHECK(m.AcquireSync(0, INFINITE) == S_OK);
    CHECK(!rec.timeoutPassed);
  }
  {
    rec = Recorder{}; rec.acquireStatus = STATUS_TIMEOUT;
    DxvkKeyedMutex m;
    CHECK(m.AcquireSync(0, 5) == WAIT_TIMEOUT);
    CHECK(m.ReleaseSync(0) == DXGI_ERROR_INVALID_CALL);
  }
  std::puts("PASS: keyed mutex acquire and release with and without a fence");
  return 0;
}
'''


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('source', type=Path)
    args = parser.parse_args()
    text = args.source.read_text()
    acquire = function(text, 'HRESULT DxvkKeyedMutex::AcquireSync(')
    release = function(text, 'HRESULT DxvkKeyedMutex::ReleaseSync(')
    code = HARNESS.replace('@ACQUIRE_SYNC@', acquire).replace('@RELEASE_SYNC@', release) + MAIN
    with tempfile.TemporaryDirectory(prefix='dxvk-keyed-mutex-') as directory:
        cpp = Path(directory) / 'keyed_mutex.cpp'
        exe = Path(directory) / 'keyed_mutex'
        cpp.write_text(code)
        subprocess.run([os.environ.get('HOST_CXX', 'c++'), '-std=c++17', '-Wall', '-Wextra', '-Wno-missing-field-initializers', str(cpp), '-o', str(exe)], check=True)
        return subprocess.run([str(exe)]).returncode


if __name__ == '__main__':
    raise SystemExit(main())
