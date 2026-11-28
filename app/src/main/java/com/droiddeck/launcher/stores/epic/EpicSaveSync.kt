package com.droiddeck.launcher.stores.epic

import com.droiddeck.launcher.stores.CloudFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** The engine refused to publish because the cloud already has a newer manifest than the base one;
 *  nothing was uploaded. [latest] names the cloud's current manifest. */
class PublishConflict(val latest: String, message: String) : IOException(message)

/** The engine refused to publish because two names differ only in case while both spellings exist
 *  as separate files (or the overlay lists one name twice); nothing was written. Like EA's case
 *  guard, every trigger retries until the save folder is unambiguous. */
class PublishCollision(message: String) : IOException(message)

/** The engine refused to publish for a reason no retry can fix (a manifest it cannot read, an
 *  unsafe or non-ASCII name); nothing was written. A missing save file is NOT one of these - the
 *  engine answers it as an ordinary error, since the next sync can plan around it. The engine
 *  marks permanent answers with `permanent: true` and carries its reason in the error text. */
class PublishPermanent(message: String) : IOException(message)

object EpicSaveSync {
    private fun base(token: String, accountId: String, appName: String) =
        JSONObject().put("accessToken", token).put("accountId", accountId).put("appName", appName)

    /** The publish transport, replaceable so JVM tests can drive the sync-level flow without the
     *  native library (EpicNative does not load there). */
    internal var engine: (request: JSONObject) -> JSONObject? = { EpicNative.call("cloud.publish", it) }

    /** The snapshot transport, replaceable for the same reason; separate from [engine] so a test
     *  faking publishes does not accidentally fake mirror snapshots too. */
    internal var snapshotEngine: (request: JSONObject) -> JSONObject? = { EpicNative.call("cloud.snapshot", it) }

    fun list(token: String, accountId: String, appName: String): List<CloudFile> {
        val answer = EpicNative.call("cloud.list", base(token, accountId, appName)) ?: throw IOException("Epic cloud is unavailable")
        if (answer.has("error")) throw IOException(answer.optString("error"))
        val files = answer.optJSONArray("files") ?: return emptyList()
        return (0 until files.length()).mapNotNull { files.optJSONObject(it) }.map { f ->
            CloudFile(
                f.optString("name"),
                f.optLong("modified", -1L),
                md5 = f.optString("hash").ifEmpty { null },
                readLink = f.optString("readLink").ifEmpty { null },
            )
        }
    }

    /**
     * Asks the store engine to mirror the newest cloud manifest (and its files) into [cacheDir] and
     * report it: `{mode, manifest, files:[{name, size, sha1}]}`. A mirror refresh that failed
     * midway answers `mirrorRefreshed: false` - the caller must not adopt or serve the mirror then.
     * Throws when the engine is unavailable or the cloud request fails, keeping the engine's error
     * text.
     */
    fun snapshot(token: String, accountId: String, appName: String, cacheDir: String): JSONObject {
        val answer = snapshotEngine(base(token, accountId, appName).put("cacheDir", cacheDir))
            ?: throw IOException("Epic cloud is unavailable")
        if (answer.has("error")) throw IOException(answer.optString("error"))
        return answer
    }

    /**
     * Publishes a new manifest on top of [baseManifest]: [overlay] (save-relative names read from
     * [saveDir]) is layered on the mirrored file set, chunks whose SHA-1 the old manifest already
     * has are reused, and the new manifest is uploaded last, so a failure leaves the newest
     * manifest - the cloud's logical state - untouched (chunks written before the failure stay
     * unreferenced). Returns the engine's
     * `{mode, manifest, files, uploaded, reused, stamp}` answer. Throws [PublishConflict] (nothing
     * uploaded) when the cloud has moved past [baseManifest], [PublishCollision] (nothing written)
     * on a case-only name clash, [PublishPermanent] (nothing written)
     * when the engine answers `permanent: true` - a refusal no retry can fix - and every other
     * engine failure stays a plain [IOException].
     */
    fun publish(
        token: String, accountId: String, appName: String, cacheDir: String, saveDir: String,
        overlay: List<String>, baseManifest: String,
    ): JSONObject {
        val request = base(token, accountId, appName)
            .put("cacheDir", cacheDir)
            .put("saveDir", saveDir)
            .put("overlay", JSONArray(overlay))
            .put("baseManifest", baseManifest)
        val answer = engine(request) ?: throw IOException("Epic cloud is unavailable")
        if (answer.optBoolean("conflict")) throw PublishConflict(answer.optString("latest"), answer.optString("error"))
        if (answer.optBoolean("collision")) throw PublishCollision(answer.optString("error"))
        if (answer.optBoolean("permanent")) throw PublishPermanent(answer.optString("error"))
        if (answer.has("error")) throw IOException(answer.optString("error"))
        return answer
    }

    fun read(file: CloudFile): Pair<ByteArray, Long> {
        val link = file.readLink ?: throw IOException("no download link for ${file.name}")
        if (!EpicNative.ready()) throw IOException("Epic cloud is unavailable: the store engine is not loaded")
        val bytes = EpicNative.fetch("cloud.read", JSONObject().put("url", link))
            ?: throw IOException("Epic cloud download failed for ${file.name}: the cloud request returned no data")
        return bytes to file.modifiedMs
    }

    fun write(token: String, accountId: String, appName: String, files: Map<String, ByteArray>): Map<String, Long> {
        if (files.isEmpty()) return emptyMap()
        val answer = EpicNative.call("cloud.writeLinks", base(token, accountId, appName).put("names", JSONArray(files.keys.toList())))
            ?: throw IOException("Epic cloud is unavailable")
        if (answer.has("error")) throw IOException(answer.optString("error"))
        val links = answer.optJSONObject("links") ?: JSONObject()
        return files.mapValues { (name, data) ->
            val link = links.optString(name, "").ifEmpty { throw IOException("no upload link for $name") }
            val sent = EpicNative.send("cloud.write", JSONObject().put("url", link), data) ?: throw IOException("Epic cloud upload failed")
            if (sent.has("error")) throw IOException(sent.optString("error"))
            -1L
        }
    }
}
