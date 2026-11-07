import { UIAbility } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';

import { Logger } from '../common/Logger';
import { Paths } from '../common/Paths';
import { Prefs } from '../common/Prefs';
import { AppStore } from '../state/AppStore';

/**
 * The launcher's entry ability.
 *
 * Holds the process-wide state (the app store), opens the settings store
 * and keeps the window in immersive mode, which the upstream launcher also
 * does so the Linux desktop can own the whole screen.
 */
export default class EntryAbility extends UIAbility {
  private windowStage: window.WindowStage | undefined = undefined;

  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    Logger.info('DroidDeck entry ability created');
    AppStore.Instance.initialise(this.context);
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    this.windowStage = windowStage;
    // ArkTS asks for a call that can throw to be handled explicitly.
    try {
      windowStage.loadContent('pages/Index', (error) => {
        if (error.code) {
          Logger.error(`loadContent failed: ${error.code} ${error.message}`);
          return;
        }
        Logger.info('main page loaded');
      });
    } catch (error) {
      Logger.error(`the main page could not be loaded: ${error}`);
    }

    // The session screen takes over the whole display; leaving the system
    // bars visible would shrink the guest desktop.
    windowStage.getMainWindow().then((mainWindow) => {
      mainWindow.setWindowLayoutFullScreen(true).catch((error: BusinessError) => {
        Logger.warn(`full screen layout refused: ${error.code}`);
      });
      mainWindow.setWindowSystemBarEnable([]).catch((error: BusinessError) => {
        Logger.warn(`system bars not hidden: ${error.code}`);
      });
    }).catch((error: BusinessError) => {
      Logger.warn(`main window unavailable: ${error.code}`);
    });
  }

  onWindowStageDestroy(): void {
    AppStore.Instance.shutdown();
    this.windowStage = undefined;
  }

  onForeground(): void {
    AppStore.Instance.onForeground();
  }

  onBackground(): void {
    AppStore.Instance.onBackground();
  }
}
