package com.droiddeck.launcher

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * Work the launcher screen started that outlives the screen: a recreated launcher activity must still see it as busy,
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
    var savePicked: ((FrontEndActivity, File) -> Unit)? = null
        private set
    enum class SavePicker { ZIP, DIRECTORY }
    private var savePicker: SavePicker? = null
    private var savePickerOwner: String? = null

    fun beginSavePick(owner: String, picker: SavePicker, then: (FrontEndActivity, File) -> Unit): Boolean {
        if (savePicked != null || saveBusy != null) return false
        savePickerOwner = owner
        savePicker = picker
        savePicked = then
        return true
    }

    fun takeSavePick(owner: String, picker: SavePicker): ((FrontEndActivity, File) -> Unit)? {
        if (savePickerOwner != owner || savePicker != picker) return null
        savePicker = null
        savePickerOwner = null
        return savePicked.also { savePicked = null }
    }

    fun abandonSavePick(owner: String) {
        if (savePickerOwner != owner) return
        savePicker = null
        savePickerOwner = null
        savePicked = null
    }
}
