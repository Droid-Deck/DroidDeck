//! File operations confined to one folder. Every step below the folder is opened relative to its
//! parent's descriptor with `O_NOFOLLOW`, so a symlink anywhere on the way (one that was there
//! before, or one swapped in while we work) makes the operation fail instead of reaching outside.
//! A path check followed by a separate open would leave that window open.

use std::ffi::CString;
use std::fs::File;
use std::io;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd};
use std::os::unix::ffi::OsStrExt;
use std::path::{Component, Path, PathBuf};

pub struct Root {
    fd: OwnedFd,
    path: PathBuf,
}

fn cstr(name: &std::ffi::OsStr) -> io::Result<CString> {
    CString::new(name.as_bytes()).map_err(|_| io::Error::from(io::ErrorKind::InvalidInput))
}

fn check(ret: libc::c_int) -> io::Result<libc::c_int> {
    if ret < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(ret)
    }
}

fn open_dir_at(dir: RawFd, name: &CString) -> io::Result<OwnedFd> {
    let fd = check(unsafe {
        libc::openat(
            dir,
            name.as_ptr(),
            libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC,
        )
    })?;
    Ok(unsafe { OwnedFd::from_raw_fd(fd) })
}

/// The plain names of `rel`; anything else (`..`, a root, a prefix) is refused.
fn names(rel: &Path) -> io::Result<Vec<CString>> {
    rel.components()
        .filter(|c| !matches!(c, Component::CurDir))
        .map(|c| match c {
            Component::Normal(name) => cstr(name),
            _ => Err(io::Error::from(io::ErrorKind::InvalidInput)),
        })
        .collect()
}

