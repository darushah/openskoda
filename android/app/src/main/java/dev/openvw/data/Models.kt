package dev.openvw.data

// --- Data shapes (mirror the live My Skoda Connect / OpenSkoda API fields) ---

data class Telemetry(
  val speedKmh: Int? = null,
  val rpm: Int? = null,
  val fuelPct: Int = 0,
  val batteryV: Double = 12.4,
  val coolantC: Int? = null,
  val intakeC: Int? = null,
  val engineLoadPct: Int? = null,
  val odometerKm: Int? = null,
  val milOn: Boolean = false,          // check-engine light
  val distanceWithMil: Int? = 0,       // km driven with MIL on
  val distanceSinceDtc: Int? = 6145,   // km since last diagnostic reset
  val engineOn: Boolean = false,
)

/** OBD dongle health. */
data class Dongle(val signal: Int = 0, val status: String = "", val firmware: String = "")

/** Driving-behaviour summary computed from per-trip telematics. */
data class DriveStats(
  val tripCount: Int = 0,
  val avgScore: Int = 0,
  val maxSpeedKmh: Int = 0,
  val idleMin: Int = 0,
  val harshBrakes: Int = 0,
  val realKmpl: Double = 0.0,   // measured mileage from per-trip fuel use (0 = unknown)
  val totalKm: Double = 0.0,
  val totalMin: Int = 0,
  val weekTrips: Int = 0,       // trips in the last 7 days
  val weekKm: Double = 0.0,
  val weekMin: Int = 0,
)

/** Something the car reported: engine on/off or a driving alarm (sudden brake, sharp turn…). */
data class CarEvent(
  val id: String,
  val kind: String,      // "engine" | "alarm"
  val title: String,     // "Engine off", "Sudden brake"
  val epoch: Long,       // seconds
  val lat: Double,
  val lon: Double,
)

/** Where the car was last seen (latest event / trip end). */
data class Parked(
  val lat: Double = 12.9756,
  val lon: Double = 77.6050,
  val epoch: Long = 0,
  val title: String = "MG Road",
  val sub: String = "Bengaluru, Karnataka",
)

/** Geofences + alert-trigger status from Cerence /policies. */
data class AlertSetup(
  val fences: List<String> = emptyList(),
  val triggers: List<String> = emptyList(),
  val triggerStates: Map<String, Boolean> = emptyMap(),
)

/** Car render + paint selection from the account (or the owner's manual pick). */
object CarLook {
  enum class Paint(val label: String, val swatch: Long) {
    WHITE("White", 0xFFF4F4F2), BLACK("Black", 0xFF16161A), GREY("Grey", 0xFF5B6B78), RED("Red", 0xFFC8102E), BLUE("Blue", 0xFF1F7FD8), YELLOW("Yellow", 0xFFF2C200),
  }

  private val words = listOf(
    Regex("white|silver") to Paint.WHITE,
    Regex("grey|gray|carbon|steel|graphite") to Paint.GREY,
    Regex("black") to Paint.BLACK,
    Regex("red|cherry|rosso|crimson|maroon") to Paint.RED,
    Regex("blue|lava|race|water") to Paint.BLUE,
    Regex("yellow|curcuma|gold|bronx|beige|cappuccino") to Paint.YELLOW,
    Regex("green|shimla") to Paint.BLUE,
  )

  private fun current() = Store.vehicle.variant + " " + Store.vehicle.model

  private val knownModels = listOf("KUSHAQ", "SLAVIA", "KODIAQ", "OCTAVIA", "SUPERB", "RAPID", "FABIA", "KYLAQ")

  /** Short model label from variant/model text; falls back to "Skoda". */
  fun name(model: String = current()): String {
    val up = model.uppercase()
    return knownModels.firstOrNull { up.contains(it) }?.lowercase()
      ?.replaceFirstChar { it.uppercase() } ?: "Skoda"
  }

  /** Paints we can render in the current generic silhouette. */
  fun paints(model: String = current()): List<Paint> = Paint.entries

  /** Fallback when the account colour has no matching render. */
  private fun fallback(model: String) = Paint.WHITE

  /** The first colour word wins: "Candy White Deep/Black" is a white car with a black roof. */
  fun paint(colour: String, model: String = current()): Paint {
    val c = colour.lowercase()
    val p = words.mapNotNull { (re, p) -> re.find(c)?.let { it.range.first to p } }.minByOrNull { it.first }?.second
    return p?.takeIf { it in paints(model) } ?: fallback(model)
  }

