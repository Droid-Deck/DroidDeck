import { Logger } from '../common/Logger';
import { Paths } from '../common/Paths';
import nativeModule from 'libdroiddeck_native.so';

/**
 * The compositor's ArkTS bindings.
 *
 * Upstream DroidDeck drives the Wayland compositor through JNI. The port
 * replaces that with Node-API, so the same compositor runs inside the
 * HarmonyOS app and reports back through the same event names.
 */
export class CompositorBridge {
  /**
   * Starts the compositor thread.
   *
   * @param onEvent receives the compositor's events: firstFrame, gameSurface,
   *   gameProgram, gameFrame, pointerLock, clipboardText and textInput
   */
  static start(onEvent: (name: string, detail: string) => void): boolean {
    try {
      // The compositor creates its Wayland socket in this directory, which
      // the guest sees as /run/droiddeck.
      const runtimeDir = Paths.runtimeDir();
      return nativeModule.compositorStart(runtimeDir, onEvent) as boolean;
    } catch (error) {
      Logger.warn(`the compositor could not be started: ${error}`);
      return false;
    }
  }

  static stop(): void {
    try {
      nativeModule.compositorStop();
    } catch (error) {
      Logger.warn(`the compositor could not be stopped: ${error}`);
    }
  }

  /** The compositor thread's id, or 0 when it is not running. */
  static tid(): number {
    try {
      return nativeModule.compositorTid() as number;
    } catch (error) {
      return 0;
    }
  }

  /** Why presenting failed, empty while it works. */
  static fault(): string {
    try {
      return nativeModule.compositorFault() as string;
    } catch (error) {
      return '';
    }
  }

  /** The display driver as the compositor reports it. */
  static displayDriver(): string {
    try {
      return nativeModule.displayDriver() as string;
    } catch (error) {
      return '';
    }
  }

  /** Feeds a pointer event into the guest. */
  static sendPointer(action: number, x: number, y: number): void {
    try {
      nativeModule.compositorSendPointer(action, x, y);
    } catch (error) {
      Logger.warn(`pointer event dropped: ${error}`);
    }
  }

  /** Feeds a touch event into the guest. */
  static sendTouch(action: number, pointerId: number, x: number, y: number): void {
    try {
      nativeModule.compositorSendTouch(action, pointerId, x, y);
    } catch (error) {
      Logger.warn(`touch event dropped: ${error}`);
    }
  }

  /** Feeds a key event into the guest. */
  static sendKey(evdev: number, state: number): void {
    try {
      nativeModule.compositorSendKey(evdev, state);
    } catch (error) {
      Logger.warn(`key event dropped: ${error}`);
    }
  }

  /** Tells the compositor how large the output surface is. */
  static setOutputSize(width: number, height: number): void {
    try {
      nativeModule.compositorSetOutputSize(width, height);
    } catch (error) {
      Logger.warn(`the output size was not applied: ${error}`);
    }
  }

  /** A snapshot of the cursor. */
  static cursorSnapshot(): number[] {
    try {
      return nativeModule.cursorSnapshot() as number[];
    } catch (error) {
      return [];
    }
  }

  /** The HUD's frame times. */
  static hudFrameTimes(): Float32Array {
    try {
      return nativeModule.hudFrameTimes() as Float32Array;
    } catch (error) {
      return new Float32Array(0);
    }
  }
}
