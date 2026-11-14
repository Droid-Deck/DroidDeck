/**
 * The Node-API surface of `libdroiddeck_native.so`.
 *
 * The native module owns everything the ArkTS shell cannot express: it
 * supervises the Linux guest, hosts the Wayland compositor that carries the
 * guest's pixels to an ArkUI XComponent, forwards gamepad events into the
 * guest and relays the guest's audio to the speaker.
 *
 * The declarations here are the contract between the two sides. They are
 * checked against the built library by `tools/check_native_deps.sh`, so a
 * function that is renamed on one side and not the other is caught before
 * it reaches a device.
 */

/** The GPU and hardware facts the launcher reports. */
export interface NativeDeviceInfo {
  /** The GPU's name as the driver reports it. */
  gpuName: string;
  /** The GPU's vendor: 'qualcomm', 'mali', 'arm', 'imagination' or 'unknown'. */
  gpuVendor: string;
  /** The vendor's model number, 0 when it is unknown. */
  gpuModel: number;
  /**
   * The OpenGL ES version the phone's driver reports, e.g.
   * "OpenGL ES 3.2 V@0.0.0". The compositor needs 3.0.
   */
  glesVersion: string;
  /** The device brand, filled in by ArkTS. */
  deviceBrand: string;
  /** The device model, filled in by ArkTS. */
  deviceModel: string;
  /** The operating system release, filled in by ArkTS. */
  osRelease: string;
  /** The API level, filled in by ArkTS. */
  apiLevel: number;
  /** The CPU architecture the guest can run. */
  abi: string;
  /** How many CPU cores the device has. */
  cpuCores: number;
  /** How much memory the device has, in MiB. */
  ramMiB: number;
  /** Whether the device exposes a usable Vulkan driver to the guest. */
  vulkanBridge: boolean;
}

/** How the guest session is executed. */
export type NativeRuntimeMode = 'proot' | 'direct' | 'unsupported';

/** The state of the runtime and the running session. */
export interface NativeRuntimeStatus {
  /** Whether a usable runtime is installed. */
  installed: boolean;
  /** The installed runtime's version, empty when it is unknown. */
  version: string;
  /** Where the guest rootfs lives inside the app sandbox. */
  rootDir: string;
  /** How much space the runtime takes, in bytes. */
  sizeBytes: number;
  /** Whether a guest session is running. */
  running: boolean;
  /** The guest's process id, 0 when nothing is running. */
  pid: number;
  /** How the guest is launched. */
  mode: NativeRuntimeMode;
  /** The last error, empty when the last call succeeded. */
  lastError: string;
}

/** The result of unpacking or installing a runtime. */
export interface NativeUnpackResult {
  /** Whether the operation succeeded. */
  ok: boolean;
  /** Why it failed, empty when it succeeded. */
  error: string;
  /** How many files were written. */
  files: number;
  /** How many bytes were written. */
  bytes: number;
}

/** What the guest session is told to run. */
export interface NativeRuntimeOptions {
  /** Absolute path of the guest rootfs inside the app sandbox. */
  rootDir: string;
  /**
   * Absolute path of the host directory that holds the compositor's Wayland
   * socket. It is bound into the guest as /run/droiddeck.
   */
  hostRuntimeDir: string;
  /**
   * The app's files directory, bound into the guest at the same path so the
   * session log the guest writes can be read by the launcher.
   */
  hostFilesDir: string;
  /**
   * The app's cache directory, bound into the guest at the same path.
   */
  hostCacheDir: string;
  /** Command to run inside the guest. */
  command: string;
  /** Arguments for the guest command. */
  args: string[];
  /** Working directory inside the guest. */
  cwd: string;
  /** Extra environment variables for the guest. */
  env: Record<string, string>;
  /**
   * The guest's Vulkan ICD directory, as the guest sees it. The loader
   * looks for its manifests there.
   */
  vulkanDriver: string;
  /**
   * The Vulkan ICD manifest the guest is pinned to, as the guest sees it.
   * `VK_ICD_FILENAMES` names manifest files, not the directory.
   */
  vulkanIcd: string;
  /** Whether to expose the guest desktop surface to the launcher. */
  desktop: boolean;
}

