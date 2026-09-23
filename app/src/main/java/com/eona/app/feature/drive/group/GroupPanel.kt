package com.eona.app.feature.drive.group

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eona.app.data.trips.TripGroup
import com.eona.app.designsystem.component.EonaBadge
import com.eona.app.designsystem.component.EonaButton
import com.eona.app.designsystem.component.EonaButtonVariant
import androidx.compose.ui.window.Dialog
import com.eona.app.designsystem.component.EonaGlowTile
import com.eona.app.designsystem.component.EonaIcon
import com.eona.app.designsystem.component.EonaSwitch
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Trajet en groupe", from the outside: open one on the chosen destination, join one with its code,
 * decide what leaves the phone, hand out the link, and step out. Who shares and who does not is
 * always written — for everybody, mine included.
 */
@Composable
fun GroupPanel(
    session: GroupSession,
    destinationName: String?,
    hasDestination: Boolean,
    myId: String?,
    onCard: (MemberCardTarget) -> Unit,
) {
    val group by session.group.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmLeave by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { session.refresh() }

    Column(verticalArrangement = Arrangement.spacedBy(EonaTheme.spacing.lg)) {
        val current = group
        if (current != null && !current.isCancelled) {
            Inside(current, session, hasDestination, myId, onCard, onLeave = { confirmLeave = true })
        } else {
            Outside(session, destinationName)
        }
    }

    val current = group
    if (confirmLeave && current != null) {
        LeaveDialog(
            host = current.isHost,
            others = current.members.count { it.isPresent } > 1,
            onDismiss = { confirmLeave = false },
            onCancelTrip = { scope.launch { session.cancel() } },
            onLeave = { scope.launch { session.leave() } },
        )
    }
}

/**
 * Stepping out, asked first. The host may cancel for everyone, or leave and let the group carry on
 * (another driver then leads it); a member simply leaves.
 */
@Composable
private fun LeaveDialog(host: Boolean, others: Boolean, onDismiss: () -> Unit, onCancelTrip: () -> Unit, onLeave: () -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(EonaTheme.shapes.xl)
                .background(colors.surfaceElevated)
                .border(1.dp, colors.border, EonaTheme.shapes.xl)
                .padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            EonaText(if (host) "Quitter le trajet en groupe ?" else "Quitter le groupe ?", style = EonaTheme.typography.headline, color = colors.textPrimary)
            EonaText(
                if (host) {
                    "Annuler arrête le trajet commun pour tous. En quittant, le groupe continue et un autre participant le mène."
                } else {
                    "Ton trajet continue normalement ; les autres ne te voient plus."
                },
                style = EonaTheme.typography.subhead,
                color = colors.textSecondary,
            )
            if (host) {
                EonaButton(text = "Annuler pour tout le monde", variant = EonaButtonVariant.Destructive, fillWidth = true, onClick = { onDismiss(); onCancelTrip() })
                if (others) {
                    EonaButton(text = "Quitter et laisser le groupe continuer", variant = EonaButtonVariant.Secondary, fillWidth = true, onClick = { onDismiss(); onLeave() })
                }
            } else {
                EonaButton(text = "Quitter le groupe", variant = EonaButtonVariant.Destructive, fillWidth = true, onClick = { onDismiss(); onLeave() })
            }
            EonaButton(text = "Rester", variant = EonaButtonVariant.Ghost, fillWidth = true, onClick = onDismiss)
        }
    }
}

// ---- Without a group ----

@Composable
private fun Outside(session: GroupSession, destinationName: String?) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    val scope = rememberCoroutineScope()
    val busy by session.busy.collectAsStateWithLifecycle()
    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        EonaGlowTile(icon = EonaIcons.People, size = 56.dp, iconSize = 26.dp, shape = EonaTheme.shapes.lg)
        EonaText("Rouler ensemble", style = EonaTheme.typography.title, color = colors.textPrimary)
        EonaText(
            "Jusqu'à cinq conducteurs vers la même adresse. Chacun part d'où il veut, suit sa route, et voit les autres avancer.",
            style = EonaTheme.typography.footnote,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }

    // Create: the destination chosen becomes the group's.
    SheetCard {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            SectionTitle("Créer un groupe")
            if (destinationName != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                    Box(
                        modifier = Modifier.size(32.dp).clip(EonaTheme.shapes.sm).background(colors.accent.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) { EonaIcon(EonaIcons.Flag, contentDescription = null, tint = colors.accent, size = 16.dp) }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        EonaText("Destination commune", style = EonaTheme.typography.caption, color = colors.textTertiary)
                        EonaText(destinationName, style = EonaTheme.typography.bodyStrong, color = colors.textPrimary, maxLines = 2)
                    }
                }
                EonaButton(
                    text = "Créer le groupe",
                    loading = busy,
                    fillWidth = true,
                    onClick = {
                        scope.launch { message = if (session.create() == null) "Création impossible — vérifie ta connexion." else null }
                    },
                )
            } else {
                EonaText(
                    "Choisis d'abord une destination : c'est elle que le groupe partagera.",
                    style = EonaTheme.typography.footnote,
                    color = colors.textSecondary,
                )
            }
        }
    }

    // Join: six characters, easy to read out loud.
    SheetCard {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            SectionTitle("Rejoindre avec un code")
            GroupCodeField(code = code, onChange = { code = it })
            EonaButton(
                text = "Rejoindre",
                variant = EonaButtonVariant.Secondary,
                loading = busy,
                enabled = code.length == CODE_LENGTH,
                fillWidth = true,
                onClick = {
                    scope.launch {
                        if (session.join(code) == null) {
                            message = "Code inconnu, groupe complet ou trajet terminé."
                        } else {
                            code = ""
                            message = null
                        }
                    }
                },
            )
            EonaText(
                "La destination du groupe devient la tienne ; ton itinéraire part de là où tu es.",
                style = EonaTheme.typography.footnote,
                color = colors.textTertiary,
            )
        }
    }

    message?.let { EonaText(it, style = EonaTheme.typography.footnote, color = colors.danger) }
}

