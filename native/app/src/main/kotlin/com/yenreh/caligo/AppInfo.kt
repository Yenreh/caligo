package com.yenreh.caligo

/**
 * What the app is called and where it lives, as `lib/core/app_info.dart`;
 * the launcher label is app_name
 */
object AppInfo {
    const val NAME = "CaliGo"

    /** GitHub owner/name: its page, and the releases updates come from */
    const val GITHUB_REPO = "Yenreh/CaliGo"
    const val REPOSITORY = "https://github.com/$GITHUB_REPO"

    /**
     * Where the transit data comes from. Metro Cali allows its reuse for
     * non-commercial, informative ends, citing it with a link to its site.
     */
    const val DATA_SOURCE = "Metro Cali S.A."
    const val DATA_SOURCE_URL = "https://www.metrocali.gov.co"

    /** Who the app's GPL names, on the licenses page */
    const val COPYRIGHT = "Copyright (C) 2026 Yenreh"
}
