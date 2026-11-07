import { common } from '@kit.AbilityKit';
import { fileIo as fs } from '@kit.CoreFileKit';

import { Logger } from '../common/Logger';
import { Paths } from '../common/Paths';
import { Prefs } from '../common/Prefs';
import { NativeBridge, NativeRuntimeOptions, NativeRuntimeStatus } from '../native/NativeBridge';
import { HttpService } from './HttpService';

/**
 * A runtime release, as published by upstream's catalog.
 *
 * The catalog is the same document upstream's LinuxRuntimeInstaller reads:
 * https://raw.githubusercontent.com/Droid-Deck/DroidDeck-Components/main/linuxfs-ubuntu.json
 */
export interface RuntimeRelease {
  version: string;
  url: string;
  sha256: string;
  size: number;
}

/**
 * How the guest session is started.
 */
export interface SessionOptions {
  /** Command to run inside the guest. */
  command: string;
  /** Arguments for that command. */
  args: string[];
  /** Width of the guest desktop in pixels. */
  width: number;
  /** Height of the guest desktop in pixels. */
  height: number;
  /** Where the guest writes its session log. */
  logPath: string;
  /** Where the guest writes its debug files. */
  debugDir: string;
}

/**
 * Owns the Linux runtime: installing it, reporting its state and starting
 * and stopping a guest session.
 *
 * The install path mirrors upstream's LinuxRuntimeInstaller:
 *
 *   catalog -> url + sha256 -> download -> verify -> unpack -> overlay
 *
 * The last step is the one upstream does not need. Upstream bakes
 * tools/linuxfs/overlay/ into the published runtime image at build time, so
 * the image on the release server has no /usr/local/bin/droiddeck-session.
 * The port ships the same overlay inside the HAP and applies it over the
 * unpacked rootfs, which is what makes the runtime usable.
 */
export class RuntimeService {
  /**
   * Where upstream publishes the runtime catalog.
   */
  static readonly CATALOG_URL: string =
    'https://raw.githubusercontent.com/Droid-Deck/DroidDeck-Components/main/linuxfs-ubuntu.json';

  /**
   * The rawfile that carries the runtime overlay.
   */
  static readonly OVERLAY_RAWFILE: string = 'overlay.tar.gz';

  /**
   * Where the guest keeps its Vulkan manifests.
   *
   * This is a path inside the guest: the rootfs is the guest's /.
   */
  static readonly ICD_DIRECTORY: string = '/usr/share/vulkan/icd.d';

  private static instance: RuntimeService = new RuntimeService();

  static get Instance(): RuntimeService {
    return RuntimeService.instance;
  }

  private constructor() {
  }

  /**
   * The artifact to download.
   *
   * A runtime URL set in the settings wins, so a runtime can be served from
   * anywhere: a mirror, a local web server, or the smoke-test artifact the
   * build tools produce. Without one, the release is resolved from
   * upstream's catalog.
   */
  static async runtimeUrl(): Promise<string> {
    const stored = await Prefs.get('runtime_url', '');
    return stored.length > 0 ? stored : RuntimeService.CATALOG_URL;
  }

  /**
   * Resolves the release to install.
   *
   * With a custom URL there is no published checksum, so the download is
   * accepted as it is; otherwise the catalog's checksum is enforced.
   */
  static async resolveRelease(): Promise<RuntimeRelease | undefined> {
    const custom = await Prefs.get('runtime_url', '');
    if (custom.length > 0) {
      return { version: 'custom', url: custom, sha256: '', size: 0 };
    }
    return RuntimeService.fetchCatalogRelease();
  }

  /**
   * Reads the release from upstream's catalog.
   */
  static async fetchCatalogRelease(): Promise<RuntimeRelease | undefined> {
    const response = await HttpService.get(RuntimeService.CATALOG_URL);
    if (!response.ok) {
      Logger.error(`the runtime catalog could not be read: ${response.code}`);
      return undefined;
    }
    try {
      const parsed = JSON.parse(response.body) as RuntimeRelease;
      if (parsed.url === undefined || parsed.version === undefined || parsed.url.length === 0) {
        Logger.error('the runtime catalog does not name a release');
        return undefined;
      }
      return {
        version: parsed.version,
        url: parsed.url,
        sha256: parsed.sha256 ?? '',
        size: parsed.size ?? 0
      };
    } catch (error) {
      Logger.error(`the runtime catalog is not valid JSON: ${error}`);
      return undefined;
    }
  }

