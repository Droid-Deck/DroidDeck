import { Logger } from '../common/Logger';
import { Game, GameSource } from '../model/Game';
import { HttpService } from './HttpService';

/**
 * Steam storefront access.
 *
 * Upstream DroidDeck reads the local Steam installation (appinfo.vdf, the
 * library folders) for the installed games and falls back to the store API
 * for artwork and metadata. The HarmonyOS port starts from the store API
 * because there is no local Steam client yet, and caches the result.
 */
export class SteamService {
  static readonly APP_LIST_URL: string = 'https://api.steampowered.com/ISteamApps/GetAppList/v2/';
  static readonly DETAILS_URL: string = 'https://store.steampowered.com/api/appdetails';
  static readonly GRID_DB_URL: string = 'https://www.steamgriddb.com/api/v2';

  private static appListCache: number[] = [];

  /** Fetches the full Steam app id list, cached for the process lifetime. */
  static async appList(): Promise<number[]> {
    if (SteamService.appListCache.length > 0) {
      return SteamService.appListCache;
    }
    const response = await HttpService.get(SteamService.APP_LIST_URL);
    if (!response.ok) {
      // The store cannot be reached. Without this the library looks empty,
      // which reads as "no games" rather than "the store is down".
      Logger.error(`the Steam app list could not be fetched: ${response.code}`);
      return SteamService.appListCache;
    }
    try {
      const parsed = JSON.parse(response.body) as SteamAppListResponse;
      const ids: number[] = [];
      for (const entry of parsed.applist.apps) {
        ids.push(entry.appid);
      }
      SteamService.appListCache = ids;
    } catch (error) {
      // The store answers with an HTML page when it is unhappy, and a parse
      // failure that is swallowed looks like an empty library. The reason is
      // logged so the difference between "no games" and "the store is
      // unreachable" is visible.
      Logger.error(`the Steam app list is not valid JSON: ${error}`);
    }
    return SteamService.appListCache;
  }

  /** Fetches the store metadata for one app. */
  static async details(appId: number): Promise<SteamAppDetails | undefined> {
    const response = await HttpService.get(`${SteamService.DETAILS_URL}?appids=${appId}&l=english`);
    if (!response.ok) {
      return undefined;
    }
    try {
      const parsed = JSON.parse(response.body) as Record<string, SteamAppDetailPayload>;
      const payload = parsed[`${appId}`];
      if (payload === undefined || !payload.success) {
        return undefined;
      }
      const data = payload.data;
      return {
        appId: appId,
        name: data.name,
        type: data.type,
        headerImage: data.header_image,
        capsuleImage: data.capsule_image,
        shortDescription: data.short_description
      };
    } catch (error) {
      return undefined;
    }
  }

  /** Cover art for the library grid. */
  static capsuleUrl(appId: number): string {
    return `https://cdn.cloudflare.steamstatic.com/steam/apps/${appId}/library_600x900_2x.jpg`;
  }

  /** Wide hero art for the home screen. */
  static heroUrl(appId: number): string {
    return `https://cdn.cloudflare.steamstatic.com/steam/apps/${appId}/library_hero.jpg`;
  }

  /** Transparent logo for the title bar. */
  static logoUrl(appId: number): string {
    return `https://cdn.cloudflare.steamstatic.com/steam/apps/${appId}/logo.png`;
  }

  /** Builds a game entry from the store metadata. */
  static toGame(details: SteamAppDetails): Game {
    const game = new Game(details.appId, details.name, GameSource.STEAM);
    game.art = SteamService.capsuleUrl(details.appId);
    game.hero = SteamService.heroUrl(details.appId);
    game.logo = SteamService.logoUrl(details.appId);
    game.storeId = `${details.appId}`;
    return game;
  }
}

export interface SteamAppDetails {
  appId: number;
  name: string;
  type: string;
  headerImage: string;
  capsuleImage: string;
  shortDescription: string;
}

interface SteamAppListResponse {
  applist: {
    apps: SteamAppListEntry[];
  };
}

interface SteamAppListEntry {
  appid: number;
  name: string;
}

interface SteamAppDetailPayload {
  success: boolean;
  data: {
    name: string;
    type: string;
    header_image: string;
    capsule_image: string;
    short_description: string;
  };
}
