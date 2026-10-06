package com.eona.app.feature.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eona.app.data.account.AccountRepository
import com.eona.app.data.admin.AdminAccount
import com.eona.app.data.admin.AdminApi
import com.eona.app.data.admin.AdminApiException
import com.eona.app.data.admin.AdminBanRecord
import com.eona.app.data.admin.AdminOverview
import com.eona.app.data.admin.AdminPresenceGroup
import com.eona.app.data.admin.AdminTrip
import com.eona.app.designsystem.component.EonaConfirmDialog
import com.eona.app.designsystem.component.EonaIcon
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme
import com.eona.app.feature.subscription.EonaPlusDivider
import com.eona.app.feature.subscription.EonaPlusGroup
import com.eona.app.feature.subscription.EonaPlusLabel
import com.eona.app.feature.subscription.EonaPlusStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Pages de la gestion, empilées dans une seule route. */
private sealed interface AdminDest {
    data object Overview : AdminDest
    data object Trips : AdminDest
    data object Accounts : AdminDest
    data class Account(val account: AdminAccount) : AdminDest
    data object Bans : AdminDest
    data object Create : AdminDest
}

/** État de la vue d'ensemble, gardé pendant la navigation dans la gestion. */
private class OverviewState {
    var data by mutableStateOf<AdminOverview?>(null)
    var loading by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var request = 0
}

/** Recherche et liste des comptes, gardées au retour d'une fiche. */
private class AccountsState {
    var query by mutableStateOf("")
    var role by mutableStateOf("")
    var bannedOnly by mutableStateOf(false)
    var accounts by mutableStateOf(listOf<AdminAccount>())
    var next by mutableStateOf<Int?>(null)
    var total by mutableIntStateOf(0)
    var loaded by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var refresh by mutableIntStateOf(0)
    var request = 0
}

/**
 * « Gestion EONA » (iOS EonaAdminScreen), admins seulement : présence observée, activité anonyme,
 * Valhalla, HERE, comptes et bannissements. Rafraîchissement seulement pendant l'affichage.
 */
@Composable
fun EonaAdminRoute(onBack: () -> Unit) {
    val account by AccountRepository.account.collectAsStateWithLifecycle()
    val access = remember { AdminAccess() }
    val allowed = access.permits(account, AccountRepository.token)
    val api = remember { AdminApi() }
    val stack = remember { mutableStateListOf<AdminDest>(AdminDest.Overview) }
    val overview = remember { OverviewState() }
    val accounts = remember { AccountsState() }
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onBack() }
    BackHandler(onBack = back)

    when (val top = stack.last()) {
        AdminDest.Overview -> OverviewScreen(api, access, allowed, overview, back) { stack.add(it) }
        AdminDest.Trips -> TripsScreen(api, access, allowed, back)
        AdminDest.Accounts -> AccountsScreen(api, access, allowed, accounts, back) { stack.add(it) }
        is AdminDest.Account -> AccountScreen(api, access, allowed, top.account, myId = account?.id, onBack = back) { updated ->
            accounts.accounts = accounts.accounts.map { if (it.id == updated.id) updated else it }
        }
        AdminDest.Bans -> BansScreen(api, access, allowed, myId = account?.id, onBack = back)
        AdminDest.Create -> CreateScreen(api, access, allowed, onBack = back) {
            accounts.refresh += 1
            back()
        }
    }
}

/** Vrai pendant que l'écran est au premier plan. */
@Composable
private fun rememberResumed(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return resumed
}

// ---- Vue d'ensemble -------------------------------------------------------------------------

