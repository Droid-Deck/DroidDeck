//! The handful of kernel32 calls the launcher needs.

#![allow(non_snake_case, clippy::upper_case_acronyms)]

use std::ffi::c_void;
use std::os::windows::ffi::OsStrExt;

pub type HANDLE = *mut c_void;
pub const INVALID: HANDLE = -1isize as HANDLE;
const PIPE_ACCESS_DUPLEX: u32 = 3;
const PIPE_TYPE_BYTE: u32 = 0;
const PIPE_READMODE_BYTE: u32 = 0;
const PIPE_WAIT: u32 = 0;
const PIPE_UNLIMITED_INSTANCES: u32 = 255;
const ERROR_PIPE_CONNECTED: u32 = 535;
const HANDLE_FLAG_INHERIT: u32 = 1;
const SYNCHRONIZE: u32 = 0x0010_0000;
const PROCESS_QUERY_LIMITED_INFORMATION: u32 = 0x1000;
pub const WAIT_OBJECT_0: u32 = 0;
const BUFFER: u32 = 65536;

#[repr(C)]
struct STARTUPINFOW {
    cb: u32,
    lpReserved: *mut u16,
    lpDesktop: *mut u16,
    lpTitle: *mut u16,
    dwX: u32,
    dwY: u32,
    dwXSize: u32,
    dwYSize: u32,
    dwXCountChars: u32,
    dwYCountChars: u32,
    dwFillAttribute: u32,
    dwFlags: u32,
    wShowWindow: u16,
    cbReserved2: u16,
    lpReserved2: *mut u8,
    hStdInput: HANDLE,
    hStdOutput: HANDLE,
    hStdError: HANDLE,
}

#[repr(C)]
struct PROCESS_INFORMATION {
    hProcess: HANDLE,
    hThread: HANDLE,
    dwProcessId: u32,
    dwThreadId: u32,
}

#[link(name = "kernel32")]
extern "system" {
    fn CreateNamedPipeW(name: *const u16, open: u32, mode: u32, max: u32, out: u32, inp: u32, timeout: u32, sa: *mut c_void) -> HANDLE;
    fn ConnectNamedPipe(h: HANDLE, ov: *mut c_void) -> i32;
    fn ReadFile(h: HANDLE, buf: *mut u8, n: u32, read: *mut u32, ov: *mut c_void) -> i32;
    fn WriteFile(h: HANDLE, buf: *const u8, n: u32, written: *mut u32, ov: *mut c_void) -> i32;
    fn FlushFileBuffers(h: HANDLE) -> i32;
    fn CloseHandle(h: HANDLE) -> i32;
    fn GetLastError() -> u32;
    fn CreateEventW(sa: *mut c_void, manual: i32, initial: i32, name: *const u16) -> HANDLE;
    fn SetEvent(h: HANDLE) -> i32;
    fn SetHandleInformation(h: HANDLE, mask: u32, flags: u32) -> i32;
    fn CreateProcessW(app: *const u16, cmd: *mut u16, pa: *mut c_void, ta: *mut c_void, inherit: i32, flags: u32,
                      env: *mut c_void, cwd: *const u16, si: *mut STARTUPINFOW, pi: *mut PROCESS_INFORMATION) -> i32;
    fn WaitForSingleObject(h: HANDLE, ms: u32) -> u32;
    fn GetExitCodeProcess(h: HANDLE, code: *mut u32) -> i32;
    fn OpenProcess(access: u32, inherit: i32, pid: u32) -> HANDLE;
    fn ProcessIdToSessionId(pid: u32, session: *mut u32) -> i32;
    fn GetCurrentProcessId() -> u32;
}

pub fn wide(s: &str) -> Vec<u16> {
    std::ffi::OsStr::new(s).encode_wide().chain(Some(0)).collect()
}

pub fn last_error() -> u32 {
    unsafe { GetLastError() }
}

