import { Logger } from '../common/Logger';
import nativeModule from 'libdroiddeck_native.so';

/**
 * Shape returned by the native `getDeviceInfo` binding.
 */
export interface NativeDeviceInfo {
  gpuName: string;
  gpuVendor: string;
  gpuModel: number;
  /**
   * The OpenGL ES version the phone's driver reports. The compositor needs
   * 3.0, so this is the fact that decides whether the phone can run it.
   */
  glesVersion: string;
  deviceBrand: string;
  deviceModel: string;
  osRelease: string;
  apiLevel: number;
  abi: string;
  cpuCores: number;
  ramMiB: number;
  vulkanBridge: boolean;
}

/**
 * Shape returned by the native `runtimeStatus` binding.
 */
export interface NativeRuntimeStatus {
  installed: boolean;
  version: string;
  rootDir: string;
  sizeBytes: number;
  running: boolean;
  pid: number;
  /** How the guest is launched: 'proot', 'direct' or 'unsupported'. */
  mode: string;
  /** Last native error, empty when the last call succeeded. */
  lastError: string;
}

/**
 * Result of unpacking a runtime archive.
 */
export interface NativeUnpackResult {
  ok: boolean;
  error: string;
  files: number;
  bytes: number;
}

/**
 * Everything the native installer needs to turn a download into a runtime.
 */
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
 * Options handed to the native runtime supervisor when starting a session.
 */
export interface NativeRuntimeOptions {
  /** Absolute path of the guest rootfs inside the app sandbox. */
  rootDir: string;
  /**
   * Absolute path of the host directory that holds the compositor's Wayland
   * socket. It is bound into the guest as /run/droiddeck, which is the path
   * upstream's guest session expects.
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
  /** Command to run inside the guest, e.g. the Steam bootstrap. */
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

/**
 * Result of starting the PulseAudio server.
 */
export interface NativeAudioServerResult {
  ok: boolean;
  error: string;
  /** The address the guest connects to, for PULSE_SERVER. */
  address: string;
  /** The socket the native audio sink connects to. */
  socket: string;
}

/**
 * Bridge to `libdroiddeck_native.so`.
 *
 * The native module owns everything that cannot be expressed in ArkTS:
 * spawning the Linux guest, hosting the Wayland surface that carries the
 * guest's pixels to an ArkUI XComponent, forwarding gamepad events into the
 * guest and relaying guest audio to the device speaker.
 *
 * Calls are guarded so a failing native call degrades to a logged error
 * instead of taking the ArkUI shell down with it.
 */
export class NativeBridge {
  /** True when the native library answered a probe successfully. */
  static available(): boolean {
    try {
      nativeModule.getDeviceInfo();
      return true;
    } catch (error) {
      Logger.warn(`native module unavailable: ${error}`);
      return false;
    }
  }

  static getDeviceInfo(): NativeDeviceInfo | undefined {
    try {
      return nativeModule.getDeviceInfo() as NativeDeviceInfo;
    } catch (error) {
      Logger.error(`getDeviceInfo failed: ${error}`);
      return undefined;
    }
  }

  static runtimeStatus(rootDir?: string): NativeRuntimeStatus | undefined {
    try {
      return nativeModule.runtimeStatus(rootDir) as NativeRuntimeStatus;
    } catch (error) {
      Logger.error(`runtimeStatus failed: ${error}`);
      return undefined;
    }
  }

  static runtimeStart(options: NativeRuntimeOptions): boolean {
    try {
      return nativeModule.runtimeStart(options) as boolean;
    } catch (error) {
      Logger.error(`runtimeStart failed: ${error}`);
      return false;
    }
  }

  static runtimeStop(): boolean {
    try {
      return nativeModule.runtimeStop() as boolean;
    } catch (error) {
      Logger.error(`runtimeStop failed: ${error}`);
      return false;
    }
  }

  /**
   * Brings up the EGL context on the XComponent's native window.
   *
   * The window itself is handed to the native module by the ArkUI framework
   * through the `OnSurfaceCreated` entry point.
   */
  static surfaceAttach(): boolean {
    try {
      return nativeModule.surfaceAttach() as boolean;
    } catch (error) {
      Logger.error(`surfaceAttach failed: ${error}`);
      return false;
    }
  }

  /**
   * Unpacks a runtime archive.
   *
   * The compression is detected from the archive's magic number, so the
   * zstd-compressed image upstream publishes and a gzip archive both work.
   */
  static runtimeUnpack(archivePath: string, destination: string): NativeUnpackResult {
    const empty: NativeUnpackResult = { ok: false, error: 'native module unavailable', files: 0, bytes: 0 };
    try {
      return nativeModule.runtimeUnpack(archivePath, destination) as NativeUnpackResult;
    } catch (error) {
      Logger.error(`runtimeUnpack failed: ${error}`);
      return empty;
    }
  }

