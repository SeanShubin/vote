package com.seanshubin.vote.frontend

import androidx.compose.runtime.Composable
import com.seanshubin.vote.domain.RankingSide
import org.jetbrains.compose.web.dom.*

/**
 * Two-button toggle for picking the active ballot side (PUBLIC or SECRET).
 * Reused on every page that reads side-scoped data — the voting view, the
 * tally view, and the three explanatory pages — so the same control sits
 * everywhere the choice matters.
 *
 * The toggle is a pure view over [currentSide] + [onSetSide]. Persistence
 * and the body.secret-mode theme swap are owned by [VoteApp], not this
 * widget — that way the active side stays sticky across navigations and
 * the dark-theme effect doesn't get duplicated per page.
 */
@Composable
fun SideToggle(currentSide: RankingSide, onSetSide: (RankingSide) -> Unit) {
    Div({ classes("ballot-side-toggle") }) {
        Button({
            classes("ballot-side-button")
            if (currentSide == RankingSide.PUBLIC) classes("ballot-side-button-active")
            onClick { onSetSide(RankingSide.PUBLIC) }
        }) { Text("Public side") }
        Button({
            classes("ballot-side-button")
            if (currentSide == RankingSide.SECRET) classes("ballot-side-button-active")
            onClick { onSetSide(RankingSide.SECRET) }
        }) { Text("Secret side") }
    }
}
