use std::ffi::c_void;
use std::io::{self, Read, Write};
use std::os::windows::ffi::OsStrExt;
use std::ptr::{null, null_mut};
use std::time::{Duration, Instant};

type Handle = *mut c_void;
const INVALID_HANDLE: Handle = -1isize as Handle;
const ERROR_IO_PENDING: i32 = 997;
const ERROR_MORE_DATA: i32 = 234;

#[repr(C)]
struct Overlapped {
    internal: usize,
    internal_high: usize,
    offset: u32,
    offset_high: u32,
    event: Handle,
}

#[link(name = "kernel32")]
extern "system" {
    fn CreateNamedPipeW(
        name: *const u16,
        open: u32,
        mode: u32,
        max: u32,
        output: u32,
        input: u32,
        timeout: u32,
        security: *const c_void,
    ) -> Handle;
    fn ConnectNamedPipe(handle: Handle, overlapped: *mut Overlapped) -> i32;
    fn GetNamedPipeClientProcessId(handle: Handle, pid: *mut u32) -> i32;
    fn CreateFileW(
        name: *const u16,
        access: u32,
        share: u32,
        security: *const c_void,
        creation: u32,
        flags: u32,
        template: Handle,
    ) -> Handle;
    fn WaitNamedPipeW(name: *const u16, timeout: u32) -> i32;
    fn CreateEventW(security: *const c_void, manual: i32, initial: i32, name: *const u16)
        -> Handle;
    fn CloseHandle(handle: Handle) -> i32;
    fn ReadFile(
        handle: Handle,
        buffer: *mut u8,
        size: u32,
        read: *mut u32,
        overlapped: *mut Overlapped,
    ) -> i32;
    fn WriteFile(
        handle: Handle,
        buffer: *const u8,
        size: u32,
        written: *mut u32,
        overlapped: *mut Overlapped,
    ) -> i32;
    fn WaitForSingleObject(handle: Handle, timeout: u32) -> u32;
    fn CancelIoEx(handle: Handle, overlapped: *mut Overlapped) -> i32;
    fn GetOverlappedResult(
        handle: Handle,
        overlapped: *mut Overlapped,
        transferred: *mut u32,
        wait: i32,
    ) -> i32;
}

struct OwnedHandle(Handle);

unsafe impl Send for OwnedHandle {}

impl Drop for OwnedHandle {
    fn drop(&mut self) {
        unsafe {
            CloseHandle(self.0);
        }
    }
}

pub struct Pipe {
    handle: OwnedHandle,
    timeout: Duration,
    deadline: Option<Instant>,
}

pub struct Listener(Pipe);

impl Listener {
    pub fn new(name: &str, timeout: Duration) -> io::Result<Self> {
        if name.is_empty() || name.contains(['\\', '/', '\0']) {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "invalid pipe name",
            ));
        }
        let path = format!(r"\\.\pipe\{name}");
        let wide: Vec<_> = std::ffi::OsStr::new(&path)
            .encode_wide()
            .chain(Some(0))
            .collect();
        let handle = unsafe {
            CreateNamedPipeW(
                wide.as_ptr(),
                3 | 0x4000_0000 | 0x0008_0000,
                8,
                1,
                65536,
                65536,
                0,
                null(),
            )
        };
        if handle == INVALID_HANDLE {
            return Err(io::Error::last_os_error());
        }
        Ok(Self(Pipe {
            handle: OwnedHandle(handle),
            timeout,
            deadline: None,
        }))
    }

    pub fn accept(self) -> io::Result<Pipe> {
        let event = unsafe { CreateEventW(null(), 1, 0, null()) };
        if event.is_null() {
            return Err(io::Error::last_os_error());
        }
        let event = OwnedHandle(event);
        let mut overlapped = Overlapped {
            internal: 0,
            internal_high: 0,
            offset: 0,
            offset_high: 0,
            event: event.0,
        };
        if unsafe { ConnectNamedPipe(self.0.handle.0, &mut overlapped) } != 0 {
            return Ok(self.0);
        }
        let error = io::Error::last_os_error();
        if error.raw_os_error() == Some(535) {
            return Ok(self.0);
        }
        if error.raw_os_error() != Some(ERROR_IO_PENDING) {
            return Err(error);
        }
        let wait = unsafe {
            WaitForSingleObject(
                event.0,
                self.0.timeout.as_millis().min(u32::MAX as u128 - 1) as u32,
            )
        };
        let mut transferred = 0;
        if wait != 0 {
            let error = if wait == 258 {
                io::Error::new(io::ErrorKind::TimedOut, "waiting for Rockstar game")
            } else {
                io::Error::last_os_error()
            };
            unsafe {
                CancelIoEx(self.0.handle.0, &mut overlapped);
                GetOverlappedResult(self.0.handle.0, &mut overlapped, &mut transferred, 1);
            }
            return Err(error);
        }
        if unsafe { GetOverlappedResult(self.0.handle.0, &mut overlapped, &mut transferred, 0) }
            == 0
        {
            return Err(io::Error::last_os_error());
        }
        Ok(self.0)
    }
}

