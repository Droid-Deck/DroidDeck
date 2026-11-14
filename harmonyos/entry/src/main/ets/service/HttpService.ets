import { common } from '@kit.AbilityKit';
import { request } from '@kit.BasicServicesKit';
import { http } from '@kit.NetworkKit';
import { fileIo as fs } from '@kit.CoreFileKit';

import { Logger } from '../common/Logger';

export interface HttpResult {
  code: number;
  body: string;
  ok: boolean;
}

/**
 * Small HTTP helper.
 *
 * Upstream DroidDeck uses OkHttp through `core/Downloader.java`; the ArkTS
 * port uses `@ohos.net.http`, which covers the same use: JSON over HTTPS
 * with a per-request timeout and a bearer token for the store APIs.
 */
export class HttpService {
  private static readonly CONNECT_TIMEOUT_MS: number = 15000;
  private static readonly READ_TIMEOUT_MS: number = 20000;

  static async get(url: string, headers: Record<string, string> = {}): Promise<HttpResult> {
    return HttpService.request(url, http.RequestMethod.GET, headers, '');
  }

  static async post(url: string, headers: Record<string, string>, body: string): Promise<HttpResult> {
    return HttpService.request(url, http.RequestMethod.POST, headers, body);
  }

  private static async request(url: string, method: http.RequestMethod,
    headers: Record<string, string>, body: string): Promise<HttpResult> {
    const request = http.createHttp();
    try {
      const response = await request.request(url, {
        method: method,
        header: headers,
        extraData: body.length > 0 ? body : undefined,
        expectDataType: http.HttpDataType.STRING,
        connectTimeout: HttpService.CONNECT_TIMEOUT_MS,
        readTimeout: HttpService.READ_TIMEOUT_MS,
        usingCache: false
      });

      const text = typeof response.result === 'string' ? response.result : JSON.stringify(response.result);
      return {
        code: response.responseCode,
        body: text,
        ok: response.responseCode >= 200 && response.responseCode < 300
      };
    } catch (error) {
      Logger.error(`http ${method} ${url} failed: ${error}`);
      return { code: -1, body: '', ok: false };
    } finally {
      request.destroy();
    }
  }

  /**
   * Downloads a file into the app sandbox.
   *
   * API 26 has no `HttpRequest.downloadFile`; the download manager in
   * `@ohos.request` owns file downloads now, so the task's progress and
   * completion events drive the progress reporting.
   *
   * @param context the ability context the download runs under
   * @param url source of the file
   * @param destination absolute path inside the app sandbox
   * @param onProgress receives a percentage, 0..100
   */
  static async download(context: common.UIAbilityContext, url: string, destination: string,
    onProgress: (percent: number) => void): Promise<boolean> {
    try {
      const parent = destination.substring(0, destination.lastIndexOf('/'));
      if (parent.length > 0) {
        await fs.mkdir(parent, true);
      }

      const task = await request.downloadFile(context, {
        url: url,
        filePath: destination,
        description: 'DroidDeck runtime'
      });

      return await new Promise<boolean>((resolve) => {
        task.on('progress', (received: number, total: number) => {
          if (total > 0) {
            onProgress(Math.min(100, Math.floor((received / total) * 100)));
          }
        });
        task.on('complete', () => {
          Logger.info(`downloaded ${url}`);
          resolve(true);
        });
        task.on('fail', (code: number) => {
          Logger.error(`download of ${url} failed with code ${code}`);
          resolve(false);
        });
      });
    } catch (error) {
      Logger.error(`download ${url} failed: ${error}`);
      return false;
    }
  }
}
