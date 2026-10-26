# Local multiplayer experiment

Status: implemented prototype; no two-device hardware or game validation. Open
**Settings → Overview → Local multiplayer · Experimental** before starting a session.

## Scope

LAN mode: two devices each run their own copy of a LAN-capable game. One device can create
an Android local-only hotspot; the other joins it through Android Wi-Fi settings.
No router, SIM, mobile data, internet connection, or root access is required by
the hotspot API. Android chooses the network name, password, band, and subnet.
Hardware/OEM policy can reject the request. Existing Wi-Fi can disconnect.

The separate USB path uses an IP link that Android has already configured. A
data-capable USB-C cable plus USB tethering on one device can work **only if**
the receiving device supports USB host mode and that tethering network driver
(RNDIS/NCM, depending on device). A USB-C connector alone guarantees none of this.
USB Ethernet adapters and an Ethernet cable are another possible transport.
DroidDeck does not create USB gadget functions, force USB roles, enable tethering,
or install kernel drivers.

Shared-screen mode: one DroidDeck device runs a game through Steam and the other
uses Valve's Steam Link app for video, audio, and controller input. This reuses
DroidDeck's existing Steam Remote Play host; it adds a setup guide and a client
app/store handoff, not a new streaming implementation or an embedded client.

Neither mode adds online matchmaking, an internet-sharing hotspot, a virtual LAN
tunnel, or multiplayer to games that do not support it.
Steam authentication, account/licensing requirements, DRM, and a game's own
network implementation still apply. Prepare and test offline mode before travel.

## Shared-screen co-op through Steam Link

This is the EasyControl-style outcome: the game runs on the host, both devices
see the host's picture, and the client supplies a separate gamepad. No ADB,
Shizuku, root permission, or Android-wide input injection is introduced. The
screen includes distinct host and client instructions, a return-to-host button
when a Steam session is running, the selected LAN address for manual pairing,
and a button to open Steam Link on the device being used. When Steam Link is
absent, that button opens its store listing with a browser fallback; installation
and pairing remain user actions. App availability refreshes when setup resumes.

1. Put both devices on the same Wi-Fi, or host the local-only hotspot and join it
   from the second device. Select the host's actual LAN interface before launching
   Steam. The Steam Link client does **not** need a Linux session or DroidDeck's
   guest-interface override.
2. Start Steam through DroidDeck on the host and enable **Settings → Remote Play →
   Enable Remote Play**. The host must remain running and its own controls must
   remain enabled. This guide returns to an existing Steam session; it does not
   bypass DroidDeck's normal setup checks to start a new one.
3. Open Steam Link on the second device, find/add the host, and approve its pairing
   PIN in the host's Steam. Attach the second player's controller to the client
   device or enable Steam Link's touch controller. Start streaming.
4. Launch a local co-op/split-screen game through the host's Steam. Enter its local
   multiplayer mode and join using the second gamepad. The desired result is two
   distinct controllers, not keyboard/mouse events controlling the same player.
   If necessary adjust Steam's controller order and the game's input settings.
   Player numbering is controlled by Steam/the game; this guide does not force
   the host into player one or merge the inputs in DroidDeck.

The host's existing capture path uses PipeWire and its MediaCodec-backed V4L2
encoder bridge ([release notes](releases/0.6.0.md),
[VideoEncodeBridge](../app/src/main/java/com/droiddeck/launcher/session/VideoEncodeBridge.kt)).
Steam Link handles pairing, the streaming protocol, decoding, and remote input;
Steam's virtual-controller support supplies guest controller devices. Games added
to Steam can be candidates, but a standalone non-Steam session is not a host for
this flow. Displaying two copies of the same split-screen picture does not give
each player an independent camera or turn a single-player game into co-op.

