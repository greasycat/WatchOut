package io.greasycat.watchout

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Tiny embedded HTTP server for "direct" transport. The dev machine POSTs the
 * event JSON straight to the phone (LAN / Tailscale). GET /ping is the
 * connectivity probe.
 *
 * If [token] is non-empty, POSTs must carry a matching `X-WatchOut-Token` header,
 * so a random device on the LAN/Tailscale can't inject fake status/buzzes. Empty
 * token = no auth (legacy).
 */
class DirectServer(
    port: Int,
    private val token: String,
    private val onPayload: (Map<String, String>) -> Unit,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.GET) {
            return newFixedLengthResponse("WatchOut")
        }
        if (session.method == Method.POST) {
            // Header names arrive lower-cased from NanoHTTPD.
            if (token.isNotEmpty() && session.headers["x-watchout-token"] != token) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "bad token")
            }
            return try {
                val files = HashMap<String, String>()
                session.parseBody(files) // raw JSON body lands under "postData"
                val json = files["postData"].orEmpty()
                val obj = JSONObject(json)
                val map = obj.keys().asSequence().associateWith { obj.optString(it) }
                onPayload(map)
                newFixedLengthResponse("ok")
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "bad request")
            }
        }
        return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "POST only")
    }
}
