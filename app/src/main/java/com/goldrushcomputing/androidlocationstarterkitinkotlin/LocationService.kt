package com.goldrushcomputing.androidlocationstarterkitinkotlin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

/**
 * Created by Takamitsu Mizutori on 2018/12/08.
 */
class LocationService: Service(), LocationListener {

    companion object {
        private val FOREGROUND_SERVICE_NOTIFICATION_ID = 12345
        private val FOREGROUND_SERVICE_NOTIFICATION_CHANNEL_ID = "location_service_notification_channel"
    }

    private val LOG_TAG = LocationService::class.java.simpleName

    private val binder = LocationServiceBinder()
    private var isLocationManagerUpdatingLocation: Boolean = false

    var locationList: ArrayList<Location>

    var oldLocationList: ArrayList<Location>
    var noAccuracyLocationList: ArrayList<Location>
    var inaccurateLocationList: ArrayList<Location>
    var kalmanNGLocationList: ArrayList<Location>

    var isLogging: Boolean = false

    private var currentSpeed = 0.0f // meters/second

    private var kalmanFilter: KalmanLatLong
    private var runStartTimeInMillis: Long = 0

    var batteryLevelArray = ArrayList<Int>()
    var batteryLevelScaledArray = ArrayList<Float>()
    var batteryScale: Int = 0
    private var gpsCount: Int = 0


    /* Battery Consumption */
    private var batteryInfoReceiver: BroadcastReceiver? = null

    init {
        isLocationManagerUpdatingLocation = false
        locationList = ArrayList()
        noAccuracyLocationList = ArrayList()
        oldLocationList = ArrayList()
        inaccurateLocationList = ArrayList()
        kalmanNGLocationList = ArrayList()
        kalmanFilter = KalmanLatLong(3f)

        isLogging = false

        batteryInfoReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctxt: Context, intent: Intent) {
                val batteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)

                val batteryLevelScaled = batteryLevel / scale.toFloat()

