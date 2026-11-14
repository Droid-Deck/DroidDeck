import { hilog } from '@kit.PerformanceAnalysisKit';

/**
 * Thin wrapper over hilog so the port keeps upstream DroidDeck's logging
 * conventions (single tag, no noisy per-call formatting).
 */
export class Logger {
  private static readonly DOMAIN: number = 0xF001;
  private static readonly TAG: string = 'DroidDeck';

  static debug(message: string): void {
    hilog.debug(Logger.DOMAIN, Logger.TAG, '%{public}s', message);
  }

  static info(message: string): void {
    hilog.info(Logger.DOMAIN, Logger.TAG, '%{public}s', message);
  }

  static warn(message: string): void {
    hilog.warn(Logger.DOMAIN, Logger.TAG, '%{public}s', message);
  }

  static error(message: string): void {
    hilog.error(Logger.DOMAIN, Logger.TAG, '%{public}s', message);
  }
}
