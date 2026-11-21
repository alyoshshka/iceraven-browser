/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.webextensions.BuiltInWebExtensionController
import org.json.JSONObject
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

/** The kind of proxy a profile is routed through. */
enum class ProxyType { NONE, SOCKS5, HTTP }

/** Proxy of one profile: a local proxy app, e.g. on 127.0.0.1. */
data class ProfileProxySettings(
    val type: ProxyType = ProxyType.NONE,
    val host: String = ProfileProxy.DEFAULT_HOST,
    val port: Int = 0,
) {
    /** Whether requests of the profile are routed through a proxy. */
    val isActive: Boolean get() = type != ProxyType.NONE && port in 1..65535
}

/**
 * Profiles: a proxy per profile. Requests of a profile with a proxy go through it (SOCKS5 with remote DNS, or HTTP) and
 * fail when the proxy is unreachable: there is never a fallback to a direct connection. The Default profile is always
 * direct. The routing itself is done by the built-in "raven-proxy" extension (a proxy channel filter keyed by the
 * profile's context id); this object stores the settings and sends them to the extension.
 *
 * Settings live in their own shared preferences, read off the main thread by [start].
 */
object ProfileProxy {
    const val DEFAULT_HOST = "127.0.0.1"
    private const val PREFS = "profiles_proxy"
    private const val KEY_SETTINGS = "settings"
    private const val EXTENSION_ID = "raven-proxy@ravenprofiles"
    private const val EXTENSION_URL = "resource://android/assets/extensions/raven-proxy/"
    private const val MESSAGING_ID = "ravenProxy"
    private const val CONNECT_TIMEOUT_MS = 1500
    private const val NOT_READY_WARNING_MS = 20_000L
    private val logger = Logger("ProfileProxy")

    private val _settings = MutableStateFlow<Map<String, ProfileProxySettings>?>(null)

    private val lock = Any()
    private var port: Port? = null // guarded by lock, as are pending and sentOnPort
    private var pending = 0 // configs sent and not yet acknowledged on the current port
    private var sentOnPort = false
    private val _ready = MutableStateFlow(false)

    /**
     * Whether the proxy extension is running (its background port is connected) and has acknowledged the current
     * config, i.e. whether requests of proxied profiles are certain to be routed. See [ProxyGateMiddleware].
     */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private fun updateReady() { // call with lock held
        _ready.value = port != null && sentOnPort && pending == 0
        logger.debug("Proxy extension: ready=${_ready.value} (port=${port != null} sent=$sentOnPort pending=$pending)")
    }

    /**
     * Whether an engine session may be created for a tab of the profile [contextId] now: the settings must be loaded,
     * and a profile with a proxy also needs the extension to be [ready]. Null [contextId] (Default) is always allowed.
     */
    fun isSessionAllowed(contextId: String?): Boolean {
        if (contextId == null) return true
        val loaded = _settings.value ?: return false
        return !(loaded[contextId]?.isActive == true) || _ready.value
    }

    /** Proxy settings by contextId (only profiles with a proxy; null until loaded). */
    val settings: StateFlow<Map<String, ProfileProxySettings>?> = _settings.asStateFlow()

    /** The proxy of the profile [contextId] (type NONE if it has none). */
    fun get(contextId: String): ProfileProxySettings = _settings.value?.get(contextId) ?: ProfileProxySettings()

    /** Stores the proxy of [contextId]; a type of NONE removes it. */
    fun set(context: Context, contextId: String, proxy: ProfileProxySettings) {
        update(context) { if (proxy.isActive) it + (contextId to proxy) else it - contextId }
    }

    /** Forgets the proxy of a deleted profile. */
    fun remove(context: Context, contextId: String) {
        update(context) { it - contextId }
    }

    private fun update(context: Context, change: (Map<String, ProfileProxySettings>) -> Map<String, ProfileProxySettings>) {
        val updated = change(_settings.value.orEmpty())
        if (updated == _settings.value) return
        _settings.value = updated
        prefs(context).edit().putString(KEY_SETTINGS, toJson(updated).toString()).apply()
    }