  /** Current state of the runtime and the running session. */
  status(): NativeRuntimeStatus | undefined {
    // The root directory is passed in because the install check depends on
    // it: without it the native side can only report on a session that has
    // already been started, and the runtime would never be seen as
    // installed.
    const status = NativeBridge.runtimeStatus(Paths.runtimeRoot());
    if (status !== undefined && status.rootDir.length === 0) {
      status.rootDir = Paths.runtimeRoot();
    }
    return status;
  }

  /**
   * Downloads and installs the runtime.
   *
   * @param context the ability context the download runs under
   * @param onProgress receives a percentage, 0..100
   */
  async install(context: common.UIAbilityContext, onProgress: (percent: number) => void): Promise<boolean> {
    const release = await RuntimeService.resolveRelease();
    if (release === undefined) {
      Logger.error('no runtime release could be resolved');
      return false;
    }
    Logger.info(`installing the runtime ${release.version} from ${release.url}`);

    const destination = Paths.runtimeRoot();
    onProgress(0);

    const archive = `${Paths.runtimeStaging()}/runtime.download`;
    const downloaded = await this.fetchArchive(context, archive, release.url, onProgress);
    if (!downloaded) {
      return false;
    }

    // The checksum is checked by the native layer before anything is
    // written, so a corrupted download never reaches the rootfs.
    onProgress(90);
    const overlay = await RuntimeService.stageOverlay(context);
    if (overlay.length === 0) {
      // Without the overlay the rootfs has no session script, so the
      // runtime would install but never start. Failing here is clearer
      // than a runtime that the supervisor keeps rejecting.
      Logger.error('the runtime overlay is not available, so the runtime cannot be installed');
      return false;
    }

    const installed = NativeBridge.runtimeInstall({
      archive: archive,
      destination: destination,
      overlay: overlay,
      version: release.version,
      sha256: release.sha256
    });
    if (!installed.ok) {
      Logger.error(`runtime install failed: ${installed.error}`);
      return false;
    }

    onProgress(100);
    Logger.info(`the runtime ${installed.files} files is installed`);

    // The download is 291 MB and the rootfs is already unpacked, so the
    // archive is not worth keeping.
    this.discardArchive(archive);
    return true;
  }

  /** Removes the downloaded archive, ignoring a failure to do so. */
  private discardArchive(archive: string): void {
    try {
      if (fs.accessSync(archive)) {
        fs.unlinkSync(archive);
      }
    } catch (error) {
      Logger.debug(`the downloaded archive could not be removed: ${error}`);
    }
  }

  /**
   * Starts a guest session.
   *
   * @param options what the guest should run and how big its desktop is
   */
  start(options: SessionOptions): boolean {
    const status = this.status();
    if (status === undefined || !status.installed) {
      Logger.warn('cannot start a session before the runtime is installed');
      return false;
    }

    const nativeOptions: NativeRuntimeOptions = {
      rootDir: status.rootDir.length > 0 ? status.rootDir : Paths.runtimeRoot(),
      hostRuntimeDir: Paths.runtimeDir(),
      hostFilesDir: Paths.filesDir(),
      hostCacheDir: Paths.cacheDir(),
      command: options.command,
      args: options.args,
      cwd: '/root',
      env: RuntimeService.sessionEnvironment(options),
      vulkanDriver: this.vulkanDriver(),
      vulkanIcd: this.vulkanIcd(),
      desktop: true
    };

    const started = NativeBridge.runtimeStart(nativeOptions);
    if (!started) {
      const failure = this.status();
      Logger.error(`session failed to start: ${failure?.lastError ?? 'unknown error'}`);
    }
    return started;
  }

  /**
   * The environment the guest session runs with.
   *
   * The BL_* variables are upstream's own: the session script reads them to
   * learn the size of the guest desktop, where to write its log and whether
   * it is the outer session or a nested one.
   *
   * BL_LOG and BL_DEBUG_DIR are host paths, the way upstream passes them.
   * They resolve inside the guest because the app's files directory is bound
   * into the guest at the same absolute path.
   *
   * PULSE_SERVER points the guest's PulseAudio client — Steam's, and the
   * Proton games' — at the server the port runs on the host. Upstream does
   * exactly this.
   *
   * The Mesa variables are the port's own. The phone's GPU is not reachable
   * from the guest — HarmonyOS does not give an app a device node for it —
   * so the guest renders on the CPU. Upstream's session script falls back
   * to the same pair when its own accelerated path is unavailable.
   */
  static sessionEnvironment(options: SessionOptions): Record<string, string> {
    const env: Record<string, string> = {
      'BL_WIDTH': `${options.width}`,
      'BL_HEIGHT': `${options.height}`,
      'BL_LOG': options.logPath,
      'BL_DEBUG_DIR': options.debugDir,
      'LIBGL_ALWAYS_SOFTWARE': '1',
      'GALLIUM_DRIVER': 'llvmpipe'
    };
    const address = RuntimeService.pulseServerAddress();
    if (address.length > 0) {
      env['PULSE_SERVER'] = address;
    }
    return env;
  }

