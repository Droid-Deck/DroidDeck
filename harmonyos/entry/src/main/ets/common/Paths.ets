import { common } from '@kit.AbilityKit';
import { fileIo as fs } from '@kit.CoreFileKit';

import { Logger } from './Logger';

/**
 * Resolves the sandbox locations the Linux runtime and its assets live in.
 *
 * Upstream DroidDeck keeps the runtime under the app's private files dir
 * (LinuxRuntime.rootDir); on HarmonyOS the equivalent is filesDir, with the
 * cache dir used for the download staging area.
 */
export class Paths {
  private static context: common.UIAbilityContext | undefined = undefined;

  static init(context: common.UIAbilityContext): void {
    Paths.context = context;
  }

  static filesDir(): string {
    return Paths.context !== undefined ? Paths.context.filesDir : '';
  }

  static cacheDir(): string {
    return Paths.context !== undefined ? Paths.context.cacheDir : '';
  }

  /** Root of the unpacked Linux runtime (the guest rootfs). */
  static runtimeRoot(): string {
    return `${Paths.filesDir()}/runtime`;
  }

  /** Where the runtime download lands before it is unpacked. */
  static runtimeStaging(): string {
    return `${Paths.cacheDir()}/runtime-download`;
  }

  /** Persistent key/value store for launcher settings. */
  static preferencesFile(): string {
    return `${Paths.filesDir()}/preferences/droiddeck.json`;
  }

  /** Guest-visible logs, mirrored from the native runtime supervisor. */
  static logDir(): string {
    return `${Paths.filesDir()}/logs`;
  }

  /**
   * Where the compositor's Wayland socket lives.
   *
   * The same directory is bound into the guest as /run/droiddeck, so the
   * guest finds the socket at the path the compositor created it.
   */
  static runtimeDir(): string {
    return `${Paths.cacheDir()}/xdg`;
  }

  /**
   * Where the runtime overlay is staged inside the sandbox.
   *
   * The overlay ships in the HAP as a rawfile and is copied here, because
   * the native installer reads it from the file system.
   */
  static overlayArchive(): string {
    return `${Paths.filesDir()}/overlay.tar.gz`;
  }

  /**
   * Where the runtime directory appears **inside the guest**.
   *
   * The host's runtime directory is bound into the guest at this path, so
   * every path the guest is told about has to use it. A host path would
   * resolve inside the guest's rootfs, where it does not exist.
   */
  static guestRuntimeDir(): string {
    return '/run/droiddeck';
  }

  /**
   * Where the HAP's native libraries were unpacked.
   *
   * The PulseAudio server and the module it loads live here, and it is the
   * one place on HarmonyOS where a binary can be run.
   */
  static bundleLibsDir(): string {
    if (Paths.context === undefined) {
      return '';
    }
    return `${Paths.context.bundleCodeDir}/libs/arm64-v8a`;
  }

  /**
   * Where the Wayland keymap lives.
   *
   * The compositor publishes it to the guest from here, the way upstream
   * does: without it the guest's clients get no keymap at all and every
   * key comes out wrong.
   */
  static keymapPath(): string {
    return `${Paths.runtimeDir()}/keymap.xkb`;
  }

  /**
   * Copies the packaged keymap into the runtime directory.
   *
   * Upstream keeps the same file in its APK assets and copies it into the
   * runtime directory when the session starts.
   */
  static async stageKeymap(): Promise<boolean> {
    const target = Paths.keymapPath();
    try {
      if (fs.accessSync(target)) {
        return true;
      }
    } catch (error) {
      Logger.debug('the keymap is not staged yet');
    }

    try {
      const content = await Paths.context?.resourceManager.getRawFileContent('keymap.xkb');
      if (content === undefined) {
        return false;
      }
      const file = fs.openSync(target, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      try {
        fs.writeSync(file.fd, content);
      } finally {
        fs.closeSync(file);
      }
      Logger.info('the Wayland keymap is staged');
      return true;
    } catch (error) {
      Logger.error(`the Wayland keymap could not be staged: ${error}`);
      return false;
    }
  }

  /** Covers, hero art and other cached artwork. */
  static artworkDir(): string {
    return `${Paths.filesDir()}/artwork`;
  }
}
