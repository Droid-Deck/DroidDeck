# D3D10 renderer crash investigation

Status: root cause unproven; no runtime change or publishable fix.

## Evidence

Discord message 1571620183263416432 from ThatGuy reports that the original 2007 GOG release crashes after its intro videos while the remastered release reaches the menu. The report covers application 0.7.1 on an S948U1 with Adreno 840.

The supplied session archive identifies DroidDeck-Proton-11-12.8 and DXVK v3.1.1-221-g71bf4bad. Within the original release's Steam log:

- Lines 11057–11058 show the game-local native d3dx10_33 DLL loading the builtin D3DCompiler_33 DLL.
- Line 11610 records a reflection GetDesc partial-stub warning on thread 01d8 at 182976.924.
- Lines 12011–12012 show the Renderer thread 01e0 compiling twice while flags 0x1000 are ignored, at about 183023.553.
- Lines 12014–12017 record an access violation reading address 8 at executable offset 0x52B090.
- Line 12132 identifies the faulting instruction as movl 8(%ecx), %eax, and line 12086 shows ECX is zero.

The reflection warning precedes the crash by about 47 seconds on another thread. Temporal proximity does not establish causation. No failed shader-compilation diagnostic accompanies the final two compilation calls, and the loaded compiler is version 33 rather than 47. The null object's producer remains unidentified.

## Research

- [A matching desktop report](https://github.com/doitsujin/dxvk/issues/655#issuecomment-4697290483) has the same null-plus-8 read immediately after two ignored-0x1000 messages. Installing compiler 43 did not fix that report, arguing against assuming a device-specific fault.
- [The follow-up discussion](https://github.com/doitsujin/dxvk/issues/655#issuecomment-4698071691) calls for identifying the actual compiler dependency.
- Historical DXVK commit 8630ee235a7a97c0ad87af2703b3c06bfd8e475e addressed concurrent D3D10 device access. It is not evidence that the current compiler warning causes this crash.
- [The upstream compatibility report](https://github.com/ValveSoftware/Proton/issues/388) includes DX9 fallback reports, but also crashes when loading with DX9. DX9 is an experiment, not a verified fix.
- [Wine's reflection implementation](https://github.com/wine-mirror/wine/blob/wine-10.0/dlls/d3dcompiler_43/reflection.c#L396) fills many descriptor fields despite its partial-stub warning. The warning alone does not establish a null return.
- Wine commit 815fe9deb15b2cc742a243166316a1d33c6a03e0 replaces Wine reflection with vkd3d reflection. This is a candidate comparison, not a proven fix to port.

Searches covered DroidDeck history and a repository fork, FEX, Box64, Winlator, vkd3d-proton, Wine and vkd3d reports, driver trackers, Mesa, and recent component commits. No verified matching general fix was found.

## Repository audit

No matching open or draft pull request was found. Closed pull request #1033 concerns remastered presentation timing, a different symptom. Open issues #593, #916 and #218 mention related titles or hardware but do not describe this original-release compiler crash; none should be closed by this investigation.

`DependencyDetector.kt` lines 155–159 recognize compiler filenames as d3dx9 recommendations, but this does not prove a dependency-installation failure. Existing component placement and architecture regression tests exist in `tools/tests/test_wincomponents.py`. No duplicate helper or title-specific override is warranted by the current evidence.

## Device evidence

A Retroid Pocket Duo running application 0.7.1, build pr2426 at 55c9b2b83e30f412c28dce40186359a1917c9d7f, with runtime ubuntu-r1-rc5 was inspected through Steam and the installed manifests. The original release is absent, so the reported crash and DX9 comparison could not be reproduced. No APK was changed. The session was stopped and the application closed after inspection.

## Next experiment

Obtain access to the reporter's original GOG installation on a test device, or request controlled reporter runs through the support workflow. Capture the current DX10 baseline, a DX9 run, and a DX10 run with the actual native compiler 33 selected. Preserve explicit user overrides and compare loaded-DLL evidence in each log.

Trace the two final compiler calls and their HRESULT and blob outputs, then identify what produces the null object before changing runtime code. If native compiler 33 fixes the crash, inspect general redistributable dependency detection and installation rather than adding a title-specific rule. If it does not, compare the newer upstream reflection implementation and instrument the shader-compilation path.

## Verification boundary

No runtime-source or test changes were made during the investigation, and no test suite was run. Reproduction and before/after game verification remain unmet. This record must not be advertised as a fix or used to close reports.
