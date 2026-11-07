import { common } from '@kit.AbilityKit';
import { deviceInfo } from '@kit.BasicServicesKit';
import { display } from '@kit.ArkUI';

import { Logger } from '../common/Logger';
import { Paths } from '../common/Paths';
import { Prefs } from '../common/Prefs';
import { DeviceInfo, GpuClassifier, GpuSupport } from '../model/DeviceInfo';
import { Game, GameSource } from '../model/Game';
import { NativeBridge, NativeRuntimeStatus } from '../native/NativeBridge';
import { RuntimeService } from '../service/RuntimeService';
import { SteamService } from '../service/SteamService';

/**
 * Process-wide launcher state.
 *
 * Played the same role as upstream's combination of `GlobalSettings`,
 * `SessionPrefs` and the activity-scoped view models: it is the single
 * place the UI reads from and the only place that talks to the services.
 */
@Observed
export class AppStore {
  private static instance: AppStore = new AppStore();

  static get Instance(): AppStore {
    return AppStore.instance;
  }

  /** Tabs of the bottom navigation. */
  readonly tabs: Resource[] = [
    $r('app.string.tab_home'),
    $r('app.string.tab_library'),
    $r('app.string.tab_stores'),
    $r('app.string.tab_downloads'),
    $r('app.string.tab_settings')
  ];

  currentTab: number = 0;
  device: DeviceInfo = new DeviceInfo();
  runtime: NativeRuntimeStatus | undefined = undefined;
  library: Game[] = [];
  /** Games filtered by the search box on the library page. */
  visibleLibrary: Game[] = [];
  searchQuery: string = '';
  isInstallingRuntime: boolean = false;
  installProgress: number = 0;
  isRefreshing: boolean = false;
  lastError: string = '';
  /** Where the Linux runtime is downloaded from. */
  runtimeUrl: string = RuntimeService.CATALOG_URL;

  private context: common.UIAbilityContext | undefined = undefined;

  async initialise(context: common.UIAbilityContext): Promise<void> {
    this.context = context;
    Paths.init(context);
    await Prefs.init(context);

    this.refreshDevice();
    this.refreshRuntime();
    this.runtimeUrl = await RuntimeService.runtimeUrl();
    await this.refreshLibrary();
  }

  /**
   * Points the launcher at a different runtime artifact.
   *
   * An empty URL clears the override, so the launcher goes back to
   * resolving the release from upstream's catalog.
   */
  async setRuntimeUrl(url: string): Promise<void> {
    const trimmed = url.trim();
    if (trimmed.length === 0) {
      this.runtimeUrl = RuntimeService.CATALOG_URL;
      await Prefs.delete('runtime_url');
      Logger.info('the runtime comes from the catalog again');
      return;
    }
    this.runtimeUrl = trimmed;
    await Prefs.put('runtime_url', trimmed);
    Logger.info(`the runtime now comes from ${trimmed}`);
  }

  onForeground(): void {
    this.refreshRuntime();
  }

  onBackground(): void {
    Logger.debug('backgrounded');
  }

  shutdown(): void {
    RuntimeService.Instance.stop();
    NativeBridge.surfaceDetach();
    NativeBridge.inputClose();
    NativeBridge.audioClose();
    NativeBridge.audioServerStop();
  }

  /** Reads the GPU and hardware facts the runtime needs. */
  refreshDevice(): void {
    const native = NativeBridge.getDeviceInfo();
    if (native === undefined) {
      Logger.warn('no device information from the native layer');
      return;
    }

    const device = new DeviceInfo();
    device.gpuName = native.gpuName;
    device.gpuVendor = native.gpuVendor;
    device.gpuModel = native.gpuModel;
    device.gpuFamily = GpuClassifier.classify(native.gpuName);
    device.vulkanBridge = native.vulkanBridge;
    device.deviceBrand = deviceInfo.brand;
    device.deviceModel = deviceInfo.hardwareModel;
    device.osRelease = deviceInfo.osFullName;
    device.apiLevel = 26;
    device.abi = native.abi;
    device.cpuCores = native.cpuCores;
    device.ramMiB = native.ramMiB;
    device.gpuSupport = GpuClassifier.support(device.gpuFamily, device.gpuModel, device.vulkanBridge);
    this.device = device;
  }

