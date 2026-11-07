import { router } from '@kit.ArkUI';

import { GameCard } from '../components/GameCard';
import { Game } from '../model/Game';
import { AppStore } from '../state/AppStore';

/**
 * Home screen: the runtime state, the most recently played games and a
 * shortcut into the library, mirroring upstream's FrontEndScreen.
 */
@Component
export struct HomePage {
  @ObjectLink store: AppStore;

  private openGame(game: Game): void {
    router.pushUrl({ url: 'pages/GameDetailPage', params: { appId: game.appId, source: game.source } });
  }

  @Builder
  private runtimeBanner() {
    Column({ space: 6 }) {
      if (this.store.runtime !== undefined && this.store.runtime.installed) {
        Text($r('app.string.runtime_installed'))
          .fontSize(14)
          .fontColor($r('app.color.onBackground'))
        if (this.store.isSessionRunning()) {
          Text($r('app.string.runtime_running'))
            .fontSize(12)
            .fontColor($r('app.color.good'))
        }
      } else {
        Text($r('app.string.runtime_missing'))
          .fontSize(14)
          .fontColor($r('app.color.onBackground'))
        Text($r('app.string.runtime_install_hint'))
          .fontSize(12)
          .fontColor($r('app.color.onSurfaceVariant'))
        if (this.store.isInstallingRuntime) {
          Progress({ value: this.store.installProgress, total: 100, type: ProgressType.Linear })
            .width('100%')
            .margin({ top: 6 })
        } else {
          Button($r('app.string.action_install'))
            .margin({ top: 8 })
            .onClick(() => {
              this.store.installRuntime();
            })
        }
      }
    }
    .alignItems(HorizontalAlign.Start)
    .width('100%')
    .padding(14)
    .backgroundColor($r('app.color.surface'))
    .borderRadius(12)
  }

  build() {
    Scroll() {
      Column({ space: 16 }) {
        Row() {
          Text('DroidDeck')
            .fontSize(26)
            .fontWeight(FontWeight.Bold)
            .fontColor($r('app.color.onBackground'))
          Blank()
          Text(this.store.device.deviceBrand + ' ' + this.store.device.deviceModel)
            .fontSize(11)
            .fontColor($r('app.color.onSurfaceVariant'))
        }
        .width('100%')

        this.runtimeBanner()

        if (this.store.library.length > 0) {
          Text($r('app.string.library_title'))
            .fontSize(18)
            .fontWeight(FontWeight.Medium)
            .fontColor($r('app.color.onBackground'))
            .width('100%')

          Grid() {
            ForEach(this.store.library.slice(0, 12), (game: Game) => {
              GameCard({ game: game, onOpen: (selected: Game) => this.openGame(selected) })
            }, (game: Game) => game.key)
          }
          .columnsTemplate('1fr 1fr 1fr')
          .columnsGap(10)
          .rowsGap(10)
          .width('100%')
          .height(520)
          .scrollBar(BarState.Off)
        } else {
          Column({ space: 6 }) {
            Text($r('app.string.library_empty'))
              .fontSize(16)
              .fontColor($r('app.color.onBackground'))
            Text($r('app.string.library_empty_hint'))
              .fontSize(12)
              .fontColor($r('app.color.onSurfaceVariant'))
          }
          .alignItems(HorizontalAlign.Start)
          .width('100%')
          .padding(16)
        }
      }
      .padding(16)
      .alignItems(HorizontalAlign.Start)
    }
    .width('100%')
    .height('100%')
    .scrollBar(BarState.Off)
  }
}
