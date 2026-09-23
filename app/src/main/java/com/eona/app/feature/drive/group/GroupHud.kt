package com.eona.app.feature.drive.group

import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eona.app.data.trips.GroupMember
import com.eona.app.data.trips.GroupMemberState
import com.eona.app.data.trips.GroupRankEntry
import com.eona.app.data.trips.TripGroup
import com.eona.app.designsystem.component.EonaButton
import com.eona.app.designsystem.component.EonaButtonVariant
import com.eona.app.designsystem.component.EonaIcon
import com.eona.app.designsystem.component.EonaIconButton
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Profile pictures, loaded once and kept for the session: the strip, the lists and the cards share them. */
object AvatarImages {
    private val images = HashMap<String, ImageBitmap>()
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    fun cached(url: String?): ImageBitmap? = url?.let { images[it] }

    suspend fun load(url: String): ImageBitmap? {
        images[url]?.let { return it }
        val image = withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val bytes = r.body?.bytes() ?: return@use null
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            }.getOrNull()
        }
        if (image != null) images[url] = image
        return image
    }
}

/** A member's picture in a ring of their colour, their initial while it loads or when there is none. */
@Composable
fun GroupAvatar(url: String?, name: String, color: Color, size: Dp, modifier: Modifier = Modifier) {
    var image by remember(url) { mutableStateOf(AvatarImages.cached(url)) }
    LaunchedEffect(url) {
        if (image == null && url != null) image = AvatarImages.load(url)
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .padding(2.dp)
            .clip(CircleShape)
            .background(Color(0xFF1F1F1F)),
        contentAlignment = Alignment.Center,
    ) {
        val loaded = image
        if (loaded != null) {
            Image(bitmap = loaded, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            EonaText(
                name.take(1).uppercase(),
                style = EonaTheme.typography.bodyStrong.copy(fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold),
                color = Color.White,
            )
        }
    }
}

/**
 * The group on the HUD, over the main map: a chip for everyone at once, then one per member —
 * picture, name, what they are doing. A tap on the photo opens their card; on the name, the camera
 * follows them; again, back to me.
 */
@Composable
fun GroupStrip(
    chips: List<GroupChip>,
    focus: String?,
    onOverview: () -> Unit,
    onFocus: (String?) -> Unit,
    onCard: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (chips.isEmpty()) return
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(
            modifier = Modifier
                .height(40.dp)
                .clip(CircleShape)
                .background(colors.surface.copy(alpha = 0.62f))
                .border(1.dp, colors.border, CircleShape)
                .clickable(onClick = onOverview)
                .padding(horizontal = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            EonaIcon(EonaIcons.People, contentDescription = null, tint = colors.textPrimary, size = 15.dp)
            EonaText("Tous", style = EonaTheme.typography.caption, color = colors.textPrimary)
        }
        chips.forEach { chip -> MemberChip(chip, focused = focus == chip.id, onFocus = onFocus, onCard = onCard) }
    }
}

@Composable
private fun MemberChip(chip: GroupChip, focused: Boolean, onFocus: (String?) -> Unit, onCard: (String) -> Unit) {
    val colors = EonaTheme.colors
    val color = GroupPalette.color(chip.colorIndex)
    val fill by animateColorAsState(
        if (focused) color.copy(alpha = 0.35f) else colors.surface.copy(alpha = 0.62f),
        label = "chipFill",
    )
    // Two targets on one capsule: the photo opens the card, the rest follows on the map.
    Row(
        modifier = Modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(fill)
            .border(if (focused) 1.5.dp else 1.dp, if (focused) color else colors.border, CircleShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable { onCard(chip.id) },
            contentAlignment = Alignment.Center,
        ) {
            GroupAvatar(chip.avatarUrl, chip.name, color, 30.dp)
        }
        Column(
            modifier = Modifier
                .height(40.dp)
                .alpha(if (chip.onMap) 1f else 0.6f)
                .clickable(
                    enabled = chip.onMap,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onFocus(if (focused) null else chip.id) }
                .padding(start = 3.dp, end = EonaTheme.spacing.md),
            verticalArrangement = Arrangement.Center,
        ) {
            EonaText(
                chip.name,
                style = EonaTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
                maxLines = 1,
            )
            EonaText(
                chip.detail,
                style = EonaTheme.typography.caption.copy(fontSize = 11.sp),
                color = colors.textSecondary,
                maxLines = 1,
            )
        }
    }
}

/** A word about the group, over the map, for a few seconds; a tap sends it away. */
@Composable
fun GroupNoticeBanner(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = EonaTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(EonaTheme.shapes.lg)
            .background(colors.surface.copy(alpha = 0.92f))
            .border(1.dp, colors.accent.copy(alpha = 0.6f), EonaTheme.shapes.lg)
            .clickable(onClick = onDismiss)
            .padding(EonaTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EonaTheme.spacing.sm),
    ) {
        EonaIcon(EonaIcons.People, contentDescription = null, tint = colors.accent, size = 20.dp)
        EonaText(text, style = EonaTheme.typography.subhead, color = colors.textPrimary, modifier = Modifier.weight(1f))
    }
}