@Composable
private fun OverviewScreen(
    api: AdminApi,
    access: AdminAccess,
    allowed: Boolean,
    state: OverviewState,
    onBack: () -> Unit,
    open: (AdminDest) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val resumed = rememberResumed()
    suspend fun load() {
        val token = AccountRepository.token ?: return
        val id = ++state.request
        state.loading = true
        try {
            val fresh = api.overview(token)
            if (id == state.request && token == AccountRepository.token) {
                state.data = fresh
                state.message = null
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (id == state.request) {
                access.receive(e)?.let { state.message = it }
                if (access.denied != null) state.data = null
            }
        } finally {
            if (id == state.request) state.loading = false
        }
    }
    // Toutes les 30 s, écran affiché et app au premier plan.
    LaunchedEffect(resumed, allowed) {
        if (!resumed || !allowed) return@LaunchedEffect
        while (true) {
            load()
            delay(30_000)
        }
    }
    val retry: () -> Unit = { scope.launch { load() } }

    AdminPage("Gestion EONA", onBack, allowed, access.denied) {
        val data = state.data
        when {
            data != null -> OverviewContent(data, open)
            state.message != null -> AdminNotice("Gestion EONA", state.message!!, retry)
            else -> AdminLoading()
        }
        if (data != null && state.message != null) AdminNotice("Actualisation impossible", state.message!!, retry)
    }
}

@Composable
private fun OverviewContent(data: AdminOverview, open: (AdminDest) -> Unit) {
    val spacing = EonaTheme.spacing
    Footnote("Actualisé ${AdminFormat.date(data.generatedAt)}")
    AdminSection("Présence") {
        data.presence.groups.forEachIndexed { index, group ->
            PresenceGroupRow(group)
            if (index < data.presence.groups.lastIndex) EonaPlusDivider(Modifier.padding(horizontal = spacing.lg))
        }
    }
    Footnote("Présence observée sur ${data.presence.ttlSeconds} s. Sans signal récent, compte classé hors ligne.", indent = true)
    AdminSection("Activité") {
        AdminNavigationRow(
            "Trajets terminés",
            "${AdminFormat.count(data.trips.totalRecorded)} enregistrés · ${AdminFormat.count(data.trips.retainedCount)} conservés",
            EonaIcons.TurnRight,
            EonaPlusStyle.sky,
        ) { open(AdminDest.Trips) }
        EonaPlusDivider(Modifier.padding(start = 68.dp))
        AdminNavigationRow(
            "Comptes",
            "${AdminFormat.count(data.accounts.total)} comptes · ${data.accounts.banned} bannis · ${data.accounts.suspended} suspendus",
            EonaIcons.People,
            EonaPlusStyle.mint,
        ) { open(AdminDest.Accounts) }
    }
    AdminSection("Valhalla") {
        val up = data.routing.valhallaState == "up"
        AdminValueRow("État", engineState(data.routing.valhallaState), if (up) EonaPlusStyle.mint else EonaPlusStyle.peach)
        EonaPlusDivider(Modifier.padding(horizontal = spacing.lg))
        AdminValueRow("Moteur servi", data.routing.provider?.uppercase() ?: "—")
        AdminValueRow("Version", data.routing.version ?: "—")
        AdminValueRow("Carte", data.routing.mapVersion ?: "—")
        AdminValueRow("Réponses Valhalla", AdminFormat.count(data.routingStats.totalRequests))
        AdminValueRow("Réponses cette semaine", AdminFormat.count(data.routingStats.weekRequests))
        AdminValueRow("Secours ORS", AdminFormat.count(data.routing.fallbacks))
        AdminValueRow("Depuis", AdminFormat.date(data.routing.fallbacksSince))
        AdminValueRow("Réponses cache", AdminFormat.count(data.routingStats.cacheHits))
        AdminValueRow("Latence médiane API", AdminFormat.milliseconds(data.routingStats.medianMs))
        AdminValueRow("Latence p95 API", AdminFormat.milliseconds(data.routingStats.p95Ms))
    }
    Column(Modifier.padding(horizontal = spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Footnote(
            if (data.routingStats.available) "Journal conservé ${data.routingStats.retentionDays} jours. Latence API, cache inclus."
            else "Mesures de routage indisponibles.",
        )
        Footnote("Échecs moteur non attribués dans journal.")
        Footnote("Secours ORS comptés depuis démarrage du backend.")
    }
    AdminSection("HERE · consommation") {
        val here = data.here
        AdminValueRow("Appels ce mois", AdminFormat.count(here.monthUsed))
        AdminValueRow("Estimation ce mois", AdminFormat.euros(here.estimatedMonthEUR), EonaPlusStyle.peach)
        AdminValueRow("Budget mensuel estimé", AdminFormat.euros(here.monthlyBudgetEUR))
        AdminValueRow("Plafond journalier", here.dailyCap?.let { AdminFormat.count(it) } ?: "Aucun")
        EonaPlusDivider(Modifier.padding(horizontal = spacing.lg))
        AdminValueRow("Appels cette semaine", AdminFormat.count(here.weekRequests))
        AdminValueRow("Estimation semaine", AdminFormat.euros(here.weekEstimatedEUR))
        AdminValueRow("Appels cumulés", AdminFormat.count(here.totalRequests))
        AdminValueRow("Estimation cumulée", AdminFormat.euros(here.totalEstimatedEUR))
        here.blocked?.let { AdminValueRow("Restriction", hereReason(it), EonaPlusStyle.peach) }
    }
    Column(Modifier.padding(horizontal = spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Footnote("Coûts estimés selon tarifs configurés.")
        Footnote("Historique depuis ${AdminFormat.date(data.here.since)}.")
        if (!data.here.earlierHistoryComplete) Footnote("Cumul partiel : historique antérieur incomplet.")
        if (!data.here.weekComplete) Footnote("Semaine partielle depuis démarrage de collecte.")
    }
}

@Composable
private fun PresenceGroupRow(group: AdminPresenceGroup) {
    val spacing = EonaTheme.spacing
    val color = when (group.id) {
        "admin" -> EonaPlusStyle.mint
        "client" -> EonaPlusStyle.lavender
        else -> EonaPlusStyle.sky
    }
    Column(Modifier.fillMaxWidth().padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            EonaText(group.label, style = EonaTheme.typography.bodyStrong, color = EonaPlusStyle.primary)
            EonaText("${AdminFormat.count(group.total)} comptes", style = EonaTheme.typography.footnote, color = EonaPlusStyle.secondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            listOf("Connectés" to group.online, "Hors ligne" to group.offline, "En trajet" to group.inTrip).forEach { (label, value) ->
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    EonaText(AdminFormat.count(value), style = EonaTheme.typography.headline, color = color)
                    EonaText(label, style = EonaTheme.typography.footnote, color = EonaPlusStyle.secondary)
                }
            }
        }
    }
}

private fun engineState(state: String) = when (state) {
    "up" -> "Opérationnel"
    "down" -> "Indisponible"
    "disabled" -> "Désactivé"
    else -> "À mesurer"
}

private fun hereReason(reason: String) = when (reason) {
    "monthly budget" -> "Budget mensuel atteint"
    "daily cap" -> "Plafond journalier atteint"
    "storage unavailable" -> "Compteurs indisponibles"
    "key missing" -> "Service non configuré"
    else -> "Appels suspendus"
}

@Composable
private fun Footnote(text: String, indent: Boolean = false) {
    EonaText(
        text,
        style = EonaTheme.typography.footnote,
        color = EonaPlusStyle.secondary,
        modifier = if (indent) Modifier.padding(horizontal = EonaTheme.spacing.lg) else Modifier,
    )
}

// ---- Trajets terminés -----------------------------------------------------------------------

@Composable
private fun TripsScreen(api: AdminApi, access: AdminAccess, allowed: Boolean, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var trips by remember { mutableStateOf(listOf<AdminTrip>()) }
    var next by remember { mutableStateOf<Int?>(null) }
    var total by remember { mutableIntStateOf(0) }
    var retention by remember { mutableStateOf<Int?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var request by remember { mutableIntStateOf(0) }

    suspend fun load(reset: Boolean) {
        val token = AccountRepository.token ?: return
        if (!reset && loading) return
        val id = ++request
        loading = true
        try {
            val page = api.trips(if (reset) 0 else (next ?: 0), token)
            if (id != request || token != AccountRepository.token) return
            trips = if (reset) page.trips else trips + page.trips.filter { t -> trips.none { it.id == t.id } }
            next = page.next
            total = page.total
            retention = page.retentionPerAccount
            loaded = true
            message = null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val failure = access.receive(e)
            if (id == request && failure != null) message = failure
        } finally {
            if (id == request) loading = false
        }
    }
    LaunchedEffect(refresh, allowed) { if (allowed) load(reset = true) }

    AdminPage("Trajets terminés", onBack, allowed, access.denied) {
        if (loaded) {
            Column(verticalArrangement = Arrangement.spacedBy(EonaTheme.spacing.xs)) {
                Footnote("${AdminFormat.count(total)} trajets conservés · ordre par départ")
                retention?.let { Footnote("$it derniers trajets par compte. Flux anonyme.") }
            }
        }
        if (loading && trips.isEmpty()) AdminLoading()
        when {
            message != null -> AdminNotice("Chargement impossible", message!!) { refresh += 1 }
            loaded && trips.isEmpty() -> AdminNotice("Aucun trajet", "Aucun trajet enregistré actuellement.")
        }
        if (trips.isNotEmpty()) {
            EonaPlusGroup {
                trips.forEachIndexed { index, trip ->
                    TripRow(trip)
                    if (index < trips.lastIndex) EonaPlusDivider(Modifier.padding(horizontal = EonaTheme.spacing.lg))
                }
            }
        }
        if (next != null) AdminLink(if (loading) "Chargement…" else "Plus anciens", enabled = !loading) { scope.launch { load(reset = false) } }
    }
}

@Composable
private fun TripRow(trip: AdminTrip) {
    val spacing = EonaTheme.spacing
    val duration = when {
        trip.durationSeconds < 60 -> "${trip.durationSeconds} s"
        trip.durationSeconds / 60 >= 60 -> "${trip.durationSeconds / 3600} h ${(trip.durationSeconds / 60) % 60} min"
        else -> "${trip.durationSeconds / 60} min"
    }
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        EonaText("Départ ${AdminFormat.date(trip.startedAtMillis)}", style = EonaTheme.typography.bodyStrong, color = EonaPlusStyle.primary)
        EonaText("${AdminFormat.count(trip.distanceMeters)} m · $duration", style = EonaTheme.typography.subhead, color = EonaPlusStyle.secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            when (trip.arrived) {
                true -> EonaText("Arrivée atteinte", style = EonaTheme.typography.footnote, color = EonaPlusStyle.mint)
                false -> EonaText("Trajet arrêté", style = EonaTheme.typography.footnote, color = EonaPlusStyle.peach)
                null -> EonaText("Arrivée non renseignée", style = EonaTheme.typography.footnote, color = EonaPlusStyle.muted)
            }
            if (trip.engines.isNotEmpty()) {
                EonaText(trip.engines.joinToString(" / ").uppercase(), style = EonaTheme.typography.footnote, color = EonaPlusStyle.secondary)
            }
        }
    }
}

// ---- Comptes --------------------------------------------------------------------------------

@Composable
private fun AccountsScreen(
    api: AdminApi,
    access: AdminAccess,
    allowed: Boolean,
    state: AccountsState,
    onBack: () -> Unit,
    open: (AdminDest) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val spacing = EonaTheme.spacing

    suspend fun load(reset: Boolean) {
        val token = AccountRepository.token ?: return
        if (!reset && state.loading) return
        val id = ++state.request
        state.loading = true
        try {
            val page = api.accounts(
                query = state.query.trim(),
                role = state.role.ifEmpty { null },
                banned = if (state.bannedOnly) true else null,
                offset = if (reset) 0 else (state.next ?: 0),
                token = token,
            )
            if (id != state.request || token != AccountRepository.token) return
            state.accounts = if (reset) page.accounts else state.accounts + page.accounts.filter { a -> state.accounts.none { it.id == a.id } }
            state.total = page.total
            state.next = page.next
            state.loaded = true
            state.message = null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val failure = access.receive(e)
            if (id == state.request && failure != null) state.message = failure
        } finally {
            if (id == state.request) state.loading = false
        }
    }
    // Recherche posée 300 ms : une seule requête par saisie.
    LaunchedEffect(state.query, state.role, state.bannedOnly, state.refresh, allowed) {
        if (!allowed) return@LaunchedEffect
        delay(300)
        load(reset = true)
    }

    AdminPage(
        "Comptes",
        onBack,
        allowed,
        access.denied,
        action = {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { open(AdminDest.Create) }
                    .semantics { contentDescription = "Créer un compte" },
                contentAlignment = Alignment.Center,
            ) { EonaIcon(EonaIcons.Plus, contentDescription = null, tint = EonaPlusStyle.lavender, size = 22.dp) }
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(EonaPlusStyle.surface)
                .padding(start = spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EonaIcon(EonaIcons.Search, contentDescription = null, tint = EonaPlusStyle.muted, size = 18.dp)
            Box(Modifier.weight(1f)) { AdminField(state.query, { state.query = it }, "Pseudo, email, compte") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            AdminPills(
                listOf("" to "Tous", "guest" to "Invités", "client" to "Clients", "admin" to "Admins"),
                state.role,
            ) { state.role = it }
        }
        AdminPills(listOf("all" to "Tous comptes", "banned" to "Bannis"), if (state.bannedOnly) "banned" else "all") { state.bannedOnly = it == "banned" }
        EonaPlusGroup {
            AdminNavigationRow("Bannissements", "Compte, email et appareils connus", EonaIcons.NoEntry, EonaPlusStyle.peach) { open(AdminDest.Bans) }
        }
        if (state.loaded) EonaPlusLabel("${AdminFormat.count(state.total)} comptes")
        if (state.loading && state.accounts.isEmpty()) AdminLoading()
        when {
            state.message != null -> AdminNotice("Chargement impossible", state.message!!) { state.refresh += 1 }
            state.loaded && state.accounts.isEmpty() -> AdminNotice("Aucun compte", "Aucun compte ne correspond à la recherche.")
        }
        if (state.accounts.isNotEmpty()) {
            EonaPlusGroup {
                state.accounts.forEachIndexed { index, account ->
                    val flagged = account.banned || account.suspended == true
                    AdminNavigationRow(
                        account.name,
                        "${AdminFormat.access(account)} · ${AdminFormat.activity(account)}",
                        if (account.role == "admin") EonaIcons.Shield else EonaIcons.User,
                        if (flagged) EonaPlusStyle.peach else EonaPlusStyle.lavender,
                    ) { open(AdminDest.Account(account)) }
                    if (index < state.accounts.lastIndex) EonaPlusDivider(Modifier.padding(start = 68.dp))
                }
            }
        }
        if (state.next != null) {
            AdminLink(if (state.loading) "Chargement…" else "Plus de comptes", enabled = !state.loading) { scope.launch { load(reset = false) } }
        }
    }
}

// ---- Fiche d'un compte ----------------------------------------------------------------------

private enum class AccountAction(val wire: String, val destructive: Boolean, val title: String, val confirmation: String, val explanation: String) {
    Ban("ban", true, "Bannir compte, email et appareils", "Bannir ce compte ?", "Compte, email et appareils connus bloqués. Toutes sessions fermées. Compte conservé."),
    Unban("unban", false, "Retirer bannissement", "Retirer bannissement ?", "Bannissement du compte, email et appareils retiré. Suspension séparée conservée."),
    Suspend("suspend", true, "Suspendre compte", "Suspendre ce compte ?", "Accès application suspendu. Sessions fermées. Compte et historique conservés."),
    Restore("restore", false, "Réactiver compte", "Réactiver ce compte ?", "Suspension retirée. Bannissement éventuel reste applicable."),
    RevokeSessions("revokeSessions", true, "Fermer toutes sessions", "Fermer toutes sessions ?", "Toutes sessions actives fermées. Droits et historique du compte conservés."),
    Save("save", false, "Enregistrer", "Modifier ce compte ?", "Modification du nom affiché et du rôle.");

    val icon
        get() = when (this) {
            Ban -> EonaIcons.NoEntry
            Unban -> EonaIcons.Check
            Suspend -> EonaIcons.Pause
            Restore -> EonaIcons.Play
            RevokeSessions -> EonaIcons.Logout
            Save -> EonaIcons.Check
        }
}

@Composable
private fun AccountScreen(
    api: AdminApi,
    access: AdminAccess,
    allowed: Boolean,
    initial: AdminAccount,
    myId: String?,
    onBack: () -> Unit,
    onChange: (AdminAccount) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val spacing = EonaTheme.spacing
    var account by remember { mutableStateOf(initial) }
    var displayName by remember { mutableStateOf(initial.displayName ?: initial.username ?: "") }
    var role by remember { mutableStateOf(initial.role) }
    var reason by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<AccountAction?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var showDevices by remember { mutableStateOf(false) }
    val isSelf = myId == account.id
    val unavailable = working || loading || !loaded || !allowed
    val changed = displayName != (account.displayName ?: account.username ?: "") || role != account.role

    suspend fun load() {
        val token = AccountRepository.token ?: return
        if (loading || working) return
        loading = true
        try {
            val fresh = api.account(account.id, token)
            if (token != AccountRepository.token) return
            val keepDraft = changed && loaded
            account = fresh
            if (!keepDraft) {
                displayName = fresh.displayName ?: fresh.username ?: ""
                role = fresh.role
            }
            loaded = true
            message = null
            onChange(fresh)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            access.receive(e)?.let { message = it }
        } finally {
            loading = false
        }
    }

    suspend fun perform(action: AccountAction) {
        val token = AccountRepository.token ?: return
        if (unavailable) return
        working = true
        message = null
        try {
            val fresh = if (action == AccountAction.Save) {
                api.update(account.id, role, displayName.trim(), token)
            } else {
                val source = if (action == AccountAction.Unban) account.banId ?: account.id else account.id
                val result = api.action(source, action.wire, reason.trim(), token)
                if (action == AccountAction.Unban || result.account?.id != account.id) {
                    api.account(account.id, token)
                } else {
                    result.account ?: throw AdminApiException(AdminApiException.Kind.InvalidResponse)
                }
            }
            if (token != AccountRepository.token) return
            account = fresh
            displayName = fresh.displayName ?: fresh.username ?: ""
            role = fresh.role
            reason = ""
            onChange(fresh)
            if (isSelf) AccountRepository.reload()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            access.receive(e)?.let { message = it }
        } finally {
            working = false
        }
    }
    LaunchedEffect(refresh, allowed) { if (allowed) load() }

    Box {
        AdminPage("Compte", onBack, allowed, access.denied) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                EonaText(account.name, style = EonaTheme.typography.titleLarge, color = EonaPlusStyle.primary)
                val flagged = account.banned || account.suspended == true
                EonaText(AdminFormat.access(account), style = EonaTheme.typography.subhead, color = if (flagged) EonaPlusStyle.peach else EonaPlusStyle.mint)
            }
            if (loading) AdminLoading()
            message?.let { AdminNotice("Compte", it) { refresh += 1 } }
            AdminSection("Identité") {
                AdminValueRow("Pseudo", account.username ?: "—")
                AdminValueRow("Email", account.email ?: "—")
                EonaPlusDivider(Modifier.padding(horizontal = spacing.lg))
                Column(Modifier.padding(top = spacing.lg, start = spacing.lg, end = spacing.lg)) {
                    EonaText("Nom affiché", style = EonaTheme.typography.footnote, color = EonaPlusStyle.secondary)
                }
                AdminField(displayName, { displayName = it }, "Nom affiché", enabled = !unavailable, caps = KeyboardCapitalization.Words)
                Box(Modifier.padding(horizontal = spacing.lg)) {
                    AdminPills(listOf("guest" to "Invité", "client" to "Client", "admin" to "Admin"), role, enabled = !unavailable && !isSelf) { role = it }
                }
                Box(Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm)) {
                    AdminLink(if (working) "Enregistrement…" else "Enregistrer", enabled = !unavailable && changed) { pending = AccountAction.Save }
                }
            }
            AdminSection("Activité") {
                AdminValueRow("État observé", AdminFormat.activity(account))
                AdminValueRow("Création", AdminFormat.date(account.createdAt))
                AdminValueRow("Dernière connexion", AdminFormat.date(account.lastSeenAt))
                AdminValueRow("Trajets enregistrés", AdminFormat.count(account.tripCount))
            }
            AdminSection("Application & appareils") {
                AdminValueRow("Plateforme", account.platform?.uppercase() ?: "—")
                AdminValueRow("Modèle", account.model ?: "—")
                AdminValueRow("Système", account.osVersion ?: "—")
                AdminValueRow("Version EONA", account.appVersion ?: "—")
                AdminValueRow("Appareils connus", AdminFormat.count(account.devices.size))
                if (account.devices.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { showDevices = !showDevices }
                            .padding(spacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EonaText("Identifiants appareil", style = EonaTheme.typography.subhead, color = EonaPlusStyle.primary, modifier = Modifier.weight(1f))
                        EonaIcon(if (showDevices) EonaIcons.ChevronUp else EonaIcons.ChevronDown, contentDescription = null, tint = EonaPlusStyle.lavender, size = 16.dp)
                    }
                    if (showDevices) {
                        Column(Modifier.padding(start = spacing.lg, end = spacing.lg, bottom = spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                            account.devices.forEach { device ->
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    EonaText(device, style = EonaTheme.typography.footnote.copy(fontFamily = FontFamily.Monospace), color = EonaPlusStyle.secondary)
                                }
                            }
                        }
                    }
                }
            }
            AdminSection("Accès") {
                if (account.banned) {
                    AdminValueRow("Banni depuis", AdminFormat.date(account.bannedAt))
                    account.banReason?.takeIf { it.isNotEmpty() }?.let { AdminValueRow("Motif bannissement", it) }
                }
                Column(Modifier.padding(top = spacing.lg, start = spacing.lg, end = spacing.lg)) {
                    EonaText("Motif facultatif", style = EonaTheme.typography.footnote, color = EonaPlusStyle.secondary)
                }
                AdminField(reason, { reason = it }, "Motif", enabled = !unavailable, singleLine = false)
                EonaPlusDivider(Modifier.padding(horizontal = spacing.lg))
                ActionRow(if (account.banned) AccountAction.Unban else AccountAction.Ban, working, !unavailable && !isSelf) { pending = it }
                EonaPlusDivider(Modifier.padding(horizontal = spacing.lg))
                ActionRow(if (account.suspended == true || account.revoked == true) AccountAction.Restore else AccountAction.Suspend, working, !unavailable && !isSelf) { pending = it }
                EonaPlusDivider(Modifier.padding(horizontal = spacing.lg))
                ActionRow(AccountAction.RevokeSessions, working, !unavailable && !isSelf) { pending = it }
            }
            if (isSelf) Footnote("Gestion de tes propres droits désactivée ici.", indent = true)
        }

        pending?.let { action ->
            ConfirmDialog(
                title = action.confirmation,
                message = if (action == AccountAction.Save) "Nom affiché : $displayName. Rôle : ${AdminFormat.role(role)}." else action.explanation,
                confirm = action.title,
                destructive = action.destructive,
                onCancel = { pending = null },
            ) {
                pending = null
                scope.launch { perform(action) }
            }
        }
    }
}

