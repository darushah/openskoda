package dev.openvw.auto

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import dev.openvw.data.CarLook
import dev.openvw.data.WidgetData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OpenSkodaCarAppService : CarAppService() {
  override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

  override fun onCreateSession(): Session = OpenSkodaCarSession()
}

private class OpenSkodaCarSession : Session() {
  override fun onCreateScreen(intent: Intent): Screen = OpenSkodaOverviewScreen(carContext)
}

private class OpenSkodaOverviewScreen(carContext: CarContext) : Screen(carContext) {
  override fun onGetTemplate(): Template {
    val snap = WidgetData.load(carContext)
    val session = dev.openvw.data.Session(carContext)
    val model = CarLook.name("${snap.variant} ${snap.model}")
    val parked = if (snap.parkTitle.isNotBlank()) snap.parkTitle else "Location unavailable"
    val parkedSub = if (snap.parkSub.isNotBlank()) snap.parkSub else "Drive once to fetch parked location"
    val trip = if (snap.tripDate.isNotBlank()) "${snap.tripDate} · ${snap.tripStart} to ${snap.tripEnd}" else "No trip yet"
    val updated = if (snap.updatedAt > 0L) "Updated ${fmtTime(snap.updatedAt)}" else "Open app on phone once to sync"
    val ownership = session.connectedSince.ifBlank { "Not available yet" }

    val list = ItemList.Builder()
      .addItem(
        Row.Builder()
          .setTitle("Vehicle")
          .addText("$model · ${snap.status}")
          .addText(updated)
          .build(),
      )
      .addItem(
        Row.Builder()
          .setTitle("Fuel & battery")
          .addText("${snap.fuelPct}% fuel · ${"%.1f".format(Locale.US, snap.batteryV)}V battery")
          .addText("Range ~${snap.rangeKm} km · Odo ${snap.odometerKm} km")
          .build(),
      )
      .addItem(
        Row.Builder()
          .setTitle("Ownership")
          .addText("Purchased $ownership")
          .addText("Status: ${snap.status}")
          .build(),
      )
      .addItem(
        Row.Builder()
          .setTitle("Last trip")
          .addText(trip)
          .addText("${"%.1f".format(Locale.US, snap.tripKm)} km · ${snap.tripMin} min")
          .build(),
      )
      .addItem(
        Row.Builder()
          .setTitle("Parked")
          .addText(parked)
          .addText(parkedSub)
          .build(),
      )
      .build()

    return ListTemplate.Builder()
      .setTitle("OpenSkoda")
      .setSingleList(list)
      .setHeaderAction(Action.APP_ICON)
      .setActionStrip(
        ActionStrip.Builder()
          .addAction(
            Action.Builder()
              .setTitle("Refresh")
              .setOnClickListener { invalidate() }
              .build(),
          )
          .build(),
      )
      .build()
  }

  private fun fmtTime(epochMs: Long): String {
    val fmt = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
    return fmt.format(Date(epochMs))
  }

}
