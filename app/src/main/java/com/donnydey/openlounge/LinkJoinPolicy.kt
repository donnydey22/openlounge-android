package com.donnydey.openlounge

import org.json.JSONObject

/**
 * Open Lounge Audio link join (t5993u): a link call never rings, so neither call:outgoing nor Accept starts
 * the call foreground service. The page's connect op carries link:true and the coordinator starts the
 * microphone foreground service at that tap, before Connecting. No Telecom on this path.
 */
internal object LinkJoinPolicy {
    fun micFgsBeforeConnect(connectCmd: JSONObject): Boolean = connectCmd.optBoolean("link", false)
}
