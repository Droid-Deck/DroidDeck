#!/usr/bin/env python3
"""Resolve one authorized Android device, collapsing transports for the same device."""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys


class DeviceResolutionError(RuntimeError):
    pass


def _run(adb: str, *args: str, timeout: int = 15) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            [adb, *args], capture_output=True, text=True, check=False, timeout=timeout
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise DeviceResolutionError(str(exc)) from exc


def _device_list(adb: str) -> list[str]:
    result = _run(adb, "devices", "-l")
    if result.returncode != 0:
        raise DeviceResolutionError(result.stderr.strip() or "adb devices failed")
    devices = []
    for line in result.stdout.splitlines()[1:]:
        fields = line.split()
        if len(fields) >= 2 and fields[1] == "device":
            devices.append(fields[0])
    return devices


def _identity(adb: str, serial: str) -> str | None:
    for prop in ("ro.serialno", "ro.boot.serialno"):
        result = _run(adb, "-s", serial, "shell", "getprop", prop)
        value = result.stdout.strip()
        if result.returncode == 0 and value and value.lower() not in {"unknown", "null", "0"}:
            return value
    return None


def _rank(serial: str) -> tuple[int, str]:
    # Prefer a stable mDNS transport over a network address with an ephemeral port.
    if "_adb-tls-connect._tcp" in serial:
        return (0, serial)
    if not re.search(r":\d+$", serial):
        return (1, serial)
    return (2, serial)


def resolve_serial(adb: str, explicit: str | None = None, *, log: bool = False) -> str:
    selected = explicit or os.environ.get("ADB_SERIAL") or os.environ.get("ANDROID_SERIAL")
    if selected:
        result = _run(adb, "-s", selected, "get-state")
        if result.returncode != 0 or result.stdout.strip() != "device":
            raise DeviceResolutionError(f"ADB serial '{selected}' is not an authorized device")
        serial = selected
    else:
        devices = _device_list(adb)
        if not devices:
            raise DeviceResolutionError("No authorized Android device found; set ADB_SERIAL or ANDROID_SERIAL")
        identities = {device: _identity(adb, device) for device in devices}
        groups: dict[str, list[str]] = {}
        for device in devices:
            identity = identities[device]
            key = f"device:{identity}" if identity else f"transport:{device}"
            groups.setdefault(key, []).append(device)
        if len(groups) != 1:
            detail = ", ".join(
                f"{device}" + (f" (serial {identities[device]})" if identities[device] else "")
                for device in devices
            )
            raise DeviceResolutionError(
                f"Multiple Android devices found ({detail}); set ADB_SERIAL or ANDROID_SERIAL"
            )
        aliases = next(iter(groups.values()))
        serial = min(aliases, key=_rank)
        if log and len(aliases) > 1:
            print(f"Collapsed {len(aliases)} ADB transports for one device.", file=sys.stderr)

    if log:
        print(f"Resolved ADB serial: {serial}", file=sys.stderr)
    return serial


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    parser.add_argument("--serial", help="Explicit ADB serial; defaults to ADB_SERIAL or ANDROID_SERIAL")
    args = parser.parse_args()
    try:
        print(resolve_serial(args.adb, args.serial, log=True))
        return 0
    except DeviceResolutionError as exc:
        print(f"adb-device: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
