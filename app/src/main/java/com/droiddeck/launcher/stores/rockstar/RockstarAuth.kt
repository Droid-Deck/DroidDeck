package com.droiddeck.launcher.stores.rockstar

import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom

object RockstarAuth {
    private const val TAG = "RockstarAuth"
    const val SIGN_IN_HOST = "https://signin.rockstargames.com"
    private const val LAUNCHER_SITE = "https://rgl.rockstargames.com"
    private const val GATEWAY = "$LAUNCHER_SITE/api/connect/gateway"

    const val START_URL = "$SIGN_IN_HOST/robots.txt"

    const val CODE_URL = "$SIGN_IN_HOST/droiddeck/auth-code"
    val SIGN_IN_URLS = listOf(SIGN_IN_HOST, LAUNCHER_SITE, "https://www.rockstargames.com", "https://socialclub.rockstargames.com")
    const val COOKIE_DOMAIN = ".rockstargames.com"

    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    private const val PREFS = "stores.rockstar"
    private const val TICKET_MARGIN_MS = 15 * 60 * 1000L
    private val lock = Any()

    class Ticket(val ticket: String, val sessionKey: String, val sessionTicket: String, val rockstarId: String, val nickname: String, val expiresAt: Long, val email: String = "", val sdkTicket: String = "")

