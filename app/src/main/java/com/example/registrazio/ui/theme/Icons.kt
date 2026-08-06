package com.example.registrazio.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp

/**
 * Le icone del prototipo, tenute come path SVG letterali.
 *
 * Sono gli stessi identici `d=` che stanno in `const ICON = {...}` dentro
 * `prova-app-v3-integrata.html`: parsarli a runtime costa una frazione di
 * millisecondo e garantisce che il disegno sia quello, invece di una
 * ridisegnata a mano che diverge di un pixel qua e là.
 *
 * Le uniche riscritture rispetto all'HTML sono `<rect>` e `<circle>`,
 * che non hanno un `d=`: sono stati convertiti in archi equivalenti
 * (annotati caso per caso qui sotto).
 */

data class IconPath(
    val d: String,
    val filled: Boolean = false,
    val strokeWidth: Float = 0f,
    val roundCap: Boolean = false,
    val roundJoin: Boolean = false
)

data class AppIconSpec(
    val paths: List<IconPath>,
    val viewBox: Float = 24f
)

private fun stroke(
    d: String,
    width: Float,
    cap: Boolean = false,
    join: Boolean = false
) = IconPath(d, filled = false, strokeWidth = width, roundCap = cap, roundJoin = join)

private fun fill(d: String) = IconPath(d, filled = true)

object AppIcons {

    val Play = AppIconSpec(listOf(fill("M6 4 L20 12 L6 20 Z")))

    // pause: due <rect x=5|14 y=4 w=5 h=16 rx=1>
    val Pause = AppIconSpec(
        listOf(
            fill("M6 4 H9 A1 1 0 0 1 10 5 V19 A1 1 0 0 1 9 20 H6 A1 1 0 0 1 5 19 V5 A1 1 0 0 1 6 4 Z"),
            fill("M15 4 H18 A1 1 0 0 1 19 5 V19 A1 1 0 0 1 18 20 H15 A1 1 0 0 1 14 19 V5 A1 1 0 0 1 15 4 Z")
        )
    )

    val Send = AppIconSpec(
        listOf(stroke("M5 12h13M13 6l6 6-6 6", 2.1f, cap = true, join = true))
    )

    // viewBox 14x14 nell'originale
    val Minus = AppIconSpec(
        listOf(stroke("M2 7 L12 7", 1.8f, cap = true)),
        viewBox = 14f
    )

    // nell'HTML ha transform="translate(-1,-1)": applicato direttamente alle coordinate
    val Comment = AppIconSpec(
        listOf(stroke("M11 3a8 8 0 1 0 4.9 14.3L20 19l-1.3-4.1A8 8 0 0 0 11 3z", 1.8f, cap = true, join = true))
    )

    val Trash = AppIconSpec(
        listOf(
            stroke(
                "M4 7h16M9 7V5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2m-9 0 1 13a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1l1-13",
                1.8f, cap = true, join = true
            )
        )
    )

    val Edit = AppIconSpec(
        listOf(stroke("M14.5 4.5l5 5L8 21H3v-5L14.5 4.5z", 1.7f, cap = true, join = true))
    )

    /** Alias: nel prototipo `pencil` e `edit` hanno lo stesso path. */
    val Pencil = Edit

    // info: <circle cx=12 cy=12 r=9.5> convertito in due semiarchi
    val Info = AppIconSpec(
        listOf(
            stroke("M2.5 12a9.5 9.5 0 1 0 19 0a9.5 9.5 0 1 0-19 0", 1.7f, cap = true, join = true),
            stroke("M12 16 L12 12", 1.7f, cap = true, join = true),
            stroke("M12 8 L12.01 8", 1.7f, cap = true, join = true)
        )
    )

    val Check = AppIconSpec(
        listOf(stroke("M4 12.5l5 5L20 6", 2.2f, cap = true, join = true))
    )