impl Root {
    /// Opens (creating it if needed) the folder `path`. The folder itself may be reached through
    /// symlinks (library aliases); only what is below it is confined.
    pub fn open(path: &Path, create: bool) -> io::Result<Root> {
        if create {
            std::fs::create_dir_all(path)?;
        }
        let name = cstr(path.as_os_str())?;
        let fd = check(unsafe {
            libc::open(
                name.as_ptr(),
                libc::O_RDONLY | libc::O_DIRECTORY | libc::O_CLOEXEC,
            )
        })?;
        Ok(Root {
            fd: unsafe { OwnedFd::from_raw_fd(fd) },
            path: path.to_path_buf(),
        })
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    /// The folder holding `rel` (its last name returned beside it), each step opened without
    /// following symlinks; missing folders are created when `create`.
    fn parent(&self, rel: &Path, create: bool) -> io::Result<(OwnedFd, CString)> {
        let mut parts = names(rel)?;
        let last = parts
            .pop()
            .ok_or_else(|| io::Error::from(io::ErrorKind::InvalidInput))?;
        let mut dir = self.fd.try_clone()?;
        for part in parts {
            dir = match open_dir_at(dir.as_raw_fd(), &part) {
                Ok(next) => next,
                Err(err) if create && err.raw_os_error() == Some(libc::ENOENT) => {
                    let made = unsafe { libc::mkdirat(dir.as_raw_fd(), part.as_ptr(), 0o755) };
                    if made < 0 {
                        let err = io::Error::last_os_error();
                        if err.raw_os_error() != Some(libc::EEXIST) {
                            return Err(err);
                        }
                    }
                    open_dir_at(dir.as_raw_fd(), &part)?
                }
                Err(err) => return Err(err),
            };
        }
        Ok((dir, last))
    }

    /// Creates the folder `rel` and its missing parents.
    pub fn make_dirs(&self, rel: &Path) -> io::Result<()> {
        let (dir, last) = self.parent(rel, true)?;
        let made = unsafe { libc::mkdirat(dir.as_raw_fd(), last.as_ptr(), 0o755) };
        if made < 0 {
            let err = io::Error::last_os_error();
            if err.raw_os_error() != Some(libc::EEXIST) {
                return Err(err);
            }
        }
        open_dir_at(dir.as_raw_fd(), &last).map(drop)
    }

    /// Opens the file `rel` for reading and writing, creating it (and its folders) when missing.
    pub fn open_rw(&self, rel: &Path, mode: u32) -> io::Result<File> {
        let (dir, last) = self.parent(rel, true)?;
        let fd = check(unsafe {
            libc::openat(
                dir.as_raw_fd(),
                last.as_ptr(),
                libc::O_RDWR | libc::O_CREAT | libc::O_NOFOLLOW | libc::O_CLOEXEC,
                mode as libc::c_uint,
            )
        })?;
        Ok(unsafe { File::from_raw_fd(fd) })
    }

    /// Opens the existing file `rel` for reading.
    pub fn open_read(&self, rel: &Path) -> io::Result<File> {
        let (dir, last) = self.parent(rel, false)?;
        let fd = check(unsafe {
            libc::openat(
                dir.as_raw_fd(),
                last.as_ptr(),
                libc::O_RDONLY | libc::O_NOFOLLOW | libc::O_CLOEXEC,
            )
        })?;
        Ok(unsafe { File::from_raw_fd(fd) })
    }

    /// The type of `rel` without following a final symlink; `None` when it does not exist.
    pub fn kind(&self, rel: &Path) -> io::Result<Option<libc::mode_t>> {
        let (dir, last) = match self.parent(rel, false) {
            Ok(found) => found,
            Err(err) if err.raw_os_error() == Some(libc::ENOENT) => return Ok(None),
            Err(err) => return Err(err),
        };
        let mut st: libc::stat = unsafe { std::mem::zeroed() };
        let ret = unsafe {
            libc::fstatat(
                dir.as_raw_fd(),
                last.as_ptr(),
                &mut st,
                libc::AT_SYMLINK_NOFOLLOW,
            )
        };
        if ret < 0 {
            let err = io::Error::last_os_error();
            return if err.raw_os_error() == Some(libc::ENOENT) {
                Ok(None)
            } else {
                Err(err)
            };
        }
        Ok(Some(st.st_mode & libc::S_IFMT))
    }

    /// Replaces whatever is at `rel` (never following it) with a symlink to `target`.
    pub fn symlink(&self, rel: &Path, target: &str) -> io::Result<()> {
        let (dir, last) = self.parent(rel, true)?;
        let unlinked = unsafe { libc::unlinkat(dir.as_raw_fd(), last.as_ptr(), 0) };
        if unlinked < 0 {
            let err = io::Error::last_os_error();
            if err.raw_os_error() != Some(libc::ENOENT) {
                return Err(err);
            }
        }
        let target = CString::new(target).map_err(|_| io::Error::from(io::ErrorKind::InvalidInput))?;
        check(unsafe { libc::symlinkat(target.as_ptr(), dir.as_raw_fd(), last.as_ptr()) }).map(drop)
    }

    /// Unlinks the file or symlink `rel` without following it.
    pub fn remove_file(&self, rel: &Path) -> io::Result<()> {
        let (dir, last) = self.parent(rel, false)?;
        check(unsafe { libc::unlinkat(dir.as_raw_fd(), last.as_ptr(), 0) }).map(drop)
    }

    /// Moves the regular file `rel` of this folder to `dest_rel` in `dest` when nothing is there yet.
    /// Returns false when the destination exists or the source is not a regular file.
    pub fn move_new(&self, rel: &Path, dest: &Root, dest_rel: &Path) -> io::Result<bool> {
        if self.kind(rel)? != Some(libc::S_IFREG) || dest.kind(dest_rel)?.is_some() {
            return Ok(false);
        }
        let (from, from_name) = self.parent(rel, false)?;
        let (to, to_name) = dest.parent(dest_rel, true)?;
        check(unsafe {
            libc::renameat(
                from.as_raw_fd(),
                from_name.as_ptr(),
                to.as_raw_fd(),
                to_name.as_ptr(),
            )
        })?;
        Ok(true)
    }

    /// Every regular file below this folder, as relative paths; symlinks are neither followed
    /// nor listed.
    pub fn files(&self) -> Vec<PathBuf> {
        let mut out = Vec::new();
        let mut pending = vec![PathBuf::new()];
        while let Some(rel) = pending.pop() {
            let dir = if rel.as_os_str().is_empty() {
                self.fd.try_clone().ok()
            } else {
                self.parent(&rel, false)
                    .and_then(|(parent, last)| open_dir_at(parent.as_raw_fd(), &last))
                    .ok()
            };
            let Some(dir) = dir else {
                continue;
            };
            let Ok(entries) = std::fs::read_dir(format!("/proc/self/fd/{}", dir.as_raw_fd())) else {
                continue;
            };
            for entry in entries.flatten() {
                let child = rel.join(entry.file_name());
                match self.kind(&child) {
                    Ok(Some(libc::S_IFDIR)) => pending.push(child),
                    Ok(Some(libc::S_IFREG)) => out.push(child),
                    _ => {}
                }
            }
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn base(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("dd-safefs-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn creates_and_opens_below_the_folder() {
        let dir = base("open");
        let root = Root::open(&dir.join("game"), true).unwrap();
        let mut f = root.open_rw(Path::new("a/b/c.bin"), 0o644).unwrap();
        std::io::Write::write_all(&mut f, b"x").unwrap();
        assert_eq!(fs::read(dir.join("game/a/b/c.bin")).unwrap(), b"x");
        assert!(root.open_read(Path::new("a/b/c.bin")).is_ok());
        assert_eq!(root.kind(Path::new("a/b")).unwrap(), Some(libc::S_IFDIR));
        assert_eq!(root.kind(Path::new("a/missing")).unwrap(), None);
        assert!(root.open_rw(Path::new("../escape"), 0o644).is_err());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn never_follows_a_symlink_below_the_folder() {
        let dir = base("links");
        let outside = dir.join("outside");
        fs::create_dir_all(&outside).unwrap();
        let root = Root::open(&dir.join("game"), true).unwrap();
        std::os::unix::fs::symlink(&outside, dir.join("game/link")).unwrap();
        std::os::unix::fs::symlink(outside.join("x"), dir.join("game/dangling")).unwrap();
        assert!(root.open_rw(Path::new("link/f.bin"), 0o644).is_err());
        assert!(root.make_dirs(Path::new("link/sub")).is_err());
        assert!(root.open_rw(Path::new("dangling"), 0o644).is_err());
        assert!(root.open_read(Path::new("link/f.bin")).is_err());
        assert!(!outside.join("f.bin").exists() && !outside.join("sub").exists() && !outside.join("x").exists());
        root.remove_file(Path::new("link")).unwrap();
        assert!(outside.is_dir());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn moves_files_between_folders_without_replacing() {
        let dir = base("move");
        let from = Root::open(&dir.join("staging"), true).unwrap();
        let to = Root::open(&dir.join("game"), true).unwrap();
        from.open_rw(Path::new("sub/a.bin"), 0o644).unwrap();
        from.open_rw(Path::new("b.bin"), 0o644).unwrap();
        to.open_rw(Path::new("b.bin"), 0o644).unwrap();
        std::os::unix::fs::symlink("/etc", dir.join("staging/link")).unwrap();
        let mut files = from.files();
        files.sort();
        assert_eq!(files, vec![PathBuf::from("b.bin"), PathBuf::from("sub/a.bin")]);
        assert!(from.move_new(Path::new("sub/a.bin"), &to, Path::new("sub/a.bin")).unwrap());
        assert!(!from.move_new(Path::new("b.bin"), &to, Path::new("b.bin")).unwrap());
        assert!(!from.move_new(Path::new("link"), &to, Path::new("link")).unwrap());
        assert!(dir.join("game/sub/a.bin").is_file());
        let _ = fs::remove_dir_all(&dir);
    }
}