pub fn pid() -> u32 {
    unsafe { GetCurrentProcessId() }
}

pub fn session_id() -> u32 {
    let mut s = 0;
    if unsafe { ProcessIdToSessionId(GetCurrentProcessId(), &mut s) } == 0 {
        return 1;
    }
    s
}

pub struct Handle(pub HANDLE);
unsafe impl Send for Handle {}
unsafe impl Sync for Handle {}

impl Drop for Handle {
    fn drop(&mut self) {
        if !self.0.is_null() && self.0 != INVALID {
            unsafe { CloseHandle(self.0) };
        }
    }
}

impl Handle {
    pub fn read(&self, buf: &mut [u8]) -> Option<usize> {
        let mut n = 0u32;
        let ok = unsafe { ReadFile(self.0, buf.as_mut_ptr(), buf.len() as u32, &mut n, std::ptr::null_mut()) };
        (ok != 0 && n > 0).then_some(n as usize)
    }

    pub fn write_all(&self, mut data: &[u8]) -> bool {
        while !data.is_empty() {
            let mut n = 0u32;
            if unsafe { WriteFile(self.0, data.as_ptr(), data.len() as u32, &mut n, std::ptr::null_mut()) } == 0 || n == 0 {
                return false;
            }
            data = &data[n as usize..];
        }
        unsafe { FlushFileBuffers(self.0) };
        true
    }

    pub fn wait(&self, ms: u32) -> bool {
        unsafe { WaitForSingleObject(self.0, ms) == WAIT_OBJECT_0 }
    }

    pub fn exit_code(&self) -> u32 {
        let mut c = 0;
        unsafe { GetExitCodeProcess(self.0, &mut c) };
        c
    }

    pub fn set(&self) {
        unsafe { SetEvent(self.0) };
    }
}

/// A new instance of the named pipe, waiting for its client; `None` when it cannot be created.
pub fn accept(name: &str) -> Option<Handle> {
    let w = wide(name);
    let h = unsafe {
        CreateNamedPipeW(w.as_ptr(), PIPE_ACCESS_DUPLEX, PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT,
                         PIPE_UNLIMITED_INSTANCES, BUFFER, BUFFER, 0, std::ptr::null_mut())
    };
    if h == INVALID {
        return None;
    }
    let pipe = Handle(h);
    if unsafe { ConnectNamedPipe(h, std::ptr::null_mut()) } == 0 && last_error() != ERROR_PIPE_CONNECTED {
        return None;
    }
    Some(pipe)
}

/// An auto-reset event the started process inherits.
pub fn inheritable_event() -> Handle {
    let h = unsafe { CreateEventW(std::ptr::null_mut(), 0, 0, std::ptr::null()) };
    unsafe { SetHandleInformation(h, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT) };
    Handle(h)
}

pub fn spawn(command_line: &str, cwd: &str) -> Result<(Handle, u32), u32> {
    let mut cmd = wide(command_line);
    let dir = wide(cwd);
    let mut si: STARTUPINFOW = unsafe { std::mem::zeroed() };
    si.cb = std::mem::size_of::<STARTUPINFOW>() as u32;
    let mut pi: PROCESS_INFORMATION = unsafe { std::mem::zeroed() };
    let ok = unsafe {
        CreateProcessW(std::ptr::null(), cmd.as_mut_ptr(), std::ptr::null_mut(), std::ptr::null_mut(), 1, 0,
                       std::ptr::null_mut(), if cwd.is_empty() { std::ptr::null() } else { dir.as_ptr() }, &mut si, &mut pi)
    };
    if ok == 0 {
        return Err(last_error());
    }
    drop(Handle(pi.hThread));
    Ok((Handle(pi.hProcess), pi.dwProcessId))
}

pub fn open_process(pid: u32) -> Option<Handle> {
    let h = unsafe { OpenProcess(SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION, 0, pid) };
    (!h.is_null()).then(|| Handle(h))
}