impl Pipe {
    pub fn with_timeout(mut self, timeout: Duration) -> Self {
        self.timeout = timeout;
        self.deadline = None;
        self
    }

    pub fn with_deadline(mut self, timeout: Duration) -> Self {
        self.timeout = timeout;
        self.deadline = Some(Instant::now() + timeout);
        self
    }

    pub fn client_pid(&self) -> io::Result<u32> {
        let mut pid = 0;
        if unsafe { GetNamedPipeClientProcessId(self.handle.0, &mut pid) } == 0 || pid == 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(pid)
    }
    pub fn connect(name: &str, timeout: Duration) -> io::Result<Self> {
        if name.is_empty() || name.contains(['\\', '/', '\0']) {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "invalid pipe name",
            ));
        }
        let path = format!(r"\\.\pipe\{name}");
        let wide: Vec<_> = std::ffi::OsStr::new(&path)
            .encode_wide()
            .chain(Some(0))
            .collect();
        let deadline = Instant::now() + timeout;
        loop {
            let handle = unsafe {
                CreateFileW(
                    wide.as_ptr(),
                    0xc000_0000,
                    0,
                    null(),
                    3,
                    0x4000_0000,
                    null_mut(),
                )
            };
            if handle != INVALID_HANDLE {
                return Ok(Self {
                    handle: OwnedHandle(handle),
                    timeout,
                    deadline: None,
                });
            }
            let error = io::Error::last_os_error();
            if !matches!(error.raw_os_error(), Some(2 | 231)) {
                return Err(error);
            }
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    "waiting for Rockstar pipe",
                ));
            }
            unsafe {
                WaitNamedPipeW(wide.as_ptr(), remaining.as_millis().min(50) as u32);
            }
            std::thread::sleep(remaining.min(Duration::from_millis(2)));
        }
    }

    fn transfer(&self, buffer: *mut u8, size: usize, writing: bool) -> io::Result<usize> {
        if size == 0 {
            return Ok(0);
        }
        let timeout = self
            .deadline
            .map(|deadline| deadline.saturating_duration_since(Instant::now()))
            .unwrap_or(self.timeout);
        if timeout.is_zero() {
            return Err(io::Error::new(
                io::ErrorKind::TimedOut,
                "Rockstar pipe deadline exceeded",
            ));
        }
        let event = unsafe { CreateEventW(null(), 1, 0, null()) };
        if event.is_null() {
            return Err(io::Error::last_os_error());
        }
        let event = OwnedHandle(event);
        let mut overlapped = Overlapped {
            internal: 0,
            internal_high: 0,
            offset: 0,
            offset_high: 0,
            event: event.0,
        };
        let mut transferred = 0;
        let size = size.min(u32::MAX as usize) as u32;
        let ok = unsafe {
            if writing {
                WriteFile(
                    self.handle.0,
                    buffer,
                    size,
                    &mut transferred,
                    &mut overlapped,
                )
            } else {
                ReadFile(
                    self.handle.0,
                    buffer,
                    size,
                    &mut transferred,
                    &mut overlapped,
                )
            }
        };
        if ok != 0 {
            return Ok(transferred as usize);
        }
        let error = io::Error::last_os_error();
        if error.raw_os_error() == Some(ERROR_MORE_DATA) && !writing && transferred > 0 {
            return Ok(transferred as usize);
        }
        if error.raw_os_error() != Some(ERROR_IO_PENDING) {
            return Err(error);
        }
        let wait = unsafe {
            WaitForSingleObject(
                event.0,
                timeout.as_millis().min(u32::MAX as u128 - 1) as u32,
            )
        };
        if wait != 0 {
            let error = if wait == 258 {
                io::Error::new(io::ErrorKind::TimedOut, "Rockstar pipe operation timed out")
            } else {
                io::Error::last_os_error()
            };
            unsafe {
                CancelIoEx(self.handle.0, &mut overlapped);
                GetOverlappedResult(self.handle.0, &mut overlapped, &mut transferred, 1);
            }
            return Err(error);
        }
        let ok =
            unsafe { GetOverlappedResult(self.handle.0, &mut overlapped, &mut transferred, 0) };
        if ok != 0 {
            return Ok(transferred as usize);
        }
        let error = io::Error::last_os_error();
        if error.raw_os_error() == Some(ERROR_MORE_DATA) && !writing && transferred > 0 {
            return Ok(transferred as usize);
        }
        Err(error)
    }
}

