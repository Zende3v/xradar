package com.xradar.app.feature.drive

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xradar.app.R
import com.xradar.app.core.model.ReportType
import com.xradar.app.core.model.Role
import com.xradar.app.data.account.AccountRepository
import com.xradar.app.designsystem.component.XRadarGlowIcon
import com.xradar.app.designsystem.component.XRadarIcon
import com.xradar.app.designsystem.component.XRadarText
import com.xradar.app.designsystem.foundation.XRadarIcons
import com.xradar.app.designsystem.theme.XRadarTheme
import com.xradar.app.feature.drive.component.icon

/** Which side of the road the report is on, from the driver's point of view. */
const val DIRECTION_SAME = "same"
const val DIRECTION_OPPOSITE = "opposite"

/**
 * What the sheet collected: the category, which side of the road it is on, and the
 * plate for a radar car (optional — it only sharpens the probable zone).
 */
data class ReportDraft(
    val type: ReportType,
    val direction: String = DIRECTION_SAME,
    val plate: String? = null,
)

/**
 * Quick-pick report sheet, role-gated: only categories the account may create are
 * shown, six per page. Picking one asks which way it is — and files it in the
 * driver's own direction on its own after a few seconds, hands-free.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(onReport: OnReport, onDismiss: () -> Unit) {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing
    val sheetState = rememberModalBottomSheetState()
    val account by AccountRepository.account.collectAsStateWithLifecycle()
    val role = account?.role ?: Role.Guest
    val available = ReportType.PICKER.filter { it.allowedFor(role) }
    var selected by remember { mutableStateOf<ReportType?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surfaceElevated,
        scrimColor = colors.scrim,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(modifier = Modifier.fillMaxWidth().padding(top = spacing.md), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(width = 40.dp, height = 4.dp)
                        .clip(XRadarTheme.shapes.pill)
                        .background(colors.borderStrong),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            val current = selected
            if (current == null) {
                XRadarText("Signaler", style = XRadarTheme.typography.title, color = colors.textPrimary)
                val pages = available.chunked(PER_PAGE)
                val pagerState = rememberPagerState(pageCount = { pages.size })
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                        // Always the full grid, empty slots included, so a half-filled
                        // page keeps the height of a full one.
                        repeat(PER_PAGE / PER_ROW) { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                                repeat(PER_ROW) { column ->
                                    val type = pages[page].getOrNull(row * PER_ROW + column)
                                    if (type == null) {
                                        Spacer(Modifier.weight(1f).height(TILE_SLOT_HEIGHT))
                                    } else {
                                        ReportTile(
                                            type = type,
                                            onClick = { selected = type },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (pages.size > 1) {
                    PageDots(count = pages.size, current = pagerState.currentPage)
                }
            } else {
                DirectionStep(type = current, onBack = { selected = null }, onReport = onReport)
            }
        }
    }
}

/**
 * "Mon sens" or "Sens opposé". Nobody should have to answer while driving, so the
 * driver's own direction is sent by itself once the countdown runs out.
 */
@Composable
private fun DirectionStep(type: ReportType, onBack: () -> Unit, onReport: OnReport) {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing
    var plate by remember { mutableStateOf("") }
    val countdown = remember { Animatable(1f) }
    var auto by remember { mutableStateOf(true) }
    var sent by remember { mutableStateOf(false) }

    fun send(direction: String) {
        if (sent) return
        sent = true
        onReport(ReportDraft(type, direction, plate.trim().ifBlank { null }))
    }

    LaunchedEffect(auto) {
        if (!auto) return@LaunchedEffect
        countdown.animateTo(0f, tween(durationMillis = AUTO_MILLIS, easing = LinearEasing))
        send(DIRECTION_SAME)
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Box(
            modifier = Modifier
                .clip(XRadarTheme.shapes.pill)
                .clickable { auto = false; onBack() }
                .padding(4.dp),
        ) {
            XRadarIcon(XRadarIcons.ChevronLeft, contentDescription = "Retour", tint = colors.textSecondary, size = 22.dp)
        }
        XRadarText(type.label, style = XRadarTheme.typography.title, color = colors.textPrimary)
    }

    if (type.needsPlate) {
        SheetField(
            value = plate,
            onChange = {
                plate = it.uppercase()
                auto = false // typing means the driver is not in a hurry: stop the timer
            },
            placeholder = "Plaque (facultatif)",
            caps = KeyboardCapitalization.Characters,
        )
        XRadarText(
            "Facultative — elle reste privée et sert seulement à resserrer la zone probable.",
            style = XRadarTheme.typography.footnote,
            color = colors.textTertiary,
        )
    }

    XRadarText("Dans quel sens ?", style = XRadarTheme.typography.subhead, color = colors.textSecondary)
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
        DirectionPill("Mon sens", highlighted = true, onClick = { send(DIRECTION_SAME) }, modifier = Modifier.weight(1f))
        DirectionPill("Sens opposé", highlighted = false, onClick = { send(DIRECTION_OPPOSITE) }, modifier = Modifier.weight(1f))
    }
    if (auto) {
        // Runs down to nothing, then files the report in the driver's own direction.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(XRadarTheme.shapes.pill)
                .background(colors.surfaceHigh),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(countdown.value)
                    .height(5.dp)
                    .clip(XRadarTheme.shapes.pill)
                    .background(colors.accent),
            )
        }
    }
}

