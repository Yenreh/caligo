package com.yenreh.caligo.ui.lab

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yenreh.caligo.ui.theme.LabTheme

private const val SWITCH_MS = 150

/**
 * Square switch: rule-coloured track, filled with fill when on. Drawn
 * only: the row it sits in takes the taps and says it is a switch.
 */
@Composable
fun LabSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val p = LabTheme.palette
    val track by animateColorAsState(if (checked) p.fill else p.rule, tween(SWITCH_MS), label = "track")
    val knob by animateColorAsState(if (checked) p.onFill else p.ink, tween(SWITCH_MS), label = "knob")
    // Track 40 wide with 3 of padding leaves 18 for the knob of 16 to travel
    val shift by animateDpAsState(if (checked) 18.dp else 0.dp, tween(SWITCH_MS), label = "shift")
    Box(
        modifier
            .size(width = 40.dp, height = 22.dp)
            .background(track)
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .offset(x = shift)
                .size(16.dp)
                .background(knob),
        )
    }
}
