package dev.openvw.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

object Api {
  private const val AUTH = "https://savwconnect.com/authentication/api/"
  private const val REG = "https://savwconnect.com/registration/api/"
  private const val PA = "https://savwconnect.com/personalassist/api/"
  private const val CERENCE = "https://savwipl-connect15.in.onecloud.cerenceapi.com"
  private const val BRAND = "C"
  private const val UA = "okhttp/4.12.0"

  private data class HttpRes(
    val code: Int,
    val text: String,
    val headers: Map<String, String>
  )

  // ---------- low level ----------
  private fun reqEx(method: String, urlStr: String, body: String?, auth: String?): HttpRes {
    val c = (URL(urlStr).openConnection() as HttpURLConnection).apply {
      requestMethod = method
      connectTimeout = 15000
      readTimeout = 20000
      setRequestProperty("Accept", "application/json")
      setRequestProperty("User-Agent", UA)
      setRequestProperty("brand", BRAND)
      if (auth != null) setRequestProperty("Authorization", if (auth.startsWith("Bearer")) auth else "Bearer $auth")
      if (body != null) {
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
      }
    }
    return try {
      if (body != null) c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
      val code = c.responseCode
      val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
      val text = stream.bufferedReader().use { it.readText() }
      val headers = c.headerFields
        .filterKeys { it != null }
        .mapValues { (_, v) -> v.joinToString(",") }
      HttpRes(code, text, headers)
    } catch (e: Exception) {
      HttpRes(-1, e.message ?: "network error", emptyMap())
    } finally {
      c.disconnect()
    }
  }

  private fun req(method: String, urlStr: String, body: String?, auth: String?): Pair<Int, String> {
    val r = reqEx(method, urlStr, body, auth)
    return r.code to r.text
  }

