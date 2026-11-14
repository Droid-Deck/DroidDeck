import { Game } from '../model/Game';
import { AppStore } from '../state/AppStore';

/**
 * Store fronts.
 *
 * Upstream DroidDeck ships dedicated pages for Steam, GOG, Epic, Xbox,
 * itch.io, GameJolt and Flatpak, each with its own sign-in flow. The
 * HarmonyOS port presents the same set of stores and reuses the shared
 * library once a store has been linked.
 */
@Component
export struct StoresPage {
  @ObjectLink store: AppStore;

  private readonly stores: string[] = ['Steam', 'GOG', 'Epic Games', 'Xbox', 'itch.io', 'GameJolt', 'Flatpak'];

  build() {
    Scroll() {
      Column({ space: 12 }) {
        Text($r('app.string.stores_title'))
          .fontSize(22)
          .fontWeight(FontWeight.Bold)
          .fontColor($r('app.color.onBackground'))
          .width('100%')

        ForEach(this.stores, (name: string) => {
          Row() {
            Column({ space: 2 }) {
              Text(name)
                .fontSize(16)
                .fontColor($r('app.color.onBackground'))
              Text($r('app.string.stores_empty'))
                .fontSize(11)
                .fontColor($r('app.color.onSurfaceVariant'))
            }
            .alignItems(HorizontalAlign.Start)
            .layoutWeight(1)

            Button($r('app.string.stores_signin'))
              .fontSize(13)
              .onClick(() => {
                this.store.refreshLibrary();
              })
          }
          .width('100%')
          .padding(14)
          .backgroundColor($r('app.color.surface'))
          .borderRadius(12)
        }, (name: string) => name)
      }
      .padding(16)
      .alignItems(HorizontalAlign.Start)
    }
    .width('100%')
    .height('100%')
    .scrollBar(BarState.Off)
  }
}
