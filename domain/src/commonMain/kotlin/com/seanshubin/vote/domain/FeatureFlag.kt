package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable

/**
 * Named runtime switches the owner can flip without redeploying. Each flag
 * names a feature whose behavior the system can toggle on/off — the data
 * model is preserved either way so flipping back enables existing data.
 *
 * Add a new flag here, give it a default and a description, and the admin
 * page picks it up automatically. The default applies until the owner has
 * ever explicitly set it; once set, the stored value wins.
 *
 * Storage and audit: state lives in the SystemSettings repository (one
 * row per flag). No who/when audit trail is kept — feature flags are
 * operator state, not domain events.
 */
@Serializable
enum class FeatureFlag(val defaultEnabled: Boolean, val description: String) {
    /**
     * Dual-sided ballots. When enabled, voters see a Public/Secret side
     * toggle on the voting view, and the tally / explanatory pages can
     * be flipped to the secret side. When disabled the toggle disappears
     * and only public-side voting is available. Existing secret-side
     * rankings remain in the event log; flipping back on resurfaces them.
     */
    SECRET_BALLOT(
        defaultEnabled = true,
        description = "Dual-sided ballots (public + secret sides). Off = only public-side voting visible.",
    ),
}