  /** Paint to show: the owner's manual pick (Store.paintPick, a Paint name) wins over the account colour. */
  fun effective(colour: String, model: String = current()): Paint =
    paints(model).firstOrNull { it.name == Store.paintPick } ?: paint(colour, model)

  private fun detectModel(model: String): String? {
    val up = model.uppercase()
    return knownModels.firstOrNull { up.contains(it) }
  }

  fun image(colour: String, model: String = current()): Int {
    val modelKey = detectModel(model)
    val p = effective(colour, model)
    when (modelKey) {
      "KUSHAQ" -> return when (p) {
        Paint.WHITE -> dev.openvw.R.drawable.kushaq_white
        Paint.BLACK -> dev.openvw.R.drawable.kushaq_black
        Paint.GREY -> dev.openvw.R.drawable.kushaq_grey
        Paint.RED -> dev.openvw.R.drawable.kushaq_red
        Paint.BLUE -> dev.openvw.R.drawable.kushaq_blue
        Paint.YELLOW -> dev.openvw.R.drawable.kushaq_grey
      }
      "SLAVIA" -> return when (p) {
        Paint.WHITE -> dev.openvw.R.drawable.slavia_white
        Paint.BLACK -> dev.openvw.R.drawable.slavia_black
        Paint.GREY -> dev.openvw.R.drawable.slavia_grey
        Paint.RED -> dev.openvw.R.drawable.slavia_red
        Paint.BLUE -> dev.openvw.R.drawable.slavia_blue
        Paint.YELLOW -> dev.openvw.R.drawable.slavia_white
      }
      "KODIAQ" -> return when (p) {
        Paint.WHITE -> dev.openvw.R.drawable.kodiaq_white
        Paint.BLACK -> dev.openvw.R.drawable.kodiaq_black
        Paint.GREY -> dev.openvw.R.drawable.kodiaq_grey
        Paint.RED -> dev.openvw.R.drawable.kodiaq_grey
        Paint.BLUE -> dev.openvw.R.drawable.kodiaq_blue
        Paint.YELLOW -> dev.openvw.R.drawable.kodiaq_yellow
      }
      "OCTAVIA" -> return when (p) {
        Paint.WHITE -> dev.openvw.R.drawable.octavia_white
        Paint.BLACK -> dev.openvw.R.drawable.octavia_black
        Paint.GREY -> dev.openvw.R.drawable.octavia_grey
        Paint.RED -> dev.openvw.R.drawable.octavia_grey
        Paint.BLUE -> dev.openvw.R.drawable.octavia_blue
        Paint.YELLOW -> dev.openvw.R.drawable.octavia_white
      }
      "SUPERB" -> return when (p) {
        Paint.WHITE -> dev.openvw.R.drawable.superb_black
        Paint.BLACK -> dev.openvw.R.drawable.superb_black
        Paint.GREY -> dev.openvw.R.drawable.superb_black
        Paint.RED -> dev.openvw.R.drawable.superb_red
        Paint.BLUE -> dev.openvw.R.drawable.superb_blue
        Paint.YELLOW -> dev.openvw.R.drawable.superb_red
      }
      "FABIA" -> return dev.openvw.R.drawable.fabia_oem
      "KYLAQ" -> return when (p) {
        Paint.WHITE -> dev.openvw.R.drawable.kylaq_white
        Paint.BLACK -> dev.openvw.R.drawable.kylaq_black
        Paint.GREY -> dev.openvw.R.drawable.kylaq_grey
        Paint.RED -> dev.openvw.R.drawable.kylaq_red
        Paint.BLUE -> dev.openvw.R.drawable.kylaq_blue
        Paint.YELLOW -> dev.openvw.R.drawable.kylaq_white
      }
      "RAPID" -> return dev.openvw.R.drawable.skoda_generic_car
    }
    return when (p) {
      Paint.WHITE -> dev.openvw.R.drawable.virtus_hero_white
      Paint.BLACK -> dev.openvw.R.drawable.virtus_hero_black
      Paint.GREY -> dev.openvw.R.drawable.kushaq_hero_grey
      Paint.RED -> dev.openvw.R.drawable.virtus_hero_red
      Paint.BLUE -> dev.openvw.R.drawable.kushaq_hero_blue
      Paint.YELLOW -> dev.openvw.R.drawable.kushaq_hero_yellow
    }
  }
}