// ---- In a group ----

@Composable
private fun Inside(
    group: TripGroup,
    session: GroupSession,
    hasDestination: Boolean,
    myId: String?,
    onCard: (MemberCardTarget) -> Unit,
    onLeave: () -> Unit,
) {
    Header(group)
    Participants(group, onCard)
    if (!group.isOver) {
        Visibility(session, launched = hasDestination)
        if (group.isHost) WatchLink(group, session)
        EonaButton(
            text = if (group.isHost) "Quitter ou annuler le trajet" else "Quitter le groupe",
            onClick = onLeave,
            variant = EonaButtonVariant.Ghost,
            fillWidth = true,
        )
    } else {
        SheetCard {
            Column(verticalArrangement = Arrangement.spacedBy(EonaTheme.spacing.md)) {
                GroupRankingView(group.ranking, myId)
                GroupDoneButton { session.dismiss() }
            }
        }
    }
}

/** Where the group goes, its code in large, and the way to hand it out. */
@Composable
private fun Header(group: TripGroup) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    SheetCard {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    EonaText(if (group.isOver) "Trajet terminé" else "Destination commune", style = EonaTheme.typography.caption, color = colors.textTertiary)
                    EonaText(group.toLabel ?: "Destination du groupe", style = EonaTheme.typography.headline, color = colors.textPrimary, maxLines = 2)
                }
                EonaBadge(text = "${group.members.count { it.isPresent }}/${group.maxMembers}")
            }
            if (!group.isOver) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    group.code.forEach { letter ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(EonaTheme.shapes.sm)
                                .background(colors.surface.copy(alpha = 0.55f))
                                .border(1.dp, colors.border, EonaTheme.shapes.sm),
                            contentAlignment = Alignment.Center,
                        ) {
                            EonaText(letter.toString(), style = CODE_STYLE, color = colors.textPrimary)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    EonaButton(
                        text = if (copied) "Copié" else "Copier",
                        variant = EonaButtonVariant.Secondary,
                        fillWidth = true,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            clipboard.setText(AnnotatedString(group.code))
                            copied = true
                        },
                    )
                    EonaButton(
                        text = "Inviter",
                        fillWidth = true,
                        modifier = Modifier.weight(1f),
                        onClick = { shareText(context, invitation(group)) },
                    )
                }
                if (!group.isHost && group.hostName != null) {
                    EonaText("Mené par ${group.hostName}", style = EonaTheme.typography.footnote, color = colors.textTertiary)
                }
            }
        }
    }
}

/** Everyone, with what they share — a closed eye for those who do not. A tap: their card. */
@Composable
private fun Participants(group: TripGroup, onCard: (MemberCardTarget) -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    // Those who left are gone from the list; everyone keeps the colour of their place.
    val present = group.members.withIndex().filter { it.value.isPresent }
    val free = group.maxMembers - present.size
    SheetCard {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            SectionTitle("Participants")
            present.forEachIndexed { i, (index, member) ->
                GroupMemberRow(member, GroupPalette.color(index), showsCard = true, onClick = { onCard(MemberCardTarget(member.id, index)) })
                if (i < present.lastIndex) Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))
            }
            if (free > 0 && !group.isOver) {
                EonaText(
                    "Encore $free place${if (free > 1) "s" else ""}",
                    style = EonaTheme.typography.footnote,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(top = spacing.xs),
                )
            }
        }
    }
}