  /**
   * Installs a runtime: check the download, unpack it, apply the overlay
   * and write the version marker.
   *
   * The whole sequence is native because it moves gigabytes around and
   * because the overlay has to land on top of the unpacked rootfs before
   * the guest session script exists.
   */
  static runtimeInstall(options: NativeInstallOptions): NativeUnpackResult {
    const empty: NativeUnpackResult = { ok: false, error: 'native module unavailable', files: 0, bytes: 0 };
    try {
      return nativeModule.runtimeInstall(options) as NativeUnpackResult;
    } catch (error) {
      Logger.error(`runtimeInstall failed: ${error}`);
      return empty;
    }
  }

  /** Whether a file matches the expected SHA-256 digest. */
  static runtimeVerifySha256(path: string, expected: string): boolean {
    try {
      return nativeModule.runtimeVerifySha256(path, expected) as boolean;
    } catch (error) {
      Logger.error(`runtimeVerifySha256 failed: ${error}`);
      return false;
    }
  }

  static surfaceDetach(): void {
    try {
      nativeModule.surfaceDetach();
    } catch (error) {
      Logger.error(`surfaceDetach failed: ${error}`);
    }
  }

  /** Opens the guest-facing uinput device used for gamepad forwarding. */
  static inputOpen(): boolean {
    try {
      return nativeModule.inputOpen() as boolean;
    } catch (error) {
      Logger.error(`inputOpen failed: ${error}`);
      return false;
    }
  }

  static inputClose(): void {
    try {
      nativeModule.inputClose();
    } catch (error) {
      Logger.error(`inputClose failed: ${error}`);
    }
  }

  /**
   * Forwards one gamepad event into the guest.
   *
   * @param kind 'button' | 'axis' | 'hat'
   * @param code button or axis code
   * @param value -1..1 for axes, 0/1 for buttons
   */
  static inputEvent(kind: string, code: number, value: number): void {
    try {
      nativeModule.inputEvent(kind, code, value);
    } catch (error) {
      Logger.error(`inputEvent failed: ${error}`);
    }
  }

  /**
   * Opens the guest audio socket.
   *
   * @param socketPath path of the guest's PCM socket
   */
  static audioOpen(socketPath: string): boolean {
    try {
      return nativeModule.audioOpen(socketPath) as boolean;
    } catch (error) {
      Logger.error(`audioOpen failed: ${error}`);
      return false;
    }
  }

  /**
   * Starts the PulseAudio server the guest's audio client connects to.
   *
   * Upstream runs a PulseAudio server on the host and tells the guest
   * where it is with PULSE_SERVER. The port does the same, with a sink
   * module that writes the audio to the socket above.
   *
   * @param runtimeDir the directory the sockets and the config live in
   * @param binaryDir the directory the HAP's libraries were unpacked into
   * @param logPath where the server's output is written
   */
  static audioServerStart(runtimeDir: string, binaryDir: string, logPath: string): NativeAudioServerResult {
    const empty: NativeAudioServerResult = { ok: false, error: 'native module unavailable', address: '', socket: '' };
    try {
      return nativeModule.audioServerStart(runtimeDir, binaryDir, logPath) as NativeAudioServerResult;
    } catch (error) {
      Logger.error(`audioServerStart failed: ${error}`);
      return empty;
    }
  }

  /** Stops the PulseAudio server. */
  static audioServerStop(): void {
    try {
      nativeModule.audioServerStop();
    } catch (error) {
      Logger.error(`audioServerStop failed: ${error}`);
    }
  }

  /**
   * Pulls interleaved stereo float frames from the guest.
   *
   * @param maxFrames maximum number of frames to read
   * @returns an ArrayBuffer of float32 samples, empty when the guest is idle
   */
  static audioRead(maxFrames: number): ArrayBuffer {
    try {
      return nativeModule.audioRead(maxFrames) as ArrayBuffer;
    } catch (error) {
      Logger.error(`audioRead failed: ${error}`);
      return new ArrayBuffer(0);
    }
  }

  static audioClose(): void {
    try {
      nativeModule.audioClose();
    } catch (error) {
      Logger.error(`audioClose failed: ${error}`);
    }
  }

  /** Recent supervisor log lines, newest last. */
  static logTail(maxLines: number): string[] {
    try {
      return nativeModule.logTail(maxLines) as string[];
    } catch (error) {
      Logger.error(`logTail failed: ${error}`);
      return [];
    }
  }
}