impl Read for Pipe {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        self.transfer(buffer.as_mut_ptr(), buffer.len(), false)
    }
}

impl Write for Pipe {
    fn write(&mut self, buffer: &[u8]) -> io::Result<usize> {
        self.transfer(buffer.as_ptr() as *mut u8, buffer.len(), true)
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Frame, Length};
    use std::sync::mpsc;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn name() -> String {
        format!(
            "dd-rockstar-test-{}-{}",
            std::process::id(),
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        )
    }

    #[test]
    fn initialization_deadline_survives_partial_reads() {
        let name = name();
        let listener = Listener::new(&name, Duration::from_secs(2)).unwrap();
        let worker = std::thread::spawn(move || {
            let mut server = listener
                .accept()
                .unwrap()
                .with_deadline(Duration::from_millis(150));
            let mut byte = [0];
            server.read_exact(&mut byte).unwrap();
            std::thread::sleep(Duration::from_millis(180));
            assert_eq!(
                server.read(&mut byte).unwrap_err().kind(),
                io::ErrorKind::TimedOut
            );
        });
        let mut client = Pipe::connect(&name, Duration::from_secs(2)).unwrap();
        client.write_all(&[1]).unwrap();
        worker.join().unwrap();
    }

    #[test]
    fn real_pipe_exchanges_both_framing_modes_and_identifies_the_client() {
        let name = name();
        let listener = Listener::new(&name, Duration::from_secs(2)).unwrap();
        let worker = std::thread::spawn(move || {
            let mut server = listener.accept().unwrap();
            assert_eq!(server.client_pid().unwrap(), std::process::id());
            assert_eq!(
                Frame::read(&mut server, Length::Total).unwrap().tag,
                *b"COLE"
            );
            Frame {
                tag: *b"DUNN",
                payload: vec![b'A'; 640],
                nul_terminated: false,
            }
            .write(&mut server, Length::Body)
            .unwrap();
        });
        let mut client = Pipe::connect(&name, Duration::from_secs(2)).unwrap();
        Frame {
            tag: *b"COLE",
            payload: Vec::new(),
            nul_terminated: true,
        }
        .write(&mut client, Length::Total)
        .unwrap();
        let response = Frame::read(&mut client, Length::Body).unwrap();
        assert_eq!(response.payload.len(), 640);
        assert!(!response.nul_terminated);
        worker.join().unwrap();
    }

    #[test]
    fn timed_out_accept_releases_the_first_pipe_instance() {
        let name = name();
        let listener = Listener::new(&name, Duration::from_millis(20)).unwrap();
        assert!(matches!(listener.accept(), Err(error) if error.kind() == io::ErrorKind::TimedOut));
        assert!(Listener::new(&name, Duration::from_millis(20)).is_ok());
    }

    #[test]
    fn timed_out_read_cancels_the_pending_operation() {
        let name = name();
        let listener = Listener::new(&name, Duration::from_secs(2)).unwrap();
        let (done, wait) = mpsc::channel();
        let worker = std::thread::spawn(move || {
            let _server = listener.accept().unwrap();
            wait.recv_timeout(Duration::from_secs(2)).unwrap();
        });
        let mut client = Pipe::connect(&name, Duration::from_millis(30)).unwrap();
        assert_eq!(
            client.read(&mut [0u8; 4]).unwrap_err().kind(),
            io::ErrorKind::TimedOut
        );
        done.send(()).unwrap();
        worker.join().unwrap();
    }
}
