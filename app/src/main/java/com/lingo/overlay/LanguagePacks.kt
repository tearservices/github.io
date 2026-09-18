package com.lingo.overlay

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import java.util.Locale

/**
 * Thin wrapper over ML Kit's model store, used by the settings screen.
 *
 * A language pack is roughly 30 MB and is fetched the first time a language is
 * seen, which means the very first message in a new language sits untranslated
 * for as long as that download takes. Being able to pull the packs down ahead of
 * time -- on Wi-Fi, before a trip -- is the difference between the overlay
 * feeling instant and feeling broken.
 */
object LanguagePacks {

    private val manager get() = RemoteModelManager.getInstance()

    /** Every language ML Kit can translate, sorted for display. */
    fun allLanguages(): List<LanguageOption> =
        TranslateLanguage.getAllLanguages()
            .map { LanguageOption(it, displayName(it)) }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }

    fun downloaded(onResult: (Set<String>) -> Unit) {
        manager.getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models -> onResult(models.map { it.language }.toSet()) }
            .addOnFailureListener { onResult(emptySet()) }
    }

    fun download(language: String, requireWifi: Boolean, onResult: (Boolean) -> Unit) {
        val model = TranslateRemoteModel.Builder(language).build()
        val conditions = DownloadConditions.Builder()
            .apply { if (requireWifi) requireWifi() }
            .build()
        manager.download(model, conditions)
            .addOnSuccessListener { onResult(true) }
            .addOnFailureListener { onResult(false) }
    }

    fun delete(language: String, onResult: (Boolean) -> Unit) {
        manager.deleteDownloadedModel(TranslateRemoteModel.Builder(language).build())
            .addOnSuccessListener { onResult(true) }
            .addOnFailureListener { onResult(false) }
    }

    fun displayName(language: String): String =
        Locale.forLanguageTag(language).displayLanguage.ifBlank { language }
}

/** A language as the settings spinners show it. */
data class LanguageOption(val code: String, val label: String) {
    override fun toString(): String = label
}