@Composable
private fun ActionRow(action: AccountAction, working: Boolean, enabled: Boolean, onClick: (AccountAction) -> Unit) {
    val spacing = EonaTheme.spacing
    val color = if (!enabled) EonaPlusStyle.muted else if (action.destructive) EonaPlusStyle.peach else EonaPlusStyle.lavender
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(enabled = enabled, role = Role.Button) { onClick(action) }
            .padding(spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        EonaIcon(action.icon, contentDescription = null, tint = color, size = 20.dp)
        EonaText(action.title, style = EonaTheme.typography.bodyStrong, color = color, modifier = Modifier.weight(1f))
        if (working) CircularProgressIndicator(Modifier.size(16.dp), color = EonaPlusStyle.lavender, strokeWidth = 2.dp)
    }
}

@Composable
private fun ConfirmDialog(title: String, message: String, confirm: String, destructive: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onCancel,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        EonaConfirmDialog(title = title, message = message, confirmLabel = confirm, onCancel = onCancel, onConfirm = onConfirm, destructive = destructive)
    }
}

// ---- Bannissements --------------------------------------------------------------------------

@Composable
private fun BansScreen(api: AdminApi, access: AdminAccess, allowed: Boolean, myId: String?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val spacing = EonaTheme.spacing
    var bans by remember { mutableStateOf(listOf<AdminBanRecord>()) }
    var total by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var workingId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<AdminBanRecord?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    suspend fun load() {
        val token = AccountRepository.token ?: return
        if (loading) return
        loading = true
        try {
            val page = api.bans(token)
            if (token != AccountRepository.token) return
            bans = page.bans
            total = page.total
            loaded = true
            message = null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            access.receive(e)?.let { message = it }
        } finally {
            loading = false
        }
    }
    LaunchedEffect(refresh, allowed) { if (allowed) load() }

    Box {
        AdminPage("Bannissements", onBack, allowed, access.denied) {
            if (loaded) EonaPlusLabel("${AdminFormat.count(total)} bannissements")
            if (loading && bans.isEmpty()) AdminLoading()
            when {
                message != null -> AdminNotice("Bannissements", message!!) { refresh += 1 }
                loaded && bans.isEmpty() -> AdminNotice("Aucun bannissement", "Registre vide.")
            }
            bans.forEach { record ->
                EonaPlusGroup {
                    Column(Modifier.fillMaxWidth().padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                        EonaText(
                            record.displayName ?: if (record.accountExists) "Compte" else "Compte supprimé",
                            style = EonaTheme.typography.bodyStrong,
                            color = EonaPlusStyle.primary,
                        )
                        record.email?.let { EonaText(it, style = EonaTheme.typography.subhead, color = EonaPlusStyle.secondary) }
                        Footnote(AdminFormat.date(record.bannedAt))
                        record.reason?.takeIf { it.isNotEmpty() }?.let { EonaText(it, style = EonaTheme.typography.subhead, color = EonaPlusStyle.secondary) }
                        Footnote("${record.deviceCount} appareils connus · ${if (record.accountExists) "Compte conservé" else "Compte source supprimé"}")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            if (workingId == record.accountId) CircularProgressIndicator(Modifier.size(16.dp), color = EonaPlusStyle.lavender, strokeWidth = 2.dp)
                            AdminLink("Retirer bannissement", enabled = !loading && workingId == null && record.accountId != myId) { pending = record }
                        }
                    }
                }
            }
        }

        pending?.let { record ->
            ConfirmDialog(
                title = "Retirer bannissement ?",
                message = "Bannissement du compte, email et appareils connus retiré. Suspension séparée conservée.",
                confirm = "Retirer",
                destructive = false,
                onCancel = { pending = null },
            ) {
                pending = null
                scope.launch {
                    val token = AccountRepository.token ?: return@launch
                    workingId = record.accountId
                    try {
                        api.action(record.accountId, "unban", token = token)
                        load()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        access.receive(e)?.let { message = it }
                    } finally {
                        workingId = null
                    }
                }
            }
        }
    }
}

