package com.example.registrazio.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.registrazio.ui.theme.AppIcon
import com.example.registrazio.ui.theme.AppIconSpec
import com.example.registrazio.ui.theme.AppTheme
import com.example.registrazio.ui.theme.Radius

/**
 * `button:active { transform:scale(0.97) }` del prototipo.
 * Tutti i tasti si "schiacciano" leggermente alla pressione.
 */
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.97f
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, label = "pressScale")
    return this.scale(scale)
}

/**
 * `.icon-btn`: cerchio 40dp trasparente, sfondo surface-alt alla pressione.
 * Senza ripple — il prototipo non ce l'ha e su fondo chiaro si nota.
 */
@Composable
fun AppIconButton(
    icon: AppIconSpec,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 18.dp,
    tint: Color = AppTheme.colors.textSecondary,
    background: Color = Color.Transparent
) {
    val colors = AppTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (pressed && background == Color.Transparent) colors.surfaceAlt else background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        AppIcon(icon, iconSize, tint)
    }
}

/**
 * `.avatar` / `.account-dot` / `.recover-dot`: pallino colorato con l'iniziale.
 * La dimensione del testo segue quella del cerchio come nel CSS originale.
 */
@Composable
fun Avatar(
    lettera: String,
    colore: Color,
    size: Dp,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = (size.value * 0.45f).sp
) {
    Box(
        modifier.size(size).clip(CircleShape).background(colore),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = lettera,
            color = Color.White,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            lineHeight = fontSize
        )
    }
}

/**
 * `#toast`: pillola scura ancorata in basso, colori invertiti rispetto allo sfondo.
 */
@Composable
fun AppToast(testo: String, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Box(
        modifier
            .clip(Radius.pillShape)
            .background(colors.text)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(testo, color = colors.bg, fontSize = 12.5.sp, lineHeight = 17.sp)
    }
}

/** Riga di azioni di un bottom sheet: due tasti affiancati a larghezza uguale. */
@Composable
fun SheetActions(
    testoAnnulla: String,
    testoConferma: String,
    coloreConferma: Color,
    onAnnulla: () -> Unit,
    onConferma: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton(testoAnnulla, onAnnulla, Modifier.weight(1f))
        FilledButton(testoConferma, coloreConferma, onConferma, Modifier.weight(1f))
    }
}

/** Tasto neutro: superficie con bordo, come il `button` di default del prototipo. */
@Composable
fun SecondaryButton(
    testo: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
    paddingVertical: Dp = 11.dp
) {
    val colors = AppTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interaction)
            .clip(Radius.cardSm)
            .background(colors.surface)
            .appBorder(colors.border)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = paddingVertical, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(testo, color = colors.text, fontSize = fontSize)
    }
}

/** Tasto pieno: `.btn-primary` (accent) e `.btn-danger` (danger). */
@Composable
fun FilledButton(
    testo: String,
    colore: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
    paddingVertical: Dp = 11.dp
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interaction)
            .clip(Radius.cardSm)
            .background(colore)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = paddingVertical, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(testo, color = Color.White, fontSize = fontSize)
    }
}

/** Pillola accent con la freccia, usata nel Gate ("Crea account →", "Entra →"). */
@Composable
fun GateActionButton(
    testo: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interaction)
            .clip(Radius.pillShape)
            .background(colors.accent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            testo,
            color = Color.White,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.135).sp
        )
    }
}

/** Bordo 1dp: nel prototipo praticamente ogni superficie ne ha uno. */
fun Modifier.appBorder(colore: Color, radius: Dp = Radius.sm): Modifier =
    this.border(1.dp, colore, RoundedCornerShape(radius))

/**
 * `input` / `textarea` del prototipo: bordo border-strong, raggio 8px,
 * padding 6px 9px, testo 14px. Il bordo passa ad accent quando ha il fuoco.
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
    fontWeight: FontWeight = FontWeight.Normal,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    minHeight: Dp = 0.dp,
    textAlign: TextAlign = TextAlign.Start,
    radius: Dp = Radius.input,
    background: Color = AppTheme.colors.surface,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions =
        androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions =
        androidx.compose.foundation.text.KeyboardActions.Default,
    /**
     * Contenuto agganciato al bordo destro, dentro il campo.
     *
     * Serve per la crocetta che svuota: è il posto in cui la cercano tutti,
     * perché è dove sta in ogni barra di ricerca. Fuori dal campo occuperebbe
     * uno spazio suo e si confonderebbe con i tasti dell'azione.
     */
    trailing: (@Composable () -> Unit)? = null
) {
    val colors = AppTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .background(background)
            .appBorder(if (focused) colors.accent else colors.borderStrong, radius)
            .heightIn(min = minHeight)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        readOnly = readOnly,
        singleLine = singleLine,
        interactionSource = interaction,
        textStyle = androidx.compose.ui.text.TextStyle(
            color = if (readOnly) colors.textMuted else colors.text,
            fontSize = fontSize,
            fontWeight = fontWeight,
            textAlign = textAlign
        ),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        decorationBox = { inner ->
            // Un campo che accoglie più righe parte in alto a sinistra, come una
            // `textarea`: centrare il testo in un riquadro alto lo fa galleggiare
            // e poi saltare in su quando arriva la seconda riga. Su una riga sola
            // invece il centraggio è l'unica cosa che ha senso.
            val allineaInAlto = !singleLine
            Row(
                verticalAlignment = if (allineaInAlto) Alignment.Top else Alignment.CenterVertically
            ) {
                Box(
                    Modifier.weight(1f),
                    contentAlignment = when {
                        textAlign == TextAlign.Center -> Alignment.Center
                        allineaInAlto -> Alignment.TopStart
                        else -> Alignment.CenterStart
                    }
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, color = colors.textMuted, fontSize = fontSize)
                    }
                    inner()
                }
                trailing?.let {
                    Spacer(Modifier.width(4.dp))
                    it()
                }
            }
        }
    )
}

/** Testo secondario piccolo ricorrente (`.sub`, `.ghost-sublabel`, `.acc-folder-empty`). */
@Composable
fun MutedText(
    testo: String,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 12.sp
) {
    Text(testo, modifier, color = AppTheme.colors.textMuted, fontSize = fontSize)
}

/** Larghezza massima della colonna app, centrata (`.app { max-width:480px; margin:0 auto }`). */
fun Modifier.appColumn(maxWidth: Dp): Modifier = this.widthIn(max = maxWidth)
