package com.xradar.app.data.routing

import com.xradar.app.core.model.GeoPoint
import com.xradar.app.core.model.Route
import com.xradar.app.data.account.AccessDenial
import com.xradar.app.data.account.AccessDeniedException
import kotlin.coroutines.cancellation.CancellationException

/** A route, or why there is none: refused for the account's access, or simply not obtained. */
sealed interface RouteAnswer {
    data class Found(val route: Route) : RouteAnswer
    data class Denied(val denial: AccessDenial) : RouteAnswer
    data object Failed : RouteAnswer

    val routeOrNull: Route? get() = (this as? Found)?.route
}

class RoutingRepository(private val api: RoutingApi = RoutingApi()) {
    suspend fun route(from: GeoPoint, to: GeoPoint, avoid: List<String> = emptyList()): RouteAnswer = try {
        api.route(from, to, avoid)?.let { RouteAnswer.Found(it) } ?: RouteAnswer.Failed
    } catch (e: AccessDeniedException) {
        RouteAnswer.Denied(e.denial)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RouteAnswer.Failed
    }
}