/** The group trip is over: the ranking, on the HUD, until the driver closes it. */
@Composable
fun GroupFinishCard(finished: TripGroup, myId: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(EonaTheme.shapes.lg)
            .background(colors.surface.copy(alpha = 0.94f))
            .border(1.dp, colors.border, EonaTheme.shapes.lg)
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EonaText(
                finished.toLabel?.let { "Arrivés à $it" } ?: "Trajet en groupe terminé",
                style = EonaTheme.typography.headline,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            EonaIconButton(icon = EonaIcons.Close, contentDescription = "Fermer", onClick = onDismiss, tint = colors.textSecondary, size = 36.dp)
        }
        GroupRankingView(finished.ranking, myId)
    }
}

/** The arrival ranking, frozen when the trip ended: who got there, in which order, in how long. */
@Composable
fun GroupRankingView(entries: List<GroupRankEntry>, mineId: String? = null) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        EonaText("Classement", style = EonaTheme.typography.headline, color = colors.textPrimary)
        entries.forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                EonaText(
                    entry.rankLabel,
                    style = EonaTheme.typography.numeric,
                    color = if (entry.rank == 1) colors.accent else colors.textSecondary,
                    modifier = Modifier.width(34.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    EonaText(
                        if (entry.id == mineId) "${entry.name} (moi)" else entry.name,
                        style = EonaTheme.typography.body,
                        color = colors.textPrimary,
                    )
                    EonaText(
                        if (entry.rank == null) entry.state.label else entry.timeLabel,
                        style = EonaTheme.typography.footnote,
                        color = colors.textSecondary,
                    )
                }
            }
        }
    }
}

/** One driver in a list: where they are in their own trip, and how fast. */
@Composable
fun GroupMemberRow(member: GroupMember, color: Color, showsCard: Boolean = false, onClick: (() -> Unit)? = null) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        GroupAvatar(
            member.avatarUrl,
            member.name,
            color,
            34.dp,
            modifier = Modifier.alpha(if (!member.online && member.sharing) 0.45f else 1f),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                EonaText(member.name, style = EonaTheme.typography.bodyStrong, color = colors.textPrimary, maxLines = 1)
                if (!member.sharing) EonaIcon(EonaIcons.EyeSlash, contentDescription = "Ne partage pas", tint = colors.textTertiary, size = 14.dp)
            }
            EonaText(
                member.detailLabel,
                style = EonaTheme.typography.footnote,
                color = if (member.online || !member.sharing) colors.textSecondary else colors.warning,
                maxLines = 1,
            )
            if (member.sharing && member.state != GroupMemberState.Arrived) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceHigh),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(member.progress.toFloat().coerceIn(0f, 1f))
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(color),
                    )
                }
            }
        }
        if (showsCard) EonaIcon(EonaIcons.ChevronRight, contentDescription = null, tint = colors.textTertiary, size = 14.dp)
    }
}

/** "Terminer" under a ranking, in the panel. */
@Composable
fun GroupDoneButton(onClick: () -> Unit) {
    EonaButton(text = "Terminer", onClick = onClick, variant = EonaButtonVariant.Primary, fillWidth = true)
}