    private fun load(context: Context) {
        if (_settings.value != null) return
        val loaded = runCatching {
            val json = JSONObject(prefs(context).getString(KEY_SETTINGS, "{}").orEmpty())
            json.keys().asSequence().mapNotNull { id ->
                val o = json.getJSONObject(id)
                val type = runCatching { ProxyType.valueOf(o.getString("type")) }.getOrDefault(ProxyType.NONE)
                val proxy = ProfileProxySettings(type, o.optString("host", DEFAULT_HOST), o.optInt("port"))
                if (proxy.isActive) id to proxy else null
            }.toMap()
        }.getOrDefault(emptyMap())
        if (_settings.value == null) _settings.value = loaded
    }

    private fun toJson(map: Map<String, ProfileProxySettings>) = JSONObject().also { json ->
        map.forEach { (id, p) ->
            json.put(id, JSONObject().put("type", p.type.name).put("host", p.host).put("port", p.port))
        }
    }

    /**
     * What GeckoView puts into a request's origin attributes for the session context id [contextId]
     * (GeckoSession: "gvctx" + hex of the id's bytes).
     */
    internal fun safeContextId(contextId: String): String =
        if (contextId.isEmpty()) {
            "gvctxempty"
        } else {
            String.format("gvctx%x", BigInteger(contextId.toByteArray())).lowercase(Locale.ROOT)
        }

    /** The message for the extension: profiles keyed by raw and by "safe" context id. */
    internal fun configMessage(map: Map<String, ProfileProxySettings>): JSONObject {
        val profiles = JSONObject()
        map.forEach { (id, p) ->
            val spec = JSONObject()
                .put("type", if (p.type == ProxyType.SOCKS5) "socks" else "http")
                .put("host", p.host)
                .put("port", p.port)
            profiles.put(id, spec)
            profiles.put(safeContextId(id), spec)
        }
        return JSONObject().put("type", "config").put("profiles", profiles)
    }

    private fun send(map: Map<String, ProfileProxySettings>) {
        synchronized(lock) {
            val connected = port ?: return
            runCatching { connected.postMessage(configMessage(map)) }
                .onSuccess {
                    pending++
                    sentOnPort = true
                    updateReady()
                }
                .onFailure { logger.warn("Could not send the proxy config", it) }
        }
    }

    /**
     * Loads the settings off the main thread, installs the routing extension and keeps it up to date. Call once from
     * the application, as early as possible: until the extension runs, profile requests are not proxied.
     */
    fun start(context: Context, engine: Engine, scope: CoroutineScope) {
        val appContext = context.applicationContext
        val controller = BuiltInWebExtensionController(EXTENSION_ID, EXTENSION_URL, MESSAGING_ID)
        controller.registerBackgroundMessageHandler(
            object : MessageHandler {
                override fun onPortConnected(port: Port) {
                    synchronized(lock) {
                        ProfileProxy.port = port
                        pending = 0
                        sentOnPort = false
                        updateReady()
                    }
                    _settings.value?.let { send(it) }
                }

                override fun onPortDisconnected(port: Port) {
                    synchronized(lock) {
                        ProfileProxy.port = null
                        pending = 0
                        sentOnPort = false
                        updateReady()
                    }
                }

                override fun onPortMessage(message: Any, port: Port) {
                    logger.debug("Proxy extension: $message")
                    val type = runCatching { JSONObject(message.toString()).optString("type") }.getOrNull()
                    if (type == "ack") {
                        synchronized(lock) {
                            // (not compared with the connected port: the engine may hand out another wrapper object)
                            pending = maxOf(0, pending - 1)
                            updateReady()
                            logger.debug("Proxy extension ready=${_ready.value} pending=$pending")
                        }
                    }
                }
            },
        )
        scope.launch {
            delay(NOT_READY_WARNING_MS)
            if (!_ready.value) {
                logger.warn("The proxy extension is not ready: profiles with a proxy will not load until it is")
            }
        }
        scope.launch(Dispatchers.Main) {
            controller.install(
                engine,
                onSuccess = { logger.debug("Installed the proxy extension") },
                onError = { logger.error("Could not install the proxy extension", it) },
            )
            withContext(Dispatchers.IO) { load(appContext) } // shared preferences read: not on the main thread
            settings.filterNotNull().map { toJson(it).toString() }.distinctUntilChanged().collect {
                _settings.value?.let { current -> send(current) }
            }
        }
    }

    /** Whether something accepts TCP connections on [host]:[port] (about 1.5 s at most). */
    suspend fun checkReachable(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
            true
        }.getOrDefault(false)
    }

    // Always through the application context, whose preferences cache was filled off the main thread by [start].
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
