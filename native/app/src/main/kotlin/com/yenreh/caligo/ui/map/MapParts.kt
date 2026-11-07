package com.yenreh.caligo.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yenreh.caligo.AppInfo
import com.yenreh.caligo.R
import com.yenreh.caligo.ui.lab.LabTooltip
import com.yenreh.caligo.ui.theme.LabPalette
import com.yenreh.caligo.ui.theme.LabText
import com.yenreh.caligo.ui.theme.LabTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Pieces every map in the app shares, as map_parts.dart: the credits and
// the frameless controls drawn on the tiles, and the stop marker.

/** The controls sit on the tiles, so they take the tiles' colours */
fun tilesPalette(dark: Boolean): LabPalette = if (dark) LabPalette.Dark else LabPalette.Light

/** Width added to every stroke of the drawn glyphs for their halo */
private val HALO_WIDTH = 3.dp

/** The tap size every map control shares */
val MAP_BUTTON_SIZE = 44.dp

/**
 * Map control drawn straight on the tiles, without a frame: a halo in the
 * tiles' paper keeps it readable. Named by [tooltip], which a long press
 * shows; takes no taps without [onClick].
 */
@Composable
fun MapButton(onClick: (() -> Unit)?, tooltip: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    LabTooltip(tooltip, modifier) {
        Box(
            Modifier
                .size(MAP_BUTTON_SIZE)
                .semantics { contentDescription = tooltip }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = false, radius = MAP_BUTTON_SIZE / 2),
                    enabled = onClick != null,
                    role = Role.Button,
                    onClick = { onClick?.invoke() },
                ),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/**
 * An icon with a thin halo of [halo] around its glyph, standing in for the
 * frame it lacks, as the Flutter icon's eight shadows 1.2 dp out
 */
@Composable
fun HaloIcon(painter: Painter, tint: Color, halo: Color, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size)) {
        for ((dx, dy) in HALO_OFFSETS) {
            Icon(painter, contentDescription = null, tint = halo, modifier = Modifier.size(size).offset(dx.dp, dy.dp))
        }
        Icon(painter, contentDescription = null, tint = tint, modifier = Modifier.size(size))
    }
}

private val HALO_OFFSETS = listOf(
    1.2f to 0f, -1.2f to 0f, 0f to 1.2f, 0f to -1.2f,
    0.9f to 0.9f, -0.9f to 0.9f, 0.9f to -0.9f, -0.9f to -0.9f,
)

/**
 * Info icon on the line of the map buttons, opening a paper panel above
 * it with the sources: OpenStreetMap's data (ODbL), CARTO's tiles when
 * they are, and Metro Cali's stops, routes and buses, all asking to be
 * credited on the map. The icon sits on the tiles, so it follows [tiles]
 * rather than the theme.
 */
@Composable
fun MapCredits(open: Boolean, onToggle: () -> Unit, tiles: LabPalette, carto: Boolean, modifier: Modifier = Modifier) {
    val p = LabTheme.palette
    val links = LocalUriHandler.current

    @Composable
    fun Link(label: String, url: String) {
        Text(
            text = "© $label",
            style = LabText.mono(p, size = 12f, color = p.accent),
            modifier = Modifier
                .clickable { links.openUri(url) }
                .padding(vertical = 4.dp),
        )
    }

    Column(modifier) {
        if (open) {
            Column(
                Modifier
                    .testTag("map_credits")
                    .background(p.paper)
                    .border(1.dp, p.rule)
                    .padding(horizontal = 13.dp, vertical = 7.dp),
            ) {
                Link("OpenStreetMap contributors", "https://openstreetmap.org/copyright")
                if (carto) Link("CARTO", "https://carto.com/attributions")
                // The stops, routes and buses drawn on top are theirs
                Link(AppInfo.DATA_SOURCE, AppInfo.DATA_SOURCE_URL)
            }
            Spacer(Modifier.height(6.dp))
        }
        MapButton(onClick = onToggle, tooltip = stringResource(R.string.map_credits)) {
            HaloIcon(
                painterResource(if (open) R.drawable.ic_close else R.drawable.ic_info_outline),
                tint = tiles.ink,
                halo = tiles.paper,
                size = 22.dp,
            )
        }
    }
}

/** Square stop marker: outlined on paper, filled with accent when chosen */
@Composable
fun MapStopMarker(selected: Boolean, palette: LabPalette, modifier: Modifier = Modifier) {
    val p = palette
    Box(
        modifier
            .size(if (selected) 30.dp else 24.dp)
            .background(if (selected) p.fill else p.paper)
            .border(1.5.dp, if (selected) p.accent else p.ink),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_directions_bus_rounded),
            contentDescription = null,
            modifier = Modifier.size(if (selected) 18.dp else 15.dp),
            tint = if (selected) p.onFill else p.ink,
        )
    }
}

