package com.yenreh.caligo.ui.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yenreh.caligo.R
import com.yenreh.caligo.domain.entities.FavoritePlace
import com.yenreh.caligo.domain.entities.TripPlace
import com.yenreh.caligo.ui.lab.LabTooltip
import com.yenreh.caligo.ui.theme.LabTheme

/**
 * The star of a field holding [place]: empty saves it, filled, as the
 * place [saved], edits it. 48 square, so the field keeps its height.
 */
@Composable
internal fun PlaceStar(place: TripPlace, saved: FavoritePlace?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LabTheme.palette
    val description = if (saved == null) {
        stringResource(R.string.save_place_action, place.name)
    } else {
        stringResource(R.string.edit_place_action, saved.name)
    }
    LabTooltip(description) {
        Box(
            modifier
                .size(48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = false, radius = 20.dp),
                    role = Role.Button,
                    onClick = onClick,
                )
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(if (saved == null) R.drawable.ic_star_outline else R.drawable.ic_star),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (saved == null) p.muted else p.accent,
            )
        }
    }
}

/**
 * The saved places as chips, in the order saved, scrolling sideways: a
 * tap plans there, a long press or the custom action edits the place
 */
@Composable
internal fun QuickPlans(
    places: List<FavoritePlace>,
    onPlan: (FavoritePlace) -> Unit,
    onEdit: (FavoritePlace) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier
            .fillMaxWidth()
            .testTag("quick_plans"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(places, key = { it.id }) { place ->
            PlaceChip(place, onClick = { onPlan(place) }, onEdit = { onEdit(place) })
        }
    }
}

/** A saved place's chip: 40 high to the eye, 48 to the finger */
@Composable
private fun PlaceChip(place: FavoritePlace, onClick: () -> Unit, onEdit: () -> Unit) {
    val p = LabTheme.palette
    val plan = stringResource(R.string.plan_to_place, place.name)
    val edit = stringResource(R.string.edit_place)
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .editAction(edit, onEdit, onClick, onClickLabel = plan, role = Role.Button, interaction = interaction)
            .padding(vertical = 4.dp)
            .testTag("quick_plan"),
    ) {
        Row(
            Modifier
                .background(p.paper)
                .border(1.dp, p.accent)
                .indication(interaction, ripple())
                .heightIn(min = 40.dp)
                .padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // As large as the name beside it, at any text size
            val star = with(LocalDensity.current) { STAR_SIZE.toDp() }
            Icon(painterResource(R.drawable.ic_star), contentDescription = null, modifier = Modifier.size(star), tint = p.accent)
            Spacer(Modifier.width(6.dp))
            Text(
                place.name,
                style = MaterialTheme.typography.bodyMedium.copy(color = p.ink, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
        }
    }
}

/** The chip's star, in text units so it grows with the name */
private val STAR_SIZE = 16.sp
