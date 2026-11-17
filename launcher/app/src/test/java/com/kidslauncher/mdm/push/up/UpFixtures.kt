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

    override fun pull(acks: List<String>): UpMessagesAnswer? {
        pullAcks += acks
        return if (pulls.isEmpty()) UpMessagesAnswer() else pulls.removeFirst()
    }
}

/** The phone side, recorded: every broadcast as a line, the app states, the deliveries held until [sendAll]. */
class FakeUpPhone : UpPhone {
    val sent = mutableListOf<String>()
    val states = mutableMapOf<String, UpAppState>()
    val delivering = mutableListOf<RaiseToForeground.Message>()
    var onSent: (RaiseToForeground.Message) -> Unit = {}
    var pullsLater = 0

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

    override fun deliver(pkg: String, message: RaiseToForeground.Message) {
        delivering += message
    }

    override fun pullLater(delayMs: Long) {
        pullsLater++
    }

    /** The raise's sends went out: reported to the engine as the runtime does. */
    fun sendAll() {
        val all = delivering.toList()
        delivering.clear()
        all.forEach(onSent)
    }
}
