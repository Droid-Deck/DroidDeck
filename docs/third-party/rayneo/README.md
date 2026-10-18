# RayNeo protocol and orientation filter attribution

RayNeoProtocol.kt and RayNeoOrientationFilter.kt are adapted from
https://github.com/informalTechCode/RayNeo-Air-Series-3DoF-Scaffold
at 581dbf712ed601ece4cf5e4a7c1f52e780299c88 (Apache License 2.0; LICENSE here).
The scaffold references Monado for protocol details; its notice is preserved here.

DroidDeck adaptations add frame validation, unsigned device ticks, endpoint filtering,
and conversion into the existing VITURE pose coordinate convention. The transport,
USB discovery and DroidDeck integration are implemented for this project.

GT USB identification and interface selection reference
https://github.com/verncat/RayNeo-Air-3S-Pro-OpenVR
at dfd2e8abab7052d88a1047266d89260347d0dd67, especially merged PR #3:
https://github.com/verncat/RayNeo-Air-3S-Pro-OpenVR/pull/3
(MIT; copyright 2025 verncat; full notice in LICENSE_VERNcat).
That contribution reports GT hardware tests for 3941:af50 on interface 5,
IMU streaming, spatial-button notification 0x08, and display/volume ACK handling.
DroidDeck keeps its Android transport and existing command-specific ACK parser;
it prefers interface 5 for this identity and falls back only to a unique pair
of protocol-sized endpoints. GT Max is not separately verified by this source.
