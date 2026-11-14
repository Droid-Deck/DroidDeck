import { router } from '@kit.ArkUI';

import { GameCard } from '../components/GameCard';
import { Game } from '../model/Game';
import { AppStore } from '../state/AppStore';

/**
 * Library page: the whole collection, with the source filter chips and the
 * search box that upstream's LibraryPage offers.
 */
@Component
export struct LibraryPage {
  @ObjectLink store: AppStore;

  private openGame(game: Game): void {
    router.pushUrl({ url: 'pages/GameDetailPage', params: { appId: game.appId, source: game.source } });
  }

  build() {
    Column({ space: 12 }) {
      Row() {
        Text($r('app.string.library_title'))
          .fontSize(22)
          .fontWeight(FontWeight.Bold)
          .fontColor($r('app.color.onBackground'))
        Blank()
        Text(`${this.store.visibleLibrary.length}`)
          .fontSize(13)
          .fontColor($r('app.color.onSurfaceVariant'))
      }
      .width('100%')

      Search({ value: this.store.searchQuery, placeholder: $r('app.string.library_search_hint') })
        .height(40)
        .backgroundColor($r('app.color.surface'))
        .onChange((value: string) => {
          this.store.onSearchChanged(value);
        })

      if (this.store.visibleLibrary.length === 0) {
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
      } else {
        Grid() {
          ForEach(this.store.visibleLibrary, (game: Game) => {
            GameCard({ game: game, onOpen: (selected: Game) => this.openGame(selected) })
          }, (game: Game) => game.key)
        }
        .columnsTemplate('1fr 1fr 1fr')
        .columnsGap(10)
        .rowsGap(10)
        .width('100%')
        .layoutWeight(1)
        .scrollBar(BarState.Off)
      }
    }
    .padding(16)
    .width('100%')
    .height('100%')
    .alignItems(HorizontalAlign.Start)
    .backgroundColor($r('app.color.background'))
  }
}