data class VehicleInfo(
  val variant: String = "Skoda",
  val model: String = "SKODA",
  val vehicleName: String = "Factory:MEXA11AA0AA000000",
  val regNo: String = "",
  val colour: String = "Candy White Deep/Black",
  val engineType: String = "DUM A00000",
  val dongleImei: String = "860000000000000",
  val dongleFirmware: String = "1.0.0.27",
  val status: String = "Parked",
)

data class PlanInfo(
  val title: String,
  val details: String = "",
  val active: Boolean? = null,
)

data class Owner(
  val name: String = "SKODA OWNER",
  val email: String = "",
  val mobile: String = "+91 00000 00000",
  val address: String = "",
) {
  val initials: String
    get() = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
      .take(2).joinToString("") { it.first().uppercase() }
  val first: String
    get() = name.trim().split(Regex("\\s+")).firstOrNull()
      ?.lowercase()?.replaceFirstChar { it.uppercase() } ?: ""
  val proper: String
    get() = name.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
}

data class Trip(
  val id: String,
  val start: String,
  val end: String,
  val fromTitle: String,
  val fromSub: String,
  val toTitle: String,
  val toSub: String,
  val durMin: Int,
  val type: String,
  val date: String = "16 Oct",
  val km: Double = 0.0,
  val score: Int = 0,
  val epoch: Long = 0,   // trip start, seconds
  val endEpoch: Long = 0, // trip end, seconds
  val pending: Boolean = false,  // derived from engine on/off; full trip still finalizing on the server
  val drivingMin: Int = 0,
  val idleMin: Int = 0,
  val haltMin: Int = 0,
  val maxSpeed: Int = 0,
  val avgSpeed: Int = 0,
  val startOdo: Double = 0.0,
  val endOdo: Double = 0.0,
  val startFuelPct: Int = 0,
  val endFuelPct: Int = 0,
  val brakes: Int = 0,
  val startLat: Double = 0.0,
  val startLon: Double = 0.0,
  val endLat: Double = 0.0,
  val endLon: Double = 0.0,
) {
  /** Litres burned, from the fuel-% drop × the default 50 L tank (telematics reports percent only). */
  val fuelUsedL: Double get() = (startFuelPct - endFuelPct).coerceAtLeast(0) / 100.0 * 50.0
  /** Measured mileage for this trip (km/L); 0 = not computable (fuel % didn't change). */
  val mileage: Double get() = if (fuelUsedL > 0 && km > 0) km / fuelUsedL else 0.0
}

data class Workshop(val id: String, val name: String, val address: String)

data class Spec(val label: String, val value: String, val mono: Boolean = false)

// --- Sample payload shown until live data loads (identifiers are placeholders) ---
object Repo {
  val vehicle = VehicleInfo()
  val telemetry = Telemetry()
  val owner = Owner()

  val trips = listOf(
    Trip(
      "a", "10:10 AM", "10:24 AM",
      "MG Road", "Mahatma Gandhi Rd, Bengaluru, Karnataka 560001",
      "Indiranagar", "100 Feet Rd, Indiranagar, Bengaluru, Karnataka 560038", 14, "Personal",
    ),
    Trip(
      "b", "09:13 AM", "09:40 AM",
      "Indiranagar", "100 Feet Rd, Indiranagar, Bengaluru, Karnataka 560038",
      "MG Road", "Mahatma Gandhi Rd, Bengaluru, Karnataka 560001", 27, "Personal",
    ),
  )

  val workshops = listOf(
    Workshop("w1", "Skoda Sarjapur Road", "Survey No.154/1, Doddakanahalli Village, Varthur Hobli, Bengaluru East"),
    Workshop("w2", "Skoda Bangalore", "Elite Motors P Ltd, 49/8-9-10, Singasandra Village, Hosur Main Road"),
    Workshop("w3", "Skoda Old Airport Road", "#227, 16th D Main Road, HAL 2nd Stage, Bengaluru"),
  )

  val specs: List<Spec>
    get() = listOf(
      Spec("Model", vehicle.model),
      Spec("Variant", vehicle.variant),
      Spec("Colour", vehicle.colour),
      Spec("Fuel", "Petrol"),
      Spec("Engine type", vehicle.engineType, mono = true),
      Spec("Dongle firmware", vehicle.dongleFirmware, mono = true),
      Spec("Dongle IMEI", vehicle.dongleImei, mono = true),
    )
}
