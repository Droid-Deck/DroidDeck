import { audio } from '@kit.AudioKit';

import { Logger } from '../common/Logger';
import { NativeBridge } from '../native/NativeBridge';

/**
 * Plays the guest's audio through the device speaker.
 *
 * The native layer reads interleaved stereo float frames out of the guest's
 * PCM socket; this class hands them to the platform's `AudioRenderer`,
 * which is the supported playback path on HarmonyOS.
 */
export class AudioService {
  private static instance: AudioService = new AudioService();

  static get Instance(): AudioService {
    return AudioService.instance;
  }

  private renderer: audio.AudioRenderer | undefined = undefined;
  private pumping: boolean = false;
  private socketPath: string = '';

  private constructor() {
  }

  /**
   * Opens the renderer and starts pumping frames.
   *
   * The guest creates its audio socket inside the shared runtime directory,
   * and it only exists once the guest is running. The native sink is
   * therefore connected lazily, from the pump loop, until the socket shows
   * up.
   */
  async start(socketPath: string): Promise<boolean> {
    if (this.renderer !== undefined) {
      return true;
    }
    this.socketPath = socketPath;

    try {
      this.renderer = await audio.createAudioRenderer({
        streamInfo: {
          samplingRate: 48000,
          channels: audio.AudioChannel.CHANNEL_2,
          sampleFormat: audio.AudioSampleFormat.SAMPLE_FORMAT_F32LE,
          encodingType: audio.AudioEncodingType.ENCODING_TYPE_RAW
        },
        rendererInfo: {
          usage: audio.StreamUsage.STREAM_USAGE_GAME,
          rendererFlags: 0
        }
      });
      await this.renderer.start();
    } catch (error) {
      Logger.error(`cannot create the audio renderer: ${error}`);
      this.renderer = undefined;
      return false;
    }

    this.pumping = true;
    this.pump();
    return true;
  }

  /** Stops pumping and releases the renderer. */
  async stop(): Promise<void> {
    this.pumping = false;
    NativeBridge.audioClose();
    if (this.renderer !== undefined) {
      try {
        await this.renderer.stop();
        await this.renderer.release();
      } catch (error) {
        Logger.warn(`audio renderer release failed: ${error}`);
      }
      this.renderer = undefined;
    }
  }

  private async pump(): Promise<void> {
    while (this.pumping && this.renderer !== undefined) {
      // The guest socket appears once the guest is running, so the
      // connection is retried until it succeeds.
      if (this.socketPath.length > 0 && !NativeBridge.audioOpen(this.socketPath)) {
        await AudioService.delay(500);
        continue;
      }

      const buffer = NativeBridge.audioRead(2048);
      if (buffer.byteLength > 0) {
        try {
          await this.renderer.write(buffer);
        } catch (error) {
          Logger.warn(`audio write failed: ${error}`);
          break;
        }
      } else {
        // The guest is idle; yield so the pump does not spin.
        await AudioService.delay(20);
      }
    }
  }

  private static delay(milliseconds: number): Promise<void> {
    return new Promise<void>((resolve) => {
      setTimeout(() => resolve(), milliseconds);
    });
  }
}
