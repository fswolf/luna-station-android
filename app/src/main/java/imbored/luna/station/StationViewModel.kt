package imbored.luna.station

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import imbored.luna.station.audio.ClipPlayer
import imbored.luna.station.audio.Recorder
import imbored.luna.station.audio.Wav
import imbored.luna.station.station.LunaCa
import imbored.luna.station.station.NotPairedError
import imbored.luna.station.station.PairLink
import imbored.luna.station.station.Pairing
import imbored.luna.station.station.StationClient
import imbored.luna.station.station.StationError
import imbored.luna.station.station.StationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Which screen is showing, the pairing behind it, and the conversation. Survives rotation;
 * the conversation lives only here (no history kept on the device).
 */
class StationViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface Screen {
        data class Unpaired(val note: String? = null) : Screen
        data object Scanning : Screen
        /** Keeps what was typed, so a failed attempt doesn't clear the boxes. */
        data class Typing(val note: String? = null, val address: String = "", val code: String = "") : Screen
        data class Working(val step: String) : Screen
        data class Paired(val host: String, val fingerprint: String, val link: Link) : Screen
    }

    /** How the paired PC is doing, as far as this device can tell. */
    sealed interface Link {
        data object Checking : Link
        data object Connected : Link
        data object Removed : Link           // unpaired on the PC
        data class Down(val why: String) : Link
    }

    /** One bubble. [waiting]: shown as "…" until the words arrive. [cut]: stopped part way. */
    data class Message(
        val id: Int, val mine: Boolean, val text: String, val waiting: Boolean = false, val cut: Boolean = false,
        val file: String? = null,            // the name of a file sent with it
    )

    /** A file picked to go with the next message (its bytes stay in the view model). */
    data class Picked(val name: String, val size: Long, val image: Boolean)

    data class Chat(
        val messages: List<Message> = emptyList(),
        val busy: Boolean = false,           // a turn is running: her bubble shows Stop, the mic and attach are off
        val recording: Boolean = false,
        val status: String = "",
        val note: String? = null,            // the last thing that went wrong
        val ask: StationClient.Ask? = null,
        val attachments: StationClient.Attachments? = null,   // what this Luna takes; null: no attach button
        val picked: Picked? = null,
    )

    private val store = StationStore(app)
    private var pairing: Pairing? = store.load()
    private val _screen = MutableStateFlow<Screen>(pairing?.let { paired(it, Link.Checking) } ?: Screen.Unpaired())
    val screen = _screen.asStateFlow()
    private val _chat = MutableStateFlow(Chat())
    val chat = _chat.asStateFlow()

    private val player = ClipPlayer { turn, n ->
        val p = pairing ?: return@ClipPlayer
        viewModelScope.launch(Dispatchers.IO) { runCatching { StationClient.playing(p, turn, n) } }
    }
    private var recorder: Recorder? = null
    private var turnJob: Job? = null
    private var turnId: String? = null
    private var checking = false            // all of these are only touched on the main thread
    private var nextId = 0
    private var file: StationClient.Attachment? = null    // the picked file's bytes, sent with the next message

    // --- pairing -------------------------------------------------------------------------

    fun scan() { _screen.value = Screen.Scanning }
    fun type() { _screen.value = Screen.Typing() }
    fun back() { _screen.value = Screen.Unpaired() }
    fun noCamera(why: String = "No camera to scan with. Type the address and code instead.") {
        _screen.value = Screen.Unpaired(why)
    }

    fun cameraDenied() {
        _screen.value = Screen.Unpaired(
            "Luna isn't allowed to use the camera. Allow it in Settings, Apps, Luna, Permissions, or type the address and code instead."
        )
    }

    fun scanned(text: String) {
        val link = PairLink.parse(text)
        if (link == null) {
            _screen.value = Screen.Unpaired("That QR code isn't a Luna pairing code (or her PC isn't on a home address).")
            return
        }
        pair(link) { Screen.Unpaired(it) }
    }

    fun typed(address: String, code: String) {
        val link = PairLink.typed(address, code)
        if (link == null) {
            _screen.value = Screen.Typing("Check the address (a home address like 192.168.1.20) and the code.", address, code)
            return
        }
        pair(link) { Screen.Typing(it, address, code) }
    }

    private fun pair(link: PairLink, onError: (String) -> Screen) {
        if (_screen.value is Screen.Working) return
        viewModelScope.launch {
            try {
                _screen.value = Screen.Working("Getting Luna's certificate from ${link.host}")
                val ca = withContext(Dispatchers.IO) { StationClient.fetchCa(link) }
                _screen.value = Screen.Working("Pairing")
                val token = withContext(Dispatchers.IO) { StationClient.pair(link, ca) }
                val p = Pairing(link.host, link.stationPort, ca, token)
                withContext(Dispatchers.IO) { store.save(p) }
                pairing = p
                _chat.value = Chat()
                _screen.value = paired(p, Link.Connected)
            } catch (e: StationError) {
                _screen.value = onError(e.message ?: "Pairing failed.")
            } catch (e: Exception) {
                _screen.value = onError("Pairing failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Called whenever the conversation comes to the front, and from the info box. */
    fun check() {
        val p = pairing ?: return
        if (checking) return
        checking = true
        _screen.value = paired(p, Link.Checking)
        viewModelScope.launch {
            val link = try {
                when (val r = withContext(Dispatchers.IO) { StationClient.check(p) }) {
                    StationClient.Check.Connected -> Link.Connected
                    StationClient.Check.Unpaired -> Link.Removed
                    is StationClient.Check.Unreachable -> Link.Down(r.why)
                }
            } catch (e: Exception) {
                Link.Down("Couldn't check: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                checking = false
            }
            if (pairing == p) _screen.value = paired(p, link)
            if (link == Link.Connected) learn(p)
        }
    }

    /** What this Luna can take (GET /info). An older Luna, or a failed look: no attach button. */
    private suspend fun learn(p: Pairing) {
        val info = try {
            withContext(Dispatchers.IO) { StationClient.info(p) }.also {
                // logcat (tag LunaStation): why there is or isn't a paperclip
                android.util.Log.i("LunaStation", if (it == null) "GET /info: 404, an older Luna (no attachments)" else "GET /info: $it")
            }
        } catch (e: Exception) {
            android.util.Log.w("LunaStation", "GET /info failed: ${e.message ?: e.javaClass.simpleName}")
            null
        }
        if (pairing == p) _chat.update { it.copy(attachments = info?.attachments) }
    }

    fun forget() {
        leave()
        stop()
        turnJob?.cancel()          // the old turn must not write into the cleared conversation
        finish(null)
        store.clear()
        file = null
        pairing = null
        _chat.value = Chat()
        _screen.value = Screen.Unpaired()
    }

    // --- talking to her ------------------------------------------------------------------

    fun send(text: String) {
        val words = text.trim()
        val p = pairing ?: return
        val f = file
        if ((words.isEmpty() && f == null) || _chat.value.busy || recorder != null) return
        player.hush()
        file = null
        _chat.update { it.copy(picked = null) }
        begin(Message(nextId++, mine = true, text = words, file = f?.name)) {
            if (f == null) StationClient.say(p, words) else StationClient.say(p, words, f)
        }
    }

    /**
     * A file picked to go with the next message. Checked here against what Luna said she takes
     * (type and size), so a no is instant and in plain words; she checks again on her side.
     */
    fun attach(uri: Uri) {
        val rules = _chat.value.attachments ?: return
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    var name = "file"
                    var size = -1L
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            c.getString(0)?.let { name = it }
                            if (!c.isNull(1)) size = c.getLong(1)
                        }
                    }
                    val type = typeOf(resolver.getType(uri), name, rules)
                        ?: return@runCatching "Luna can't take that kind of file. Pictures (JPEG, PNG, WebP), text, Markdown or PDF."
                    val max = rules.maxBytes(type)
                    if (size > max) return@runCatching tooBig(name, max)
                    val bytes = resolver.openInputStream(uri)?.use { readUpTo(it, max + 1) }
                        ?: return@runCatching "Couldn't open $name."
                    if (bytes.size > max) return@runCatching tooBig(name, max)
                    StationClient.Attachment(name, type, bytes)
                }.getOrElse { "Couldn't read that file: ${it.message ?: it.javaClass.simpleName}" }
            }
            when (result) {
                is StationClient.Attachment -> {
                    file = result
                    val image = rules.isImage(result.type)
                    _chat.update {
                        it.copy(
                            picked = Picked(result.name, result.bytes.size.toLong(), image),
                            note = if (image && rules.canSee == false) "Luna can't see pictures with the model she has loaded now." else null,
                        )
                    }
                }
                is String -> _chat.update { it.copy(note = result) }
            }
        }
    }

    /** The x on the picked file. */
    fun detach() {
        file = null
        _chat.update { it.copy(picked = null) }
    }

    /** The file's type as Luna names it: what Android says, or else its extension. */
    private fun typeOf(said: String?, name: String, rules: StationClient.Attachments): String? {
        if (said != null && rules.allows(said)) return said
        val byName = when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"
            "txt" -> "text/plain"; "md", "markdown" -> "text/markdown"; "pdf" -> "application/pdf"
            else -> null
        }
        return byName?.takeIf { rules.allows(it) }
    }

    private fun tooBig(name: String, max: Long) =
        "$name is too big for Luna (up to ${if (max >= 1 shl 20) "${max shr 20} MB" else "${max shr 10} KB"})."

    private fun readUpTo(input: java.io.InputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), limit - out.size()).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** The mic button: start listening, or (while listening) send what was said. */
    fun talk() {
        val p = pairing ?: return
        recorder?.let { it.finish(); return }
        if (_chat.value.busy) return
        player.hush()
        val r = Recorder()
        recorder = r
        _chat.update { it.copy(recording = true, status = "Listening. Tap the mic to send.", note = null) }
        viewModelScope.launch {
            val pcm = try {
                withContext(Dispatchers.IO) { r.record() }
            } catch (e: Exception) {
                _chat.update { it.copy(note = e.message ?: "The microphone failed.") }
                null
            } finally {
                recorder = null
                _chat.update { it.copy(recording = false, status = "") }
            }
            if (pcm == null) {
                if (_chat.value.note == null) _chat.update { it.copy(status = "Didn't hear anything.") }
                return@launch
            }
            val wav = Wav.encode(pcm, Recorder.RATE)
            begin(Message(nextId++, mine = true, text = "…", waiting = true)) { StationClient.talk(p, wav) }
        }
    }

    /** The app went to the background: never leave the mic open behind the user's back. */
    fun leave() {
        recorder?.cancel()
    }

    /** Stop: she stops writing and talking; the reply so far stays, marked as stopped. */
    fun stop() {
        player.hush()
        val p = pairing ?: return
        val id = turnId ?: return
        _chat.update { it.copy(status = "Stopping") }
        viewModelScope.launch(Dispatchers.IO) { runCatching { StationClient.stop(p, id) } }
    }

    fun answer(allow: Boolean) {
        val p = pairing ?: return
        val id = turnId ?: return
        _chat.update { it.copy(ask = null) }
        viewModelScope.launch(Dispatchers.IO) { runCatching { StationClient.answer(p, id, allow) } }
    }

    private fun begin(mine: Message, start: () -> String) {
        val p = pairing ?: return
        _chat.update { it.copy(messages = it.messages + mine, busy = true, status = "Sending", note = null) }
        turnJob = viewModelScope.launch {
            val id = try {
                withContext(Dispatchers.IO) { start() }
            } catch (e: Exception) {
                edit(mine.id) { if (it.waiting) it.copy(text = "(not sent)", waiting = false) else it }
                return@launch finish(problem(e))
            }
            turnId = id
            follow(p, id, mine.id)
        }
    }

    /** Poll the turn like the web station does, playing her clips as they come. */
    private suspend fun follow(p: Pairing, id: String, mineId: Int) {
        var played = 0
        var lost = 0
        var herId: Int? = null
        while (viewModelScope.isActive) {
            delay(350)
            val t = try {
                withContext(Dispatchers.IO) { StationClient.poll(p, id) }
            } catch (e: NotPairedError) {
                return finish(problem(e))
            } catch (e: Exception) {
                if (++lost > 40) return finish("Lost touch with Luna. Is the PC awake?")
                continue
            }
            lost = 0
            if (t == null) return finish(null)        // forgotten on the PC: nothing more to show
            t.heard?.let { heard ->
                edit(mineId) { if (it.waiting) it.copy(text = heard.ifEmpty { "(didn't catch that)" }, waiting = false) else it }
            }
            if (t.reply.isNotEmpty() || t.state == "thinking") {
                val her = herId ?: nextId++.also { new ->
                    herId = new
                    _chat.update { it.copy(messages = it.messages + Message(new, mine = false, text = "…", waiting = true)) }
                }
                if (t.reply.isNotEmpty()) edit(her) { it.copy(text = t.reply, waiting = false) }
            }
            if (t.stopped) herId?.let { her -> edit(her) { it.copy(cut = true) } }
            if (t.error.isNotEmpty() && !t.error.startsWith("no voice")) return finish(t.error)
            _chat.update {
                it.copy(
                    status = when (t.state) {
                        "hearing" -> "Listening to you"; "thinking" -> "Thinking"; "speaking" -> "Speaking"; else -> it.status
                    },
                    ask = t.ask,
                )
            }
            if (!t.stopped) while (played < t.clips) {
                val n = played
                withContext(Dispatchers.IO) { runCatching { StationClient.clip(p, id, n) }.getOrNull() }?.let { player.add(it, id, n) }
                played++
            }
            if (t.state == "done" && (t.stopped || played >= t.clips)) {
                if (!t.stopped) while (!player.idle) delay(100)
                return finish(null)
            }
        }
    }

    /** The turn is over, however it ended: no "…" bubble is left waiting forever. */
    private fun finish(note: String?) {
        turnId = null
        turnJob = null
        _chat.update { c ->
            c.copy(
                messages = c.messages
                    .filterNot { !it.mine && it.waiting }
                    .map { if (it.mine && it.waiting) it.copy(text = "(not heard)", waiting = false) else it },
                busy = false, status = "", ask = null, note = note,
            )
        }
    }

    private fun problem(e: Exception): String {
        if (e is NotPairedError) pairing?.let { _screen.value = paired(it, Link.Removed) }
        return e.message ?: e.javaClass.simpleName
    }

    private fun edit(id: Int, change: (Message) -> Message) =
        _chat.update { c -> c.copy(messages = c.messages.map { if (it.id == id) change(it) else it }) }

    /** What her portrait's WebView needs: where she is, the token and the CA to trust. */
    fun portraitPairing(): Pairing? = pairing

    private fun paired(p: Pairing, link: Link) = Screen.Paired(p.host, LunaCa.fingerprint(p.ca), link)

    override fun onCleared() {
        recorder?.cancel()
        player.close()
    }
}
