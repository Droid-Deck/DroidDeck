package com.droiddeck.launcher.session

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.droiddeck.launcher.R
import com.droiddeck.launcher.gpu.SteamVr
import com.droiddeck.launcher.ui.xrModelStatusText
import java.text.NumberFormat

internal class XrPreparation(private val context: Context) {
    private val loading = LoadingState(context, SessionState.mode == SessionService.MODE_STEAM)
    private val image = Bitmap.createBitmap(1024, 768, Bitmap.Config.ARGB_8888)

    fun bitmap(): Bitmap {
        val steam = SessionState.mode == SessionService.MODE_STEAM
        loading.update(context, SessionState.logFile)
        val title = context.getString(if (SessionState.firstFrameSeen) R.string.load_xr_preparing_steamvr
            else if (steam) R.string.load_starting_steam else R.string.session_starting_desktop)
        val detail = if (SessionState.firstFrameSeen) context.getString(
            if (SteamVrSession.status == SteamVr.Status.INSTALLING) R.string.cardboard_steamvr_installing
            else if (steam) R.string.load_xr_steam_ready else R.string.load_xr_preparing_steamvr)
        else loading.step + if (loading.percent >= 0) " · " +
            NumberFormat.getPercentInstance(context.resources.configuration.locales[0]).format(loading.percent / 100.0) else ""
        val models = xrModelStatusText(context, XrControllerModels.status.value)
        image.eraseColor(Color.rgb(20, 24, 31))
        val canvas = Canvas(image)
        var top = 100f
        for ((index, text) in listOf(title, detail, models).withIndex()) {
            if (text.isEmpty()) continue
            val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                color = if (index == 0) Color.WHITE else Color.rgb(192, 202, 218)
                textSize = (if (index == 0) 38f else 28f) * context.resources.configuration.fontScale
                typeface = if (index == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                textLocale = context.resources.configuration.locales[0]
            }
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, 928)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
            canvas.save()
            canvas.translate(48f, top)
            layout.draw(canvas)
            canvas.restore()
            top += layout.height + 26f
        }
        return image
    }
}
