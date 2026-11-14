// What the hook and the code it adds to a static Go program (gosys.c) agree on.
//
// A Go program built without cgo makes its system calls itself, so the preloaded hook never sees
// them and Ubuntu's paths (/etc/resolv.conf, /usr/bin/git, ...) mean nothing to it. Before such a
// program starts, the hook adds a small piece of position-independent code to its file (see
// go_programs.c): the program's one system call function, internal/runtime/syscall.Syscall6, jumps
// to it, and it maps paths into the rootfs like the hook does, then makes the call.
//
// This header has no dependencies beyond <stdint.h>, so that the freestanding code and the hook,
// which are built with different flags, can both include it.

#ifndef DDNATV_GOSYS_ABI_H
#define DDNATV_GOSYS_ABI_H

#include <stdint.h>

#ifndef GOSYS_VERSION
#define GOSYS_VERSION 3u  // raised when the code or its header changes: patched programs are patched again
#endif

// The scratch memory for rewritten paths: a small state area and 64 slots, in zero-filled memory the
// patcher appends to the program's data segment (so nothing is allocated per call). A slot belongs to
// the thread that took it until its call is over: a word in the state area for each slot says
// which goroutine (x28) has it. A slot that a vfork child kept by a successful execve is given back
// at the next call of the goroutine that made the child (the state area counts the execve calls
// that have not returned, [8]).
// (A ring of slots shared by every call was tried first: a thread that was descheduled for a
// moment between taking a slot and the call let sixteen others go round the ring and overwrite its
// path, so a Go program opened another file than it asked for.)
#define GOSYS_SLOTS 64u
#define GOSYS_SLOT_SIZE 32768u  // two paths (4K each), an argv array (8K), an environment array (16K)
#define GOSYS_STATE_SIZE 1024u  // [4] rewriting mode, [8] execve calls on their way, [64 + 8 * slot] who has the slot (0: nobody)
#define GOSYS_SCRATCH_SIZE (GOSYS_STATE_SIZE + GOSYS_SLOTS * GOSYS_SLOT_SIZE)

#define GOSYS_THUNK_BYTES 256u  // room for the small code that connects a call site to the code

#define GOSYS_ROOT_MAX 448
#define GOSYS_PATH_MAX 448
#define GOSYS_LIBS_MAX 1024

struct gosys_hdr {
    char magic[8];           // "CXGOSYS1"
    uint32_t version;        // GOSYS_VERSION
    uint32_t flags;
    int64_t scratch_rel;     // scratch memory, relative to this header (the same in any load address)
    uint32_t root_len;       // the rootfs, as Android sees it
    uint32_t ldso_len;       // the rootfs's dynamic linker
    uint32_t hook_len;       // libddnatv-hook.so; 0: none
    uint32_t libs_len;       // the rootfs's library directories, ':' separated
    uint32_t runner_len;     // the rootfs's bash, or env without it; 0: Ubuntu programs cannot be started
    uint32_t reserved;
    char root[GOSYS_ROOT_MAX];
    char ldso[GOSYS_PATH_MAX];
    char hook[GOSYS_PATH_MAX];
    char libs[GOSYS_LIBS_MAX];
    char runner[GOSYS_PATH_MAX];
};

#define GOSYS_FLAG_RUNNER_IS_BASH 1u  // gosys_hdr.flags

// The last 64 bytes of the added code: where it is, and what the patcher changed to add it.
struct gosys_trailer {
    char magic[8];           // "CXGOEND1"
    uint32_t version;
    uint32_t total_size;     // the added code, header to trailer
    uint64_t text_orig_filesz;   // the segment that holds it: its size before
    uint64_t rw_orig_memsz;      // the data segment: its size before
    uint32_t text_index;         // their numbers in the program header table
    uint32_t rw_index;
    uint32_t text_orig_flags;
    uint32_t reserved;
    uint64_t site_file_off;      // the instruction that was replaced (a "svc #0")
    uint64_t reserved2;
};

_Static_assert(sizeof(struct gosys_trailer) == 64, "the trailer is 64 bytes");
_Static_assert(sizeof(struct gosys_hdr) % 16 == 0, "the header keeps the code after it aligned");

#endif
