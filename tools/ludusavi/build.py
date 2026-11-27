import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

VERSION = "0.31.0"
URL = f"https://codeload.github.com/mtkennerly/ludusavi/tar.gz/refs/tags/v{VERSION}"
ROOT = Path(__file__).resolve().parents[2]
STUB = Path(__file__).resolve().parent / "rfd-stub"
OUTPUT = ROOT / "tools/linuxfs/ludusavi/linuxfs/usr/local/lib/droiddeck-ludusavi/ludusavi"

CARGO_OLD = '''[[bin]]
name = "ludusavi"
required-features = ["app"]

[features]
default = ["app"]
# GUI and CLI
app = [
    "dep:clap",
    "dep:clap_complete",
    "dep:dialoguer",
    "dep:flexi_logger",
    "dep:iced",
    "dep:image",
    "dep:indicatif",
    "dep:rfd",
    "dep:signal-hook",
]
'''

CARGO_NEW = '''[[bin]]
name = "ludusavi"

[features]
default = ["app"]
cli = [
    "dep:clap",
    "dep:clap_complete",
    "dep:dialoguer",
    "dep:flexi_logger",
    "dep:indicatif",
    "dep:rfd",
    "dep:signal-hook",
]
app = ["cli", "dep:iced", "dep:image"]

[patch.crates-io]
rfd = { path = "vendor/rfd-stub" }
'''

MAIN_OLD = '''    match args.sub {
        None => {
            #[cfg(target_os = "windows")]
            if std::env::var(crate::prelude::ENV_DEBUG).is_err() {
                unsafe {
                    detach_console(debug);
                }
            }

            let flags = Flags {
                update_manifest: !args.no_manifest_update,
                custom_game: None,
            };
            gui::run(flags);
        }
        Some(cli::parse::Subcommand::Gui { custom_game }) => {
            #[cfg(target_os = "windows")]
            if std::env::var(crate::prelude::ENV_DEBUG).is_err() {
                unsafe {
                    detach_console(debug);
                }
            }

            let flags = Flags {
                update_manifest: !args.no_manifest_update,
                custom_game,
            };
            gui::run(flags);
        }
        Some(sub) => {
'''

MAIN_NEW = '''    match args.sub {
        #[cfg(feature = "app")]
        None => {
            #[cfg(target_os = "windows")]
            if std::env::var(crate::prelude::ENV_DEBUG).is_err() {
                unsafe {
                    detach_console(debug);
                }
            }

            let flags = Flags {
                update_manifest: !args.no_manifest_update,
                custom_game: None,
            };
            gui::run(flags);
        }
        #[cfg(not(feature = "app"))]
        None => {
            eprintln!("This build does not include the GUI; pass a subcommand such as backup or restore.");
            failed = true;
        }
        #[cfg(feature = "app")]
        Some(cli::parse::Subcommand::Gui { custom_game }) => {
            #[cfg(target_os = "windows")]
            if std::env::var(crate::prelude::ENV_DEBUG).is_err() {
                unsafe {
                    detach_console(debug);
                }
            }

            let flags = Flags {
                update_manifest: !args.no_manifest_update,
                custom_game,
            };
            gui::run(flags);
        }
        #[cfg(not(feature = "app"))]
        Some(cli::parse::Subcommand::Gui { .. }) => {
            eprintln!("This build does not include the GUI.");
            failed = true;
        }
        Some(sub) => {
'''


def replace(path, old, new, count=1):
    text = path.read_text()
    if old not in text:
        if new in text:
            return
        raise SystemExit(f"upstream layout changed: {old[:60]!r} not in {path.name}")
    path.write_text(text.replace(old, new, count))


def main():
    work = Path(os.environ.get("DROIDDECK_LUDUSAVI_BUILD_DIR", tempfile.gettempdir())) / f"ludusavi-{VERSION}"
    archive = work.parent / f"ludusavi-{VERSION}.tar.gz"
    source = work / f"ludusavi-{VERSION}"
    work.mkdir(parents=True, exist_ok=True)
    if not archive.is_file():
        urllib.request.urlretrieve(URL, archive)
    if not source.is_dir():
        with tarfile.open(archive) as tar:
            tar.extractall(work)
    replace(source / "Cargo.toml", CARGO_OLD, CARGO_NEW)
    replace(source / "src/main.rs", "mod cli;\nmod gui;\n", 'mod cli;\n#[cfg(feature = "app")]\nmod gui;\n')
    replace(source / "src/main.rs", "use self::gui::Flags;\n", '#[cfg(feature = "app")]\nuse self::gui::Flags;\n')
    replace(source / "src/main.rs", MAIN_OLD, MAIN_NEW)
    replace(source / "src/prelude.rs", '#[cfg(feature = "app")]', '#[cfg(feature = "cli")]', -1)
    vendor = source / "vendor/rfd-stub"
    shutil.copytree(STUB, vendor, dirs_exist_ok=True)
    subprocess.run([
        "cargo", "build", "--release", "--no-default-features", "--features", "cli",
    ], cwd=source, check=True)
    shutil.copyfile(source / "target/release/ludusavi", OUTPUT)
    print(f"wrote {OUTPUT}")


if __name__ == "__main__":
    sys.exit(main())