Install the client, authenticate Steam, and pair while online before travel. The
hotspot itself needs no internet, but fully offline streaming and first-time
offline pairing have **not** been validated in this DroidDeck setup. Do not equate
successful hotspot creation with working Steam Link. Sources:
[Valve Remote Play support](https://help.steampowered.com/en/faqs/view/0689-74B8-92AC-10F2),
[network requirements](https://help.steampowered.com/en/faqs/view/3E3D-BE6B-787D-A5D2),
and [Valve's Android client listing](https://play.google.com/store/apps/details?id=com.valvesoftware.steamlink).

## Linux integration

The guest uses Android's kernel networking rather than a separate network
namespace. Selecting **Use for games** advertises an actual private/link-local
IPv4 interface, subnet, MTU, and kernel interface index through `droiddeck-net`.
The existing preload uses that snapshot for `getifaddrs()` and `if_nameindex()`;
an explicit selection overrides normal enumeration. The real index matters for
multicast membership. Broadcast addresses are computed from the real prefix.
NetworkManager's compatibility snapshot also reports the selected LAN without
claiming internet validation, an upstream gateway, or DNS. Android's default
network continues to supply the guest's resolver configuration.

Selection is process-local, survives launcher/activity recreation, and is cleared
by **Android default network** or process exit. A Wi-Fi multicast lock is held
while selected and released on reset/process death. The session rechecks the
selected interface every second, including address changes and unplug/reconnect.
An absent selected interface clears its advertised addresses rather than
substituting cellular or the usual synthetic offline address. The hotspot is
owned by a foreground service, so launching a game or leaving the setup screen
does not release the reservation. Stop it from the screen or its notification.
The service releases late reservations delivered after shutdown, and does not
restart a hotspot automatically after process death.

**Advertising a link does not install routes or bind guest sockets to an Android
Network.** `ConnectivityManager.bindProcessToNetwork()` alone is insufficient to
establish that glibc/FEX subprocess sockets obey Android's bionic networking
hooks. This prototype leaves kernel routing to Android. On the joining device,
accept “stay connected without internet” and turn off mobile data if Android
prefers cellular. First try direct IP; a visible interface does not prove that
unicast, broadcast, or multicast traffic works. VPNs, OEM firewall/client
isolation, Steam/Wine behavior, and games that insist on internet remain risks.

## Prior art and alternatives

Reviewed on 2026-10-24; no third-party source was copied.

| Approach | Source and conclusion |
| --- | --- |
| Android local-only hotspot | [Android guide](https://developer.android.com/develop/connectivity/wifi/localonlyhotspot) and [reservation lifecycle](https://developer.android.com/reference/android/net/wifi/WifiManager.LocalOnlyHotspotReservation): public API since 26, designed for nearby devices without internet. Fits ordinary Wi-Fi clients. DroidDeck currently targets API 28, so hosting requests precise location and requires Location services; a future target-33 migration must revisit `NEARBY_WIFI_DEVICES`. |
| Wi-Fi Direct | [Android guide](https://developer.android.com/develop/connectivity/wifi/wifip2p): possible without an access point, but needs discovery/group ownership/connection recovery and device capability handling. A second wireless backend is deferred until hotspot traffic is verified. |
| Steam Deck wired LAN | [dafta's USB Ethernet gadget script](https://gist.github.com/dafta/0aadeba3aa8bcbbc8b92a233977571ed), [DeckMTP](https://github.com/dafta/DeckMTP), and [GadgetDeck](https://github.com/Frederic98/GadgetDeck): useful USB gadget prior art. The script needs root/configfs, and DeckMTP documents enabling dual-role USB in firmware. A normal Android app cannot transplant that setup. GadgetDeck lists Ethernet multiplayer as a goal rather than evidence of tested DroidDeck support. |
| Steam Deck wireless LAN | [NetDeck](https://github.com/fewtarius/NetDeck) creates hotspots through Linux networking tools with root access and recommends a second Wi-Fi adapter. Its privileges and adapter assumptions differ from an Android app. |
| Android co-op streaming | [EasyControl fork](https://github.com/daitj/Easycontrol), identified from [the Thor co-op guide](https://www.reddit.com/r/AynThor/comments/1usuxsx/share_your_screen_to_another_device_for_couch/): Android-to-Android scrcpy remote control/streaming, including USB. The guide uses a Shizuku fork and runs the game on one device. A stream or ADB port forward does not create a LAN for two independent games. |

A generic cable-only fallback using Android USB host/accessory bulk endpoints
would require a new packet tunnel and an IP stack/VPN integration, with permission,
VPN coexistence, routing, MTU, UDP/multicast, and lifecycle work on both ends.
That is substantially larger than socket forwarding and is deferred pending
evidence that stock USB tethering is inadequate on the target handhelds.

## Validation before promoting the experiment

Automated coverage checks hotspot request deduplication, permission/location
failures, unsupported hardware, reservation cleanup (including late callbacks),
LAN snapshot/index/address publication, disconnect/reconnect, returning to default,
and native interface enumeration/broadcast calculation. These tests simulate
platform behavior; they do not verify a radio, cable, Android routing, or a game.

Use two physical devices, recording models, OS builds, transport, selected
interfaces/addresses, game versions, and whether mobile data/VPNs were enabled:

1. With no external Wi-Fi and mobile data off, host/join a hotspot. Check its
   credentials, leaving/reopening setup, launching Steam/Desktop, notification
   stop, permission denial, Location off, and process death.
2. In both Linux sessions compare `cat /etc/droiddeck-net`,
   `cat /etc/droiddeck-network.json`, and Python
   `socket.if_nameindex()`; confirm distinct real addresses in the same subnet.
3. Test bidirectional TCP and UDP between guest sessions, binding the real
   addresses and using a high port (for example 27842). Repeat after disconnect,
   changed subnet, Android backgrounding, and resume. Android-to-Android reachability
   alone does not establish guest-to-guest reachability.
4. Test a subnet-directed UDP broadcast and UDP multicast with membership on the
   selected interface; then test an actual game's LAN browser and direct-IP join.
   Record failures separately from successful direct-IP traffic.
5. Repeat with mobile data active and with a VPN to establish routing limitations.
6. For USB, try both host/device roles and tethering directions; confirm an
   actual interface/address before testing guest traffic. Repeat with airplane
   mode/no SIM, cable unplug/replug, and USB Ethernet adapters. Check charging
   direction, thermal load, and sustained play.
7. For shared-screen co-op, test Steam Link installed/missing, store/browser
   fallback, returning after installation, pairing PIN approval, manual host-IP
   entry, and return-to-host behavior. In a local co-op game, press different
   buttons/sticks on host and client simultaneously and confirm two independent
   players. Repeat using the Steam Link touch controller; check controller order,
   video, audio, reconnect, and behavior when the client exits.
8. Repeat shared-screen play over the local-only hotspot with no mobile data or
   external Wi-Fi, first with an already-paired client and then first-time pairing.
   Record authentication/pairing failures separately from discovery, video, audio,
   input, and LAN routing failures. Measure stream latency, frame drops, and host
   thermal load during sustained play before choosing performance defaults.

Keep this experimental until those results exist; successful builds are not
evidence of working multiplayer.
