#!/bin/bash
# Runs tests/d3d11_check.exe under Proton Experimental's wine + DXVK. Needs a prefix dir with the
# exe, DXVK's d3d11/dxgi and vkd3d's DLLs (see README). VK_DRIVER_FILES picks native or bridge.
P="$HOME/.steam/steam/steamapps/common/Proton - Experimental/files"
W=${VKB_WINE_DIR:?set VKB_WINE_DIR}
cd "$W/app" && WINEPREFIX=$W/pfx WINEDEBUG=-all WINEDLLOVERRIDES="d3d11,dxgi=n,b" DXVK_LOG_PATH=$W timeout 180 "$P/bin/wine" d3d11_check.exe 2>&1 \
  | grep -v "^info:\|^warn:\|fixme\|err:hid\|wineserver"