// ---- Créer un compte ------------------------------------------------------------------------

@Composable
private fun CreateScreen(api: AdminApi, access: AdminAccess, allowed: Boolean, onBack: () -> Unit, onCreated: () -> Unit) {
    val scope = rememberCoroutineScope()
    val spacing = EonaTheme.spacing
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("client") }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    val ready = !working && allowed && username.isNotBlank() && email.isNotBlank() && password.isNotEmpty()
    // Mot de passe oublié en quittant l'écran.
    DisposableEffect(Unit) { onDispose { password = "" } }

    Box {
        AdminPage("Créer un compte", onBack, allowed, access.denied) {
            AdminSection("Nouveau compte") {
                AdminField(username, { username = it.trim() }, "Pseudo", enabled = !working)
                EonaPlusDivider(Modifier.padding(horizontal = spacing.lg))
                AdminField(email, { email = it.trim() }, "Email", enabled = !working, keyboardType = KeyboardType.Email)
                EonaPlusDivider(Modifier.padding(horizontal = spacing.lg))
                AdminField(password, { password = it }, "Mot de passe", enabled = !working, password = true, keyboardType = KeyboardType.Password)
                EonaPlusDivider(Modifier.padding(horizontal = spacing.lg))
                Box(Modifier.padding(spacing.lg)) {
                    AdminPills(listOf("guest" to "Invité", "client" to "Client", "admin" to "Admin"), role, enabled = !working) { role = it }
                }
            }
            message?.let { AdminNotice("Création impossible", it) }
            AdminLink(if (working) "Création…" else "Créer compte", enabled = ready) { confirming = true }
        }

        if (confirming) {
            ConfirmDialog(
                title = "Créer ce compte ?",
                message = "$username · $email · ${AdminFormat.role(role)}.",
                confirm = "Créer",
                destructive = false,
                onCancel = { confirming = false },
            ) {
                confirming = false
                scope.launch {
                    val token = AccountRepository.token ?: return@launch
                    working = true
                    try {
                        api.create(username.trim(), email.trim(), password, role, token)
                        password = ""
                        onCreated()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        access.receive(e)?.let { message = it }
                    } finally {
                        working = false
                    }
                }
            }
        }
    }
}
