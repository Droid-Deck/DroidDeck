import { router } from '@kit.ArkUI';
import { fileIo as fs } from '@kit.CoreFileKit';

import { Logger } from '../common/Logger';
import { Paths } from '../common/Paths';
import { NativeBridge } from '../native/NativeBridge';
import { CompositorBridge } from '../native/CompositorBridge';
import { AudioService } from '../service/AudioService';
import { InputService } from '../service/InputService';
import { RuntimeService } from '../service/RuntimeService';
import { AppStore } from '../state/AppStore';

/**
 * The running session.
 *
 * The Linux desktop is published into an XComponent whose native library is
 * `droiddeck_native`, so the same module that supervises the guest also
 * owns the surface the guest draws into. The Wayland compositor runs on its
 * own thread inside that module and reaches the screen through the GLES
 * presentation path.
 */
@Entry
@Component
struct SessionPage {
  @State store: AppStore = AppStore.Instance;
  @State surfaceReady: boolean = false;
  @State compositorRunning: boolean = false;
  @State firstFrameShown: boolean = false;
  @State sessionStarted: boolean = false;

  /** Size of the guest desktop in pixels. */
  private outputWidth: number = 1080;
  private outputHeight: number = 2400;

  aboutToAppear(): void {
    this.store.refreshRuntime();
    NativeBridge.inputOpen();
    // The guest creates its audio socket inside the shared runtime
    // directory, so the native sink connects to it there. The socket only
    // exists once the guest is running, so the service keeps retrying.
    AudioService.Instance.start(`${Paths.runtimeDir()}/droiddeck-audio.sock`);
  }

  aboutToDisappear(): void {
    RuntimeService.Instance.stop();
    NativeBridge.surfaceDetach();
    NativeBridge.inputClose();
    NativeBridge.audioServerStop();
    AudioService.Instance.stop();
  }

  /** Starts the compositor once the surface exists. */
  private async startCompositor(): Promise<void> {
    if (this.compositorRunning) {
      return;
    }
    // The compositor creates its socket in this directory, and the guest
    // looks for it there, so it has to exist first.
    try {
      await fs.mkdir(Paths.runtimeDir(), true);
    } catch (error) {
      Logger.debug(`the runtime directory already exists: ${error}`);
    }
    // The compositor publishes the keymap from this directory. Without it
    // the guest's clients get no keymap, and every key comes out wrong.
    await Paths.stageKeymap();
    const started = CompositorBridge.start((name: string, detail: string) => {
      this.onCompositorEvent(name, detail);
    });
    this.compositorRunning = started;
    if (started) {
      Logger.info('the compositor started');
      // The audio server has to be up before the guest starts, because the
      // guest's PulseAudio client connects to it as soon as it runs.
      this.startAudioServer();
      this.startGuest();
    } else {
      Logger.warn('the compositor did not start');
    }
  }

  /**
   * Starts the PulseAudio server the guest's audio client connects to.
   *
   * Upstream runs a PulseAudio server on the host and tells the guest where
   * it is with PULSE_SERVER. The port does the same, with a sink module
   * that writes the audio to the socket the native sink reads.
   */
  private startAudioServer(): void {
    const result = NativeBridge.audioServerStart(
      Paths.runtimeDir(),
      Paths.bundleLibsDir(),
      `${Paths.logDir()}/pulseaudio.log`
    );
    if (result.ok) {
      Logger.info('the PulseAudio server started');
    } else {
      Logger.warn(`the PulseAudio server did not start: ${result.error}`);
    }
  }

  /**
   * Starts the PulseAudio server the guest's audio client connects to.
   *
   * Upstream runs a PulseAudio server on the host and tells the guest where
   * it is with PULSE_SERVER. The port does the same, with a sink module
   * that writes the audio to the socket the native sink reads.
   */
  private startAudioServer(): void {
    const result = NativeBridge.audioServerStart(
      Paths.runtimeDir(),
      Paths.bundleLibsDir(),
      `${Paths.logDir()}/pulseaudio.log`
    );
    if (result.ok) {
      Logger.info('the PulseAudio server started');
    } else {
      Logger.warn(`the PulseAudio server did not start: ${result.error}`);
    }
  }

  /**
   * Starts the Linux guest.
   *
   * The compositor has to be up first: the guest looks for its Wayland
   * socket as soon as it starts, and gives up if it is not there yet.
   *
   * The guest is started in Steam's mode, the way upstream starts it for a
   * game. The guest's desktop modes need more than the published runtime
   * carries: `desktop` execs pcmanfm, and `lxqt` needs the LXQt desktop
   * pack and the real kwin wrapper, neither of which is in the image or
   * the overlay. They cannot be installed either: the guest installs
   * packages through `droiddeck-root`, which talks to the Android app's
   * root helper over a socket that does not exist here. The Steam mode
   * needs neither: it downloads the client with curl and unpacks it, and
   * both are in the image.
   */
  private startGuest(): void {
    if (this.sessionStarted) {
      return;
    }
    const started = RuntimeService.Instance.start({
      command: '/usr/local/bin/droiddeck-session',
      args: ['steam'],
      width: this.outputWidth,
      height: this.outputHeight,
      logPath: `${Paths.logDir()}/session.log`,
      debugDir: Paths.logDir()
    });
    this.sessionStarted = started;
    if (started) {
      Logger.info('the guest session started');
    } else {
      Logger.error('the guest session did not start');
    }
  }

  private onCompositorEvent(name: string, detail: string): void {
    if (name === 'firstFrame') {
      this.firstFrameShown = true;
    }
  }

  build() {
    Stack({ alignContent: Alignment.TopStart }) {
      XComponent({
        id: 'droiddeck_surface',
        type: XComponentType.SURFACE,
        libraryname: 'droiddeck_native'
      })
        .width('100%')
        .height('100%')
        .onLoad((event?: object) => {
          // The native module receives the window through the
          // OnSurfaceCreated entry point; this call reports whether the EGL
          // context is up.
          this.surfaceReady = NativeBridge.surfaceAttach();
          this.startCompositor();
        })
        .onDestroy(() => {
          NativeBridge.surfaceDetach();
        })
        .onAreaChange((oldArea: Area, newArea: Area) => {
          // ArkUI reports the area in virtual pixels; the compositor and
          // the guest session both need real pixels.
          const width = vp2px(Number(newArea.width));
          const height = vp2px(Number(newArea.height));
          this.outputWidth = width;
          this.outputHeight = height;
          CompositorBridge.setOutputSize(width, height);
        })
        .onTouch((event: TouchEvent) => {
          InputService.handleTouch(event);
        })
        .onKeyEvent((event: KeyEvent) => {
          return InputService.handleKey(event);
        })

      if (!this.surfaceReady) {
        Column({ space: 8 }) {
          Text($r('app.string.session_surface_placeholder'))
            .fontSize(14)
            .fontColor($r('app.color.onSurfaceVariant'))
            .textAlign(TextAlign.Center)
        }
        .width('100%')
        .height('100%')
        .justifyContent(FlexAlign.Center)
      }

      Button($r('app.string.session_exit'))
        .fontSize(12)
        .backgroundColor($r('app.color.surface'))
        .fontColor($r('app.color.onBackground'))
        .margin(12)
        .onClick(() => {
          this.store.stopSession();
          router.back();
        })
    }
    .width('100%')
    .height('100%')
    .backgroundColor(Color.Black)
  }
}