@Composable
private fun DirectionPill(label: String, highlighted: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = XRadarTheme.colors
    Box(
        modifier = modifier
            .clip(XRadarTheme.shapes.lg)
            .background(if (highlighted) colors.accent else colors.surface)
            .border(1.dp, if (highlighted) colors.accent else colors.border, XRadarTheme.shapes.lg)
            .clickable(onClick = onClick)
            .padding(vertical = XRadarTheme.spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        XRadarText(
            label,
            style = XRadarTheme.typography.bodyStrong,
            color = if (highlighted) colors.onAccent else colors.textPrimary,
        )
    }
}

/** Page dots — the only hint that there is more to the side. */
@Composable
private fun PageDots(count: Int, current: Int) {
    val colors = XRadarTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(count) { i ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(if (i == current) 7.dp else 6.dp)
                    .clip(CircleShape)
                    .background(if (i == current) colors.accent else colors.borderStrong),
            )
        }
    }
}

@Composable
private fun SheetField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    caps: KeyboardCapitalization,
) {
    val colors = XRadarTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(XRadarTheme.shapes.md)
            .background(colors.surface)
            .border(1.dp, colors.border, XRadarTheme.shapes.md)
            .padding(horizontal = XRadarTheme.spacing.md, vertical = XRadarTheme.spacing.md),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = XRadarTheme.typography.body.merge(androidx.compose.ui.text.TextStyle(color = colors.textPrimary)),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(capitalization = caps),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    XRadarText(placeholder, style = XRadarTheme.typography.body, color = colors.textTertiary)
                }
                inner()
            },
        )
    }
}

/**
 * The icon of each category in the picker. Supplied artwork where there is some; control
 * zone, roadworks, camera (and the legacy hazard) keep their drawn icon. All of them are
 * painted in one colour by the tile. Reduced visibility has no icon yet.
 */
@Composable
private fun ReportType.pickerPainter(): Painter? = when (this) {
    ReportType.RadarMobile -> painterResource(R.drawable.ic_report_radar_mobile)
    ReportType.VoitureRadar -> painterResource(R.drawable.ic_report_radar_car)
    ReportType.StoppedVehicle -> painterResource(R.drawable.ic_report_stopped_vehicle)
    ReportType.Accident -> painterResource(R.drawable.ic_report_accident)
    ReportType.ObjectOnRoad -> painterResource(R.drawable.ic_report_object_on_road)
    ReportType.TrafficJam -> painterResource(R.drawable.ic_report_traffic_jam)
    ReportType.DamagedRoad -> painterResource(R.drawable.ic_report_damaged_road)
    ReportType.SlipperyRoad -> painterResource(R.drawable.ic_report_slippery_road)
    ReportType.RoadCrew -> painterResource(R.drawable.ic_report_road_crew)
    ReportType.WrongWay -> painterResource(R.drawable.ic_report_wrong_way)
    ReportType.ControlZone,
    ReportType.Roadworks,
    ReportType.Camera,
    ReportType.Hazard,
    -> rememberVectorPainter(alertType.icon())
    ReportType.LowVisibility -> null
}

@Composable
private fun ReportTile(type: ReportType, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val colors = XRadarTheme.colors
    val painter = type.pickerPainter()
    Column(
        modifier = modifier
            .height(TILE_SLOT_HEIGHT)
            .clip(XRadarTheme.shapes.lg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = XRadarTheme.spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.sm, Alignment.CenterVertically),
    ) {
        // Every category on the same disc; the icon in orange with a soft glow.
        Box(
            modifier = Modifier
                .size(60.dp)
                .background(colors.reportTile, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (painter != null) {
                XRadarGlowIcon(painter = painter, contentDescription = null, tint = colors.reportIcon, size = 30.dp)
            }
        }
        XRadarText(
            type.label,
            style = XRadarTheme.typography.caption,
            color = XRadarTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/** Fixed slot height: two-line labels fit, and every page ends up the same height. */
private val TILE_SLOT_HEIGHT = 120.dp

/** Six categories per page, two rows of three — swipe sideways for the rest. */
private const val PER_PAGE = 6
private const val PER_ROW = 3
private const val AUTO_MILLIS = 5_000