  private fun json(text: String): JSONObject = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
  private fun jsonOrRaw(text: String): JSONObject = runCatching { JSONObject(text) }.getOrElse { JSONObject().put("_raw", text) }
  private fun headerValue(headers: Map<String, String>, name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()?.takeIf { it.isNotEmpty() }
  private fun parseRetryAfterSeconds(v: String?): Int? = v?.toIntOrNull()?.takeIf { it > 0 }

  /** Encrypted call: payload object -> {rqt: enc}; response {data: hex} -> decrypted object. */
  private fun encCall(url: String, payload: JSONObject, token: String?, reqKey: String, resKey: String): JSONObject {
    val body = JSONObject().put("rqt", Crypto.encHex(payload.toString(), reqKey)).toString()
    val (code, text) = req("POST", url, body, token)
    val j = json(text)
    val enc = j.optString("data").ifEmpty { j.optString("encryptedResponse").ifEmpty { j.optString("response") } }
    if (enc.isNotEmpty() && enc.matches(Regex("[0-9A-Fa-f]+"))) {
      for (k in listOf(resKey, if (resKey == Crypto.GEN) Crypto.DATA else Crypto.GEN)) {
        val p = runCatching { Crypto.decHex(enc, k) }.getOrNull()
        if (!p.isNullOrEmpty()) {
          val d = runCatching { JSONObject(p) }.getOrNull()
          if (d != null) return d.put("_status", code)
        }
      }
      return JSONObject().put("_decryptFailed", true).put("_status", code)
    }
    return j.put("_status", code)
  }

  // ---------- endpoints ----------
  suspend fun appInstallation(deviceId: String): JSONObject = withContext(Dispatchers.IO) {
    val p = JSONObject()
      .put("appDisplayVersion", "2.0.29").put("appVersionWhileInstall", 44).put("brand", BRAND)
      .put("deviceID", deviceId).put("deviceName", "google").put("deviceOS", "Android")
      .put("deviceOsVersion", "14").put("deviceType", "Mobile").put("pushNotificationID", "")
    val (code, text) = req("POST", AUTH + "Installation/1.0/appInstallation", p.toString(), null)
    json(text).put("_status", code)
  }

  suspend fun authenticate(installationID: Int, signStamp: Long): JSONObject = withContext(Dispatchers.IO) {
    encCall(AUTH + "Authentication/1.0/authenticationEncryption",
      JSONObject().put("installationID", installationID).put("brand", BRAND).put("signStamp", signStamp),
      null, Crypto.GEN, Crypto.GEN)
  }

  suspend fun registerUser(mobile: String, installationID: Int, token: String): JSONObject = withContext(Dispatchers.IO) {
    val p = JSONObject()
      .put("brand", BRAND).put("emailID", Crypto.encHex("", Crypto.DATA)).put("installationID", installationID)
      .put("mobileNo", Crypto.encHex(mobile, Crypto.DATA)).put("sixDigitOTP", true).put("type", "Mobile")
    val (code, text) = req("POST", REG + "Registration/1.0/registerUser", p.toString(), token)
    json(text).put("_status", code)
  }

  suspend fun resendLoginOTP(loginId: Int, userId: Int, mobile: String, installationID: Int, token: String): JSONObject = withContext(Dispatchers.IO) {
    val p = JSONObject()
      .put("loginId", loginId)
      .put("installationID", installationID)
      .put("userId", userId)
      .put("emailID", Crypto.encHex("", Crypto.DATA))
      .put("mobileNo", Crypto.encHex(mobile, Crypto.DATA))
      .put("type", "Mobile")
      .put("sixDigitOTP", true)
      .put("brand", BRAND)
    val (code, text) = req("POST", REG + "UserLogin/1.0/resendLoginOTP", p.toString(), token)
    json(text).put("_status", code)
  }

  suspend fun verifyLoginOTP(otp: String, s: Session): JSONObject = withContext(Dispatchers.IO) {
    val encMobile = Crypto.encHex(s.mobile, Crypto.DATA)
    val p = JSONObject()
      .put("otp", otp).put("installationID", s.installationID).put("type", "Mobile").put("brand", BRAND)
      .put("type_Detail", encMobile).put("typeDetail", encMobile)
      .put("loginID", s.loginId).put("loginId", s.loginId)
      .put("userID", s.userId).put("userId", s.userId)
      .put("sixDigitOTP", true).put("signStamp", System.currentTimeMillis())
    encCall(REG + "UserLogin/1.0/verifyLoginOTPEncryption", p, s.token, Crypto.GEN, Crypto.GEN)
  }

  suspend fun crmToken(userId: Int, token: String): JSONObject = withContext(Dispatchers.IO) {
    encCall(AUTH + "CRMToken/1.0/CRMToken", JSONObject().put("userID", userId).put("brand", BRAND), token, Crypto.GEN, Crypto.GEN)
  }

  suspend fun userInfo(s: Session): JSONObject = withContext(Dispatchers.IO) {
    val p = JSONObject().put("installationID", s.installationID).put("brand", BRAND).put("userID", s.userId).put("signStamp", s.signStamp)
    encCall(PA + "UserMaster/1.0/getUserInfoEncryption", p, s.token, Crypto.GEN, Crypto.GEN)
  }

  /**
   * Some profiles return registration details only from vehicle-specific profile APIs.
   * Try the known endpoint variants used by MySkoda builds and return the first usable response.
   */
  suspend fun vehicleProfileInfo(s: Session): JSONObject? = withContext(Dispatchers.IO) {
    if (s.userId == 0) return@withContext null
    val payload = JSONObject()
      .put("installationID", s.installationID)
      .put("brand", BRAND)
      .put("userID", s.userId)
      .put("signStamp", s.signStamp)
      .put("vinNo", s.vin)
      .put("vin", s.vin)
    val candidates = listOf(
      PA + "VehicleMaster/1.0/getVehicleInfoEncryption",
      PA + "VehicleMaster/1.0/getVehicleInformationEncryption",
      PA + "VehicleMaster/1.0/getVehicleDetailsEncryption",
      PA + "VehicleInformation/1.0/getVehicleInformationEncryption",
      REG + "VehicleMaster/1.0/getVehicleInfoEncryption",
    )
    for (url in candidates) {
      val r = runCatching { encCall(url, payload, s.token, Crypto.GEN, Crypto.GEN) }.getOrNull() ?: continue
      val status = r.optInt("_status")
      if (status in 200..299 && !r.optBoolean("_decryptFailed")) return@withContext r
    }
    null
  }

  suspend fun cerenceToken(userId: Int): JSONObject = withContext(Dispatchers.IO) {
    val body = JSONObject().put("userID", userId).put("brand", BRAND).toString()
    var last = JSONObject().put("_status", -1)
    var retries = 0
    repeat(3) { attempt ->
      val r = reqEx("POST", AUTH + "CerenceToken/1.0/CerenceToken", body, null)
      val retryAfter = parseRetryAfterSeconds(headerValue(r.headers, "Retry-After"))
      val rateLimitReset = headerValue(r.headers, "X-RateLimit-Reset")
      last = json(r.text).put("_status", r.code)
      if (retryAfter != null) last.put("_retryAfterSeconds", retryAfter)
      if (!rateLimitReset.isNullOrEmpty()) last.put("_rateLimitReset", rateLimitReset)
      if (r.code != 429) return@withContext last.put("_retries", retries)
      if (attempt < 2) {
        retries += 1
        val waitMs = ((retryAfter?.coerceIn(1, 60)?.times(1000L)) ?: (1500L * (attempt + 1))) + Random.nextLong(150, 550)
        delay(waitMs)
      }
    }
    last.put("_retries", retries).put("_throttled", true)
  }

  suspend fun telematicsToken(cerenceToken: String, phone: String, vin: String): JSONObject = withContext(Dispatchers.IO) {
    val body = JSONObject().put("phone", phone).put("vins", JSONArray().put(vin)).toString()
    val (code, text) = req("POST", "$CERENCE/api/identity/v1/users/end-user/token", body, cerenceToken)
    json(text).put("_status", code)
  }

  suspend fun resolveCar(vin: String, teleToken: String): JSONObject = withContext(Dispatchers.IO) {
    val (code, text) = req("GET", "$CERENCE/api/v1/car?vin=$vin", null, teleToken)
    json(text).put("_status", code)
  }

  suspend fun telematics(carId: String, teleToken: String): JSONObject = withContext(Dispatchers.IO) {
    val (code, text) = req("GET", "$CERENCE/api/v1/car/$carId", null, teleToken)
    json(text).put("_status", code)
  }

  /** Trips; with [start]/[end] (epoch seconds) only that window — the server caps one query at 30 days. */
  suspend fun trips(carId: String, teleToken: String, start: Long? = null, end: Long? = null): JSONObject = withContext(Dispatchers.IO) {
    val q = if (start != null && end != null) "limit=200&start_date=$start&end_date=$end" else "limit=50"
    val (code, text) = req("GET", "$CERENCE/api/v1/car/$carId/trips?$q", null, teleToken)
    json(text).put("_status", code)
  }

  suspend fun events(carId: String, teleToken: String): JSONObject = withContext(Dispatchers.IO) {
    val (code, text) = req("GET", "$CERENCE/api/v1/car/$carId/events?limit=50", null, teleToken)
    json(text).put("_status", code)
  }

  suspend fun policies(carId: String, teleToken: String): JSONObject = withContext(Dispatchers.IO) {
    val (code, text) = req("GET", "$CERENCE/api/v1/car/$carId/policies", null, teleToken)
    json(text).put("_status", code)
  }

  /** Debug probe: SAVW vehicle-info style endpoints (encrypted + plain variants). */
  suspend fun savwVehicleInfoProbe(s: Session): JSONObject = withContext(Dispatchers.IO) {
    val payload = JSONObject()
      .put("installationID", s.installationID)
      .put("brand", BRAND)
      .put("BrandID", BRAND)
      .put("userID", s.userId)
      .put("signStamp", s.signStamp)
      .put("vinNo", s.vin)
      .put("vin", s.vin)
    val attempts = JSONArray()

    val encrypted = listOf(
      PA + "VehicleMaster/1.0/getVehicleInfoEncryption",
      PA + "VehicleMaster/1.0/getVehicleInformationEncryption",
      PA + "VehicleMaster/1.0/getVehicleDetailsEncryption",
      PA + "VehicleInformation/1.0/getVehicleInformationEncryption",
      REG + "VehicleMaster/1.0/getVehicleInfoEncryption",
    )
    for (url in encrypted) {
      val r = runCatching { encCall(url, payload, s.token, Crypto.GEN, Crypto.GEN) }.getOrNull()
        ?: JSONObject().put("_status", -1).put("_error", "request failed")
      attempts.put(r.put("_url", url).put("_mode", "encrypted"))
    }

    val plain = listOf(
      PA + "VehicleMaster/1.0/getVehicleInfo",
      PA + "VehicleMaster/1.0/getVehicleInformation",
      PA + "VehicleInformation/1.0/getVehicleInformation",
      PA + "UserMaster/1.0/getUserbyVehicleID",
    )
    val body = payload.toString()
    for (url in plain) {
      val (code, text) = req("POST", url, body, s.token)
      attempts.put(jsonOrRaw(text).put("_status", code).put("_url", url).put("_mode", "plain-json"))
    }

    JSONObject().put("attempts", attempts)
  }

  /** Debug probe: SAVW subscription history endpoint variants. */
  suspend fun subscriptionHistoryProbe(s: Session): JSONObject = withContext(Dispatchers.IO) {
    val payload = JSONObject()
      .put("BrandID", BRAND)
      .put("brand", BRAND)
      .put("userID", s.userId)
      .put("installationID", s.installationID)
      .put("signStamp", s.signStamp)
      .put("vinNo", s.vin)
      .put("vin", s.vin)
    val attempts = JSONArray()

    val plainUrls = listOf(
      "https://savwconnect.com/payment/api/Payment/1.0/SubscriptionHistory",
      "https://savwconnect.com/payment/api/Payment/1.0/Subscriptionhistory",
    )
    for (url in plainUrls) {
      val (code, text) = req("POST", url, payload.toString(), s.token)
      attempts.put(jsonOrRaw(text).put("_status", code).put("_url", url).put("_mode", "plain-json"))
    }

    val encUrls = listOf(
      "https://savwconnect.com/payment/api/Payment/1.0/SubscriptionHistoryEncryption",
      "https://savwconnect.com/payment/api/OnlineContract/1.0/SubscriptionHistoryEncryption",
      "https://savwconnect.com/payment/api/OnlineContract/1.0/SubscriptionHistory",
    )
    for (url in encUrls) {
      val r = runCatching { encCall(url, payload, s.token, Crypto.GEN, Crypto.GEN) }.getOrNull()
        ?: JSONObject().put("_status", -1).put("_error", "request failed")
      attempts.put(r.put("_url", url).put("_mode", "encrypted"))
    }

    JSONObject().put("attempts", attempts)
  }
}
