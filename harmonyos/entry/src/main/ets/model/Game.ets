/**
 * Domain model for a game in the library.
 *
 * Mirrors upstream DroidDeck's `Library.SteamGame`, renamed for ArkTS
 * conventions and extended with the fields the HarmonyOS UI needs.
 */
export enum GameSource {
  STEAM = 'steam',
  GOG = 'gog',
  EPIC = 'epic',
  XBOX = 'xbox',
  ITCH = 'itch',
  GAMEJOLT = 'gamejolt',
  BATTLENET = 'battlenet',
  LOCAL = 'local'
}

@Observed
export class Game {
  /** Steam-style numeric app id. 0 for sources that use string ids only. */
  appId: number = 0;
  /** Stable identifier: `source:appId`, or the absolute path for local games. */
  key: string = '';
  name: string = '';
  source: GameSource = GameSource.LOCAL;
  /** Which library folder on the guest the game lives in. */
  library: string = '';
  installed: boolean = false;
  installedSize: number = 0;
  lastPlayed: number = 0;
  /** Cached artwork, resolved from the artwork cache rather than the network. */
  art: string = '';
  hero: string = '';
  logo: string = '';
  icon: string = '';
  /** Store-side id when it differs from the Steam app id. */
  storeId: string = '';
  /** Compatibility tool override, e.g. a specific Proton build. */
  compatTool: string = '';

  constructor(appId: number = 0, name: string = '', source: GameSource = GameSource.LOCAL) {
    this.appId = appId;
    this.name = name;
    this.source = source;
    this.key = `${source}:${appId}`;
  }
}

/** A ROM or disc image tracked for an emulator. */
export class Rom {
  name: string = '';
  hostPath: string = '';
  guestPath: string = '';
  emulatorId: string = '';
  art: string = '';
}

/** An emulator installed into the runtime. */
export class Emulator {
  id: string = '';
  name: string = '';
  system: string = '';
  program: string = '';
  installed: boolean = false;
  games: Rom[] = [];
}
