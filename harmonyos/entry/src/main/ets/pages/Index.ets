import { BottomNav } from '../components/BottomNav';
import { AppStore } from '../state/AppStore';
import { DownloadsPage } from './DownloadsPage';
import { HomePage } from './HomePage';
import { LibraryPage } from './LibraryPage';
import { SettingsPage } from './SettingsPage';
import { StoresPage } from './StoresPage';

/**
 * Root of the launcher.
 *
 * Holds the process-wide store, swaps the visible page and keeps the
 * bottom navigation in place, the way upstream's FrontEndActivity hosts
 * the Compose navigation graph.
 */
@Entry
@Component
struct Index {
  @State store: AppStore = AppStore.Instance;

  aboutToAppear(): void {
    this.store.refreshRuntime();
  }

  @Builder
  private pageContent() {
    if (this.store.currentTab === 0) {
      HomePage({ store: this.store })
    } else if (this.store.currentTab === 1) {
      LibraryPage({ store: this.store })
    } else if (this.store.currentTab === 2) {
      StoresPage({ store: this.store })
    } else if (this.store.currentTab === 3) {
      DownloadsPage({ store: this.store })
    } else {
      SettingsPage({ store: this.store })
    }
  }

  build() {
    Stack({ alignContent: Alignment.Bottom }) {
      Column() {
        this.pageContent()
      }
      .width('100%')
      .height('100%')
      .backgroundColor($r('app.color.background'))

      BottomNav({ store: this.store })
    }
    .width('100%')
    .height('100%')
    .backgroundColor($r('app.color.background'))
  }
}
