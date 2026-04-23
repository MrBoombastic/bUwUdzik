package com.mrboombastic.buwudzik

import android.content.Context

class UpdateChecker(context: Context) : GitHubUpdateChecker(
    context = context,
    includePrerelease = false,
    apkNameHint = "clowock-release"
)

fun UpdateManager.Companion.create(context: Context): UpdateManager {
    return UpdateChecker(context)
}