    // more: tre <circle r=1.8> pieni
    val More = AppIconSpec(
        listOf(
            fill("M10.2 5a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0-3.6 0"),
            fill("M10.2 12a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0-3.6 0"),
            fill("M10.2 19a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0-3.6 0")
        )
    )

    // lock/unlock: <rect x=5 y=11 w=14 h=9 rx=2> + archetto
    private const val LOCK_BODY =
        "M7 11 H17 A2 2 0 0 1 19 13 V18 A2 2 0 0 1 17 20 H7 A2 2 0 0 1 5 18 V13 A2 2 0 0 1 7 11 Z"

    val Lock = AppIconSpec(
        listOf(
            stroke(LOCK_BODY, 1.8f),
            stroke("M8 11V8a4 4 0 0 1 8 0v3", 1.8f, cap = true)
        )
    )

    val Unlock = AppIconSpec(
        listOf(
            stroke(LOCK_BODY, 1.8f),
            stroke("M8 11V8a4 4 0 0 1 7.5-2", 1.8f, cap = true)
        )
    )

    private const val STAR_POINTS =
        "M12 2.5 L15.1 9.3 L22.5 10.1 L17 15.1 L18.5 22.5 L12 18.8 L5.5 22.5 L7 15.1 L1.5 10.1 L8.9 9.3 Z"

    val Star = AppIconSpec(listOf(stroke(STAR_POINTS, 1.6f, join = true)))
    val StarFilled = AppIconSpec(listOf(fill(STAR_POINTS)))

    val Sort = AppIconSpec(
        listOf(
            stroke("M6 4v14M6 18l-3-3M6 18l3-3M18 20V6M18 6l-3 3M18 6l3 3", 1.8f, cap = true, join = true)
        )
    )

    val ChevronLeft = AppIconSpec(
        listOf(stroke("M15 5l-7 7 7 7", 2.1f, cap = true, join = true))
    )

    val ChevronRight = AppIconSpec(
        listOf(stroke("M9 5l7 7-7 7", 2.1f, cap = true, join = true))
    )

    val ChevronDown = AppIconSpec(
        listOf(stroke("M5 8l7 7 7-7", 2.2f, cap = true, join = true))
    )

    val ChevronUp = AppIconSpec(
        listOf(stroke("M5 15l7-7 7 7", 2.2f, cap = true, join = true))
    )

    /** Come ChevronRight ma con tratto 2.0 — usata su ghost card e folder card. */
    val ChevronRightSmall = AppIconSpec(
        listOf(stroke("M9 5l7 7-7 7", 2f, cap = true, join = true))
    )

    val Back = AppIconSpec(
        listOf(stroke("M15 5l-7 7 7 7", 2.2f, cap = true, join = true))
    )

    val Refresh = AppIconSpec(
        listOf(
            stroke("M4 10a8 8 0 0 1 14-4.9M20 14a8 8 0 0 1-14 4.9", 2f, cap = true),
            stroke("M18 3v4h-4M6 21v-4h4", 2f, cap = true, join = true)
        )
    )

    val Folder = AppIconSpec(
        listOf(
            stroke("M3 7a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V7z", 1.7f, join = true)
        )
    )

    val Link = AppIconSpec(
        listOf(
            stroke(
                "M9.5 14.5l5-5M8 12l-2.5 2.5a3 3 0 0 0 4.24 4.24L12 16.5M16 12l2.5-2.5a3 3 0 0 0-4.24-4.24L12 7.5",
                1.7f, cap = true, join = true
            )
        )
    )

    // mic: <rect x=9 y=3 w=6 h=11 rx=3> — con rx = metà larghezza è una pillola
    val Mic = AppIconSpec(
        listOf(
            stroke("M9 6 A3 3 0 0 1 15 6 L15 11 A3 3 0 0 1 9 11 Z", 1.7f),
            stroke("M6 11a6 6 0 0 0 12 0M12 17v3.5", 1.7f, cap = true)
        )
    )

