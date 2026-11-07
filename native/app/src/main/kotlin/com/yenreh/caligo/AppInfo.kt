package com.yenreh.caligo

/**
 * What the app is called and where it lives, as `lib/core/app_info.dart`;
 * the launcher label is app_name
 */
object AppInfo {
    const val NAME = "CaliGo"

    /**
     * Android application ID of CaliGo, also how the map introduces itself
     * to the tile servers. CaliGo Next installs under its own ID until it
     * takes this one; the tile servers know CaliGo's.
     */
    const val PACKAGE_ID = "com.yenreh.caligo"

    /**
     * SHA-1 of the certificate every release is signed with. Public, like
     * any certificate; the map key is limited to this app and this
     * certificate, which the tile requests announce.
     */
    const val SIGNING_SHA1 = "9B:AD:EB:EF:73:AF:2E:F5:95:AF:94:F9:00:B0:34:45:B3:E5:44:C2"

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
