package com.droiddeck.launcher.stores.ubisoft

import android.content.Context
import com.droiddeck.launcher.frontend.AddedGames
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File

/** The one prefix every Ubisoft game runs in, so Ubisoft Connect is installed and signed in once. */
object UbisoftConnect {
    const val APP_ID = 2929927072L

    fun prefix(context: Context) = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/steamapps/compatdata/$APP_ID")

    fun installed(context: Context) = File(prefix(context), "pfx/drive_c/Program Files (x86)/Ubisoft/Ubisoft Game Launcher/upc.exe").isFile

    fun launchOptions(context: Context): String =
        AddedGames.guestPath(context, prefix(context))?.let { "STEAM_COMPAT_DATA_PATH=$it %command%" }.orEmpty()
}
