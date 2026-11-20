package org.openmiles.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openmiles.app.data.Source
import org.openmiles.app.domain.Calc
import org.openmiles.app.domain.RouteMap
import org.openmiles.app.domain.TripEdit
import org.openmiles.app.domain.Validation

private val StartGreen = Color(0xFF2E9E5B)

/**
 * The map of one drive, shown when you tap it. It draws the GPS route the phone saved. There are
 * no street pictures, because those would have to come from the internet. "Open in Maps" hands
 * the route to the phone's maps app, which does go online, but only when you tap it.
 */
@Composable
fun DriveMapSection(tripId: Long, source: String, loadRoute: suspend () -> String) {
    var points by remember(tripId) { mutableStateOf<List<TripEdit.RoutePoint>?>(null) }
    LaunchedEffect(tripId) {
        points = try {
            val route = loadRoute()
            withContext(Dispatchers.Default) {
                RouteMap.thin(RouteMap.points(route), RouteMap.MAX_DRAW_POINTS)
            }
        } catch (e: CancellationException) {
            throw e // the dialog closed; stop quietly
        } catch (e: Exception) {
            emptyList() // a damaged route shows "no map" instead of closing the app
        }
    }
    val pts = points ?: return // still loading; it takes a moment at most

    Text("Map", fontWeight = FontWeight.Bold)
    if (pts.isEmpty()) {
        Text(
            when (source) {
                Source.MANUAL -> "This drive was added by hand, so it has no GPS route to show."
                Source.MILEIQ -> "MileIQ reports don't include the route, so there is no map for this drive."
                else -> "No GPS route was saved for this drive, so there is no map."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        return
    }

    val straightOnly = source == Source.TIMELINE && pts.size <= 2
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val endColor = MaterialTheme.colorScheme.secondary
    val ringColor = MaterialTheme.colorScheme.surface
    val across = remember(pts) { RouteMap.widthMeters(pts) }

    Box(
        Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics {
                contentDescription = "Map of this drive's route, about ${Calc.fmtMiles(across)} across. " +
                    "Green dot is the start, yellow dot is the end. North is up."
            }
    ) {
        Canvas(Modifier.fillMaxWidth().height(200.dp)) {
            // A light grid so the route reads as a map, not a doodle.
            val step = 32.dp.toPx()
            var x = step
            while (x < size.width) {
                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1f)
                x += step
            }
            var y = step
            while (y < size.height) {
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
                y += step
            }

            val pad = 20.dp.toPx()
            val xy = RouteMap.project(pts, size.width, size.height, pad)
            // Nothing to draw before the box has a size.
            if (xy.isEmpty()) return@Canvas
            if (xy.size >= 2) {
                val path = Path().apply {
                    moveTo(xy[0].x, xy[0].y)
                    for (i in 1 until xy.size) lineTo(xy[i].x, xy[i].y)
                }
                drawPath(
                    path,
                    lineColor,
                    style = Stroke(
                        width = 4.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        pathEffect = if (straightOnly) PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) else null
                    )
                )
            }
            val r = 7.dp.toPx()
            val ring = 2.dp.toPx()
            val first = Offset(xy.first().x, xy.first().y)
            val last = Offset(xy.last().x, xy.last().y)
            drawCircle(ringColor, r + ring, last)
            drawCircle(endColor, r, last)
            drawCircle(ringColor, r + ring, first)
            drawCircle(StartGreen, r, first)
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Dot(StartGreen)
        Text("Start", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(6.dp))
        Dot(endColor)
        Text("End", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(6.dp))
        Text(
            "North is up. About ${Calc.fmtMiles(across)} across.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (straightOnly) {
        Text(
            "Google Timeline only saved where this drive started and ended, so the dashed line is a straight line, not the road you took.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    val context = LocalContext.current
    val link = remember(pts) { RouteMap.mapsLink(pts) }
    if (link != null) {
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = { openInMaps(context, link) }) { Text("Open in Maps") }
        Text(
            "Shows streets in Google Maps. Maps goes online to do that. OpenMiles never does. " +
                "Maps picks its own roads between a few points of your drive, so its miles can differ from yours.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

private fun openInMaps(context: Context, url: String) {
    if (!Validation.isSafeWebLink(url)) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        Toast.makeText(context, "No maps app or browser found to open this.", Toast.LENGTH_LONG).show()
    }
}
