package com.droiddeck.launcher.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import com.droiddeck.launcher.session.SessionPrefs

object WifiDiscovery {
    val permissions = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)

    fun permissionGranted(context: Context): Boolean = permissions.any {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    fun locationEnabled(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
        (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.isLocationEnabled == true

    fun available(context: Context): Boolean = SessionPrefs.wifiDiscoveryEnabled(context) &&
        permissionGranted(context) && locationEnabled(context)
}
