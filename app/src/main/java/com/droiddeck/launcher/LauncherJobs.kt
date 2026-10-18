package com.droiddeck.launcher

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * Work the launcher screen started that outlives the screen: a recreated MainActivity must still see it as busy,
 * or it would offer to start a session or remove the runtime while the work is writing into it. Main thread only.
 */
internal object LauncherJobs {
    var pkgId by mutableStateOf<String?>(null)
    var pkgStage by mutableStateOf<String?>(null)
    var pkgPercent by mutableIntStateOf(-1)
    var saveBusy by mutableStateOf<String?>(null)
    var compBusy by mutableStateOf<String?>(null)
    var protonBusyId by mutableStateOf<String?>(null)

    /** Takes the Activity that receives the picker result, so a recreated screen still gets it. */
    var savePicked: ((MainActivity, File) -> Unit)? = null
}
