import { router } from '@kit.ArkUI';

import { Logger } from '../common/Logger';
import { Game, GameSource } from '../model/Game';
import { AppStore } from '../state/AppStore';

interface GameDetailParams {
  appId: number;
  source: string;
}

/**
 * Detail view of one game: artwork, the metadata the store returned and
 * the action that starts it.
 */
@Entry
@Component
struct GameDetailPage {
  @State store: AppStore = AppStore.Instance;
  @State game: Game = new Game();
  @State isStarting: boolean = false;

  aboutToAppear(): void {
    const params = router.getParams() as GameDetailParams;
    if (params === undefined || params === null) {
      return;
    }
    const found = this.store.library.find((item: Game) => item.appId === params.appId);
    if (found !== undefined) {
      this.game = found;
      return;
    }
    const fallback = new Game(params.appId, 'App ' + params.appId, GameSource.LOCAL);
    this.game = fallback;
  }

  private async launch(): Promise<void> {
    if (this.isStarting) {
      return;
    }
    this.isStarting = true;
    const started = await this.store.startSession(this.game);
    this.isStarting = false;
    if (started) {
      router.pushUrl({ url: 'pages/SessionPage' });
    }
  }

  build() {
    Scroll() {
      Column({ space: 14 }) {
        Image(this.game.hero.length > 0 ? this.game.hero : this.game.art)
          .width('100%')
          .height(200)
          .objectFit(ImageFit.Cover)
          .backgroundColor($r('app.color.surfaceVariant'))

        Text(this.game.name)
          .fontSize(22)
          .fontWeight(FontWeight.Bold)
          .fontColor($r('app.color.onBackground'))
          .width('100%')

        Row({ space: 18 }) {
          this.detailItem($r('app.string.detail_source').toString(), this.game.source);
          this.detailItem($r('app.string.detail_installed_size').toString(),
            this.game.installed ? 'yes' : 'no');
        }
        .width('100%')

        if (this.store.lastError.length > 0) {
          Text(this.store.lastError)
            .fontSize(13)
            .fontColor($r('app.color.danger'))
            .width('100%')
        }

        Button($r('app.string.detail_play'))
          .width('100%')
          .height(46)
          .backgroundColor($r('app.color.primary'))
          .fontColor($r('app.color.onPrimary'))
          .enabled(!this.isStarting)
          .onClick(() => {
            // The launch runs in the background; its failure is caught, or it
            // would be an unhandled rejection.
            this.launch().catch((error: Error) => {
              Logger.error(`the game did not start: ${error}`);
            });
          })
      }
      .padding(16)
      .alignItems(HorizontalAlign.Start)
    }
    .width('100%')
    .height('100%')
    .backgroundColor($r('app.color.background'))
    .scrollBar(BarState.Off)
  }

  @Builder
  private detailItem(label: string, value: string) {
    Column({ space: 2 }) {
      Text(label)
        .fontSize(11)
        .fontColor($r('app.color.onSurfaceVariant'))
      Text(value)
        .fontSize(13)
        .fontColor($r('app.color.onBackground'))
    }
    .alignItems(HorizontalAlign.Start)
  }
}
