package com.goju.ribs.myrainassist.update

import android.content.Context

/** This install's version, read from the package (BuildConfig generation is off). */
object BuildConfigVersion {
    fun code(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    fun name(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
}
