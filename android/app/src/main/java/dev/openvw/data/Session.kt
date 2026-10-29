package dev.openvw.data

import android.content.Context
import java.util.UUID

/** Persistent session: tokens + ids, survives app restarts. */
class Session(context: Context) {
  private val sp = context.getSharedPreferences("openvw", Context.MODE_PRIVATE)

  private fun s(k: String, d: String = "") = sp.getString(k, d) ?: d
  private fun i(k: String, d: Int = 0) = sp.getInt(k, d)
  private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()
  private fun put(k: String, v: Int) = sp.edit().putInt(k, v).apply()

  var mobile: String get() = s("mobile"); set(v) = put("mobile", v)
  var deviceID: String
    get() {
      var d = s("deviceID")
      if (d.isEmpty()) { d = UUID.randomUUID().toString(); put("deviceID", d) }
      return d
    }
    set(v) = put("deviceID", v)
  var installationID: Int get() = i("installationID"); set(v) = put("installationID", v)
  var token: String get() = s("token"); set(v) = put("token", v)
  var refreshToken: String get() = s("refreshToken"); set(v) = put("refreshToken", v)
  var signStamp: Long get() = sp.getLong("signStamp", 0); set(v) = sp.edit().putLong("signStamp", v).apply()
  var loginId: Int get() = i("loginId"); set(v) = put("loginId", v)
  var userId: Int get() = i("userId"); set(v) = put("userId", v)
  var crmToken: String get() = s("crmToken"); set(v) = put("crmToken", v)
  var vin: String get() = s("vin"); set(v) = put("vin", v)
  var carId: String get() = s("carId"); set(v) = put("carId", v)
  var cerenceToken: String get() = s("cerenceToken"); set(v) = put("cerenceToken", v)
  var teleToken: String get() = s("teleToken"); set(v) = put("teleToken", v)
  // Cerence end-user login: OTP session between send and verify, and the refresh token for teleToken
  var otpSessionId: String get() = s("otpSessionId"); set(v) = put("otpSessionId", v)
  var teleRefreshToken: String get() = s("teleRefreshToken"); set(v) = put("teleRefreshToken", v)
  // owner from the Cerence login (no profile endpoint to re-read it later)
  var ownerName: String get() = s("ownerName"); set(v) = put("ownerName", v)
  var ownerEmail: String get() = s("ownerEmail"); set(v) = put("ownerEmail", v)
  // owner details typed in on this phone (no server API to change them); "" = use the account's
  var nameOverride: String get() = s("nameOverride"); set(v) = put("nameOverride", v)
  var emailOverride: String get() = s("emailOverride"); set(v) = put("emailOverride", v)
  var addressOverride: String get() = s("addressOverride"); set(v) = put("addressOverride", v)
  var telePhone: String get() = s("telePhone"); set(v) = put("telePhone", v)
  var connectedSince: String get() = s("connectedSince", ""); set(v) = put("connectedSince", v)
  var regNoOverride: String get() = s("regNoOverride", ""); set(v) = put("regNoOverride", v)
  var verified: Boolean get() = sp.getBoolean("verified", false); set(v) = sp.edit().putBoolean("verified", v).apply()
  var paintPick: String get() = s("paintPick"); set(v) = put("paintPick", v)
  var fuelPrice: Float get() = sp.getFloat("fuelPrice", 0f); set(v) = sp.edit().putFloat("fuelPrice", v).apply()
  var dark: Boolean get() = sp.getBoolean("dark", false); set(v) = sp.edit().putBoolean("dark", v).apply()
  var privacy: Boolean get() = sp.getBoolean("privacy", false); set(v) = sp.edit().putBoolean("privacy", v).apply()

  // Not gated on loginId: the Cerence login has none.
  val loggedIn: Boolean get() = verified && installationID != 0

  fun clearAuth() {
    verified = false; token = ""; crmToken = ""; teleToken = ""; cerenceToken = ""; teleRefreshToken = ""; otpSessionId = ""; ownerName = ""; ownerEmail = ""
    nameOverride = ""; emailOverride = ""; addressOverride = ""
  }
}
