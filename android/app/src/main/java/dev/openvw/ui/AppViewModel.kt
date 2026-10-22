package dev.openvw.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.openvw.data.AlertSetup
import dev.openvw.data.Api
import dev.openvw.data.CarEvent
import dev.openvw.data.CarLook
import dev.openvw.data.LiveFeed
import dev.openvw.data.MapSnapshot
import dev.openvw.data.Parked
import dev.openvw.data.Places
import dev.openvw.data.Trip
import dev.openvw.data.Crypto
import dev.openvw.data.DriveStats
import dev.openvw.data.Dongle
import dev.openvw.data.Owner
import dev.openvw.data.PlanInfo
import dev.openvw.data.Repo
import dev.openvw.data.Session
import dev.openvw.data.Store
import dev.openvw.data.Telemetry
import dev.openvw.data.VehicleInfo
import dev.openvw.data.WidgetData
import dev.openvw.data.WidgetSnapshot
import dev.openvw.widget.OpenVWWidgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

enum class Phase { BOOTING, LOGIN, OTP, LOADING, READY }

class AppViewModel(app: Application) : AndroidViewModel(app) {
  val session = Session(app)

  var phase by mutableStateOf(Phase.BOOTING); private set
  var message by mutableStateOf<String?>(null); private set
  var busy by mutableStateOf(false); private set
  var phone by mutableStateOf("")

  private val loadLock = Mutex()
  private var lastFull = 0L
  private var widgetsDue = true // widgets refresh once per app open, not on every poll

  // live push feed (SignalR) — runs only while the app is in the foreground
  private var feed: LiveFeed? = null
  private var liveWanted = false
  private var liveJob: Job? = null
  private var lastGeo: Pair<Double, Double>? = null
  private var pollQueued = false
  private var searchJob: Job? = null
  private var lastProfileSyncAt = 0L
  private var lastUserInfoPayload: JSONObject? = null
  private var lastVehicleProfilePayload: JSONObject? = null

  init {
    phone = session.mobile
    Store.privacy = session.privacy
    Store.paintPick = session.paintPick
    Store.fuelPrice = session.fuelPrice.toDouble()
    Store.dark = session.dark; dev.openvw.theme.Palette.dark = session.dark
    Store.connectedSince = session.connectedSince
    if (session.loggedIn) { phase = Phase.LOADING; refresh() } else phase = Phase.LOGIN
  }

  fun setPrivacy(b: Boolean) { session.privacy = b; Store.privacy = b }

  fun setDark(b: Boolean) { session.dark = b; Store.dark = b; dev.openvw.theme.Palette.dark = b }

  /** Manual reg no fallback (used when backend omits it). */
  fun setVehicleRegNoManual(raw: String) {
    val v = raw.trim().uppercase(Locale.ENGLISH)
    session.regNoOverride = v
    if (v.isNotBlank()) Store.vehicle = Store.vehicle.copy(regNo = v)
  }

  /** Manual car colour ("" = follow the account colour); widgets redraw with it right away. */
  fun setPaint(name: String) {
    session.paintPick = name; Store.paintPick = name
    runCatching { saveWidgets() }
  }

  /** Owner's average petrol price in ₹/L (0 = back to the default). */
  fun setFuelPrice(p: Double) { session.fuelPrice = p.toFloat(); Store.fuelPrice = p }

  fun startLogin(p: String) {
    val mob = p.filter { it.isDigit() }
    if (mob.length != 10) { message = "Enter a 10-digit mobile number"; return }
    busy = true; message = null
    viewModelScope.launch {
      try {
        session.mobile = mob
        val inst = Api.appInstallation(session.deviceID)
        session.installationID = inst.optInt("installationID", session.installationID)
        if (session.installationID == 0) { fail("Could not register device"); return@launch }
        val auth = Api.authenticate(session.installationID, 0)
        val tok = auth.optString("accessToken")
        if (tok.isEmpty()) { fail("Could not reach Skoda Connect (try again)"); return@launch }
        session.token = tok; session.refreshToken = auth.optString("refreshToken")
        val reg = Api.registerUser(mob, session.installationID, tok)
        val regOk = reg.optBoolean("isSuccessful") || reg.optBoolean("success")
        if (!regOk) { fail(reg.optString("message", "Could not send OTP")); return@launch }
        val loginId = reg.deepIntAny("loginId", "loginID", "LoginId")
        val userId = reg.deepIntAny("userId", "userID", "UserId")
        if (loginId == 0) { fail("Could not start OTP session (missing LoginId)"); return@launch }
        if (userId == 0) { fail("Could not start OTP session (missing UserId)"); return@launch }
        session.loginId = loginId
        session.userId = userId
        busy = false; phase = Phase.OTP
      } catch (e: Exception) { fail(e.message ?: "Network error") }
    }
  }

  /** Back from the OTP step to the phone-number step. */
  fun backToPhone() { busy = false; message = null; phase = Phase.LOGIN }

  fun resend() {
    viewModelScope.launch {
      if (session.loginId == 0 || session.userId == 0) {
        val reg = runCatching { Api.registerUser(session.mobile, session.installationID, session.token) }.getOrNull()
        reg?.deepIntAny("loginId", "loginID", "LoginId")?.takeIf { it > 0 }?.let { session.loginId = it }
        reg?.deepIntAny("userId", "userID", "UserId")?.takeIf { it > 0 }?.let { session.userId = it }
      }
      val resend = runCatching {
        Api.resendLoginOTP(session.loginId, session.userId, session.mobile, session.installationID, session.token)
      }.getOrNull()
      if (resend != null && (resend.optBoolean("isSuccessful") || resend.optBoolean("success"))) {
        message = "Code re-sent"
      } else {
        message = resend?.optString("message", "Could not resend code") ?: "Could not resend code"
      }
    }
  }

  fun verify(otp: String) {
    val code = otp.filter { it.isDigit() }
    if (code.length != 6) { message = "Enter the 6-digit code"; return }
    busy = true; message = null
    viewModelScope.launch {
      try {
        if (session.loginId == 0 || session.userId == 0) {
          fail("Session expired. Please resend code and try again")
          return@launch
        }
        val r = Api.verifyLoginOTP(code, session)
        if (!r.optBoolean("isSuccessful")) { fail(r.optString("message", "Incorrect code")); return@launch }
        session.verified = true
        if (r.has("signStamp")) session.signStamp = r.optLong("signStamp")
        busy = false; phase = Phase.LOADING
        loadData(); phase = Phase.READY; poll()
      } catch (e: Exception) { fail(e.message ?: "Network error") }
    }
  }

  fun refresh() {
    viewModelScope.launch {
      try { loadData() } catch (_: Exception) {}
      phase = Phase.READY
      poll()
    }
  }

  /** App opened / brought to the foreground: full refresh, widgets once, then the live push feed. */
  fun onOpened() {
    widgetsDue = true; lastFull = 0L
    lastProfileSyncAt = 0L
    liveWanted = true
    poll()
    startLive()
  }

  /** App left the foreground: drop the live connection (nothing runs in the background). */
  fun onBackground() {
    liveWanted = false
    liveJob?.cancel(); liveJob = null
    feed?.let { f -> feed = null; viewModelScope.launch(Dispatchers.IO) { f.stop() } }
    Store.liveConnected = false
  }

  /** One REST refresh: live telemetry, plus trips/events if the last full load is >5 min old. */
  fun poll() {
    if (phase != Phase.READY || session.vin.isEmpty() || pollQueued) return
    pollQueued = true // waits for any running load instead of being dropped
    viewModelScope.launch {
      try {
        loadLock.withLock {
          pollQueued = false
          if (Store.vehicle.regNo.isBlank()) runCatching { refreshProfileFromServer(force = false) }
          runCatching { loadTelematics(full = System.currentTimeMillis() - lastFull > 5 * 60_000L) }
        }
      } finally { pollQueued = false }
    }
  }

  fun refreshTelematics() { if (feed?.connected != true) poll() }

