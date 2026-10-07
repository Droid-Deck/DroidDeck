package com.droiddeck.launcher.files

import java.io.File

/** Metadata for design previews. [FileManagerScreen] ignores it outside inspection mode. */
class FileManagerPreviewState(val directory: File, val entries: List<File>, val grid: Boolean = true, val favorites: Boolean = false)
