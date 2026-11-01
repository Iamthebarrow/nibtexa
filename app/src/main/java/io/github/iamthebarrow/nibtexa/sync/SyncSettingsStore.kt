package io.github.iamthebarrow.nibtexa.sync

import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.iamthebarrow.nibtexa.core.sync.SyncSettings

/** The sync settings. No secrets: the token is in [TokenStore]. */
class SyncSettingsStore(private val prefs: SharedPreferences) {

    fun load(): SyncSettings = SyncSettings(
        remoteUrl = prefs.getString(REMOTE, null).orEmpty(),
        username = prefs.getString(USERNAME, null).orEmpty(),
        branch = prefs.getString(BRANCH, null) ?: SyncSettings.DEFAULT_BRANCH,
        destination = prefs.getString(DESTINATION, null) ?: SyncSettings.DEFAULT_DESTINATION,
        authorName = prefs.getString(AUTHOR_NAME, null).orEmpty(),
        authorEmail = prefs.getString(AUTHOR_EMAIL, null).orEmpty(),
        includeSource = prefs.getBoolean(INCLUDE_SOURCE, false),
        useFolders = prefs.getBoolean(USE_FOLDERS, false),
    )

    fun save(settings: SyncSettings) = prefs.edit {
        putString(REMOTE, settings.remoteUrl.trim())
        putString(USERNAME, settings.username.trim())
        putString(BRANCH, settings.branch.trim())
        putString(DESTINATION, settings.destination.trim().trim('/'))
        putString(AUTHOR_NAME, settings.authorName.trim())
        putString(AUTHOR_EMAIL, settings.authorEmail.trim())
        putBoolean(INCLUDE_SOURCE, settings.includeSource)
        putBoolean(USE_FOLDERS, settings.useFolders)
    }

    fun clear() = prefs.edit { clear() }

    private companion object {
        const val REMOTE = "remote"
        const val USERNAME = "username"
        const val BRANCH = "branch"
        const val DESTINATION = "destination"
        const val AUTHOR_NAME = "author_name"
        const val AUTHOR_EMAIL = "author_email"
        const val INCLUDE_SOURCE = "include_source"
        const val USE_FOLDERS = "use_folders"
    }
}
