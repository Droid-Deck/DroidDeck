package com.droiddeck.launcher.ui

import androidx.compose.runtime.Composable

@DroidDeckPreviews
@Composable
internal fun GpuDriversPreview() {
    PreviewHost {
        GpuDriversPanel(PreviewData.gpu, GpuDriversActions()) {}
    }
}

@DroidDeckPreviews
@Composable
internal fun ProtonsPreview() {
    PreviewHost {
        ProtonPage(listOf(ProtonRow("ge-proton", "GE-Proton 10-20", "10-20", false), ProtonRow("proton", "Proton Experimental", null, false)), null, null, -1, true, false, {}, {}, {}, {})
    }
}

@DroidDeckPreviews
@Composable
internal fun ProtonDownloadPreview() {
    PreviewHost {
        ProtonPage(listOf(ProtonRow("ge-proton", "GE-Proton 10-20", null, false)), "ge-proton", "Downloading", 42, true, false, {}, {}, {}, {})
    }
}

@DroidDeckPreviews
@Composable
internal fun ProtonsEmptyPreview() {
    PreviewHost {
        ProtonPage(emptyList(), null, null, -1, false, false, {}, {}, {}, {})
    }
}

@DroidDeckPreviews
@Composable
internal fun DriversPreview() {
    PreviewHost {
        DriverPage("GPU drivers", "Choose a driver", PreviewData.drivers, "auto",
            listOf(DownloadRow("turnip", "Turnip 25.3", "Adreno 7xx", 42)), "Downloading", false, "Import driver", true,
            {}, {}, {}, {}, {}, {}, {})
    }
}

@DroidDeckPreviews
@Composable
internal fun ComponentsPreview() {
    PreviewHost {
        ComponentsPage(PreviewData.components, PreviewData.catalog, PreviewData.TIME, "ge-proton", "dxvk", false, null, emptyMap(),
            {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, requestInitialFocus = false, gpu = PreviewData.gpu)
    }
}

@DroidDeckPreviews
@Composable
internal fun ComponentsLoadingPreview() {
    PreviewHost {
        ComponentsPage(null, PreviewData.catalog, PreviewData.TIME, "ge-proton", "dxvk", true, null, emptyMap(),
            {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, requestInitialFocus = false, gpu = PreviewData.gpu)
    }
}

@DroidDeckPreviews
@Composable
internal fun GpuComponentsPreview() {
    PreviewHost {
        ComponentsPage(PreviewData.components, PreviewData.catalog, PreviewData.TIME, "ge-proton", GPU_TAB, false, null, emptyMap(),
            {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, requestInitialFocus = false, gpu = PreviewData.gpu)
    }
}
