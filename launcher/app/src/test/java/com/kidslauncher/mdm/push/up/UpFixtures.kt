package com.kidslauncher.mdm.push.up

import com.kidslauncher.mdm.push.up.vendor.RaiseToForeground
import com.kidslauncher.mdm.server.ParentPinStoreTest
import com.kidslauncher.mdm.server.SealedPrefs
import com.kidslauncher.mdm.server.SealedPrefsTest
import com.kidslauncher.mdm.server.ServerJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** `up_rules.json` (design 108's launcher-only rules) and the fakes the UP tests share. */
object UpFixtures {
    val rules: JsonObject by lazy {
        ServerJson.parseToJsonElement(javaClass.classLoader!!.getResource("up_rules.json")!!.readText()).jsonObject
    }

    val rows: List<UpRow> by lazy { rules["rows"]!!.jsonArray.map { ServerJson.decodeFromJsonElement(UpRow.serializer(), it) } }

    /** The fixture row [reg] with [overrides] (snake_case JSON fields) laid over it. */
    fun rowWith(reg: String, overrides: JsonObject): UpRow {
        val base = rules["rows"]!!.jsonArray.map { it.jsonObject }.first { it["reg"]!!.jsonPrimitive.content == reg }
        return ServerJson.decodeFromJsonElement(UpRow.serializer(), JsonObject(base + overrides))
    }

    fun view(name: String): UpView {
        val v = rules["views"]!!.jsonObject[name]!!.jsonObject
        return UpView(
            on = v["on"]!!.jsonPrimitive.content == "true",
            origin = v["origin"]!!.takeIf { it != JsonNull }?.jsonPrimitive?.content,
            allowlist = v["allowlist"]!!.takeIf { it != JsonNull }?.jsonArray?.map { it.jsonPrimitive.content },
            stopped = v["stopped"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    fun strings(element: JsonElement?): List<String> = element?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    fun string(element: JsonElement?): String? = element?.takeIf { it != JsonNull }?.jsonPrimitive?.content

    fun long(element: JsonElement?, default: Long): Long = element?.jsonPrimitive?.long ?: default

    fun row(reg: String, pkg: String, token: String = "tok-$reg", confirmedAt: Long? = 2000) = UpRow(
        reg = reg,
        pkg = pkg,
        token = token,
        secret = "FakeSecretForTheContract" + reg.takeLast(3),
        endpoint = confirmedAt?.let { "https://phones.example.com/up/FakeSecretForTheContract" + reg.takeLast(3) },
        state = if (confirmedAt != null) UP_CONFIRMED else UP_PENDING,
        createdAtMs = 1000,
        confirmedAtMs = confirmedAt,
    )

    fun store(prefs: ParentPinStoreTest.FakePrefs = ParentPinStoreTest.FakePrefs(), box: SealedPrefsTest.FakeBox = SealedPrefsTest.FakeBox()) =
        UpStore(prefs, SealedPrefs(box) { _, _ -> })
}

/** The engine's server, scripted: [registered] answers each REGISTER in turn, [pulls] each pull. */
class FakeUpServer : UpServer {
    val registerBodies = mutableListOf<UpRegisterBody>()
    val registered = ArrayDeque<UpServer.Registered>()
    val unregistered = mutableListOf<String>()
    var unregisterWorks = true
    val pullAcks = mutableListOf<List<String>>()
    val pulls = ArrayDeque<UpMessagesAnswer?>()

    override fun register(body: UpRegisterBody): UpServer.Registered {
        registerBodies += body
        return registered.removeFirstOrNull() ?: UpServer.Registered.Endpoint("https://phones.example.com/up/${body.secret}")
    }

    override fun unregister(reg: String): Boolean {
        if (unregisterWorks) unregistered += reg
        return unregisterWorks
    }

    /** Each pull's `held` (design 115). */
    val pullHeld = mutableListOf<Boolean>()

    override fun pull(acks: List<String>, held: Boolean): UpMessagesAnswer? {
        pullAcks += acks
        pullHeld += held
        return if (pulls.isEmpty()) UpMessagesAnswer() else pulls.removeFirst()
    }
}

/** The phone side, recorded: every broadcast as a line, the app states, the deliveries held until [sendAll]. */
class FakeUpPhone : UpPhone {
    val sent = mutableListOf<String>()
    val states = mutableMapOf<String, UpAppState>()

    /** The NEW_ENDPOINTs sent through the raise (ping, refresh, rewrite): "pkg token endpoint id". */
    val raised = mutableListOf<String>()
    var inUse = true
    val delivering = mutableListOf<RaiseToForeground.Message>()
    var onSent: (RaiseToForeground.Message) -> Unit = {}
    var pullsLater = 0

    /** Design 115: the hold the phone reports, and the "net" pulls asked. */
    var hold = UpHold.NONE
    var pullsAfterHold = 0

    override fun newEndpoint(pkg: String, token: String, endpoint: String, id: String) {
        sent += "NEW_ENDPOINT $pkg $token $endpoint"
    }

    override fun registrationFailed(pkg: String, token: String, reason: String) {
        sent += "REGISTRATION_FAILED $pkg $token $reason"
    }

    override fun unregistered(pkg: String, token: String) {
        sent += "UNREGISTERED $pkg $token"
    }

    override fun appState(pkg: String): UpAppState = states[pkg] ?: UpAppState.INSTALLED

    override fun inUse() = inUse

    override fun deliver(pkg: String, message: RaiseToForeground.Message) {
        if (message.kind == RaiseToForeground.Kind.NEW_ENDPOINT) {
            raised += "$pkg ${message.token} ${message.endpoint} ${message.id}"
            sent += "NEW_ENDPOINT $pkg ${message.token} ${message.endpoint}"
            return
        }
        delivering += message
    }

    override fun pullLater(delayMs: Long) {
        pullsLater++
    }

    override fun hold() = hold

    override fun pullAfterHold() {
        pullsAfterHold++
    }

    /** The raise's sends went out: reported to the engine as the runtime does. */
    fun sendAll() {
        val all = delivering.toList()
        delivering.clear()
        all.forEach(onSent)
    }
}
