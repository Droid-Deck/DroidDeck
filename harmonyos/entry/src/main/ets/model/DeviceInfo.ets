/**
 * GPU family classification, mirroring upstream DroidDeck's
 * `GpuInfo.Family`.
 *
 * Upstream probes Qualcomm's `/sys/class/kgsl/kgsl-3d0` and the Adreno
 * vendor HAL. Neither exists on HarmonyOS, so the port classifies the GPU
 * from the GL_RENDERER / GL_VENDOR strings the native layer reads through
 * EGL, and falls back to the "bridge" path (the phone's own Vulkan driver)
 * for Mali parts that no open driver build covers.
 */
export enum GpuFamily {
  A8XX = 'A8XX',
  A7XX_LOW = 'A7XX_LOW',
  A7XX = 'A7XX',
  A6XX = 'A6XX',
  ADRENO_UNKNOWN = 'ADRENO_UNKNOWN',
  POWERVR = 'POWERVR',
  MALI_JM = 'MALI_JM',
  MALI_G610_G710 = 'MALI_G610_G710',
  MALI_G615 = 'MALI_G615',
  MALI_G720 = 'MALI_G720',
  MALI_LATEST = 'MALI_LATEST',
  MALI_OTHER = 'MALI_OTHER',
  NOT_ADRENO = 'NOT_ADRENO'
}

export enum GpuSupport {
  TESTED = 'TESTED',
  UNTESTED = 'UNTESTED',
  UNSUPPORTED = 'UNSUPPORTED'
}

export class DeviceInfo {
  /** Raw GPU renderer string, e.g. "Mali-G78" or "Adreno (TM) 740". */
  gpuName: string = '';
  gpuVendor: string = '';
  gpuFamily: GpuFamily = GpuFamily.NOT_ADRENO;
  gpuSupport: GpuSupport = GpuSupport.UNSUPPORTED;
  /** Numeric model used to pick between driver builds. */
  gpuModel: number = 0;
  /** True when the GPU is reached through the phone's own Vulkan driver. */
  vulkanBridge: boolean = false;

  deviceBrand: string = '';
  deviceModel: string = '';
  osRelease: string = '';
  /** API level reported by the HarmonyOS SDK. */
  apiLevel: number = 0;
  abi: string = 'arm64-v8a';
  /** CPU core count, used to size the guest's worker pools. */
  cpuCores: number = 0;
  /** Total RAM in MiB. */
  ramMiB: number = 0;
}

/**
 * Maps a GL_RENDERER string onto the upstream family taxonomy.
 *
 * Kept in ArkTS (rather than in C++) so the classification rules stay
 * editable without a native rebuild, and so the UI can be unit tested.
 */
export class GpuClassifier {
  static classify(renderer: string): GpuFamily {
    const name = renderer.toLowerCase();

    if (name.includes('adreno')) {
      const model = GpuClassifier.adrenoModel(name);
      if (model >= 800) {
        return GpuFamily.A8XX;
      }
      if (model >= 700 && model < 710) {
        return GpuFamily.A7XX_LOW;
      }
      if (model >= 700) {
        return GpuFamily.A7XX;
      }
      if (model >= 600) {
        return GpuFamily.A6XX;
      }
      return GpuFamily.ADRENO_UNKNOWN;
    }

    if (name.includes('mali')) {
      if (name.includes('g610') || name.includes('g710')) {
        return GpuFamily.MALI_G610_G710;
      }
      if (name.includes('g615')) {
        return GpuFamily.MALI_G615;
      }
      if (name.includes('g720')) {
        return GpuFamily.MALI_G720;
      }
      if (name.includes('g715') || name.includes('g725') || name.includes('g925')) {
        return GpuFamily.MALI_LATEST;
      }
      const model = GpuClassifier.maliModel(name);
      if (model >= 52 && model <= 78) {
        return GpuFamily.MALI_JM;
      }
      return GpuFamily.MALI_OTHER;
    }

    if (name.includes('powervr') || name.includes('xclipse')) {
      return GpuFamily.POWERVR;
    }

    return GpuFamily.NOT_ADRENO;
  }

  static support(family: GpuFamily, model: number, bridge: boolean): GpuSupport {
    if (family === GpuFamily.NOT_ADRENO) {
      // The bridge gives Mali parts a path through the phone's own driver.
      return bridge ? GpuSupport.UNTESTED : GpuSupport.UNSUPPORTED;
    }
    if (family === GpuFamily.MALI_JM || family === GpuFamily.MALI_LATEST) {
      return GpuSupport.UNTESTED;
    }
    if (family === GpuFamily.MALI_G610_G710 || family === GpuFamily.MALI_G615 ||
      family === GpuFamily.MALI_G720 || family === GpuFamily.MALI_OTHER) {
      return GpuSupport.UNTESTED;
    }
    if (family === GpuFamily.A8XX) {
      return GpuSupport.TESTED;
    }
    if (family === GpuFamily.A7XX && model >= 725) {
      return GpuSupport.TESTED;
    }
    if (family === GpuFamily.A6XX && model === 650) {
      return GpuSupport.TESTED;
    }
    if (family === GpuFamily.POWERVR || family === GpuFamily.A7XX_LOW) {
      return GpuSupport.UNTESTED;
    }
    return GpuSupport.UNTESTED;
  }

  /** Human readable driver label, matching upstream's DriverPairs names. */
  static driverLabel(family: GpuFamily): string {
    switch (family) {
      case GpuFamily.A8XX:
        return 'Banners-Turnip · Adreno 8xx';
      case GpuFamily.A7XX:
        return 'Banners-Turnip · Adreno 7xx';
      case GpuFamily.A6XX:
        return 'Banners-Turnip · Adreno 6xx/7xx';
      case GpuFamily.A7XX_LOW:
        return 'Banners-Turnip · Adreno 710/720/722 (test)';
      case GpuFamily.MALI_JM:
        return 'PanVK · Mali G52-G78';
      case GpuFamily.MALI_G610_G710:
        return 'PanVK · Mali-G610/G710';
      case GpuFamily.MALI_G615:
        return 'PanVK · Mali-G615';
      case GpuFamily.MALI_G720:
        return 'PanVK · Mali-G720';
      case GpuFamily.MALI_LATEST:
        return 'PanVK kbase · Mali G57-G925';
      case GpuFamily.MALI_OTHER:
        return 'Mali through the phone\'s own driver (Venus)';
      case GpuFamily.POWERVR:
        return 'System PowerVR driver';
      default:
        return 'Unknown GPU';
    }
  }

  private static adrenoModel(name: string): number {
    const match = name.match(/(\d{3,4})/);
    return match ? Number.parseInt(match[1], 10) : 0;
  }

  private static maliModel(name: string): number {
    const match = name.match(/mali[-\s]?g?(\d{2,3})/);
    return match ? Number.parseInt(match[1], 10) : 0;
  }
}
