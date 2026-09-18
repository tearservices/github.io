package com.lingo.overlay

import android.util.Log
import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Detects what language a message is in and translates it, entirely on device.
 *
 * ML Kit is the engine rather than the Cloud Translation API, and the reason is
 * latency. A cloud call is 100-300 ms of network per message, on a surface that
 * has to keep up with a finger flinging a chat list; the overlay would visibly
 * trail the text it is covering. ML Kit runs the same models Google Translate
 * uses offline, in this process, so a translation that is already in the cache
 * costs a hash lookup.
 *
 * The contract with the scan loop is the important part: [translate] never
 * blocks and never does work. It answers purely from cache, and quietly
 * enqueues anything it has not seen. A message therefore stays as WhatsApp drew
 * it until its translation is genuinely ready, which is why nothing ever
 * flashes an empty box. When results land, [onTranslationsArrived] fires and the
 * service rebuilds the overlay straight out of the cache -- no tree walk.
 */
class TranslationEngine(
    private val onTranslationsArrived: () -> Unit,
    private val onStatus: (Status) -> Unit
) {

    sealed class Status {
        data class Downloading(val language: String) : Status()
        data class Ready(val language: String) : Status()
        data class Failed(val language: String) : Status()
    }

    private val identifier: LanguageIdentifier = LanguageIdentification.getClient()

    /** Callbacks land here, never on the main thread. */
    private val io: ExecutorService = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "lingo-translate").apply { isDaemon = true }
    }

    /**
     * Source text -> translated text, or [SKIP] for text we have decided not to
     * patch. Keyed on the text alone, deliberately: scrolling changes a
     * message's position constantly but never its content, so scrolling back
     * over messages already seen costs nothing.
     */
    private val cache = LruCache<String, String>(CACHE_ENTRIES)

    /** Consecutive failures per string, so a permanently failing one gives up. */
    private val failures = LruCache<String, Int>(128)

    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Source language -> open translator. Closing one frees native memory. */
    private val translators = HashMap<String, Translator>()

    /** Source languages whose model pair is downloaded, so we can skip the check. */
    private val ready: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val downloading: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * The language this chat is mostly in.
     *
     * Language identification is unreliable on very short strings -- "ok", "да",
     * "no" come back undetermined about as often as not. Rather than leave those
     * untranslated in the middle of an otherwise translated conversation, they
     * are assumed to be in whatever the surrounding messages turned out to be.
     */
    @Volatile private var dominantSource: String? = null

    @Volatile private var target: String = TranslateLanguage.ENGLISH

    @Volatile private var closed = false

    /** Number of texts currently being worked on; drives the button's busy look. */
    val pending: Int get() = inFlight.size

    val isDownloading: Boolean get() = downloading.isNotEmpty()

    /**
     * Sets the language everything is translated into. Changing it invalidates
     * every cached result, including the "already in the right language, leave
     * it alone" decisions, so the whole cache goes.
     */
    @Synchronized
    fun setTarget(languageTag: String) {
        val next = TranslateLanguage.fromLanguageTag(languageTag) ?: TranslateLanguage.ENGLISH
        if (next == target) return
        target = next
        cache.evictAll()
        failures.evictAll()
        ready.clear()
        dominantSource = null
        closeTranslators()
    }

    /**
     * Cache-only lookup. Returns the translated text, or null when there is
     * nothing to paint -- either the message is already in the target language,
     * or its translation has not arrived yet.
     *
     * Safe to call from the scan thread: it does no IO and takes no lock that a
     * network call is holding.
     */
    fun translate(text: String): String? {
        if (closed) return null
        cache.get(text)?.let { return if (it === SKIP) null else it }
        enqueue(text)
        return null
    }

    private fun enqueue(text: String) {
        // Bound the concurrent work. Anything turned away here is simply picked
        // up by the next rebuild, which the arrival of these results triggers.
        if (inFlight.size >= MAX_IN_FLIGHT) return
        if (!inFlight.add(text)) return

        identifier.identifyLanguage(text)
            .addOnSuccessListener(io) { tag -> onIdentified(text, tag) }
            .addOnFailureListener(io) { t ->
                Log.w(TAG, "language id failed", t)
                fail(text)
            }
    }

    private fun onIdentified(text: String, tag: String?) {
        val detected = TranslateLanguage.fromLanguageTag(tag ?: "")
        // "und" on a short string is not evidence of anything; fall back to what
        // the rest of the conversation turned out to be.
        val source = detected
            ?: dominantSource?.takeIf { text.length <= SHORT_TEXT_CHARS }
            ?: run { skip(text); return }

        if (detected != null) dominantSource = detected

        if (source == target) {
            // Already readable. Leaving WhatsApp's own pixels alone is both
            // faster and better looking than repainting identical text.
            skip(text)
            return
        }
        runTranslation(source, text)
    }

    private fun runTranslation(source: String, text: String) {
        val translator = translatorFor(source) ?: run { skip(text); return }

        if (source in ready) {
            translateNow(translator, source, text)
            return
        }

        val first = downloading.add(source)
        if (first) onStatus(Status.Downloading(source))

        translator.downloadModelIfNeeded(downloadConditions())
            .addOnSuccessListener(io) {
                downloading.remove(source)
                ready.add(source)
                if (first) onStatus(Status.Ready(source))
                translateNow(translator, source, text)
            }
            .addOnFailureListener(io) { t ->
                downloading.remove(source)
                Log.w(TAG, "model download failed for $source", t)
                if (first) onStatus(Status.Failed(source))
                fail(text)
            }
    }

    private fun translateNow(translator: Translator, source: String, text: String) {
        translator.translate(text)
            .addOnSuccessListener(io) { result ->
                inFlight.remove(text)
                failures.remove(text)
                // A translation identical to the source is not worth a patch --
                // it would repaint the same glyphs in a slightly different font.
                cache.put(text, if (result.isNullOrBlank() || result == text) SKIP else result)
                onTranslationsArrived()
            }
            .addOnFailureListener(io) { t ->
                Log.w(TAG, "translate failed ($source -> $target)", t)
                // The model can be evicted by Play services under storage
                // pressure, so stop trusting the cached "ready" flag.
                ready.remove(source)
                fail(text)
            }
    }

    @Synchronized
    private fun translatorFor(source: String): Translator? {
        if (closed) return null
        translators[source]?.let { return it }

        // Each open translator holds native model memory. A chat is realistically
        // one or two languages, so keep a couple and close the rest.
        if (translators.size >= MAX_OPEN_TRANSLATORS) {
            val victim = translators.keys.first()
            translators.remove(victim)?.let { runCatching { it.close() } }
            // `ready` deliberately keeps the entry: it tracks whether the model
            // is on disk, which closing a client does not change.
        }

        return try {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build()
            ).also { translators[source] = it }
        } catch (t: Throwable) {
            Log.w(TAG, "could not create translator for $source -> $target", t)
            null
        }
    }

    private fun downloadConditions(): DownloadConditions =
        DownloadConditions.Builder()
            .apply { if (wifiOnly) requireWifi() }
            .build()

    /** Mirrors the user preference; set by the service when it changes. */
    @Volatile var wifiOnly: Boolean = false

    /**
     * Records "decided, paint nothing" for a string.
     *
     * This notifies too, and it has to. The in-flight cap turns work away, and
     * the only thing that picks it back up is a rebuild. A batch that resolved
     * entirely to skips would otherwise notify nobody, and everything the cap
     * had turned away would sit untranslated until the user happened to scroll.
     * A rebuild is debounced and drops itself when nothing changed, so the extra
     * notification costs a hash comparison.
     */
    private fun skip(text: String) {
        inFlight.remove(text)
        cache.put(text, SKIP)
        onTranslationsArrived()
    }

    /**
     * A failure is not cached, so the text gets another go on the next rebuild --
     * but only a few, otherwise a string the engine simply cannot handle would
     * be retried on every single scan forever.
     */
    private fun fail(text: String) {
        inFlight.remove(text)
        val count = (failures.get(text) ?: 0) + 1
        if (count >= MAX_RETRIES) {
            failures.remove(text)
            cache.put(text, SKIP)
        } else {
            failures.put(text, count)
        }
        // Frees the in-flight slot and drains the backlog -- see skip().
        onTranslationsArrived()
    }

    @Synchronized
    fun close() {
        closed = true
        closeTranslators()
        runCatching { identifier.close() }
        io.shutdown()
    }

    private fun closeTranslators() {
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
    }

    private companion object {
        const val TAG = "Lingo"

        /**
         * Sentinel meaning "decided, paint nothing". Compared by identity, so a
         * message that genuinely translates to this string is unaffected.
         */
        val SKIP = String(charArrayOf('\u0000'))

        const val CACHE_ENTRIES = 600
        const val MAX_IN_FLIGHT = 6
        const val MAX_OPEN_TRANSLATORS = 2
        const val MAX_RETRIES = 3
        const val SHORT_TEXT_CHARS = 24
    }
}
