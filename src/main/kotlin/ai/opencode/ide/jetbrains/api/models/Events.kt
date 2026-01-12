package ai.opencode.ide.jetbrains.api.models

import com.google.gson.*
import com.google.gson.annotations.SerializedName
import java.lang.reflect.Type

/**
 * Base class for OpenCode SSE events.
 */
sealed class OpenCodeEvent {
    abstract val type: String
}

/**
 * Event fired when a session produces new diffs.
 */
data class SessionDiffEvent(
    @SerializedName("type")
    override val type: String = "session.diff",
    val properties: SessionDiffProperties
) : OpenCodeEvent()

data class SessionDiffProperties(
    val sessionID: String,
    val diff: List<FileDiff>
) {
    /**
     * Convert to DiffBatch for proper tracking.
     */
    fun toDiffBatch(): DiffBatch {
        return DiffBatch(
            sessionId = sessionID,
            messageId = null,
            diffs = diff
        )
    }
}

/**
 * Event fired when a session becomes idle (conversation turn complete).
 */
data class SessionIdleEvent(
    @SerializedName("type")
    override val type: String = "session.idle",
    val properties: SessionIdleProperties
) : OpenCodeEvent()

data class SessionIdleProperties(
    val sessionID: String
)

/**
 * Event fired when a file is edited.
 */
data class FileEditedEvent(
    @SerializedName("type")
    override val type: String = "file.edited",
    val properties: FileEditedProperties
) : OpenCodeEvent()

data class FileEditedProperties(
    val file: String
)

/**
 * Event fired when session status changes (busy/idle/retry).
 */
data class SessionStatusEvent(
    @SerializedName("type")
    override val type: String = "session.status",
    val properties: SessionStatusProperties
) : OpenCodeEvent()

data class SessionStatusProperties(
    val sessionID: String,
    val status: SessionStatusType
)

/**
 * Event fired when session is updated.
 */
data class SessionUpdatedEvent(
    @SerializedName("type")
    override val type: String = "session.updated",
    val properties: SessionUpdatedProperties
) : OpenCodeEvent()

data class SessionUpdatedProperties(
    val info: Session
)

/**
 * Event fired when assistant produces/updates a message part.
 */
data class MessagePartUpdatedEvent(
    @SerializedName("type")
    override val type: String = "message.part.updated",
    val properties: MessagePartUpdatedProperties
) : OpenCodeEvent()

data class MessagePartUpdatedProperties(
    val part: JsonElement,
    val delta: String? = null
)

/**
 * Event fired when a message part is removed.
 */
data class MessagePartRemovedEvent(
    @SerializedName("type")
    override val type: String = "message.part.removed",
    val properties: MessagePartRemovedProperties
) : OpenCodeEvent()

data class MessagePartRemovedProperties(
    val sessionID: String,
    val messageID: String,
    val partID: String
)

/**
 * Unknown event type - for events we don't specifically handle.
 */
data class UnknownEvent(
    override val type: String
) : OpenCodeEvent()

/**
 * Custom Gson deserializer for OpenCodeEvent.
 * Routes to the appropriate event class based on the "type" field.
 */
class OpenCodeEventDeserializer : JsonDeserializer<OpenCodeEvent> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): OpenCodeEvent {
        val jsonObject = json.asJsonObject
        val eventType = jsonObject.get("type")?.asString ?: return UnknownEvent("unknown")

        return when (eventType) {
            "session.diff" -> context.deserialize(json, SessionDiffEvent::class.java)
            "session.idle" -> context.deserialize(json, SessionIdleEvent::class.java)
            "session.status" -> context.deserialize(json, SessionStatusEvent::class.java)
            "session.updated" -> context.deserialize(json, SessionUpdatedEvent::class.java)
            "file.edited" -> context.deserialize(json, FileEditedEvent::class.java)
            "message.part.updated" -> context.deserialize(json, MessagePartUpdatedEvent::class.java)
            "message.part.removed" -> context.deserialize(json, MessagePartRemovedEvent::class.java)
            else -> UnknownEvent(eventType)
        }
    }
}