    internal fun fingerprint(context: Context): JSONObject {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun stable(key: String, bytes: Int): String = p.getString(key, null) ?: ByteArray(bytes).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }.also { p.edit().putString(key, it).apply() }
        val device = "DROIDDECK-" + (Build.MODEL ?: "ANDROID").uppercase().replace(Regex("[^A-Z0-9]"), "").take(12)
        return fingerprintOf(device, stable("volume_serial", 4), stable("cpu_info", 8))
    }

    internal fun fingerprintOf(device: String, volume: String, cpu: String): JSONObject = JSONObject()
        .put("device_name", device)
        .put("machine_name", device)
        .put("os_version", "10.0.19045")
        .put("volume_serial", volume)
        .put("cpu_info", cpu)

    internal fun state(fingerprint: JSONObject) = "{\"fp\": $fingerprint}"

    internal fun bridgeScript(fingerprint: JSONObject, title: String, codeUrl: String = CODE_URL): String = """
(function(){
  if (window.__droiddeckRgsc) return 'again';
  window.__droiddeckRgsc = true;
  var fp = $fingerprint, title = ${JSONObject.quote(title)}, back = ${JSONObject.quote(codeUrl)}, seq = 0;
  function later(cb, v){ setTimeout(function(){ try { if (cb) cb(v); } catch (e) {} }, 0); }
  var answers = {
    GetWindowConfig: { serviceInfo: { rosPlatform: 'pc' }, platformInfo: {} },
    GetDeviceId: { fingerprintKvp: fp },
    GetVersionInfo: { isDebugSdk: false },
    GetProfileList: { profiles: [] },
    GetCommandLineArguments: { arguments: [] }
  };
  window.rgscQueryCancel = function(){};
  window.rgscQuery = function(q){
    q = q || {};
    var p = q.params || {};
    if (q.method === 'CallAuthResult' && p.authCode) {
      later(q.onSuccess, {});
      setTimeout(function(){ location.replace(back + '?code=' + encodeURIComponent(p.authCode)); }, 50);
    } else if ((q.method === 'CallAuthResult' && p.result === 3) || (q.method === 'CallSetWindowState' && p.state === 3)) {
      later(q.onSuccess, {});
      setTimeout(function(){ location.replace(back + '?cancel=1'); }, 50);
    } else if (q.method === 'CallLaunchExternalBrowser' && p.url) {
      window.open(p.url, '_blank');
      later(q.onSuccess, {});
    } else {
      later(q.onSuccess, Object.prototype.hasOwnProperty.call(answers, q.method) ? answers[q.method] : {});
    }
    return ++seq;
  };
  window.rgscAddSubscription = function(s){
    s = s || {};
    if (s.method === 'OnStartAuth') later(s.onSuccess, { launchPlatform: 0, titleLaunchInfo: { activeTitleName: title, externalPlatformInfo: {} }, profile: null, errorMessage: null });
    return ++seq;
  };
  var form = '/signin/user-form?cid=launcher&lang=en-US';
  fetch(form, { credentials: 'include' })
    .then(function(r){ return r.text(); })
    .then(function(h){
      history.replaceState(null, '', form); document.open(); document.write(h); document.close();
      var css = document.createElement('style');
      css.textContent = '[data-ui-name="launcherSettings"]{display:none!important}';
      (document.head || document.documentElement).appendChild(css);
    });
  return 'ok';
})()
""".trimIndent()

    fun isCodeUrl(url: String) = url.startsWith(CODE_URL)

    internal fun cancelled(url: String): Boolean = android.net.Uri.parse(url).getQueryParameter("cancel") == "1"

    internal fun codeFrom(url: String): String? = android.net.Uri.parse(url).getQueryParameter("code")?.takeIf { it.isNotBlank() }

    fun completeSignIn(context: Context, url: String): String? {
        val code = codeFrom(url) ?: return "no auth code"
        val fp = fingerprint(context)
        val gateway = "$GATEWAY?code=" + URLEncoder.encode(code, "UTF-8") + "&fingerprint=" + URLEncoder.encode(state(fp), "UTF-8")
        val c = URL(gateway).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 25_000
        c.instanceFollowRedirects = false
        c.setRequestProperty("User-Agent", USER_AGENT)
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("X-Requested-With", "XMLHttpRequest")
        c.setRequestProperty("Origin", LAUNCHER_SITE)
        c.setRequestProperty("Referer", "$LAUNCHER_SITE/")
        runCatching { CookieManager.getInstance().getCookie(LAUNCHER_SITE) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { c.setRequestProperty("Cookie", it) }
        val (status, body) = try {
            val s = c.responseCode
            s to ((if (s in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty())
        } finally {
            c.disconnect()
        }
        val o = runCatching { JSONObject(body) }.getOrNull()
        val token = o?.optString("ScAuthToken")?.takeIf { it.isNotEmpty() && it != "null" }
        if (token == null) {
            Log.w(TAG, "gateway $status; fields ${o?.keys()?.asSequence()?.toList() ?: "not json"}")
            return "gateway $status"
        }
        val account = JSONObject()
            .put("access_token", token)
            .put("remembered_machine_token", o.optString("RememberedMachineToken").takeIf { it != "null" }.orEmpty())
            .put("login_guid", o.optString("LoginGuid").takeIf { it != "null" }.orEmpty())
            .put("display_name", "Rockstar Games")
            .put("signed_in_at", System.currentTimeMillis())
        StoreAccounts.write(context.applicationContext, Store.ROCKSTAR, account)
        val t = runCatching { mint(context) }.onFailure { Log.w(TAG, "first ticket: ${it.message}") }.getOrNull()
        if (t == null) {
            StoreAccounts.clear(context.applicationContext, Store.ROCKSTAR)
            return "ticket"
        }
        return null
    }

    fun ticket(context: Context, fresh: Boolean = false): Ticket? = synchronized(lock) {
        val o = StoreAccounts.read(context.applicationContext, Store.ROCKSTAR) ?: return null
        if (!fresh) {
            val current = stored(o)
            if (com.droiddeck.launcher.session.SessionState.running &&
                com.droiddeck.launcher.stores.StoreLaunchRequests.lastLaunched?.first == Store.ROCKSTAR) {
                return current?.takeIf { it.expiresAt > System.currentTimeMillis() }
            }
            current?.takeIf { it.expiresAt - System.currentTimeMillis() > TICKET_MARGIN_MS }?.let { return it }
        }
        runCatching { mint(context) }.onFailure { Log.w(TAG, "ticket: ${it.message}") }.getOrNull()
    }

    private fun stored(o: JSONObject): Ticket? {
        val t = o.optString("ticket").takeIf { it.isNotEmpty() } ?: return null
        return Ticket(t, o.optString("session_key"), o.optString("session_ticket"), o.optString("rockstar_id"), o.optString("display_name"), o.optLong("ticket_expires_at"), o.optString("email"), o.optString("sdk_ticket").takeIf { o.optInt("sdk_ticket_version") == 1 }.orEmpty())
    }

    private fun mint(context: Context): Ticket {
        val app = context.applicationContext
        val o = StoreAccounts.read(app, Store.ROCKSTAR) ?: throw IllegalStateException("not signed in")
        val root = try {
            RockstarRos.result("auth.asmx/CreateTicketScAuthToken2", listOf(
                "ticket" to "", "platformName" to "pcros", "scAuthToken" to o.optString("access_token"),
                "rememberedMachineToken" to o.optString("remembered_machine_token"),
            ), headers = mapOf("SCS-MFAMachineFingerPrint" to state(fingerprint(app))))
        } catch (e: RockstarRos.RosException) {
            if (e.code.startsWith("AuthenticationFailed")) com.droiddeck.launcher.stores.StoresState.markSignInExpired(Store.ROCKSTAR)
            throw e
        }
        val t = parseTicket(root) ?: throw IllegalStateException("no ticket in the answer")
        o.put("ticket", t.ticket).put("session_key", t.sessionKey).put("session_ticket", t.sessionTicket)
            .put("rockstar_id", t.rockstarId).put("ticket_expires_at", t.expiresAt).put("email", t.email).put("sdk_ticket", t.sdkTicket).put("sdk_ticket_version", 1)
        if (t.nickname.isNotEmpty()) o.put("display_name", t.nickname)
        StoreAccounts.write(app, Store.ROCKSTAR, o)
        return t
    }

    internal fun parseTicket(root: org.w3c.dom.Element, now: Long = System.currentTimeMillis()): Ticket? {
        val ticket = RockstarRos.text(root, "Ticket")?.takeIf { it.isNotEmpty() } ?: return null
        val account = RockstarRos.first(root, "RockstarAccount")
        val secs = RockstarRos.text(root, "SecsUntilExpiration")?.toLongOrNull() ?: 3600L
        return Ticket(
            ticket, RockstarRos.text(root, "SessionKey").orEmpty(), RockstarRos.text(root, "SessionTicket").orEmpty(),
            (account?.let { RockstarRos.text(it, "RockstarId") } ?: RockstarRos.text(root, "RockstarId")).orEmpty(),
            account?.let { RockstarRos.text(it, "Nickname") }.orEmpty(),
            now + secs * 1000L,
            account?.let { RockstarRos.text(it, "Email") }.orEmpty(),
            sdkTicket(root),
        )
    }

    internal fun sdkTicket(root: org.w3c.dom.Element): String {
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val response = document.createElement("Response").also { document.appendChild(it) }
        val fields = setOf("Status", "Ticket", "SessionKey", "SessionTicket", "SecsUntilExpiration", "PlayerAccountId", "PlayerAccountRuid",
            "Region", "Privileges", "Privs", "Services", "SslServices", "Hosts", "DisabledSecurityFlags", "SessionId", "CloudKey", "PublicIp", "PosixTime", "IsSubAccount")
        val blocked = setOf("scauthtoken", "accesstoken", "refreshtoken", "rememberedmachinetoken", "loginguid", "password")
        fun copy(source: org.w3c.dom.Element): org.w3c.dom.Element {
            val target = document.createElement(source.tagName)
            for (i in 0 until source.attributes.length) {
                val attribute = source.attributes.item(i)
                if (attribute.nodeName.lowercase() !in blocked) target.setAttribute(attribute.nodeName, attribute.nodeValue)
            }
            var child = source.firstChild
            while (child != null) {
                if (child is org.w3c.dom.Element) {
                    if (child.tagName.lowercase() !in blocked) target.appendChild(copy(child))
                } else if (child.nodeType == org.w3c.dom.Node.TEXT_NODE || child.nodeType == org.w3c.dom.Node.CDATA_SECTION_NODE) {
                    target.appendChild(document.createTextNode(child.nodeValue))
                }
                child = child.nextSibling
            }
            return target
        }
        for (field in fields) RockstarRos.first(root, field)?.let { response.appendChild(copy(it)) }
        RockstarRos.first(root, "RockstarAccount")?.let { account ->
            val target = document.createElement("RockstarAccount")
            for (field in setOf("RockstarId", "Nickname", "Email", "Age", "CountryCode", "LanguageCode", "AvatarUrl", "ZipCode", "Ruid")) {
                RockstarRos.first(account, field)?.let { target.appendChild(copy(it)) }
            }
            response.appendChild(target)
        }
        val output = java.io.StringWriter()
        javax.xml.transform.TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes")
            transform(javax.xml.transform.dom.DOMSource(document), javax.xml.transform.stream.StreamResult(output))
        }
        return output.toString()
    }

    fun signOut(context: Context) = synchronized(lock) { StoreAccounts.clear(context.applicationContext, Store.ROCKSTAR) }
}