/** What leaves my phone. Both switch at once and stay switched. */
@Composable
private fun Visibility(session: GroupSession, launched: Boolean) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    val scope = rememberCoroutineScope()
    val sharing by session.sharing.collectAsStateWithLifecycle()
    val observable by session.observable.collectAsStateWithLifecycle()
    SheetCard {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            SectionTitle("Ce que je partage")
            // Once the trip is launched the switches speak for themselves: only the warning that
            // nothing is shared stays.
            SwitchRow(
                title = "Ma position et ma vitesse",
                detail = if (sharing) {
                    if (launched) null else "Visibles par le groupe dès que tu es en route."
                } else {
                    "Personne ne voit ni ta position, ni ta vitesse, ni ton avancement."
                },
                checked = sharing,
                enabled = true,
                onChange = { on -> scope.launch { session.setSharing(on) } },
            )
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))
            SwitchRow(
                title = "Visible depuis le lien",
                detail = if (launched) null else "Hors du lien d'observation, tu restes visible pour le groupe.",
                checked = observable,
                enabled = sharing,
                onChange = { on -> scope.launch { session.setObservable(on) } },
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val colors = EonaTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EonaTheme.spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            EonaText(title, style = EonaTheme.typography.bodyStrong, color = colors.textPrimary)
            detail?.let { EonaText(it, style = EonaTheme.typography.footnote, color = colors.textSecondary) }
        }
        EonaSwitch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** The host's link for people who watch without driving. */
@Composable
private fun WatchLink(group: TripGroup, session: GroupSession) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy by session.busy.collectAsStateWithLifecycle()
    SheetCard {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            SectionTitle("Observer sans conduire")
            val link = group.link
            if (link != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(colors.success))
                    EonaText(
                        when (link.observers) {
                            0 -> "Lien actif · personne ne regarde"
                            1 -> "Lien actif · 1 personne regarde"
                            else -> "Lien actif · ${link.observers} personnes regardent"
                        },
                        style = EonaTheme.typography.footnote,
                        color = colors.textSecondary,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    EonaButton(
                        text = "Envoyer",
                        fillWidth = true,
                        modifier = Modifier.weight(1f),
                        onClick = { shareText(context, "Suis notre trajet en groupe sur EONA : ${link.url}") },
                    )
                    EonaButton(
                        text = "Révoquer",
                        variant = EonaButtonVariant.Destructive,
                        fillWidth = true,
                        modifier = Modifier.weight(1f),
                        onClick = { scope.launch { session.setLink(open = false) } },
                    )
                }
            } else {
                EonaButton(
                    text = "Créer un lien",
                    variant = EonaButtonVariant.Secondary,
                    loading = busy,
                    fillWidth = true,
                    onClick = { scope.launch { session.setLink(open = true) } },
                )
            }
            EonaText(
                "Qui l'ouvre voit la carte, l'avancement, le tracé et la vitesse des seuls participants qui l'acceptent. Il meurt avec le trajet.",
                style = EonaTheme.typography.footnote,
                color = colors.textTertiary,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    EonaText(text.uppercase(), style = EonaTheme.typography.caption, color = EonaTheme.colors.textTertiary)
}

/**
 * Six boxes for six characters. The real field is invisible over them: typing, pasting and deleting
 * behave as in any text field, uppercase and nothing that could be misread.
 */
@Composable
fun GroupCodeField(code: String, onChange: (String) -> Unit) {
    val colors = EonaTheme.colors
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                runCatching { focus.requestFocus() }
            },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(EonaTheme.spacing.xs)) {
            repeat(CODE_LENGTH) { index ->
                val current = focused && index == minOf(code.length, CODE_LENGTH - 1)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .clip(EonaTheme.shapes.sm)
                        .background(colors.surface.copy(alpha = 0.55f))
                        .border(if (current) 2.dp else 1.dp, if (current) colors.accent else colors.border, EonaTheme.shapes.sm),
                    contentAlignment = Alignment.Center,
                ) {
                    EonaText(code.getOrNull(index)?.toString() ?: "", style = CODE_STYLE, color = colors.textPrimary)
                }
            }
        }
        BasicTextField(
            value = code,
            onValueChange = { typed -> onChange(typed.uppercase().filter { it.isLetterOrDigit() }.take(CODE_LENGTH)) },
            singleLine = true,
            textStyle = TextStyle(color = Color.Transparent),
            cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
            modifier = Modifier
                .matchParentSizeCompat()
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused }
                .alpha(0.01f),
        )
    }
}

/** The whole box, for the invisible field laid over the six letters. */
private fun Modifier.matchParentSizeCompat(): Modifier = this.fillMaxWidth().height(50.dp)

/** The invitation: the code, and where to type it. */
private fun invitation(group: TripGroup): String {
    val place = group.toLabel?.let { " vers $it" } ?: ""
    return "Rejoins mon trajet EONA$place : ouvre EONA, « En groupe », code ${group.code}."
}

/** Android's share sheet, with [text]. */
internal fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private const val CODE_LENGTH = 6
private val CODE_STYLE = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 24.sp)