/**
 * Small instrument dial: a ring with the four cardinal ticks and a needle
 * whose red half points at true north. The whole dial turns with the map.
 */
@Composable
fun CompassNeedle(rotationDegrees: Float, tiles: LabPalette, modifier: Modifier = Modifier) {
    val north = tiles.crit
    val south = tiles.muted
    val ring = tiles.ink
    val hub = tiles.paper
    Canvas(
        modifier
            .size(30.dp)
            .rotate(rotationDegrees),
    ) {
        val centre = center
        val radius = size.minDimension / 2 - 2.dp.toPx()
        val halo = HALO_WIDTH.toPx()

        // Halo behind the ring and needle, in the hub's paper colour
        drawCircle(hub, radius, centre, style = Stroke(2.dp.toPx() + halo))
        drawCircle(ring, radius, centre, style = Stroke(2.dp.toPx()))

        // Cardinal ticks; north's is longer and red
        for (i in 0 until 4) {
            val isNorth = i == 0
            val angle = i * PI / 2
            val direction = Offset(sin(angle).toFloat(), -cos(angle).toFloat())
            val length = (if (isNorth) 5 else 3).dp.toPx()
            drawLine(
                color = if (isNorth) north else ring,
                start = centre + direction * radius,
                end = centre + direction * (radius - length),
                strokeWidth = (if (isNorth) 2.4f else 1.6f).dp.toPx(),
            )
        }

        // Slim needle, split lengthwise into its north and south halves
        val halfWidth = 4.dp.toPx()
        val tip = radius - 4.dp.toPx()
        val outline = Path().apply {
            moveTo(centre.x, centre.y - tip)
            lineTo(centre.x + halfWidth, centre.y)
            lineTo(centre.x, centre.y + tip)
            lineTo(centre.x - halfWidth, centre.y)
            close()
        }
        drawPath(outline, hub, style = Stroke(halo, join = StrokeJoin.Round))
        drawPath(
            Path().apply {
                moveTo(centre.x, centre.y - tip)
                lineTo(centre.x + halfWidth, centre.y)
                lineTo(centre.x - halfWidth, centre.y)
                close()
            },
            north,
            style = Fill,
        )
        drawPath(
            Path().apply {
                moveTo(centre.x, centre.y + tip)
                lineTo(centre.x + halfWidth, centre.y)
                lineTo(centre.x - halfWidth, centre.y)
                close()
            },
            south,
            style = Fill,
        )
        drawCircle(hub, 1.8.dp.toPx(), centre)
    }
}

/**
 * Magnifier drawn to match the compass: an ink lens with a red reticle
 * inside, since the search looks around the point under the crosshair
 */
@Composable
fun SearchGlyph(tiles: LabPalette, modifier: Modifier = Modifier) {
    val lens = tiles.ink
    val cross = tiles.crit
    val halo = tiles.paper
    Canvas(modifier.size(30.dp)) {
        val radius = size.minDimension * 0.32f
        val two = 2.dp.toPx()
        // Lens up and left, leaving room for the handle
        val centre = Offset(radius + two, radius + two)
        val rim = centre + Offset(radius, radius) * (1 / sqrt(2f))
        val tick = radius * 0.4f
        val one = 1.dp.toPx()

        // Halo pass first, then the glyph over it
        for (haloPass in listOf(true, false)) {
            val extra = if (haloPass) HALO_WIDTH.toPx() else 0f
            fun tone(color: Color) = if (haloPass) halo else color

            drawCircle(tone(lens), radius, centre, style = Stroke(two + extra))

            // Handle, at 45 degrees from the rim to the corner
            drawLine(
                color = tone(lens),
                start = rim,
                end = Offset(size.width - two, size.height - two),
                strokeWidth = 3.5.dp.toPx() + extra,
                cap = StrokeCap.Square,
            )

            // Reticle ticks and a centre dot: the point being searched, not
            // a zoom, which a plus inside a lens would suggest
            val reticle = 1.8.dp.toPx() + extra
            for (direction in listOf(Offset(1f, 0f), Offset(-1f, 0f), Offset(0f, 1f), Offset(0f, -1f))) {
                drawLine(
                    color = tone(cross),
                    start = centre + direction * (radius - one),
                    end = centre + direction * (radius - one - tick),
                    strokeWidth = reticle,
                )
            }
            drawCircle(tone(cross), 1.8.dp.toPx() + extra / 2, centre)
        }
    }
}
