package de.lorenzgorse.chatroulette

import kotlinx.serialization.Serializable

@Serializable
sealed class ChatEvent {

    /**
     * Indicates the number of users that are connected to the server. Sent from the
     * server to the client.
     */
    @Serializable
    data class UserCount(val userCount: Long) : ChatEvent()

    /**
     * Indicates the user id that the server assigns a client.
     */
    @Serializable
    data class UserId(val userId: Long) : ChatEvent()

    /**
     * Indicates the identity of a new peer.
     */
    @Serializable
    data class Hello(val user: User) : ChatEvent()

    /**
     * Indicates that the peer has disconnected.
     */
    @Serializable
    object Disconnected : ChatEvent()

    /**
     * Indicates whether the sender is typing.
     */
    @Serializable
    data class IsTyping(val typing: Boolean) : ChatEvent()

    @Serializable
    sealed class Message : ChatEvent() {

        @Serializable
        data class Text(val text: String) : Message()

        /**
         * An image message. The wrapped type is an ArrayBuffer in the browser and a
         * ByteArray on the server. Using a ByteArray in the browser is very slow, thus
         * the distinction.
         *
         * TODO: can we model this in a type-safe way?
         */
        data class Image(val image: Any) : Message()

    }

}

@Serializable
data class User(val id: Long)