  /** One-tap debug export: all raw metadata payloads + mapped app values (for field tracing). */
  fun exportProfileMetadata() {
    viewModelScope.launch {
      val ctx = getApplication<Application>()
      runCatching {
        val payload = loadLock.withLock {
          refreshProfileFromServer(force = true, includeVehicleProfileDump = true)
          // refresh mapped values first (fuel/battery/distance/subscriptions etc.)
          runCatching { loadTelematics(full = true) }

          val raw = JSONObject()
          val userInfo = Api.userInfo(session)
          raw.put("userInfo", userInfo)

          val vehicleProfile = Api.vehicleProfileInfo(session)
          raw.put("vehicleProfileInfo", vehicleProfile ?: JSONObject.NULL)
          raw.put("savwVehicleInfoProbe", Api.savwVehicleInfoProbe(session))
          raw.put("subscriptionHistoryProbe", Api.subscriptionHistoryProbe(session))

          var teleToken = session.teleToken
          var carId = session.carId
          val phone = normalizePhoneForTelematics(
            session.telePhone.ifEmpty { if (session.mobile.isNotBlank()) "+91${session.mobile}" else "" },
          )
          val hasSessionTelematics = teleToken.isNotBlank() && carId.isNotBlank()

          // Reuse existing live session tokens first to avoid Cerence rate limits during export.
          if (hasSessionTelematics) {
            raw.put("cerenceToken", JSONObject().put("_skip", "reused session telematics token"))
            raw.put("telematicsToken", JSONObject().put("_skip", "reused session telematics token"))
            raw.put("resolveCar", JSONObject().put("_skip", "reused session carId"))
          } else {
            var cerenceToken = session.cerenceToken
            if (cerenceToken.isBlank()) {
              if (session.userId != 0) {
                val ct = Api.cerenceToken(session.userId)
                raw.put("cerenceToken", ct)
                val fresh = ct.optString("cerenceToken").ifEmpty { ct.optString("CerenceToken") }
                if (fresh.isNotBlank()) cerenceToken = fresh
              } else {
                raw.put("cerenceToken", JSONObject().put("_skip", "session.userId is 0"))
              }
            } else {
              raw.put("cerenceToken", JSONObject().put("_skip", "reused session cerence token"))
            }

            if (cerenceToken.isNotBlank() && session.vin.isNotBlank() && phone.isNotBlank()) {
              val tt = Api.telematicsToken(cerenceToken, phone, session.vin)
              raw.put("telematicsToken", tt)
              teleToken = tt.optJSONObject("loginInfo")?.optJSONObject("tokenInfo")?.optString("accessToken")
                ?: tt.optString("accessToken").ifBlank { session.teleToken }
              if (!teleToken.isNullOrBlank()) {
                val rc = Api.resolveCar(session.vin, teleToken)
                raw.put("resolveCar", rc)
                carId = rc.optJSONArray("cars")?.optJSONObject(0)?.optString("_id")
                  ?.ifEmpty { rc.optJSONArray("cars")?.optJSONObject(0)?.optString("id") ?: "" }
                  ?.ifBlank { session.carId }
                  ?: session.carId
              } else {
                raw.put("resolveCar", JSONObject().put("_skip", "telematics token unavailable"))
              }
            } else {
              raw.put("telematicsToken", JSONObject().put("_skip", "cerence token, vin, or phone unavailable"))
              raw.put("resolveCar", JSONObject().put("_skip", "cerence token, vin, or phone unavailable"))
            }
          }

          if (!teleToken.isNullOrBlank() && carId.isBlank() && session.vin.isNotBlank()) {
            val rc = Api.resolveCar(session.vin, teleToken)
            raw.put("resolveCarFallback", rc)
            carId = rc.optJSONArray("cars")?.optJSONObject(0)?.optString("_id")
              ?.ifEmpty { rc.optJSONArray("cars")?.optJSONObject(0)?.optString("id") ?: "" }
              ?.ifBlank { session.carId }
              ?: session.carId
          }

          if (!teleToken.isNullOrBlank() && carId.isNotBlank()) {
            raw.put("telematics", Api.telematics(carId, teleToken))
            raw.put("trips", Api.trips(carId, teleToken))
            raw.put("events", Api.events(carId, teleToken))
            raw.put("policies", Api.policies(carId, teleToken))
          } else {
            val why = if (teleToken.isNullOrBlank()) "telematics token unavailable" else "carId unavailable"
            raw.put("telematics", JSONObject().put("_skip", why))
            raw.put("trips", JSONObject().put("_skip", why))
            raw.put("events", JSONObject().put("_skip", why))
            raw.put("policies", JSONObject().put("_skip", why))
          }

          val plans = JSONArray().apply {
            Store.plans.forEach { p ->
              put(JSONObject().put("title", p.title).put("details", p.details).put("active", p.active))
            }
          }
          val mapped = JSONObject()
            .put("connectedSince", Store.connectedSince)
            .put("vehicle", JSONObject()
              .put("model", Store.vehicle.model)
              .put("variant", Store.vehicle.variant)
              .put("colour", Store.vehicle.colour)
              .put("regNo", Store.vehicle.regNo)
              .put("status", Store.vehicle.status)
            )
            .put("telemetry", JSONObject()
              .put("fuelPct", Store.telemetry.fuelPct)
              .put("batteryV", Store.telemetry.batteryV)
              .put("odometerKm", Store.telemetry.odometerKm)
              .put("coolantC", Store.telemetry.coolantC)
              .put("engineOn", Store.telemetry.engineOn)
            )
            .put("plans", plans)

          val exportedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
          JSONObject()
            .put("exportedAt", exportedAt)
            .put("raw", raw)
            .put("mapped", mapped)
        }

        val outDir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS) ?: ctx.filesDir
        if (!outDir.exists()) outDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(outDir, "openskoda-profile-metadata-$stamp.json")
        out.writeText(payload.toString(2))
        message = "Metadata exported: ${out.absolutePath}"
      }.onFailure {
        message = "Metadata export failed: ${it.message ?: "unknown error"}"
      }
    }
  }

  /** Connect to the car hub; on failure retry with backoff (re-minting the token on 401). */
  private fun startLive() {
    if (!liveWanted || liveJob?.isActive == true || feed?.connected == true) return
    liveJob = viewModelScope.launch {
      var backoff = 5_000L
      while (liveWanted && isActive) {
        if (phase == Phase.READY && session.vin.isNotEmpty()) {
          // (re)mint the telematics token / resolve the car if needed
          if (session.carId.isEmpty() || session.teleToken.isEmpty()) runCatching { loadLock.withLock { loadTelematics(full = false) } }
          if (session.carId.isEmpty() || session.teleToken.isEmpty()) { delay(backoff); backoff = (backoff * 2).coerceAtMost(60_000L); continue }
          val f = LiveFeed(
            session.carId, { session.teleToken },
            onStatus = { m -> viewModelScope.launch { applyCar(m, live = true) } },
            onEvent = { m -> viewModelScope.launch { applyEvent(m) } },
            onClosed = { viewModelScope.launch { if (feed != null) { feed = null; Store.liveConnected = false; startLive() } } },
          )
          val err = withContext(Dispatchers.IO) { runCatching { f.start() }.exceptionOrNull() }
          if (err == null && liveWanted) { feed = f; Store.liveConnected = true; return@launch }
          withContext(Dispatchers.IO) { f.stop() }
          if (err?.message?.contains("401") == true) session.teleToken = "" // expired: re-mint below
          // keep the screen fresh while the hub is unreachable
          runCatching { loadLock.withLock { loadTelematics(full = false) } }
          delay(backoff); backoff = (backoff * 2).coerceAtMost(60_000L)
        } else delay(2_000) // still signing in / first load
      }
    }
  }

  fun logout() { onBackground(); session.clearAuth(); Store.live = false; phase = Phase.LOGIN }

  private fun fail(m: String) { busy = false; message = m; if (phase == Phase.LOADING) phase = if (session.verified) Phase.READY else Phase.LOGIN }

  private fun JSONObject.optIntAny(vararg keys: String): Int {
    for (key in keys) {
      if (!has(key)) continue
      val raw = opt(key)
      when (raw) {
        is Number -> {
          val v = raw.toInt()
          if (v != 0) return v
        }
        is String -> raw.toIntOrNull()?.takeIf { it != 0 }?.let { return it }
      }
    }
    return 0
  }

  private fun JSONObject.deepIntAny(vararg keys: String): Int {
    val direct = optIntAny(*keys)
    if (direct != 0) return direct

    val objKeys = keys()
    while (objKeys.hasNext()) {
      val raw = opt(objKeys.next()) ?: continue
      when (raw) {
        is JSONObject -> {
          val v = raw.deepIntAny(*keys)
          if (v != 0) return v
        }
        is JSONArray -> {
          for (i in 0 until raw.length()) {
            val item = raw.opt(i)
            if (item is JSONObject) {
              val v = item.deepIntAny(*keys)
              if (v != 0) return v
            }
          }
        }
      }
    }
    return 0
  }

  private fun JSONObject.optStringAny(vararg keys: String): String {
    for (key in keys) {
      val v = optString(key)
      if (v.isNotBlank()) return v
      val it = keys()
      while (it.hasNext()) {
        val actual = it.next()
        if (actual.equals(key, ignoreCase = true)) {
          val cv = optString(actual)
          if (cv.isNotBlank()) return cv
        }
      }
    }
    return ""
  }

  private fun JSONObject.deepStringAny(vararg keys: String): String {
    val direct = optStringAny(*keys)
    if (direct.isNotBlank()) return direct
    val objKeys = keys()
    while (objKeys.hasNext()) {
      val raw = opt(objKeys.next()) ?: continue
      when (raw) {
        is JSONObject -> raw.deepStringAny(*keys).takeIf { it.isNotBlank() }?.let { return it }
        is JSONArray -> {
          for (i in 0 until raw.length()) {
            val item = raw.opt(i)
            if (item is JSONObject) item.deepStringAny(*keys).takeIf { it.isNotBlank() }?.let { return it }
          }
        }
      }
    }
    return ""
  }

  private fun JSONObject.optBooleanAny(vararg keys: String): Boolean? {
    for (key in keys) {
      if (!has(key)) continue
      when (val raw = opt(key)) {
        is Boolean -> return raw
        is Number -> return raw.toInt() != 0
        is String -> {
          when (raw.trim().lowercase()) {
            "true", "yes", "active", "1" -> return true
            "false", "no", "inactive", "0" -> return false
          }
        }
      }
    }
    return null
  }

  private fun JSONObject.deepBooleanAny(vararg keys: String): Boolean? {
    val direct = optBooleanAny(*keys)
    if (direct != null) return direct
    val objKeys = keys()
    while (objKeys.hasNext()) {
      val raw = opt(objKeys.next()) ?: continue
      when (raw) {
        is JSONObject -> raw.deepBooleanAny(*keys)?.let { return it }
        is JSONArray -> {
          for (i in 0 until raw.length()) {
            val item = raw.opt(i)
            if (item is JSONObject) item.deepBooleanAny(*keys)?.let { return it }
          }
        }
      }
    }
    return null
  }

  private fun parseNumberText(raw: String): Double? {
    val m = Regex("-?\\d+(?:\\.\\d+)?").find(raw.replace(",", ""))
    return m?.value?.toDoubleOrNull()
  }

  private fun cleanText(raw: String?): String {
    val v = raw?.trim().orEmpty()
    if (v.isEmpty()) return ""
    val n = v.lowercase()
    return if (n == "null" || n == "na" || n == "n/a" || n == "-") "" else v
  }

  private fun resolveOwnerAddress(vararg src: JSONObject?): String {
    val keys = arrayOf(
      "communicationAddress", "address", "addressLine1", "addressLine2", "address1", "address2",
      "fullAddress", "customerAddress", "ownerAddress", "mailingAddress", "residentialAddress",
      "streetAddress", "locationAddress", "cityAddress",
    )
    src.filterNotNull().forEach { obj ->
      val raw = obj.deepStringAny(*keys)
      val dec = cleanText(Crypto.decOrSelf(raw))
      if (dec.isNotBlank()) return dec
    }
    return ""
  }

  private fun normalizePhoneForTelematics(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    return when {
      digits.length == 10 -> "+91$digits"
      digits.length == 12 && digits.startsWith("91") -> "+$digits"
      raw.startsWith("+") -> raw
      else -> if (digits.isNotBlank()) raw else ""
    }
  }

  private val regFormat = Regex("^[A-Z]{2}[ -]?[0-9]{1,2}[ -]?[A-Z]{0,3}[ -]?[0-9]{3,4}$")
  private val purchaseDateLike = Regex(
    "\\b\\d{1,2}[-/ ](?:[A-Za-z]{3,9}|\\d{1,2})[-/ ]\\d{2,4}\\b|\\b\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}\\b",
  )
  private val ownershipDateParsers = listOf(
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US),
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
    SimpleDateFormat("yyyy-MM-dd", Locale.US),
    SimpleDateFormat("yyyy/MM/dd", Locale.US),
    SimpleDateFormat("dd-MMM-yyyy", Locale.US),
    SimpleDateFormat("dd-MMM-yy", Locale.US),
    SimpleDateFormat("dd MMM yyyy", Locale.US),
    SimpleDateFormat("dd/MM/yyyy", Locale.US),
    SimpleDateFormat("dd/MM/yy", Locale.US),
    SimpleDateFormat("dd-MM-yyyy", Locale.US),
    SimpleDateFormat("dd-MM-yy", Locale.US),
  ).onEach { it.isLenient = false }
  private val ownershipOut = SimpleDateFormat("yyyy-MM-dd", Locale.US)

  private fun parseOwnershipDate(raw: String): Date? {
    val text = cleanText(raw)
    if (text.isBlank()) return null
    return ownershipDateParsers.firstNotNullOfOrNull { fmt ->
      runCatching { fmt.parse(text) }.getOrNull()
    }
  }

  private fun isReasonableOwnershipDate(d: Date): Boolean {
    val cal = Calendar.getInstance().apply { time = d }
    val year = cal.get(Calendar.YEAR)
    val thisYear = Calendar.getInstance().get(Calendar.YEAR)
    return year in 1990..(thisYear + 1)
  }

  private fun normalizeOwnershipDate(raw: String): String {
    val d = parseOwnershipDate(raw) ?: return ""
    if (!isReasonableOwnershipDate(d)) return ""
    return ownershipOut.format(d)
  }

  private fun bestOwnershipDate(candidates: List<String>): String {
    val dates = candidates.mapNotNull { parseOwnershipDate(it)?.takeIf(::isReasonableOwnershipDate) }
    val best = dates.minByOrNull { it.time } ?: return ""
    return ownershipOut.format(best)
  }

  private fun looksLikeReg(value: String): Boolean {
    val v = value.uppercase(Locale.ENGLISH).replace(Regex("\\s+"), " ").trim()
    if (v.length < 6 || v.length > 16) return false
    if (v.contains("FACTORY:")) return false
    if (v.all { it.isDigit() }) return false
    return regFormat.matches(v)
  }

  private fun extractRegFromJson(root: Any?): String {
    val keyHints = listOf("registration", "vehicleReg", "regNo", "regNumber", "plate", "vehicleNumber")
    var hinted: String? = null
    val formatted = mutableListOf<String>()

    fun consider(raw: String, hintedKey: Boolean) {
      val dec = cleanText(Crypto.decOrSelf(raw))
      if (dec.isBlank()) return
      val norm = dec.uppercase(Locale.ENGLISH).replace(Regex("\\s+"), " ").trim()
      if (looksLikeReg(norm)) {
        formatted += norm
        if (hintedKey) hinted = norm
      } else if (hintedKey && hinted.isNullOrBlank()) {
        hinted = norm
      }
    }

    fun walk(node: Any?) {
      when (node) {
        is JSONObject -> {
          val it = node.keys()
          while (it.hasNext()) {
            val k = it.next()
            val v = node.opt(k)
            if (v is String) {
              val isHint = keyHints.any { h -> k.contains(h, ignoreCase = true) }
              consider(v, isHint)
            }
            if (v is JSONObject || v is JSONArray) walk(v)
          }
        }
        is JSONArray -> {
          for (i in 0 until node.length()) walk(node.opt(i))
        }
      }
    }

    walk(root)
    return (hinted?.takeIf { looksLikeReg(it) } ?: formatted.firstOrNull() ?: hinted).orEmpty()
  }

  private fun extractOwnershipDateFromJson(root: Any?): String {
    val keyHints = listOf(
      "purchased", "purchase", "registration", "delivery", "invoice",
      "carPurchaseDate", "firstDeliveryDate", "initialRegistrationDate",
    )
    val keyBlock = listOf(
      "policy", "warranty", "expiry", "expiration", "expire",
      "insurance", "puc", "service", "subscription", "updated", "created",
    )
    val hintedDates = mutableListOf<Date>()

    fun normalizeDateCandidate(raw: String): String {
      val dec = cleanText(Crypto.decOrSelf(raw))
      if (dec.isBlank()) return ""
      val compact = dec.replace(Regex("\\s+"), " ").trim()
      val candidate = purchaseDateLike.find(compact)?.value ?: compact
      return normalizeOwnershipDate(candidate)
    }

    fun keyIsHint(key: String): Boolean {
      val norm = key.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9]"), "")
      if (keyBlock.any { norm.contains(it) }) return false
      return keyHints.any { norm.contains(it) }
    }

    fun fromEpoch(raw: Number): String {
      val v = raw.toLong()
      if (v <= 0L) return ""
      val ms = if (v < 10_000_000_000L) v * 1000L else v
      val d = runCatching { Date(ms) }.getOrNull() ?: return ""
      if (!isReasonableOwnershipDate(d)) return ""
      return ownershipOut.format(d)
    }

    fun consider(value: Any?, hintedKey: Boolean) {
      when (value) {
        is String -> {
          if (!hintedKey) return
          val parsed = normalizeDateCandidate(value)
          if (parsed.isBlank()) return
          val d = parseOwnershipDate(parsed)?.takeIf(::isReasonableOwnershipDate) ?: return
          hintedDates += d
        }
        is Number -> {
          if (!hintedKey) return
          val parsed = fromEpoch(value)
          if (parsed.isBlank()) return
          val d = parseOwnershipDate(parsed)?.takeIf(::isReasonableOwnershipDate) ?: return
          hintedDates += d
        }
      }
    }

    fun walk(node: Any?) {
      when (node) {
        is JSONObject -> {
          val it = node.keys()
          while (it.hasNext()) {
            val k = it.next()
            val v = node.opt(k)
            consider(v, keyIsHint(k))
            if (v is JSONObject || v is JSONArray) walk(v)
          }
        }
        is JSONArray -> {
          for (i in 0 until node.length()) walk(node.opt(i))
        }
      }
    }

    walk(root)
    val best = hintedDates.minByOrNull { it.time } ?: return ""
    return ownershipOut.format(best)
  }

  private fun parseFlag(raw: Any?): Boolean? = when (raw) {
    is Boolean -> raw
    is Number -> raw.toInt() != 0
    is String -> when (raw.trim().lowercase()) {
      "true", "yes", "on", "active", "1" -> true
      "false", "no", "off", "inactive", "0" -> false
      else -> null
    }
    else -> null
  }

  private fun prettifyEvent(raw: String): String {
    if (raw.isBlank()) return ""
    val normalized = raw
      .replace('_', ' ')
      .replace('-', ' ')
      .replace(Regex("([a-z])([A-Z])"), "$1 $2")
      .trim()
      .lowercase(Locale.ENGLISH)
    val overrides = mapOf(
      "sudden brake" to "Sudden brake",
      "sharp turn" to "Sharp turn",
      "speed limit breached" to "Speed limit breached",
      "overspeed" to "Speed limit breached",
      "low fuel" to "Low fuel",
      "battery low" to "Low battery",
      "tow alert" to "Tow alert",
      "towing" to "Tow alert",
      "puc expiry" to "PUC expiry",
      "geofence breached" to "Geo fence breached",
      "time fence breached" to "Time fence breached",
      "idle alert" to "Idle alert",
    )
    return overrides[normalized] ?: normalized.replaceFirstChar { it.uppercase() }
  }

  private suspend fun loadData() {
    // fresh bootstrap token (30-min TTL)
    val auth = Api.authenticate(session.installationID, 0)
    if (auth.optString("accessToken").isNotEmpty()) {
      session.token = auth.optString("accessToken"); session.refreshToken = auth.optString("refreshToken")
    }
    // CRM token (may override bearer; often null — bootstrap token still works)
    runCatching { Api.crmToken(session.userId, session.token) }.getOrNull()?.let { crm ->
      val sfdc = crm.optString("sfdcToken")
      if (sfdc.isNotEmpty() && sfdc != "null") session.token = sfdc
    }
    // profile + vehicle
    refreshProfileFromServer(force = true)
    // live telemetry now; trips/events/places follow in the background via poll()
    runCatching { loadLock.withLock { loadTelematics(full = false) } }
  }

  private suspend fun refreshProfileFromServer(force: Boolean = false, includeVehicleProfileDump: Boolean = false) {
    if (session.userId == 0 || session.token.isBlank()) return
    val now = System.currentTimeMillis()
    if (!force && now - lastProfileSyncAt < 90_000L) return
    val ui = Api.userInfo(session)
    if (!ui.optBoolean("_decryptFailed")) {
      lastUserInfoPayload = JSONObject(ui.toString())
      mapUserInfo(ui)
      lastProfileSyncAt = now
    }
    if (includeVehicleProfileDump || Store.vehicle.regNo.isBlank() || Store.connectedSince.isBlank()) {
      Api.vehicleProfileInfo(session)?.let { extra ->
        lastVehicleProfilePayload = JSONObject(extra.toString())
        val reg = extractRegFromJson(extra)
        if (reg.isNotBlank()) {
          Store.vehicle = Store.vehicle.copy(regNo = reg)
          session.regNoOverride = reg
        }
        if (Store.connectedSince.isBlank()) {
          val purchased = extractOwnershipDateFromJson(extra)
          if (purchased.isNotBlank()) {
            session.connectedSince = purchased
            Store.connectedSince = purchased
          }
        }
      }
    }
  }

  private fun saveWidgets() {
    val tr = Store.trips.firstOrNull()
    val p = Store.parked
    val t = Store.telemetry
    val kmpl = Store.drive.realKmpl.takeIf { it > 0 } ?: FuelCalc.MILEAGE
    WidgetData.save(getApplication(), WidgetSnapshot(
      fuelPct = t.fuelPct,
      batteryV = t.batteryV,
      status = Store.vehicle.status,
      variant = Store.vehicle.variant,
      model = Store.vehicle.model,
      tripMin = tr?.durMin ?: 0,
      tripDate = tr?.date ?: "",
      tripStart = tr?.start ?: "",
      tripEnd = tr?.end ?: "",
      tripFrom = tr?.fromTitle ?: "",
      tripTo = tr?.toTitle ?: "",
      tripType = tr?.type ?: "Personal",
      tripKm = tr?.km ?: 0.0,
      tripScore = tr?.score ?: 0,
      parkTitle = if (Store.parkedKnown) p.title else "",
      parkSub = if (Store.parkedKnown) p.sub else "",
      parkEpoch = p.epoch,
      parkLat = p.lat,
      parkLon = p.lon,
      odometerKm = t.odometerKm ?: 0,
      rangeKm = (t.fuelPct / 100.0 * FuelCalc.TANK_L * kmpl).toInt(),
      coolantC = t.coolantC ?: 0,
      milOn = t.milOn,
      updatedAt = System.currentTimeMillis(),
      colour = CarLook.effective(Store.vehicle.colour).name, // widgets run without the app's Store
    ))
    OpenVWWidgets.updateAll(getApplication())
  }

  private fun mapUserInfo(ui: JSONObject) {
    Store.plans = emptyList()
    val prof = ui.optJSONObject("userInfo")
    val arr = ui.optJSONArray("vehicle")
    val v = if (arr != null && arr.length() > 0) arr.optJSONObject(0) else null
    if (v != null) {
      val vin = v.optString("vinNo").ifEmpty { v.optString("vin") }
      val variant = cleanText(Crypto.decOrSelf(v.optStringAny("variant", "telematics_Variant", "telematics_ModelVariant")))
        .ifEmpty { "Skoda" }
      val model = cleanText(Crypto.decOrSelf(v.optStringAny("model", "vehicleModel", "telematics_Model", "telematics_ModelName", "telematics_ModelVariant")))
        .ifEmpty { v.optString("vehicleModel") }
        .ifEmpty { variant }
      val regNo = sequenceOf(
        cleanText(Crypto.decOrSelf(
          v.deepStringAny(
            "registrationNo", "registrationNumber", "vehicleRegNo", "vehicleRegNumber", "vehicleRegistrationNo",
            "telematics_RegistrationNumber", "regNo", "plateNo", "plateNumber", "licensePlate", "vehicleNumber",
          ),
        )),
        extractRegFromJson(v),
        extractRegFromJson(prof),
        extractRegFromJson(ui),
        cleanText(session.regNoOverride),
      ).firstOrNull { it.isNotBlank() } ?: ""
      val colour = cleanText(Crypto.decOrSelf(v.optStringAny("color", "colour", "vehicleColor", "paintColor", "exteriorColor")))
        .ifEmpty { Repo.vehicle.colour }
      val purchased = bestOwnershipDate(listOf(
        normalizeOwnershipDate(Crypto.decOrSelf(v.deepStringAny(
          "purchaseDate", "purchasedDate", "vehiclePurchaseDate", "dateOfPurchase", "registrationDate",
          "carPurchaseDate", "firstDeliveryDate", "initialRegistrationDate",
        ))),
        normalizeOwnershipDate(Crypto.decOrSelf(prof?.deepStringAny(
          "purchaseDate", "purchasedDate", "vehiclePurchaseDate", "dateOfPurchase", "registrationDate",
          "carPurchaseDate", "firstDeliveryDate", "initialRegistrationDate",
        ) ?: "")),
        extractOwnershipDateFromJson(v),
        extractOwnershipDateFromJson(prof),
        extractOwnershipDateFromJson(ui),
      ))
      if (purchased.isNotBlank()) {
        session.connectedSince = purchased
        Store.connectedSince = purchased
      } else {
        val nonPurchase = setOf(
          normalizeOwnershipDate(v.optStringAny("insuranceStartDate", "insuranceExpireDate", "puc_StartDate", "puc_ExpireDate")),
          normalizeOwnershipDate(v.optStringAny("serviceStartDate", "serviceEndDate", "standardWarrantyStartDate", "standardWarrantyEndDate")),
        ).filter { it.isNotBlank() }.toSet()
        val current = normalizeOwnershipDate(Store.connectedSince)
        if (current.isNotBlank() && current in nonPurchase) {
          session.connectedSince = ""
          Store.connectedSince = ""
        }
      }
      val odoFromProfile = v.deepIntAny(
        "odometer", "Odometer", "odometerReading", "OdometerReading",
        "vehicleOdometer", "VehicleOdometer", "totalDistance", "TotalDistance",
      )
      if (odoFromProfile > 0) {
        Store.telemetry = Store.telemetry.copy(odometerKm = odoFromProfile)
      }
      session.vin = vin
      val tele = Crypto.decOrSelf(v.optString("telematicsMobileNo"))
        .ifEmpty { session.mobile }
      session.telePhone = normalizePhoneForTelematics(tele)
      Store.vehicle = VehicleInfo(
        variant = variant,
        model = model,
        vehicleName = if (vin.isNotEmpty()) "Factory:$vin" else Repo.vehicle.vehicleName,
        regNo = regNo,
        colour = colour,
        engineType = Crypto.decOrSelf(v.optStringAny("engineNo", "engineNumber")).ifEmpty { Repo.vehicle.engineType },
        dongleImei = v.optStringAny("dongleImei", "dongleIMEI", "imei").ifEmpty { Repo.vehicle.dongleImei },
        dongleFirmware = v.optStringAny("dongleFirmware", "firmwareVersion").ifEmpty { Repo.vehicle.dongleFirmware },
        status = Store.vehicle.status,
      )

      fun pick(vararg keys: String): String {
        val sources = listOfNotNull(v, prof, ui)
        for (src in sources) {
          val raw = src.deepStringAny(*keys)
          val dec = Crypto.decOrSelf(raw).trim()
          if (dec.isNotEmpty()) return dec
        }
        return ""
      }
      fun pickBool(vararg keys: String): Boolean? {
        val sources = listOfNotNull(v, prof, ui)
        for (src in sources) {
          src.deepBooleanAny(*keys)?.let { return it }
        }
        return null
      }
      fun addPlan(
        target: MutableList<PlanInfo>,
        title: String,
        name: String,
        start: String,
        end: String,
        active: Boolean?,
      ) {
        val whenText = when {
          start.isNotBlank() && end.isNotBlank() -> "$start - $end"
          end.isNotBlank() -> "Valid until $end"
          start.isNotBlank() -> "From $start"
          else -> ""
        }
        val details = listOf(name, whenText).filter { it.isNotBlank() }.joinToString("\n")
        if (details.isBlank() && active == null) return
        target += PlanInfo(title = title, details = details, active = active)
      }

      val plans = mutableListOf<PlanInfo>()
      addPlan(
        plans,
        title = "Insurance",
        name = pick("insurancePlanName", "insuranceName", "policyName"),
        start = pick("insuranceStartDate", "insuranceValidFrom", "insuranceFromDate"),
        end = pick("insuranceEndDate", "insuranceExpiryDate", "insuranceValidTo", "policyEndDate"),
        active = pickBool("insuranceActive", "isInsuranceActive", "insuranceStatus"),
      )
      addPlan(
        plans,
        title = "Road Side Assistance",
        name = pick("rsaPlanName", "roadsideAssistanceName", "roadSideAssistanceName"),
        start = pick("rsaStartDate", "roadsideAssistanceStartDate", "roadSideAssistanceStartDate"),
        end = pick("rsaEndDate", "roadsideAssistanceEndDate", "roadSideAssistanceEndDate"),
        active = pickBool("rsaActive", "isRsaActive", "isRoadsideAssistanceActive"),
      )
      addPlan(
        plans,
        title = "Service & Maintenance Plan",
        name = pick("smpPlanName", "servicePlanName", "maintenancePlanName"),
        start = pick("smpStartDate", "servicePlanStartDate", "maintenancePlanStartDate"),
        end = pick("smpEndDate", "servicePlanEndDate", "maintenancePlanEndDate"),
        active = pickBool("smpActive", "isSmpActive", "isServicePlanActive"),
      )
      addPlan(
        plans,
        title = "Extended Warranty",
        name = pick("extendedWarrantyName", "ewPlanName", "warrantyPlanName"),
        start = pick("extendedWarrantyStartDate", "ewStartDate", "warrantyStartDate"),
        end = pick("extendedWarrantyEndDate", "ewEndDate", "warrantyEndDate"),
        active = pickBool("extendedWarrantyActive", "isExtendedWarrantyActive", "isWarrantyActive"),
      )
      Store.plans = plans
    }
    if (prof != null) {
      val nm = Crypto.decOrSelf(prof.optString("name")).ifEmpty { Repo.owner.name }
      val addr = resolveOwnerAddress(prof, v, ui)
      Store.owner = Owner(
        name = nm,
        email = Crypto.decOrSelf(prof.optString("primaryMailID")),
        mobile = session.telePhone.ifEmpty { "+91" + session.mobile },
        address = addr,
      )
    }
  }

  private suspend fun loadTelematics(full: Boolean) {
    if (session.vin.isEmpty()) return
    if (session.cerenceToken.isEmpty()) {
      val ct = Api.cerenceToken(session.userId)
      session.cerenceToken = ct.optString("cerenceToken").ifEmpty { ct.optString("CerenceToken") }
    }
    if (session.cerenceToken.isEmpty()) return
    if (session.teleToken.isEmpty()) {
      val phone = normalizePhoneForTelematics(session.telePhone.ifEmpty { "+91" + session.mobile })
      val tt = Api.telematicsToken(session.cerenceToken, phone, session.vin)
      val tok = tt.optJSONObject("loginInfo")?.optJSONObject("tokenInfo")?.optString("accessToken")
        ?: tt.optString("accessToken")
      if (!tok.isNullOrEmpty()) session.teleToken = tok
      else session.cerenceToken = "" // stale CMS token: re-fetch it on the next poll
      val teleAddr = resolveOwnerAddress(tt.optJSONObject("userInfo"), tt)
      if (teleAddr.isNotBlank()) {
        Store.owner = Store.owner.copy(address = teleAddr)
      }
      // "connected since" = the telematics account creation date
      tt.optJSONObject("userInfo")?.optString("createDate")?.takeIf { it.isNotEmpty() && session.connectedSince.isBlank() }?.let {
        val normalized = normalizeOwnershipDate(it)
        if (normalized.isNotBlank()) {
          session.connectedSince = normalized
          Store.connectedSince = normalized
        }
      }
    }
    if (session.teleToken.isEmpty()) return
    if (session.carId.isEmpty()) {
      val rc = Api.resolveCar(session.vin, session.teleToken)
      rc.optJSONArray("cars")?.optJSONObject(0)?.let {
        session.carId = it.optString("_id").ifEmpty { it.optString("id") }
      }
    }
    if (session.carId.isEmpty()) return
    val t = Api.telematics(session.carId, session.teleToken)
    if (t.optInt("_status") == 401) { session.teleToken = ""; return }
    applyCar(t, live = false)
    if (full) { runCatching { loadActivity() }; lastFull = System.currentTimeMillis() }
    if (full && widgetsDue) { widgetsDue = false; runCatching { saveWidgets() } }
  }

  /** Car JSON (REST GET /car/{id} or a pushed "carStatus") -> Store. Missing fields keep their last value. */
  private suspend fun applyCar(t: JSONObject, live: Boolean) {
    val tj = t.optJSONObject("telemetry")
      ?: t.optJSONObject("carStatus")?.optJSONObject("telemetry")
      ?: t.optJSONObject("data")?.optJSONObject("telemetry")
      ?: t
    val old = Store.telemetry
    fun metricNumber(o: JSONObject): Double? {
      for (vk in arrayOf("value", "Value")) {
        if (!o.has(vk)) continue
        when (val raw = o.opt(vk)) {
          is Number -> return raw.toDouble()
          is String -> parseNumberText(raw)?.let { return it }
        }
      }
      return null
    }
    fun metricObject(name: String): JSONObject? {
      tj.optJSONObject(name)?.let { return it }
      val it = tj.keys()
      while (it.hasNext()) {
        val k = it.next()
        if (k.equals(name, ignoreCase = true)) return tj.optJSONObject(k)
      }
      return null
    }
    fun readKeyAsNumber(key: String): Double? {
      metricObject(key)?.let { metricNumber(it)?.let { v -> return v } }
      when (val raw = tj.opt(key)) {
        is Number -> return raw.toDouble()
        is String -> parseNumberText(raw)?.let { return it }
      }
      return null
    }
    fun gi(vararg keys: String): Int? {
      for (key in keys) {
        readKeyAsNumber(key)?.toInt()?.let { return it }
      }
      return null
    }
    fun gd(vararg keys: String): Double? {
      for (key in keys) {
        readKeyAsNumber(key)?.let { return it }
      }
      return null
    }
    fun findByFragment(fragments: List<String>, excludes: List<String> = emptyList(), validate: (Int) -> Boolean = { true }): Int? {
      val fs = fragments.map { it.lowercase() }
      val xs = excludes.map { it.lowercase() }
      val it = tj.keys()
      while (it.hasNext()) {
        val k = it.next()
        val lk = k.lowercase()
        if (fs.none { lk.contains(it) }) continue
        if (xs.any { lk.contains(it) }) continue
        val n = readKeyAsNumber(k)?.toInt() ?: continue
        if (validate(n)) return n
      }
      return null
    }
    fun gb(vararg keys: String): Boolean? {
      for (key in keys) {
        metricObject(key)?.let { o ->
          if (o.has("value")) return o.optBoolean("value")
          if (o.has("Value")) return o.optBoolean("Value")
        }
        when (val raw = tj.opt(key)) {
          is Boolean -> return raw
          is String -> {
            when (raw.trim().lowercase()) {
              "true" -> return true
              "false" -> return false
            }
          }
        }
      }
      return null
    }
    val rpm = gi("EngineSpeed", "RPM", "EngineRPM", "engineRpm", "engineRPM")
      ?: findByFragment(listOf("rpm", "enginespeed", "engine_speed"))
    val fuelRaw = gd(
      "FuelLevel", "fuelLevel", "Fuel", "FuelPercentage", "fuelPercentage",
      "fuelPercent", "fuelPct", "fuel_level", "fuel_percent", "fuel_pct",
      "fuelLevelPercentage", "stateOfFuel", "remainingFuel",
    ) ?: findByFragment(
      fragments = listOf("fuellevel", "fuellevel", "fuelpercent", "fuel_percentage", "fuelpct"),
      excludes = listOf("distance", "range", "trip", "avg", "average", "used", "consumption", "economy", "tank", "capacity"),
      validate = { it in 0..100 },
    )?.toDouble()
    val fuelPct = fuelRaw?.let { n ->
      val pct = if (n in 0.0..1.0) (n * 100.0) else n
      pct.toInt().coerceIn(0, 100)
    } ?: old.fuelPct
    val odometer = gi(
      "Odometer", "odometer", "VehicleOdometer", "OdometerReading", "Mileage", "TotalDistance",
      "odometerKm", "totalOdometer", "totalMileage", "tripOdometer", "totalDistanceKm", "vehicleOdo", "odo",
    ) ?: findByFragment(
      fragments = listOf("odometer", "mileage"),
      excludes = listOf("fuel", "trip", "since", "dtc", "mil", "engine", "avg", "average"),
    ) ?: old.odometerKm
    val engineState = tj.optStringAny("engine", "engineStatus").trim()
    val engineOn = when {
      engineState.equals("on", ignoreCase = true) -> true
      engineState.equals("off", ignoreCase = true) -> false
      else -> gb("IsEngineOn", "engineOn", "ignitionOn") ?: old.engineOn
    }
    Store.telemetry = Telemetry(
      speedKmh = gi("Speed") ?: old.speedKmh,
      rpm = rpm ?: old.rpm,
      fuelPct = fuelPct,
      batteryV = gd("ControlModuleVoltage") ?: old.batteryV,
      coolantC = gi("CoolantTemperature") ?: old.coolantC,
      intakeC = gi("IntakeAirTemperature") ?: old.intakeC,
      engineLoadPct = gi("EngineLoad") ?: old.engineLoadPct,
      odometerKm = odometer,
      milOn = gb("IsMILOn") ?: old.milOn,
      distanceWithMil = gi("DistanceWithMIL") ?: old.distanceWithMil,
      distanceSinceDtc = gi("DistanceSinceDTCClear") ?: old.distanceSinceDtc,
      engineOn = engineOn,
    )
    val regNo = sequenceOf(
      cleanText(Crypto.decOrSelf(
        t.deepStringAny(
          "registrationNo", "registrationNumber", "vehicleRegNo", "vehicleRegNumber", "vehicleRegistrationNo",
          "telematics_RegistrationNumber", "regNo", "plateNo", "plateNumber", "licensePlate", "registration", "vehicleNumber",
        ),
      )).trim(),
      extractRegFromJson(t),
    ).firstOrNull { it.isNotBlank() } ?: ""
    if (regNo.isNotEmpty()) {
      Store.vehicle = Store.vehicle.copy(regNo = regNo)
      session.regNoOverride = regNo
    } else if (Store.vehicle.regNo.isBlank() && session.regNoOverride.isNotBlank()) {
      Store.vehicle = Store.vehicle.copy(regNo = session.regNoOverride)
    }
    Store.vehicle = Store.vehicle.copy(status = if ((Store.telemetry.speedKmh ?: 0) > 0 || Store.telemetry.engineOn) "Driving" else "Parked")
    // dongle health
    t.optJSONArray("devices")?.optJSONObject(0)?.let { d ->
      Store.dongle = Dongle(d.optInt("netSignal"), d.optString("status"), d.optJSONObject("firmware")?.optString("version") ?: "")
    }
    // pushes carry the live position: follow the car on the maps
    if (live) t.optJSONObject("location")?.let { l ->
      val lat = l.optDouble("latitude", 0.0); val lon = l.optDouble("longitude", 0.0)
      if (lat != 0.0 && lon != 0.0) {
        val ts = l.optLong("timestamp").takeIf { it > 0 } ?: t.optLong("timestamp").takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000)
        Store.parked = Store.parked.copy(lat = lat, lon = lon, epoch = ts)
        renamePlace(lat, lon)
      }
    }
    Store.live = true
  }

  /** Geocode the car's spot only after it moved ~150 m, so a drive doesn't spam the geocoder. */
  private suspend fun renamePlace(lat: Double, lon: Double) {
    val prev = lastGeo
    if (prev != null && abs(prev.first - lat) < 0.0014 && abs(prev.second - lon) < 0.0014) return
    lastGeo = lat to lon
    val (title, sub) = withContext(Dispatchers.IO) { Places.lookup(getApplication(), lat, lon) }
    Store.parked = Store.parked.copy(title = title, sub = sub)
  }

  /** Pushed "events" message (engine on/off, alarms) -> top of Updates. */
  private fun applyEvent(e: JSONObject) {
    val kind = e.optString("type")
    val title = when (kind) {
      "engine" -> if (e.optString("event").equals("on", ignoreCase = true)) "Engine on" else "Engine off"
      else -> prettifyEvent(e.optString("name").ifEmpty { e.optString("event") }.ifEmpty { e.optString("code") })
    }
    if (title.isEmpty()) return
    val p = Store.parked
    val ev = CarEvent(e.optString("id").ifEmpty { "live-${e.optLong("timestamp")}" }, kind, title,
      e.optLong("timestamp").takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000), p.lat, p.lon)
    Store.events = listOf(ev) + Store.events.filter { it.id != ev.id }
    if (kind == "engine") {
      val on = title == "Engine on"
      Store.telemetry = Store.telemetry.copy(engineOn = on)
      Store.vehicle = Store.vehicle.copy(status = if (on) "Driving" else "Parked")
      // a finished drive becomes a trip on the server shortly after engine off: reload trips then
      if (!on) viewModelScope.launch { delay(90_000); lastFull = 0L; poll() }
    }
  }

  /** Trips, events, alert policies and the parked spot (Cerence). */
  private suspend fun loadActivity() {
    val ctx = getApplication<Application>()
    val now = System.currentTimeMillis() / 1000
    val weekAgo = now - 7 * 86400

    // ---- trips ----
    Api.trips(session.carId, session.teleToken).optJSONArray("trips")?.takeIf { it.length() > 0 }?.let { arr ->
      var count = 0; var scoreSum = 0; var maxSp = 0; var idle = 0; var brakes = 0; var sumKm = 0.0; var sumFuelL = 0.0
      var totKm = 0.0; var totMin = 0; var wTrips = 0; var wKm = 0.0; var wMin = 0
      val list = ArrayList<Trip>()
      for (i in 0 until arr.length()) {
        val tr = arr.optJSONObject(i) ?: continue
        count++
        scoreSum += tr.optInt("driverScore")
        maxSp = maxOf(maxSp, tr.optInt("maxSpeed"))
        idle += tr.optInt("idleTime")
        brakes += tr.optJSONObject("eventCount")?.optInt("sudden brake") ?: 0
        val odoKm = (tr.optJSONObject("endOdometer")?.optDouble("value") ?: 0.0) - (tr.optJSONObject("startOdometer")?.optDouble("value") ?: 0.0)
        val sf = tr.optJSONObject("startFuelLevel")?.optDouble("value") ?: 0.0
        val ef = tr.optJSONObject("endFuelLevel")?.optDouble("value") ?: 0.0
        val fuelL = (sf - ef) / 100.0 * FuelCalc.TANK_L
        if (odoKm > 0 && fuelL > 0) { sumKm += odoKm; sumFuelL += fuelL }

        val st = tr.optLong("startTime"); val et = tr.optLong("endTime")
        val km = tr.optDouble("distance", 0.0).let { if (it > 0) it / 1000.0 else odoKm }
        val min = (((et - st) + 30) / 60).toInt().coerceAtLeast(0)
        totKm += km; totMin += min
        if (st >= weekAgo) { wTrips++; wKm += km; wMin += min }

        // geocode the most recent trips; older ones use the cache or raw coordinates
        list += toTrip(tr, geocode = list.size < 10)
      }
      list.sortByDescending { it.epoch }
      Store.trips = list
      val real = if (sumFuelL > 0) sumKm / sumFuelL else 0.0
      Store.drive = DriveStats(count, if (count > 0) scoreSum / count else 0, maxSp, idle, brakes, real, totKm, totMin, wTrips, wKm, wMin)
    }

    // ---- events (engine on/off, alarms) + parked spot ----
    Api.events(session.carId, session.teleToken).optJSONArray("events")?.let { arr ->
      val list = ArrayList<CarEvent>()
      for (i in 0 until arr.length()) {
        val e = arr.optJSONObject(i) ?: continue
        val loc = e.optJSONObject("location")
        val kind = e.optString("type")
        val title = when (kind) {
          "engine" -> if (e.optString("event").equals("on", ignoreCase = true)) "Engine on" else "Engine off"
          else -> prettifyEvent(e.optString("name").ifEmpty { e.optString("event") }.ifEmpty { e.optString("code") })
        }
        list += CarEvent(e.optString("id"), kind, title, e.optLong("timestamp"), loc?.optDouble("latitude") ?: 0.0, loc?.optDouble("longitude") ?: 0.0)
      }
      list.sortByDescending { it.epoch }
      Store.events = list
      // name the places of the latest events (most hit the cache from trip endpoints)
      withContext(Dispatchers.IO) { list.take(15).forEach { Places.lookup(ctx, it.lat, it.lon) } }
      Store.events = list.toList()
    }
    // the telemetry endpoint has no position; the newest event (usually "engine off") marks where the car is
    Store.events.firstOrNull { it.lat != 0.0 }?.let { ev ->
      val (title, sub) = withContext(Dispatchers.IO) { Places.lookup(ctx, ev.lat, ev.lon) }
      Store.parked = Parked(ev.lat, ev.lon, ev.epoch, title, sub)
      withContext(Dispatchers.IO) { runCatching { MapSnapshot.update(ctx, ev.lat, ev.lon) } }
    }

    // ---- alert policies (geofences + enabled triggers) ----
    runCatching {
      val p = Api.policies(session.carId, session.teleToken)
      val fences = p.optJSONArray("fences")?.let { a -> (0 until a.length()).map { a.optJSONObject(it)?.optString("name")?.ifEmpty { null } ?: "Geofence ${it + 1}" } } ?: emptyList()
      val states = linkedMapOf<String, Boolean>()
      p.optJSONObject("firebaseNotificationTrigger")?.let { o ->
        val it = o.keys()
        while (it.hasNext()) {
          val k = it.next()
          parseFlag(o.opt(k))?.let { on -> states[k] = on }
        }
      }
      val trig = states.filterValues { it }.keys.sortedBy { it.lowercase(Locale.ENGLISH) }
      val sortedStates = states.entries.sortedBy { it.key.lowercase(Locale.ENGLISH) }.associate { it.toPair() }
      Store.alertSetup = AlertSetup(fences, trig, sortedStates)
    }

    runCatching { recomputePending() }
  }

  /** A drive that has ended (latest engine-off) but isn't in trip history yet — built from events. */
  private suspend fun recomputePending() {
    val ctx = getApplication<Application>()
    val off = Store.events.filter { it.kind == "engine" && it.title == "Engine off" && it.lat != 0.0 }.maxByOrNull { it.epoch }
    // compare with when the newest trip ENDED (its engine-off), not when it started — a single
    // trip can also span several engine on/off pairs (short stops), so allow a little slack
    val newestEnd = Store.trips.maxOfOrNull { maxOf(it.endEpoch, it.epoch) } ?: 0L
    if (off == null || off.epoch <= newestEnd + 120) { Store.pendingDrive = null; return }
    val on = Store.events.filter { it.kind == "engine" && it.title == "Engine on" && it.epoch <= off.epoch && it.lat != 0.0 }.maxByOrNull { it.epoch }
    val (tT, tS) = withContext(Dispatchers.IO) { Places.lookup(ctx, off.lat, off.lon) }
    val (fT, fS) = on?.let { withContext(Dispatchers.IO) { Places.lookup(ctx, it.lat, it.lon) } } ?: ("" to "")
    val startE = on?.epoch ?: off.epoch
    Store.pendingDrive = Trip(
      id = "pending-${off.epoch}", start = clock(startE), end = clock(off.epoch),
      fromTitle = fT, fromSub = fS, toTitle = tT, toSub = tS,
      durMin = (((off.epoch - startE) + 30) / 60).toInt().coerceAtLeast(0),
      type = "Personal", date = day(startE), epoch = startE, pending = true,
    )
  }

  /** One Cerence trip JSON -> Trip. [geocode] = look up place names; otherwise cache or raw coordinates. */
  private suspend fun toTrip(tr: JSONObject, geocode: Boolean): Trip {
    val ctx = getApplication<Application>()
    val odoKm = (tr.optJSONObject("endOdometer")?.optDouble("value") ?: 0.0) - (tr.optJSONObject("startOdometer")?.optDouble("value") ?: 0.0)
    val sf = tr.optJSONObject("startFuelLevel")?.optDouble("value") ?: 0.0
    val ef = tr.optJSONObject("endFuelLevel")?.optDouble("value") ?: 0.0
    val st = tr.optLong("startTime"); val et = tr.optLong("endTime")
    val km = tr.optDouble("distance", 0.0).let { if (it > 0) it / 1000.0 else odoKm }
    val min = (((et - st) + 30) / 60).toInt().coerceAtLeast(0)
    val sl = tr.optJSONObject("startLocation"); val el = tr.optJSONObject("endLocation")
    suspend fun place(l: JSONObject?): Pair<String, String> {
      if (l == null) return "Unknown" to ""
      val lat = l.optDouble("latitude"); val lon = l.optDouble("longitude")
      return if (geocode) withContext(Dispatchers.IO) { Places.lookup(ctx, lat, lon) }
      else Places.cached(ctx, lat, lon) ?: (Places.coords(lat, lon) to "")
    }
    val (fT, fS) = place(sl); val (tT, tS) = place(el)
    return Trip(
      id = tr.optString("id"), start = clock(st), end = clock(et),
      fromTitle = fT, fromSub = fS, toTitle = tT, toSub = tS,
      durMin = min, type = tr.optJSONArray("tags")?.optString(0)?.ifEmpty { null } ?: "Personal",
      date = day(st), km = km, score = tr.optInt("driverScore"), epoch = st, endEpoch = et,
      drivingMin = tr.optInt("drivingTime"), idleMin = tr.optInt("idleTime"), haltMin = tr.optInt("haltTime"),
      maxSpeed = tr.optInt("maxSpeed"), avgSpeed = tr.optInt("speed"),
      startOdo = tr.optJSONObject("startOdometer")?.optDouble("value") ?: 0.0,
      endOdo = tr.optJSONObject("endOdometer")?.optDouble("value") ?: 0.0,
      startFuelPct = sf.toInt(), endFuelPct = ef.toInt(),
      brakes = tr.optJSONObject("eventCount")?.optInt("sudden brake") ?: 0,
      startLat = sl?.optDouble("latitude") ?: 0.0, startLon = sl?.optDouble("longitude") ?: 0.0,
      endLat = el?.optDouble("latitude") ?: 0.0, endLon = el?.optDouble("longitude") ?: 0.0,
    )
  }

  /**
   * Trips between two picked days. [startMs]/[endMs] are the date picker's values (UTC midnight of
   * each day); the whole local days are searched. Cerence allows max 30 days per query, so longer
   * ranges are fetched window by window and merged.
   */
  fun searchTrips(startMs: Long, endMs: Long) {
    val range = startMs to endMs
    searchJob?.cancel()
    Store.tripRange = range; Store.tripResults = emptyList(); Store.tripSearchError = null; Store.tripSearching = true
    searchJob = viewModelScope.launch {
      try {
        if (session.carId.isEmpty() || session.teleToken.isEmpty()) loadLock.withLock { loadTelematics(full = false) }
        if (session.carId.isEmpty() || session.teleToken.isEmpty()) throw Exception("Couldn't reach your car right now")
        val from = localDayStart(startMs); val to = localDayStart(endMs) + 86_399
        val found = LinkedHashMap<String, JSONObject>()
        var a = from
        while (a <= to) {
          val b = minOf(a + MAX_WINDOW - 1, to)
          var r = Api.trips(session.carId, session.teleToken, a, b)
          if (r.optInt("_status") == 401) { // token expired: re-mint once
            session.teleToken = ""; loadLock.withLock { loadTelematics(full = false) }
            r = Api.trips(session.carId, session.teleToken, a, b)
          }
          val arr = r.optJSONArray("trips")
          if (arr == null && r.has("message")) throw Exception(r.optString("message"))
          if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { found[it.optString("id").ifEmpty { "t${it.optLong("startTime")}" }] = it }
          a = b + 1
        }
        val sorted = found.values.filter { it.optLong("startTime") in from..to }.sortedByDescending { it.optLong("startTime") }
        // show right away with cached names, then fill in real place names
        Store.tripResults = sorted.map { toTrip(it, geocode = false) }
        Store.tripSearching = false
        if (sorted.isNotEmpty()) Store.tripResults = sorted.mapIndexed { i, j -> toTrip(j, geocode = i < 40) }
      } catch (e: kotlinx.coroutines.CancellationException) { throw e
      } catch (e: Exception) {
        Store.tripSearchError = e.message ?: "Couldn't load trips"
      } finally {
        if (Store.tripRange == range) Store.tripSearching = false
      }
    }
  }

  fun clearTripSearch() {
    searchJob?.cancel(); searchJob = null
    Store.tripRange = null; Store.tripResults = emptyList(); Store.tripSearching = false; Store.tripSearchError = null
  }

  /** Date-picker value (UTC midnight of a calendar day) -> that day's local midnight, epoch seconds. */
  private fun localDayStart(utcMs: Long): Long {
    val u = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMs }
    val l = java.util.Calendar.getInstance().apply {
      clear(); set(u.get(java.util.Calendar.YEAR), u.get(java.util.Calendar.MONTH), u.get(java.util.Calendar.DAY_OF_MONTH))
    }
    return l.timeInMillis / 1000
  }

  private fun clock(epoch: Long) = SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(Date(epoch * 1000))
  private fun day(epoch: Long) = SimpleDateFormat("d MMM", Locale.ENGLISH).format(Date(epoch * 1000))

  private companion object {
    const val MAX_WINDOW = 2_592_000L // Cerence: "The max query time range is 2592000" (30 days)
  }
}
