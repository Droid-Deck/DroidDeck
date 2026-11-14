import { router } from '@kit.ArkUI';

import { NativeBridge } from '../native/NativeBridge';

/**
 * Controller button mapping.
 *
 * Upstream maps Android key codes onto evdev codes in
 * `input/EvdevKeys.java` and `input/PadBridge.java`. The HarmonyOS port
 * keeps the evdev side and re-plumbs the source onto the multimodal input
 * events, so the mapping table below is the guest-facing half of that.
 */
interface MappingEntry {
  label: string;
  evdevCode: number;
}

const MAPPINGS: MappingEntry[] = [
  { label: 'A / Cross', evdevCode: 0x130 },
  { label: 'B / Circle', evdevCode: 0x131 },
  { label: 'X / Square', evdevCode: 0x133 },
  { label: 'Y / Triangle', evdevCode: 0x134 },
  { label: 'L1', evdevCode: 0x136 },
  { label: 'R1', evdevCode: 0x137 },
  { label: 'Select', evdevCode: 0x13a },
  { label: 'Start', evdevCode: 0x13b },
  { label: 'L3', evdevCode: 0x13d },
  { label: 'R3', evdevCode: 0x13e }
];

@Entry
@Component
struct ControllerMapPage {
  @State selectedIndex: number = -1;

  build() {
    Column({ space: 10 }) {
      Row() {
        Text('<')
          .fontSize(20)
          .fontColor($r('app.color.onBackground'))
          .padding(12)
          .onClick(() => {
            router.back();
          })
        Text($r('app.string.controller_title'))
          .fontSize(20)
          .fontWeight(FontWeight.Bold)
          .fontColor($r('app.color.onBackground'))
      }
      .width('100%')

      Text($r('app.string.controller_hint'))
        .fontSize(12)
        .fontColor($r('app.color.onSurfaceVariant'))
        .width('100%')
        .margin({ left: 16, right: 16 })

      Scroll() {
        Column({ space: 8 }) {
          ForEach(MAPPINGS, (entry: MappingEntry, index: number) => {
            Row() {
              Text(entry.label)
                .fontSize(14)
                .fontColor($r('app.color.onBackground'))
                .layoutWeight(1)
              Text(this.selectedIndex === index ? 'waiting for input...' : 'press to assign')
                .fontSize(12)
                .fontColor(this.selectedIndex === index
                  ? $r('app.color.primary')
                  : $r('app.color.onSurfaceVariant'))
            }
            .width('100%')
            .padding(14)
            .backgroundColor($r('app.color.surface'))
            .borderRadius(10)
            .onClick(() => {
              this.selectedIndex = index;
              // Pressing a button while a slot is selected maps it straight
              // onto the guest's evdev code.
              NativeBridge.inputEvent('button', entry.evdevCode, 1);
              NativeBridge.inputEvent('button', entry.evdevCode, 0);
              this.selectedIndex = -1;
            })
          }, (entry: MappingEntry) => entry.label)
        }
        .padding(16)
        .alignItems(HorizontalAlign.Start)
      }
      .width('100%')
      .layoutWeight(1)
      .scrollBar(BarState.Off)
    }
    .width('100%')
    .height('100%')
    .backgroundColor($r('app.color.background'))
  }
}
