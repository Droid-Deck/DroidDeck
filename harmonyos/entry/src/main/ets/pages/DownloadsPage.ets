import { AppStore } from '../state/AppStore';

/**
 * Downloads page.
 *
 * Upstream shows the runtime install, the store downloads and the patch
 * downloads in one queue. The HarmonyOS port shows the same three groups,
 * with the runtime install driven from here as well as from the home
 * screen.
 */
@Component
export struct DownloadsPage {
  @ObjectLink store: AppStore;

  build() {
    Scroll() {
      Column({ space: 12 }) {
        Text($r('app.string.downloads_title'))
          .fontSize(22)
          .fontWeight(FontWeight.Bold)
          .fontColor($r('app.color.onBackground'))
          .width('100%')

        if (this.store.isInstallingRuntime) {
          Column({ space: 6 }) {
            Text($r('app.string.runtime_installing'))
              .fontSize(14)
              .fontColor($r('app.color.onBackground'))
            Progress({ value: this.store.installProgress, total: 100, type: ProgressType.Linear })
              .width('100%')
          }
          .alignItems(HorizontalAlign.Start)
          .width('100%')
          .padding(14)
          .backgroundColor($r('app.color.surface'))
          .borderRadius(12)
        } else if (this.store.library.length === 0) {
          Text($r('app.string.downloads_empty'))
            .fontSize(14)
            .fontColor($r('app.color.onSurfaceVariant'))
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
