import { Game } from '../model/Game';

/**
 * A cover tile in the library and home grids.
 *
 * The upstream launcher draws the same tile in Compose with a gradient
 * fallback while artwork loads; the port keeps that behaviour so the grid
 * never shows empty boxes while the store API is still answering.
 */
@Component
export struct GameCard {
  @ObjectLink game: Game;
  onOpen: (game: Game) => void = () => {};

  @Builder
  private artwork() {
    if (this.game.art.length > 0) {
      Image(this.game.art)
        .width('100%')
        .aspectRatio(2 / 3)
        .objectFit(ImageFit.Cover)
        .borderRadius(10)
        .backgroundColor($r('app.color.surfaceVariant'))
    } else {
      Column() {
        Text(this.game.name)
          .fontSize(13)
          .fontWeight(FontWeight.Medium)
          .maxLines(3)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .textAlign(TextAlign.Center)
          .fontColor($r('app.color.onSurfaceVariant'))
      }
      .width('100%')
      .aspectRatio(2 / 3)
      .justifyContent(FlexAlign.Center)
      .padding(10)
      .borderRadius(10)
      .backgroundColor($r('app.color.surfaceVariant'))
    }
  }

  build() {
    Column() {
      Stack({ alignContent: Alignment.BottomStart }) {
        this.artwork()

        if (this.game.logo.length > 0) {
          Image(this.game.logo)
            .width('72%')
            .height(28)
            .objectFit(ImageFit.Contain)
            .margin({ left: 10, bottom: 10 })
        } else {
          Text(this.game.name)
            .fontSize(13)
            .fontWeight(FontWeight.Medium)
            .maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .margin({ left: 10, right: 10, bottom: 10 })
            .fontColor($r('app.color.onBackground'))
        }
      }
      .width('100%')
      .onClick(() => this.onOpen(this.game))
    }
    .alignItems(HorizontalAlign.Start)
  }
}
