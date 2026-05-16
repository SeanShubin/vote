package com.seanshubin.vote.contract

import com.seanshubin.vote.domain.FeatureFlag

/**
 * Storage for the runtime feature-flag map. One row per flag, no audit
 * trail — current value only. Read by every lambda on every request that
 * gates on a flag (cheap single-row lookup) and by the frontend poller.
 * Written by the owner via the admin page.
 *
 * Unset flag → [FeatureFlag.defaultEnabled]. Once the owner sets a value
 * the stored value wins, even if the default later changes in code.
 */
interface SystemSettings {
    fun isEnabled(flag: FeatureFlag): Boolean
    fun setEnabled(flag: FeatureFlag, enabled: Boolean)

    /**
     * Resolved state of every flag in [FeatureFlag.entries]. Every flag
     * appears in the map — defaults applied where no row exists — so the
     * admin UI can render a row per flag and the frontend poller never
     * sees a partial map.
     */
    fun listAll(): Map<FeatureFlag, Boolean>
}
