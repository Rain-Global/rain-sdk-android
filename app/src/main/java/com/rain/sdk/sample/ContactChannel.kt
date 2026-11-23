package com.rain.sdk.sample

/**
 * The channel a one-time login code goes out on, for the two tabs that offer both: the Rain Wallet
 * card and the bring-your-own Turnkey card. The per-channel copy lives here so the send and confirm
 * flows stay free of channel branches.
 */
enum class ContactChannel(
    val label: String,
    val fieldLabel: String,
    val inboxHint: String,
    val codePlaceholder: String,
) {
    Email("Email", "Email", "check your email", "Code from your email"),
    Phone("Phone", "Phone number", "check your text messages", "Code from your text message"),
    ;

    /** The contact for logs: an email address or a phone number, masked. */
    fun mask(contact: String): String = when (this) {
        Email -> SampleLog.maskEmail(contact)
        Phone -> SampleLog.maskPhone(contact)
    }

    /** Email compares ignoring case; a phone number compares on its `+` and digits only. */
    fun sameContact(recorded: String, typed: String): Boolean = when (this) {
        Email -> recorded.trim().equals(typed.trim(), ignoreCase = true)
        Phone -> recorded.phoneKey() == typed.phoneKey()
    }

    /**
     * The contact as the wallet backend keys the account on it: an email is trimmed and lowercased,
     * since the backend matches it case-sensitively and a second spelling would be a second account;
     * a phone number is trimmed. The direct Turnkey card uses it on the send and on the verify call
     * alike, so the two cannot disagree when the field was edited in between.
     */
    fun canonical(contact: String): String = when (this) {
        Email -> contact.trim().lowercase()
        Phone -> contact.trim()
    }

    companion object {
        /** The recorded channel; blank or unknown reads as email, which is all an older record kept. */
        fun fromRecordOrEmail(name: String): ContactChannel = entries.firstOrNull { it.name == name } ?: Email
    }
}

/** The part of a phone number that identifies it: the leading `+` and the digits. */
private fun String.phoneKey(): String = filter { it == '+' || it.isDigit() }
