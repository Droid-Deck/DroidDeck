import { router } from '@kit.ArkUI';

import { AppStore } from '../state/AppStore';

/**
 * Settings page: the graphics driver, the runtime, the controller mapping
 * and the device facts that upstream shows in its Settings sheet.
 */
@Component
export struct SettingsPage {
  @ObjectLink store: AppStore;
  @State showRuntimeUrl: boolean = false;
  @State runtimeUrlDraft: string = '';

  @Builder
  private section(title: Resource) {
    Text(title)
      .fontSize(13)
      .fontColor($r('app.color.onSurfaceVariant'))
      .width('100%')
      .margin({ top: 14, bottom: 6 })
  }

  @Builder
  private row(label: Resource, value: string) {
    Row() {
      Text(label)
        .fontSize(14)
        .fontColor($r('app.color.onBackground'))
        .layoutWeight(1)
      Text(value)
        .fontSize(13)
        .fontColor($r('app.color.onSurfaceVariant'))
        .maxLines(2)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .margin({ left: 12 })
    }
    .width('100%')
    .padding(12)
    .backgroundColor($r('app.color.surface'))
    .borderRadius(10)
  }

  @Builder
  private runtimeUrlEditor() {
    Column() {
      Text($r('app.string.settings_runtime_url_hint'))
        .fontSize(12)
        .fontColor($r('app.color.onSurfaceVariant'))
        .width('100%')
        .margin({ bottom: 8 })
      TextInput({ text: this.runtimeUrlDraft })
        .height(40)
        .backgroundColor($r('app.color.surfaceVariant'))
        .onChange((value: string) => {
          this.runtimeUrlDraft = value;
        })
      Row({ space: 12 }) {
        Button($r('app.string.action_cancel'))
          .fontSize(13)
          .onClick(() => {
            this.showRuntimeUrl = false;
          })
        Button($r('app.string.action_confirm'))
          .fontSize(13)
          .onClick(() => {
            this.store.setRuntimeUrl(this.runtimeUrlDraft);
            this.showRuntimeUrl = false;
          })
      }
      .margin({ top: 12 })
    }
    .padding(16)
    .width('100%')
    .backgroundColor($r('app.color.surface'))
    .borderRadius(12)
  }

  build() {
    Scroll() {
      Column({ space: 8 }) {
        Text($r('app.string.settings_title'))
          .fontSize(22)
          .fontWeight(FontWeight.Bold)
          .fontColor($r('app.color.onBackground'))
          .width('100%')

        this.section($r('app.string.settings_gpu'))
        this.row($r('app.string.settings_gpu_driver'), this.store.gpuDriverLabel());
        this.row($r('app.string.gpu_gpu_name'), this.store.device.gpuName.length > 0 ? this.store.device.gpuName : 'unknown');
        this.row($r('app.string.gpu_support'), this.store.device.gpuSupport.toString());

        this.section($r('app.string.settings_runtime'))
        this.row($r('app.string.settings_runtime_path'),
          this.store.runtime?.rootDir ?? '');
        this.row($r('app.string.runtime_version'), this.store.runtime?.version ?? '');
        this.row($r('app.string.runtime_mode'), this.store.runtime?.mode ?? 'unknown');
        this.row($r('app.string.settings_runtime_url'), this.store.runtimeUrl);
        Row() {
          Text($r('app.string.settings_runtime_url_edit'))
            .fontSize(14)
            .fontColor($r('app.color.primary'))
            .layoutWeight(1)
        }
        .width('100%')
        .padding(12)
        .backgroundColor($r('app.color.surface'))
        .borderRadius(10)
        .onClick(() => {
          this.runtimeUrlDraft = this.store.runtimeUrl;
          this.showRuntimeUrl = true;
        });

        this.section($r('app.string.settings_controller'))
        Row() {
          Text($r('app.string.settings_controller_map'))
            .fontSize(14)
            .fontColor($r('app.color.onBackground'))
            .layoutWeight(1)
          Text('>')
            .fontSize(14)
            .fontColor($r('app.color.onSurfaceVariant'))
        }
        .width('100%')
        .padding(12)
        .backgroundColor($r('app.color.surface'))
        .borderRadius(10)
        .onClick(() => {
          router.pushUrl({ url: 'pages/ControllerMapPage' });
        })

        this.section($r('app.string.settings_about'))
        this.row($r('app.string.detail_device'), `${this.store.device.deviceBrand} ${this.store.device.deviceModel}`);
        this.row($r('app.string.detail_os'), this.store.device.osRelease);
        this.row($r('app.string.detail_cpu'), `${this.store.device.cpuCores} cores`);
        this.row($r('app.string.detail_ram'), `${this.store.device.ramMiB} MiB`);
        this.row($r('app.string.detail_abi'), this.store.device.abi);
        this.row($r('app.string.detail_version'), '0.6.9-harmony');

        if (this.showRuntimeUrl) {
          this.runtimeUrlEditor()
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
