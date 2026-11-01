package io.github.iamthebarrow.nibtexa.core.sync

import org.eclipse.jgit.lib.Repository
import java.net.URI
import java.net.URISyntaxException

/**
 * Where exports go. Each booklet is written to `<destination>/<booklet-id>/` on [branch] of
 * [remoteUrl]; nothing outside that folder is ever staged.
 *
 * @property username the account name sent with the token. Some hosts ignore it, but it
 *   must not be blank when a token is used.
 */
data class SyncSettings(
    val remoteUrl: String = "",
    val username: String = "",
    val branch: String = DEFAULT_BRANCH,
    val destination: String = DEFAULT_DESTINATION,
    val authorName: String = "",
    val authorEmail: String = "",
    /** Also commit the editable notebook (`source/`) with each export. Off by default. */
    val includeSource: Boolean = false,
    /**
     * A booklet not yet on the remote goes under its library folder: `<destination>/<folder>/<booklet-id>/` (Phase 27).
     * Off by default. One already on the remote stays where it is, whatever this says.
     */
    val useFolders: Boolean = false,
) {
    companion object {
        /** A dedicated branch, so exports never land on a branch the user works on by default. */
        const val DEFAULT_BRANCH = "nibtexa-export"
        const val DEFAULT_DESTINATION = "nibtexa"
    }
}

/** Which remote URL schemes are accepted. Release builds accept HTTPS only. */
data class RemotePolicy(val schemes: Set<String>) {
    companion object {
        val HTTPS_ONLY = RemotePolicy(setOf("https"))
    }
}

sealed interface SettingsProblem {
    data object MissingRemote : SettingsProblem
    data class UnsupportedScheme(val scheme: String) : SettingsProblem
    data object CredentialsInUrl : SettingsProblem
    data object MalformedRemote : SettingsProblem
    data object InvalidBranch : SettingsProblem
    data object InvalidDestination : SettingsProblem
    data object MissingAuthor : SettingsProblem
    data object InvalidEmail : SettingsProblem
}

object SettingsValidator {

    private val SEGMENT = Regex("[A-Za-z0-9._-]{1,64}")

    fun problems(settings: SyncSettings, policy: RemotePolicy): List<SettingsProblem> = buildList {
        remoteProblem(settings.remoteUrl.trim(), policy)?.let(::add)
        if (!isValidBranch(settings.branch)) add(SettingsProblem.InvalidBranch)
        if (destinationSegments(settings.destination) == null) add(SettingsProblem.InvalidDestination)
        if (settings.authorName.isBlank()) add(SettingsProblem.MissingAuthor)
        if (!settings.authorEmail.trim().matches(Regex("[^\\s@<>]+@[^\\s@<>]+"))) add(SettingsProblem.InvalidEmail)
    }

    private fun remoteProblem(url: String, policy: RemotePolicy): SettingsProblem? {
        if (url.isEmpty()) return SettingsProblem.MissingRemote
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return SettingsProblem.MalformedRemote
        }
        val scheme = uri.scheme?.lowercase() ?: return SettingsProblem.MalformedRemote
        if (scheme !in policy.schemes) return SettingsProblem.UnsupportedScheme(scheme)
        // A token in the URL would end up in the working copy's config and in error messages.
        if (uri.rawUserInfo != null) return SettingsProblem.CredentialsInUrl
        if (scheme != "file" && uri.host.isNullOrEmpty()) return SettingsProblem.MalformedRemote
        if (uri.rawQuery != null || uri.rawFragment != null) return SettingsProblem.MalformedRemote
        return null
    }

    /** Git's ref rules, plus `git branch`'s own: no leading `-`. */
    fun isValidBranch(branch: String): Boolean =
        branch.isNotBlank() && branch == branch.trim() && !branch.startsWith("-") && Repository.isValidRefName("refs/heads/$branch")

    /**
     * A library folder's name as one safe path segment: other characters become `-`. Null if nothing usable is left, so
     * the booklet goes straight under the destination.
     */
    fun folderSegment(name: String): String? =
        name.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.').take(MAX_SEGMENT).trimEnd('-', '.')
            .takeIf { it.isNotEmpty() && !it.equals("git", ignoreCase = true) && it.matches(SEGMENT) }

    private const val MAX_SEGMENT = 64

    /**
     * The destination as path segments, or null if it is not a plain relative folder. `.git`
     * and other dot-only names are refused, so the destination can never reach Git's own files.
     */
    fun destinationSegments(destination: String): List<String>? {
        val segments = destination.trim().trim('/').split('/')
        if (segments.isEmpty() || segments.size > 8) return null
        return segments.takeIf { all ->
            all.all { it.matches(SEGMENT) && it.trim('.').isNotEmpty() && !it.equals(".git", ignoreCase = true) }
        }
    }
}