    val Cloud = AppIconSpec(
        listOf(
            stroke("M7 18a4 4 0 0 1-.6-7.96A5.5 5.5 0 0 1 17.2 9.02 4 4 0 0 1 17 18H7z", 1.7f, join = true)
        )
    )

    val CloudDone = AppIconSpec(
        listOf(
            stroke("M7 17a4 4 0 0 1-.6-7.96A5.5 5.5 0 0 1 17.2 8.02 4 4 0 0 1 17 17H7z", 1.7f, join = true),
            stroke("M9.5 13l1.8 1.8L14.5 11", 1.7f, cap = true, join = true)
        )
    )

    // sun: <circle cx=12 cy=12 r=4.2> + raggi
    val Sun = AppIconSpec(
        listOf(
            stroke("M7.8 12a4.2 4.2 0 1 0 8.4 0a4.2 4.2 0 1 0-8.4 0", 1.8f),
            stroke(
                "M12 2.5v2.3M12 19.2v2.3M4.5 12H2.2M21.8 12h-2.3M6 6l1.6 1.6M16.4 16.4L18 18M18 6l-1.6 1.6M7.6 16.4L6 18",
                1.8f, cap = true
            )
        )
    )

    val Moon = AppIconSpec(
        listOf(stroke("M20 14.5A8.5 8.5 0 1 1 9.5 4a7 7 0 0 0 10.5 10.5z", 1.8f, join = true))
    )

    val Plus = AppIconSpec(
        listOf(stroke("M12 5v14M5 12h14", 2f, cap = true))
    )

    val X = AppIconSpec(
        listOf(stroke("M6 6l12 12M18 6L6 18", 2.2f, cap = true))
    )
}

/**
 * I `Path` già costruiti, per tutta la vita dell'app.
 *
 * `PathParser` legge una stringa SVG e ne costruisce un `Path`: costa poco una
 * volta, moltissimo settanta volte al secondo. Ed era quello che succedeva —
 * il `remember` di un composable vive quanto il suo posto nella composizione,
 * e in una `LazyColumn` quel posto viene buttato appena la card esce dallo
 * schermo. Scorrendo, ogni card che rientrava riparsava tutte le sue icone:
 * play, stella, kebab, nuvola, e ognuna daccapo.
 *
 * Una mappa a parte perché le icone **non cambiano mai**: sono costanti
 * dichiarate in [AppIcons]. Il primo che ne chiede una la costruisce, tutti
 * gli altri se la trovano pronta.
 *
 * Non serve sincronizzarla: la composizione gira sul thread principale, e
 * questa mappa non viene toccata da nessun altro.
 */
private val pathInCache = mutableMapOf<AppIconSpec, List<Pair<IconPath, Path>>>()

private fun pathDi(spec: AppIconSpec): List<Pair<IconPath, Path>> =
    pathInCache.getOrPut(spec) {
        spec.paths.map { it to PathParser().parsePathString(it.d).toPath() }
    }

/**
 * Disegna una [AppIconSpec] alla dimensione richiesta.
 *
 * Il canvas viene scalato da viewBox a `size`, quindi anche gli spessori di
 * tratto scalano insieme al resto — esattamente come fa un `<svg>` con
 * `width`/`height` diversi dal viewBox.
 */
@Composable
fun AppIcon(
    spec: AppIconSpec,
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier
) {
    val parsed = pathDi(spec)
    Canvas(modifier.size(size)) {
        val factor = this.size.minDimension / spec.viewBox
        scale(factor, pivot = Offset.Zero) {
            parsed.forEach { (style, path) ->
                if (style.filled) {
                    drawPath(path, color = tint)
                } else {
                    drawPath(
                        path,
                        color = tint,
                        style = Stroke(
                            width = style.strokeWidth,
                            cap = if (style.roundCap) StrokeCap.Round else StrokeCap.Butt,
                            join = if (style.roundJoin) StrokeJoin.Round else StrokeJoin.Miter
                        )
                    )
                }
            }
        }
    }
}
