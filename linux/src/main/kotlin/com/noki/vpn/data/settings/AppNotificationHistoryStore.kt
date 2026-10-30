package com.noki.vpn.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.Instant

internal data class StoredAppNotificationHistory(
    val notifications: List<BackendAppNotification> = emptyList(),
    val hasUnread: Boolean = false,
)

internal object AppNotificationHistoryStore {
    const val MAX_ENTRIES = 50

    fun canPersistForSession(
        isAuthenticated: Boolean,
        accessToken: String?,
        userId: String,
        deviceId: String,
    ): Boolean = isAuthenticated &&
        !accessToken.isNullOrBlank() &&
        userId.isNotBlank() &&
        deviceId.isNotBlank()

    fun mergeState(
        current: StoredAppNotificationHistory,
        notification: BackendAppNotification,
    ): StoredAppNotificationHistory {
        val normalized = normalize(notification)
            ?: return current.copy(notifications = prune(current.notifications))
        val isNewNotification = current.notifications.none { it.id == normalized.id }
        return StoredAppNotificationHistory(
            notifications = prune(
                current.notifications.filterNot { it.id == normalized.id } + normalized,
            ),
            hasUnread = current.hasUnread || isNewNotification,
        )
    }

    fun markRead(current: StoredAppNotificationHistory): StoredAppNotificationHistory =
        current.copy(
            notifications = prune(current.notifications),
            hasUnread = false,
        )

    fun remove(
        current: StoredAppNotificationHistory,
        notificationId: String,
    ): StoredAppNotificationHistory {
        val cleanId = notificationId.trim()
        if (cleanId.isBlank()) return current.copy(notifications = prune(current.notifications))
        val remaining = prune(current.notifications.filterNot { it.id == cleanId })
        return current.copy(
            notifications = remaining,
            hasUnread = current.hasUnread && remaining.isNotEmpty(),
        )
    }

    fun decodeState(raw: String?): StoredAppNotificationHistory {
        if (raw.isNullOrBlank()) return StoredAppNotificationHistory()
        return runCatching {
            when (val root = JSONTokener(raw).nextValue()) {
                is JSONArray -> StoredAppNotificationHistory(
                    notifications = decodeNotifications(root),
                    hasUnread = false,
                )
                is JSONObject -> StoredAppNotificationHistory(
                    notifications = decodeNotifications(
                        root.optJSONArray("notifications") ?: JSONArray(),
                    ),
                    hasUnread = root.optBoolean("hasUnread", false),
                )
                else -> StoredAppNotificationHistory()
            }
        }.getOrDefault(StoredAppNotificationHistory())
    }

    fun encodeState(state: StoredAppNotificationHistory): String = JSONObject()
        .put("hasUnread", state.hasUnread)
        .put(
            "notifications",
            JSONArray().apply {
                prune(state.notifications).forEach { notification ->
                    put(
                        JSONObject()
                            .put("id", notification.id)
                            .put("title", notification.title)
                            .put("message", notification.message)
                            .put("createdAt", notification.createdAt)
                            .put("action", notification.action.orEmpty()),
                    )
                }
            },
        )
        .toString()

    private fun decodeNotifications(array: JSONArray): List<BackendAppNotification> =
        buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                normalize(
                    BackendAppNotification(
                        id = json.optString("id"),
                        title = json.optString("title"),
                        message = json.optString("message"),
                        createdAt = json.optString("createdAt"),
                        action = json.optString("action").takeIf(String::isNotBlank),
                    ),
                )?.let(::add)
            }
        }.let(::prune)

    private fun prune(notifications: List<BackendAppNotification>): List<BackendAppNotification> =
        notifications
            .mapNotNull(::normalize)
            .distinctBy(BackendAppNotification::id)
            .sortedByDescending(BackendAppNotification::createdAt)
            .take(MAX_ENTRIES)

    private fun normalize(notification: BackendAppNotification): BackendAppNotification? {
        val id = notification.id.trim().take(128)
        val message = notification.message.trim().take(4_000)
        if (id.isBlank() || message.isBlank()) return null
        return notification.copy(
            id = id,
            title = notification.title.trim().ifBlank { "Noki" }.take(256),
            message = message,
            createdAt = runCatching { Instant.parse(notification.createdAt.trim()) }
                .getOrDefault(Instant.EPOCH)
                .toString(),
            action = notification.action?.trim()?.takeIf(String::isNotBlank)?.take(128),
        )
    }
}
