# Android's child-process limit and the guest session

Since Android 12 the platform counts the processes an app forks as "phantom processes" and
kills them under pressure. A Wine, FEX or Steam session forks dozens of them, so this limit
decides whether a session survives. This note records how the platform counts, what it kills,
why the guest cannot leave the count, and what DroidDeck changes on the device.

## What the platform counts

Discovery is a cgroup scan, not a parent-child relation. For every app process the platform
knows, it reads the cgroup member list of that process and takes every pid that is not a known
app process as a phantom of it:

- `frameworks/base/services/core/java/com/android/server/am/PhantomProcessList.java:117-122`
  holds the two cgroup roots (`/acct/uid_` for cgroup v1, `/sys/fs/cgroup/uid_` for v2) and
  the `cgroup.procs` file name.
- `PhantomProcessList.java:136-208` scans every app process; `:220-222` builds the path
  `/…/uid_<uid>/pid_<appPid>/cgroup.procs`; `:238-275` turns a pid that is not a known app
  process into a phantom of that app process.
- The mechanism is the same in Android 12.0, 12.1, 13, 14, 15, 16 and in `main`. Only the
  path lookup moved to a native helper in 16 and `main`
  (`PhantomProcessList.java:205`).

DroidDeck's guest is forked by the main app process: `SessionService` starts it with
`HostProcess.start` (`app/src/main/java/com/droiddeck/launcher/session/SessionService.kt:779`),
and both `SessionService` and `SessionActivity` run in the main process
(`app/src/main/AndroidManifest.xml:284-293`, `:326-329`). Every guest process is therefore a
phantom of the main app process.

## What the platform kills

1. Count trim. When the number of phantom processes in the system passes
   `MAX_PHANTOM_PROCESSES` (32 by default, `ActivityManagerConstants.java:192`), the surplus
   is killed, starting with the phantoms whose parent app has the least importance
   (`PhantomProcessList.java:423-466`).
2. Excessive CPU group kill. Every five minutes the platform measures each app's CPU use. For
   an app whose process state is HOME or less important (backgrounded), a phantom process that
   used more than its threshold (25, 25, 10 or 2 percent) is killed together with its phantom
   siblings **and the app process itself**
   (`ActivityManagerService.java:16114-16152`, `PhantomProcessList.java:496-515`). A
   foreground DroidDeck is exempt; a backgrounded one is not.

The second path is the one that ends a session without an error message in the guest log: the
app process dies with it.

## The gate, and how the versions differ

Both paths are behind the feature flag `settings_enable_monitor_phantom_procs`, whose default
is on. The flag is read from `Settings.Global` first
(`frameworks/base/core/java/android/util/FeatureFlagUtils.java:68-70`, `:272-283`). When it is
off, the trim returns early (`PhantomProcessList.java:423-427`) and the phantom CPU check is
skipped (`ActivityManagerService.java:16117-16118`, `:16144-16148`). From 12L on it also stops
the periodic cgroup scan that discovers phantom processes (`AppProfiler.java:1788-1829` at
`android-12.1.0_r1`).

| Android | flag `settings_enable_monitor_phantom_procs` | count limit | CPU group kill |
| --- | --- | --- | --- |
| 12.0 (SDK 31) | does not exist | DeviceConfig `activity_manager/max_phantom_processes`, default 32 | not gated |
| 12L (SDK 32) | exists | flag | flag |
| 13 and later (SDK 33+) | exists | flag | flag |

The flag was added in 12.1. On 12.0 the count limit comes from DeviceConfig
(`ActivityManagerConstants.java:1202-1206` in 12.0) and the CPU group kill has no gate at all
(`ActivityManagerService.java:14237-14271` in 12.0).

## Why the guest cannot leave the count

- **Reparenting does not help.** A double fork, `setsid()` or an orphaning parent changes the
  parent and the session, not the cgroup. Cgroup membership is inherited at fork and only a
  write into another cgroup's `cgroup.procs` changes it, so the platform still finds the pid
  in the app process's cgroup and still counts it. On cgroup v2 the uid group cannot take
  processes while it has the per-process groups as children, so there is no unscanned group to
  move into.
- **A separate `:process` service only moves the count.** The scan covers every app process the
  platform knows, so the guest tree started from another service of the same app is counted
  against that service. The manifest already has such services
  (`app/src/main/AndroidManifest.xml:78`, `:244`, `:255`), and the guest tree is one coupled
  userspace whose graphics, input and session state would not survive the split.
- **The platform's own blind spot is out of reach.** Processes forked from an app zygote are
  skipped by the scan (`PhantomProcessList.java:153-155`), but `android.os.AppZygote` is
  `@hide` (`frameworks/base/core/java/android/os/AppZygote.java:36-38`) and hidden API
  enforcement keeps it away from apps.
- **Detaching would break the session.** The guest tree is deliberately kept under a reaper: the
  processes that load the session library mark themselves as child subreapers, so that a
  double-forked Xwayland is reparented inside the session instead of to init, where a vendor
  kernel's orphan reaper would kill it (`tools/linuxfs/preload/subreaper.c:1-16`). A guest
  tree that leaves the app's process tree leaves that arrangement with nothing to reap it.

## What DroidDeck changes on the device

`PhantomProcessLimit` (`app/src/main/java/com/droiddeck/launcher/core/PhantomProcessLimit.kt`)
holds the platform lever per version: the DeviceConfig count limit on SDK 31 and the flag from
SDK 32 up (`:23-37`). `WirelessAdbFix`
(`app/src/main/java/com/droiddeck/launcher/core/WirelessAdbFix.kt:219-228`) runs those shell
commands over wireless debugging when the user has no computer at hand, and the gate screen
shows the result. The names on that screen come from `strings.xml` (`phantom_title_on`
"Child-process limit is on", `phantom_instructions_fix`, `phantom_fix_toggle`).

