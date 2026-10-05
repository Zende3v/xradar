package com.eona.app.core.model

import java.util.UUID

/** Véhicule garé : ce que montre le repère (iOS Parking.swift). */
enum class ParkedVehicle(val wire: String, val label: String) {
    Car("car", "Voiture"),
    Motorcycle("motorcycle", "Moto"),
    Bicycle("bicycle", "Vélo"),
    Scooter("scooter", "Trottinette");

    companion object {
        fun fromWire(value: String?): ParkedVehicle = entries.firstOrNull { it.wire == value } ?: Car
    }
}

/** Repère de stationnement : position fixe, heure, véhicule. Gardé sur ce téléphone seulement. */
data class ParkingSpot(
    val id: String = UUID.randomUUID().toString(),
    val lat: Double,
    val lon: Double,
    val parkedAtMillis: Long,
    val vehicle: ParkedVehicle,
) {
    companion object {
        /** "à l'instant", "il y a 12 min", "il y a 3 h", "il y a 2 j". */
        fun ageLabel(sinceMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
            val minutes = ((nowMillis - sinceMillis) / 60_000).coerceAtLeast(0)
            if (minutes < 1) return "à l'instant"
            if (minutes < 60) return "il y a $minutes min"
            val hours = minutes / 60
            if (hours < 24) return "il y a $hours h"
            return "il y a ${hours / 24} j"
        }
    }
}
