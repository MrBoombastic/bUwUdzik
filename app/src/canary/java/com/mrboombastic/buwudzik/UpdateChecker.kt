package com.mrboombastic.buwudzik

import android.content.Context

class UpdateChecker(context: Context) : GitHubUpdateChecker(
    context = context,
    includePrerelease = true,
    apkNameHint = "canary-release"
)

fun UpdateManager.Companion.create(context: Context): UpdateManager {
    return UpdateChecker(context)
}
