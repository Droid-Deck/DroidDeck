# Vulkan capability fixtures

These are sanitized, bounded real captures saved on 2026-10-12 from a Pixel 8 Pro running Mali-G715 r54.3.0. They document historical behavior before the new component; they are not current-device validation.

| Fixture | Capture source and command | Source SHA-256 | Sanitized SHA-256 |
| --- | --- | --- | --- |
| `native.json` | `vulkan-native-android-vkjson.json`; native `cmd gpu vkjson` | `61c6e912c244ab0ad49e823e9f6947cc88531cb0a213e6cbb1feff282c98c67e` | `b867399844cfbbda45fcb86b763496bacc3b3f242592d518aa7728cf1c807b1e` |
| `bare.json` | `vulkan-capabilities-bare.json`; guest bare `vulkaninfo --json` | `d7a5433d29cb42f883e875d870df47fb453fd615de72c6bc6fe22376d8ead4d6` | `f78dba559a0c587093bb8fa8103cc7db6fa50730586e21b9ff9f9fd5eb641638` |
| `compat.json` | `vulkan-capabilities-game-managed-bc.json`; guest managed-game BC `vulkaninfo --json` | `6cab565b4c87ad89e77acdd4565946ccef308defba84c8ba1d1b28d84a14f058` | `52ccd22e37cf6a06bff661928a968ef944844d44163d8229ec266b1878646d9a` |

Capture runtime SHA-256: `2bb1d31bcbb89c1125dc0e5c6f2ee702091f011ea2531b5503a042d73538a825`.

## Redaction manifest

Every array or string value under a key ending in `UUID` or `LUID` is replaced with zero bytes while retaining its JSON type and array/string length. `deviceLUIDValid` is retained because it is a capability flag, not an identifier. The guest-export profile-history `date` value is replaced with the valid ISO placeholder `1970-01-01`; no user path, device serial, Android ID, hostname, or username string was present. GPU model, numeric vendor/device IDs, driver version/build, extensions, feature/property values, formats, and duplicate-source differences remain unchanged.

| Fixture | Replaced fields |
| --- | --- |
| `native.json` | `deviceLUID` x2; `deviceUUID` x2; `driverUUID` x2; `optimalTilingLayoutUUID` x3; `pipelineCacheUUID` x1 |
| `bare.json` | `date` x1; `deviceUUID` x1; `driverUUID` x1; `optimalTilingLayoutUUID` x1; `pipelineCacheUUID` x1 |
| `compat.json` | `date` x1; `deviceUUID` x1; `driverUUID` x1; `pipelineCacheUUID` x1 |