## The remaining gap on Android 12.0

On SDK 31 the count trim is covered by the DeviceConfig write, but the backgrounded CPU group
kill is not: the flag does not exist there, and the CPU thresholds live in the global string
`activity_manager_constants` (`power_check_max_cpu_1` … `power_check_max_cpu_4`), not in
DeviceConfig. In 12.0 the DeviceConfig listener reacts to `max_phantom_processes` only
(`ActivityManagerConstants.java:654-757`), while the thresholds are parsed from
`Settings.Global activity_manager_constants` (`:852-892`). Raising them means rewriting that
global string, which changes the CPU kill thresholds for every app on the device. That is a
trade the developers have to accept; DroidDeck does not do it on its own.

## Probe to run on hardware

The mechanism above is source-verified. The probe below settles the two points a source read
cannot: what a device actually does with detached children, and whether the app uid may write
another cgroup's `cgroup.procs`. It needs a device with the guest running.

Precondition: keep `settings_enable_monitor_phantom_procs` enabled on Android 12L (SDK 32) and
later, because disabling it also stops the cgroup scan, so no snapshot would discover either batch
(`AppProfiler.java:1788-1829` at `android-12.1.0_r1`). Android 12.0 (SDK 31) has no such flag and
scans unconditionally (`AppProfiler.java:1833-1834` at `android-12.0.0_r1`). An enabled flag, and
every 12.0 device, also keeps the backgrounded CPU group kill active, which can end the guest
together with the app process (see "What the platform kills", item 2). Keep DroidDeck in the
foreground and stop CPU-intensive guest programs before the probe.

The limit counts phantom processes of every app on the device, not only the probe's, so raise it
before anything can trim. Record the current flag and limit values first. Then raise
`max_phantom_processes` well above any plausible device-wide count, and only after that enable the
flag if it was disabled, because a flag enabled under the default limit of 32 can trim existing
phantoms of every app at once. Wait for the platform to discover the existing phantoms, read the
device-wide phantom count, and check that the limit is above that count, plus both batches (80),
plus headroom for processes the guest and other apps start meanwhile; raise it again if not. With
the limit at its default, starting 80 long-lived processes can trigger the platform trim and
disrupt the guest session and processes of other apps. Restore the recorded values once the probe
processes have exited, in reverse order: the flag first, then the limit, because lowering the limit
schedules a trim that is skipped only while the flag is disabled.

Run the `sleep` loops from the app's own shell so the children run under the app uid. Run each
`dumpsys activity processes` snapshot from an authorized ADB shell, because the app uid normally
cannot read that dump. After each batch, record the child PIDs and wait long enough for the
platform to discover them before taking the snapshot.

```sh
# record the current values
adb shell settings get global settings_enable_monitor_phantom_procs
adb shell device_config get activity_manager max_phantom_processes

# raise the limit first, then enable the flag on SDK 32 and later
adb shell device_config put activity_manager max_phantom_processes 1000
adb shell settings put global settings_enable_monitor_phantom_procs true
# after platform discovery: device-wide phantom count
adb shell 'dumpsys activity processes | grep -i -n -A 5 phantom'

# from the app's own shell: attached children
for i in $(seq 1 40); do sleep 600 & echo $!; done
# from an authorized ADB shell, after platform discovery
adb shell 'dumpsys activity processes | grep -i -n -A 5 phantom'

# from the app's own shell: detached children
# (the middle process exits, so the child is reparented to init)
for i in $(seq 1 40); do ( setsid sleep 600 & echo $! ) ; done
# from an authorized ADB shell, after platform discovery
adb shell 'dumpsys activity processes | grep -i -n -A 5 phantom'

# from the app's own shell
cat /proc/<appPid>/cgroup

# whether the uid may move a child out of the scanned cgroup
echo <childPid> > /sys/fs/cgroup/uid_<uid>/cgroup.procs

# from the app's own shell: end the probe processes
kill <childPids>
# restore the flag first: put the recorded value, or delete the key if it was "null"
adb shell settings put global settings_enable_monitor_phantom_procs <recordedFlag>
adb shell settings delete global settings_enable_monitor_phantom_procs
# then the limit: put the recorded value, or delete the key if it was "null"
adb shell device_config put activity_manager max_phantom_processes <recordedLimit>
adb shell device_config delete activity_manager max_phantom_processes
```

Compare the PIDs recorded for each batch with the phantom list in the snapshot taken after that
batch. Expected from the source: both batches are counted, the detached ones included, and the
cgroup write fails (no unscanned group, or no permission).

## References

- AOSP `frameworks/base`: `services/core/java/com/android/server/am/PhantomProcessList.java`,
  `services/core/java/com/android/server/am/ActivityManagerService.java`,
  `services/core/java/com/android/server/am/ActivityManagerConstants.java`,
  `core/java/android/util/FeatureFlagUtils.java`, checked at tags android-12.0.0_r1,
  android-12.1.0_r1, android-13.0.0_r1, android-14.0.0_r2, android-15.0.0_r1,
  android-16.0.0_r1 and `main`.
- Pull request 1394, the OEM check of the same settings, and the issues it links, 713 and 1083.
- Termux meets the same limit on Android 12 and solves it the same way, with detection plus
  the adb path (termux-app issues 4388 and 4857).