  /**
   * The address the guest's PulseAudio client connects to.
   *
   * The server listens on a socket in the runtime directory, which is bound
   * into the guest as /run/droiddeck. The guest is told the **guest's**
   * path: a host path would resolve inside the guest's rootfs, where the
   * socket is not.
   */
  static pulseServerAddress(): string {
    return `unix:${Paths.guestRuntimeDir()}/pulse/native`;
  }

  stop(): boolean {
    return NativeBridge.runtimeStop();
  }

  /**
   * The guest's Vulkan ICD directory.
   *
   * The loader looks for its manifests here, and the directory is inside
   * the rootfs, which is the guest's /.
   */
  vulkanDriver(): string {
    return RuntimeService.ICD_DIRECTORY;
  }

  /**
   * The Vulkan ICD manifest the guest is pinned to.
   *
   * `VK_ICD_FILENAMES` names manifest *files*. Pinning one also decides
   * which driver the guest uses, and on HarmonyOS that matters: the phone's
   * GPU is not reachable from the guest, so the drivers that need a device
   * node — freedreno on Qualcomm, panfrost on Mali — cannot work here.
   *
   * The runtime ships Mesa's software driver, lavapipe, which renders on
   * the CPU and needs no device node at all. That is the one the port
   * pins, and it is why the guest can render at all on a Huawei phone.
   *
   * The path is the **guest's** path: the rootfs is the guest's /, and the
   * environment is rebuilt inside the guest.
   */
  vulkanIcd(): string {
    const root = this.status()?.rootDir ?? Paths.runtimeRoot();
    // Checked on the host, where the file is; handed to the guest as the
    // path it has inside the rootfs.
    const software = `${root}${RuntimeService.ICD_DIRECTORY}/lvp_icd.json`;
    if (RuntimeService.FileExists(software)) {
      return `${RuntimeService.ICD_DIRECTORY}/lvp_icd.json`;
    }
    // The image has no software driver, so fall back to the first manifest
    // it does have rather than pinning nothing.
    const first = RuntimeService.FirstIcd(`${root}${RuntimeService.ICD_DIRECTORY}`);
    return first === undefined ? '' : `${RuntimeService.ICD_DIRECTORY}/${first}`;
  }

  /** The first ICD manifest in the guest's ICD directory, if there is one. */
  private static FirstIcd(directory: string): string | undefined {
    try {
      const names = fs.listFileSync(directory);
      for (const name of names) {
        if (name.endsWith('.json')) {
          return name;
        }
      }
    } catch (error) {
      Logger.debug(`the Vulkan ICD directory could not be read: ${error}`);
    }
    return undefined;
  }

  /** Whether a file exists. */
  private static FileExists(path: string): boolean {
    try {
      return fs.accessSync(path);
    } catch (error) {
      return false;
    }
  }

  /**
   * Copies the overlay out of the HAP into the sandbox.
   *
   * The native layer reads the overlay from the file system, so it has to
   * exist there before the runtime is unpacked.
   */
  static async stageOverlay(context: common.UIAbilityContext): Promise<string> {
    const target = Paths.overlayArchive();
    try {
      if (fs.accessSync(target)) {
        return target;
      }
    } catch (error) {
      Logger.debug('the overlay is not staged yet');
    }

    try {
      const content = await context.resourceManager.getRawFileContent(RuntimeService.OVERLAY_RAWFILE);
      const file = fs.openSync(target, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      try {
        fs.writeSync(file.fd, content);
      } finally {
        fs.closeSync(file);
      }
      Logger.info('the runtime overlay is staged');
      return target;
    } catch (error) {
      Logger.error(`the runtime overlay could not be staged: ${error}`);
      return '';
    }
  }

  private async fetchArchive(context: common.UIAbilityContext, archive: string, url: string,
    onProgress: (percent: number) => void): Promise<boolean> {
    // The archive is large, so it is streamed through the download manager
    // and unpacked from the cache afterwards.
    return HttpService.download(context, url, archive, onProgress);
  }
}