  /** Refreshes the runtime and session state. */
  refreshRuntime(): void {
    this.runtime = RuntimeService.Instance.status();
  }

  /** Rebuilds the library from the store APIs. */
  async refreshLibrary(): Promise<void> {
    this.isRefreshing = true;
    try {
      const games: Game[] = [];
      const ids = await SteamService.appList();
      // The full Steam catalogue is far too large for a launcher grid, so
      // the port starts from the most recently released titles and lets the
      // player search the rest from the library page.
      for (const id of ids.slice(0, 60)) {
        const details = await SteamService.details(id);
        if (details !== undefined) {
          games.push(SteamService.toGame(details));
        }
      }
      this.library = games;
      this.applyFilter();
    } catch (error) {
      Logger.error(`library refresh failed: ${error}`);
      this.lastError = 'The library could not be refreshed';
    } finally {
      this.isRefreshing = false;
    }
  }

  /** Applies the library search filter. */
  applyFilter(): void {
    const query = this.searchQuery.trim().toLowerCase();
    if (query.length === 0) {
      this.visibleLibrary = this.library;
      return;
    }
    this.visibleLibrary = this.library.filter((game: Game) => game.name.toLowerCase().includes(query));
  }

  onSearchChanged(query: string): void {
    this.searchQuery = query;
    this.applyFilter();
  }

  /** Installs the Linux runtime. */
  async installRuntime(): Promise<boolean> {
    if (this.isInstallingRuntime) {
      return false;
    }
    this.isInstallingRuntime = true;
    this.installProgress = 0;
    try {
      if (this.context === undefined) {
        this.lastError = 'The launcher is not ready yet';
        return false;
      }
      const ok = await RuntimeService.Instance.install(this.context, (percent: number) => {
        this.installProgress = percent;
      });
      this.refreshRuntime();
      if (!ok) {
        this.lastError = 'The Linux runtime could not be installed';
      }
      return ok;
    } finally {
      this.isInstallingRuntime = false;
    }
  }

  /** Starts a guest session for the given game. */
  async startSession(game: Game): Promise<boolean> {
    this.refreshRuntime();
    if (this.runtime === undefined || !this.runtime.installed) {
      this.lastError = 'Install the Linux runtime first';
      return false;
    }
    const size = AppStore.outputSize();
    const started = RuntimeService.Instance.start({
      command: '/usr/local/bin/droiddeck-session',
      args: game.source === GameSource.STEAM ? ['steam', `${game.appId}`] : ['desktop'],
      width: size.width,
      height: size.height,
      logPath: `${Paths.logDir()}/session.log`,
      debugDir: Paths.logDir()
    });
    if (!started) {
      this.lastError = this.runtime?.lastError ?? 'The session could not start';
    }
    return started;
  }

  /**
   * The size of the screen in pixels.
   *
   * The guest session uses it to size the desktop it publishes, so the
   * guest's resolution matches the phone's.
   */
  static outputSize(): { width: number, height: number } {
    try {
      const screen = display.getDefaultDisplaySync();
      if (screen.width > 0 && screen.height > 0) {
        return { width: screen.width, height: screen.height };
      }
    } catch (error) {
      Logger.warn(`the display size is not available: ${error}`);
    }
    return { width: 1080, height: 2400 };
  }

  /** Stops the running session. */
  stopSession(): void {
    RuntimeService.Instance.stop();
    this.refreshRuntime();
  }

  /** True when a session is running. */
  isSessionRunning(): boolean {
    return this.runtime !== undefined && this.runtime.running;
  }

  /**
   * Human readable label for the driver the guest renders with.
   *
   * Upstream's taxonomy names the driver *builds* it ships for each GPU
   * family (Turnip for Adreno, PanVK for Mali), and the phone's GPU does
   * drive the compositor. The guest itself cannot use it: those builds
   * need a device node (/dev/kgsl, /dev/mali0) that an app sandbox on
   * HarmonyOS does not get, so the runtime pins Mesa's software driver
   * instead (see RuntimeService.vulkanIcd()). The label therefore names
   * the phone's GPU and says where the guest's frames are rendered.
   */
  gpuDriverLabel(): string {
    const name = this.device.gpuName.length > 0 ? this.device.gpuName : 'Unknown GPU';
    return `${name} - the guest renders on the CPU (Mesa lavapipe)`;
  }
}
