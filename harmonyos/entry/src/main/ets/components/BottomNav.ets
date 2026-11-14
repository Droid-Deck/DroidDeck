import { AppStore } from '../state/AppStore';

/**
 * Bottom navigation, mirroring upstream's five-page launcher bar
 * (Home, Library, Stores, Downloads and Settings).
 */
@Component
export struct BottomNav {
  @ObjectLink store: AppStore;

  build() {
    Row() {
      ForEach(this.store.tabs, (label: Resource, index: number) => {
        Text(label)
          .fontSize(12)
          .fontWeight(index === this.store.currentTab ? FontWeight.Bold : FontWeight.Normal)
          .fontColor(index === this.store.currentTab
            ? $r('app.color.primary')
            : $r('app.color.onSurfaceVariant'))
          .layoutWeight(1)
          .textAlign(TextAlign.Center)
          .onClick(() => {
            this.store.currentTab = index;
          })
      }, (label: Resource, index: number) => index.toString())
    }
    .width('100%')
    .height(58)
    .padding({ left: 8, right: 8 })
    .backgroundColor($r('app.color.surface'))
    .border({ width: { top: 1 }, color: $r('app.color.line') })
  }
}
