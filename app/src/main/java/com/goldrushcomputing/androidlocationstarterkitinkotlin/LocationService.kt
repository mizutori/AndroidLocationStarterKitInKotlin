package com.goldrushcomputing.androidlocationstarterkitinkotlin


import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.location.*
import android.os.*
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.PRIORITY_MIN
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.*

/**
 * Created by Takamitsu Mizutori on 2018/12/08.
 */
class LocationService: Service(), LocationListener, GpsStatus.Listener {
    val LOG_TAG = LocationService::class.java.simpleName

    private val binder = LocationServiceBinder()
    private var isLocationManagerUpdatingLocation: Boolean = false

    var locationList: ArrayList<Location>
    var isLogging: Boolean = false

    private val ANDROID_CHANNEL_ID = "com.goldrushcomputing.androidlocationstarterkitinkotlin.Channel"
    private val NOTIFICATION_ID = 555


    init {
        isLocationManagerUpdatingLocation = false
        locationList = ArrayList()
        isLogging = false
    }


    override fun onStartCommand(i: Intent, flags: Int, startId: Int): Int {
        super.onStartCommand(i, flags, startId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground()
        }
        return Service.START_STICKY
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
    }

    //This is where we detect the app is being killed, thus stop service.
    override fun onTaskRemoved(rootIntent: Intent) {
        Log.d(LOG_TAG, "onTaskRemoved ")
        this.stopUpdatingLocation()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopForeground(true);
        } else {
            stopSelf();
        }
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

    override fun onLocationChanged(newLocation: Location?) {
        newLocation?.let{
            Log.d(LOG_TAG, "(" + it.latitude + "," + it.longitude + ")")

            if (isLogging) {
                locationList.add(newLocation);
            }

            val intent = Intent("LocationUpdated")
            intent.putExtra("location", it)

            LocalBroadcastManager.getInstance(this.application).sendBroadcast(intent)
        }
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        if (provider == LocationManager.GPS_PROVIDER) {
            if (status == LocationProvider.OUT_OF_SERVICE) {
                notifyLocationProviderStatusUpdated(false)
            } else {
                notifyLocationProviderStatusUpdated(true)
            }
        }
    }

    override fun onProviderEnabled(provider: String?) {
        if (provider == LocationManager.GPS_PROVIDER) {
            notifyLocationProviderStatusUpdated(true)
        }
    }

    override fun onProviderDisabled(provider: String?) {
        if (provider == LocationManager.GPS_PROVIDER) {
            notifyLocationProviderStatusUpdated(false)
        }
    }

    override fun onGpsStatusChanged(event: Int) {

    }

    private fun notifyLocationProviderStatusUpdated(isLocationProviderAvailable: Boolean) {
        //Broadcast location provider status change here
    }

    fun startLogging() {
        isLogging = true
    }

    fun stopLogging() {
        isLogging = false
    }


    fun startUpdatingLocation() {
        if (this.isLocationManagerUpdatingLocation == false) {
            isLocationManagerUpdatingLocation = true

            locationList.clear()

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

                locationManager.addGpsStatusListener(this)

                locationManager.requestLocationUpdates(
                    gpsFreqInMillis.toLong(),
                    gpsFreqInDistance.toFloat(),
                    criteria,
                    this,
                    null
                )
            } catch (e: IllegalArgumentException) {
                Log.e(LOG_TAG, e.localizedMessage)
            } catch (e: SecurityException) {
                Log.e(LOG_TAG, e.localizedMessage)
            } catch (e: RuntimeException) {
                Log.e(LOG_TAG, e.localizedMessage)
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

    private fun startForeground() {
        val channelId =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                createNotificationChannel("my_service", "Location Tracking Service")
            } else {
                // If earlier version channel ID is not used
                // https://developer.android.com/reference/android/support/v4/app/NotificationCompat.Builder.html#NotificationCompat.Builder(android.content.Context)
                ""
            }
        val notificationBuilder = NotificationCompat.Builder(this, channelId )
        val notification = notificationBuilder.setOngoing(true)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        startForeground(101, notification)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(channelId: String, channelName: String): String{
        val chan = NotificationChannel(channelId,
            channelName, NotificationManager.IMPORTANCE_NONE)
        chan.lightColor = Color.BLUE
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(chan)
        return channelId
    }
}
