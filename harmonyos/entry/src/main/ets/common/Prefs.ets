import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { Logger } from './Logger';

const STORE_NAME: string = 'droiddeck_preferences';

/**
 * Persistent settings for the launcher.
 *
 * Mirrors upstream's GlobalSettings / SessionPrefs split: everything the
 * launcher UI needs to survive a restart lives here, while session-scoped
 * state stays in the native runtime layer.
 */
export class Prefs {
  private static store: preferences.Preferences | undefined = undefined;

  static async init(context: common.UIAbilityContext): Promise<void> {
    try {
      Prefs.store = await preferences.getPreferences(context, { name: STORE_NAME });
    } catch (error) {
      Logger.error(`preferences open failed: ${error}`);
      Prefs.store = undefined;
    }
  }

  static async put(key: string, value: string): Promise<void> {
    if (Prefs.store === undefined) {
      return;
    }
    try {
      await Prefs.store.put(key, value);
      await Prefs.store.flush();
    } catch (error) {
      Logger.error(`preferences put ${key} failed: ${error}`);
    }
  }

  static async putNumber(key: string, value: number): Promise<void> {
    await Prefs.put(key, `${value}`);
  }

  static async putBoolean(key: string, value: boolean): Promise<void> {
    await Prefs.put(key, value ? 'true' : 'false');
  }

  static async get(key: string, fallback: string): Promise<string> {
    if (Prefs.store === undefined) {
      return fallback;
    }
    try {
      const value = await Prefs.store.get(key, fallback);
      return typeof value === 'string' ? value : fallback;
    } catch (error) {
      Logger.error(`preferences get ${key} failed: ${error}`);
      return fallback;
    }
  }

  static async getNumber(key: string, fallback: number): Promise<number> {
    const raw = await Prefs.get(key, `${fallback}`);
    const parsed = Number.parseInt(raw, 10);
    return Number.isNaN(parsed) ? fallback : parsed;
  }

  static async getBoolean(key: string, fallback: boolean): Promise<boolean> {
    return (await Prefs.get(key, fallback ? 'true' : 'false')) === 'true';
  }

  static async has(key: string): Promise<boolean> {
    if (Prefs.store === undefined) {
      return false;
    }
    try {
      return await Prefs.store.has(key);
    } catch (error) {
      Logger.error(`preferences has ${key} failed: ${error}`);
      return false;
    }
  }

  static async delete(key: string): Promise<void> {
    if (Prefs.store === undefined) {
      return;
    }
    try {
      await Prefs.store.delete(key);
      await Prefs.store.flush();
    } catch (error) {
      Logger.error(`preferences delete ${key} failed: ${error}`);
    }
  }
}