/** Everything the native installer needs to turn a download into a runtime. */
export interface NativeInstallOptions {
  /** Absolute path of the downloaded archive. */
  archive: string;
  /** Absolute path of the rootfs the runtime is unpacked into. */
  destination: string;
  /** Absolute path of the overlay archive applied over the rootfs. */
  overlay: string;
  /** Version written into the runtime's version marker. */
  version: string;
  /** Expected SHA-256 of the archive; empty skips the check. */
  sha256: string;
}

/**
 * The result of starting the PulseAudio server.
 */
export interface NativeAudioServerResult {
  ok: boolean;
  error: string;
  /** The address the guest connects to, for PULSE_SERVER. */
  address: string;
  /** The socket the native audio sink connects to. */
  socket: string;
}

/** Reads the GPU and hardware facts. */
export const getDeviceInfo: () => NativeDeviceInfo;

/**
 * Reads the state of the runtime and the running session.
 *
 * @param rootDir  the guest rootfs to look at. The install check depends on
 *        it, so it has to be passed before a session has ever been started;
 *        without it the running session's own root is used.
 */
export const runtimeStatus: (rootDir?: string) => NativeRuntimeStatus;

/** Starts a guest session. */
export const runtimeStart: (options: NativeRuntimeOptions) => boolean;

/** Stops the guest session. */
export const runtimeStop: () => boolean;

/** Unpacks a runtime archive. */
export const runtimeUnpack: (archivePath: string, destination: string) => NativeUnpackResult;

/** Installs a runtime: verify, unpack, apply the overlay, mark the version. */
export const runtimeInstall: (options: NativeInstallOptions) => NativeUnpackResult;

/** Whether a file matches the expected SHA-256 digest. */
export const runtimeVerifySha256: (path: string, expected: string) => boolean;

/** Recent supervisor log lines, oldest first. */
export const logTail: (maxLines: number) => string[];

/** Brings up the EGL context on the XComponent's native window. */
export const surfaceAttach: () => boolean;

/** Releases the EGL context and the native window. */
export const surfaceDetach: () => void;

/** Opens the guest-facing uinput device used for gamepad forwarding. */
export const inputOpen: () => boolean;

/** Closes the gamepad device. */
export const inputClose: () => void;

/**
 * Forwards one gamepad event into the guest.
 *
 * @param kind 'button' | 'axis' | 'hat'
 * @param code button or axis code
 * @param value -1..1 for axes, 0/1 for buttons
 */
export const inputEvent: (kind: string, code: number, value: number) => void;

/**
 * Opens the guest audio socket.
 *
 * @param socketPath path of the guest's PCM socket
 */
export const audioOpen: (socketPath: string) => boolean;

/**
 * Pulls interleaved stereo float frames from the guest.
 *
 * @param maxFrames maximum number of frames to read
 */
export const audioRead: (maxFrames: number) => ArrayBuffer;

/** Closes the guest audio socket. */
export const audioClose: () => void;

/**
 * Starts the PulseAudio server the guest's audio client connects to.
 *
 * @param runtimeDir the directory the sockets and the config live in
 * @param binaryDir the directory the HAP's libraries were unpacked into
 * @param logPath where the server's output is written
 */
export const audioServerStart: (runtimeDir: string, binaryDir: string,
  logPath: string) => NativeAudioServerResult;

/** Stops the PulseAudio server. */
export const audioServerStop: () => void;

/**
 * Starts the compositor thread.
 *
 * @param runtimeDir the directory the Wayland socket is created in
 * @param onEvent receives the compositor's events
 */
export const compositorStart: (runtimeDir: string,
  onEvent: (name: string, detail: string) => void) => boolean;

/** Stops the compositor thread. */
export const compositorStop: () => boolean;

/** The compositor thread's id, or 0 when it is not running. */
export const compositorTid: () => number;

/** Why presenting failed, empty while it works. */
export const compositorFault: () => string;

/** The display driver as the compositor reports it. */
export const displayDriver: () => string;

/** Feeds a pointer event into the guest. */
export const compositorSendPointer: (action: number, x: number, y: number) => void;

/** Feeds a touch event into the guest. */
export const compositorSendTouch: (action: number, pointerId: number, x: number, y: number) => void;

/** Feeds a key event into the guest. */
export const compositorSendKey: (evdev: number, state: number) => void;

/** Tells the compositor how large the output surface is. */
export const compositorSetOutputSize: (width: number, height: number) => void;

/** A snapshot of the cursor. */
export const cursorSnapshot: () => number[];

/** The HUD's frame times, in milliseconds. */
export const hudFrameTimes: () => Float32Array;