                batteryLevelArray.add(Integer.valueOf(batteryLevel))
                batteryLevelScaledArray.add(java.lang.Float.valueOf(batteryLevelScaled))
                batteryScale = scale
            }
        }.also {
            @Suppress("DEPRECATION")
            LocalBroadcastManager.getInstance(this@LocationService).registerReceiver(it, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
    }


    override fun onStartCommand(i: Intent, flags: Int, startId: Int): Int {
        super.onStartCommand(i, flags, startId)
        startForeground()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onRebind(intent: Intent) {
        Log.d(LOG_TAG, "onRebind ")
    }

    override fun onUnbind(intent: Intent): Boolean {
        Log.d(LOG_TAG, "onUnbind ")
        return true
    }

    override fun onDestroy() {
        Log.d(LOG_TAG, "onDestroy ")
        try {
            batteryInfoReceiver?.let{
                unregisterReceiver(it)
            }
        } catch (ex: IllegalArgumentException) {
            ex.printStackTrace()
        }
    }

    //This is where we detect the app is being killed, thus stop service.
    override fun onTaskRemoved(rootIntent: Intent) {
        Log.d(LOG_TAG, "onTaskRemoved ")
        this.stopUpdatingLocation()
        stopForeground(true)
    }

    /**
     * Binder class
     *
     * @author Takamitsu Mizutori
     */
    inner class LocationServiceBinder : Binder() {
        val service: LocationService
            get() = this@LocationService
    }

    override fun onLocationChanged(newLocation: Location) {
        Log.d(LOG_TAG, "(" + newLocation.latitude + "," + newLocation.longitude + ")")

        gpsCount++

        if (isLogging) {
            //locationList.add(newLocation);
            filterAndAddLocation(newLocation)
        }

        val intent = Intent("LocationUpdated")
        intent.putExtra("location", newLocation)

        @Suppress("DEPRECATION")
        LocalBroadcastManager.getInstance(this.application).sendBroadcast(intent)
    }

    override fun onProviderEnabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            notifyLocationProviderStatusUpdated(true)
        }
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            notifyLocationProviderStatusUpdated(false)
        }
    }

    private fun notifyLocationProviderStatusUpdated(isLocationProviderAvailable: Boolean) {
        //Broadcast location provider status change here
    }

    fun startLogging() {
        isLogging = true
    }

    fun stopLogging() {
        if (locationList.size > 1 && batteryLevelArray.size > 1) {
            val currentTimeInMillis = SystemClock.elapsedRealtimeNanos() / 1000000
            val elapsedTimeInSeconds = (currentTimeInMillis - runStartTimeInMillis) / 1000
            var totalDistanceInMeters = 0f
            for (i in 0 until locationList.size - 1) {
                totalDistanceInMeters += locationList[i].distanceTo(locationList[i + 1])
            }
            val batteryLevelStart = batteryLevelArray[0]
            val batteryLevelEnd = batteryLevelArray[batteryLevelArray.size - 1]

            val batteryLevelScaledStart = batteryLevelScaledArray[0]
            val batteryLevelScaledEnd = batteryLevelScaledArray[batteryLevelScaledArray.size - 1]

            saveLog(
                elapsedTimeInSeconds,
                totalDistanceInMeters.toDouble(),
                gpsCount,
                batteryLevelStart,
                batteryLevelEnd,
                batteryLevelScaledStart,
                batteryLevelScaledEnd
            )
        }
        isLogging = false
    }


    fun startUpdatingLocation() {
        if (!this.isLocationManagerUpdatingLocation) {
            isLocationManagerUpdatingLocation = true
            runStartTimeInMillis = SystemClock.elapsedRealtimeNanos() / 1000000

            locationList.clear()
            oldLocationList.clear()
            noAccuracyLocationList.clear()
            inaccurateLocationList.clear()
            kalmanNGLocationList.clear()

            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

            //Exception thrown when GPS or Network provider were not available on the user's device.
            try {
                val criteria = Criteria()
                criteria.accuracy =
                        Criteria.ACCURACY_FINE //setAccuracyは内部では、https://stackoverflow.com/a/17874592/1709287の用にHorizontalAccuracyの設定に変換されている。
                criteria.powerRequirement = Criteria.POWER_HIGH
                criteria.isAltitudeRequired = false
                criteria.isSpeedRequired = true
                criteria.isCostAllowed = true
                criteria.isBearingRequired = false

                //API level 9 and up
                criteria.horizontalAccuracy = Criteria.ACCURACY_HIGH
                criteria.verticalAccuracy = Criteria.ACCURACY_HIGH
                //criteria.setBearingAccuracy(Criteria.ACCURACY_HIGH);
                //criteria.setSpeedAccuracy(Criteria.ACCURACY_HIGH);

                val gpsFreqInMillis = 5000
                val gpsFreqInDistance = 5  // in meters

                @Suppress("DEPRECATION")
                locationManager.requestLocationUpdates(
                    gpsFreqInMillis.toLong(),
                    gpsFreqInDistance.toFloat(),
                    criteria,
                    this,
                    null
                )

                /* Battery Consumption Measurement */
                gpsCount = 0
                batteryLevelArray.clear()
                batteryLevelScaledArray.clear()

            } catch (e: IllegalArgumentException) {
                e.localizedMessage?.let { Log.e(LOG_TAG, it) }
            } catch (e: SecurityException) {
                e.localizedMessage?.let { Log.e(LOG_TAG, it) }
            } catch (e: RuntimeException) {
                e.localizedMessage?.let { Log.e(LOG_TAG, it) }
            }
        }
    }


    fun stopUpdatingLocation() {
        if (this.isLocationManagerUpdatingLocation == true) {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager.removeUpdates(this)
            isLocationManagerUpdatingLocation = false
        }
    }

    private fun getLocationAge(newLocation: Location): Long {
        val locationAge: Long
        val currentTimeInMilli = SystemClock.elapsedRealtimeNanos() / 1000000
        val locationTimeInMilli = newLocation.elapsedRealtimeNanos / 1000000
        locationAge = currentTimeInMilli - locationTimeInMilli
        return locationAge
    }


    private fun filterAndAddLocation(location: Location): Boolean {

        val age = getLocationAge(location)

        if (age > 5 * 1000) { //more than 5 seconds
            Log.d(LOG_TAG, "Location is old")
            oldLocationList.add(location)
            return false
        }

        if (location.accuracy <= 0) {
            Log.d(LOG_TAG, "Latitidue and longitude values are invalid.")
            noAccuracyLocationList.add(location)
            return false
        }

        //setAccuracy(newLocation.getAccuracy());
        val horizontalAccuracy = location.accuracy
        if (horizontalAccuracy > 1000) { //10meter filter
            Log.d(LOG_TAG, "Accuracy is too low.")
            inaccurateLocationList.add(location)
            return false
        }

        /* Kalman Filter */
        var Qvalue: Float = 3.0f

        val locationTimeInMillis = location.elapsedRealtimeNanos / 1000000
        val elapsedTimeInMillis = locationTimeInMillis - runStartTimeInMillis

        @Suppress("DEPRECATION")
        if (currentSpeed == 0.0f) {
            Qvalue = 3.0f //3 meters per second
        } else {
            Qvalue = currentSpeed // meters per second
        }

        kalmanFilter.Process(location.latitude, location.longitude, location.accuracy, elapsedTimeInMillis, Qvalue)
        val predictedLat = kalmanFilter.get_lat()
        val predictedLng = kalmanFilter.get_lng()

        val predictedLocation = Location("")//provider name is unecessary
        predictedLocation.latitude = predictedLat//your coords of course
        predictedLocation.longitude = predictedLng
        val predictedDeltaInMeters = predictedLocation.distanceTo(location)

        if (predictedDeltaInMeters > 60) {
            Log.d(LOG_TAG, "Kalman Filter detects mal GPS, we should probably remove this from track")
            kalmanFilter.consecutiveRejectCount += 1

            if (kalmanFilter.consecutiveRejectCount > 3) {
                kalmanFilter = KalmanLatLong(3f) //reset Kalman Filter if it rejects more than 3 times in raw.
            }

            kalmanNGLocationList.add(location)
            return false
        } else {
            kalmanFilter.consecutiveRejectCount = 0
        }

        /* Notifiy predicted location to UI */
        val intent = Intent("PredictLocation")
        intent.putExtra("location", predictedLocation)
        @Suppress("DEPRECATION")
        LocalBroadcastManager.getInstance(this.application).sendBroadcast(intent)

        Log.d(LOG_TAG, "Location quality is good enough.")
        currentSpeed = location.speed
        locationList.add(location)

        return true
    }

    /* Data Logging */
    @Synchronized
    fun saveLog(
        timeInSeconds: Long,
        distanceInMeters: Double,
        gpsCount: Int,
        batteryLevelStart: Int,
        batteryLevelEnd: Int,
        batteryLevelScaledStart: Float,
        batteryLevelScaledEnd: Float
    ) {
        val fileNameDateTimeFormat = SimpleDateFormat("yyyy_MMdd_HHmm", Locale.US)
        val filePath = (this.getExternalFilesDir(null)!!.absolutePath + "/"
                + fileNameDateTimeFormat.format(Date()) + "_battery" + ".csv")

        Log.d(LOG_TAG, "saving to $filePath")

        var fileWriter: FileWriter? = null
        try {
            fileWriter = FileWriter(filePath, false)
            fileWriter.append("Time,Distance,GPSCount,BatteryLevelStart,BatteryLevelEnd,BatteryLevelStart(/$batteryScale),BatteryLevelEnd(/$batteryScale)\n")
            val record =
                "$timeInSeconds,$distanceInMeters,$gpsCount,$batteryLevelStart,$batteryLevelEnd,$batteryLevelScaledStart,$batteryLevelScaledEnd\n"
            fileWriter.append(record)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            if (fileWriter != null) {
                try {
                    fileWriter.close()
                } catch (ioe: IOException) {
                    ioe.printStackTrace()
                }
            }
        }
    }

    private fun startForeground() {
        val notification = createNotification()
        startForeground(FOREGROUND_SERVICE_NOTIFICATION_ID, notification)
    }

    private fun createNotification(): Notification {
        /* Create Notification Channel */
        val channelName = resources.getString(R.string.location_service_notification_channel_name)
        val channel = NotificationChannel(FOREGROUND_SERVICE_NOTIFICATION_CHANNEL_ID,
            channelName, NotificationManager.IMPORTANCE_NONE)
        channel.description = getString(R.string.location_service_notification_channel_description)
        channel.lightColor = Color.BLUE
        channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // After this, we can use FOREGROUND_SERVICE_NOTIFICATION_CHANNEL_ID to refer the created channel
        service.createNotificationChannel(channel)

        /* Create Notification */
        val notificationTitle = resources.getString(R.string.app_name)
        val notificationText = resources.getString(R.string.location_service_notification_text)

        // give FOREGROUND_SERVICE_NOTIFICATION_CHANNEL_ID to use the channel we've just created.
        val notificationBuilder = NotificationCompat.Builder(this, FOREGROUND_SERVICE_NOTIFICATION_CHANNEL_ID )

        return notificationBuilder
            .setOngoing(true)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(notificationTitle)
            .setContentText(notificationText)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }
}