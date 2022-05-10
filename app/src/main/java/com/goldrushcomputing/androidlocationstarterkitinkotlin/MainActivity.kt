package com.goldrushcomputing.androidlocationstarterkitinkotlin

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ImageButton
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.*

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"

    private lateinit var activityResultLauncher: ActivityResultLauncher<String>
    private var map: GoogleMap? = null

    var locationService: LocationService? = null
    var isServiceBound = false

    private var userPositionMarker: Marker? = null
    private var locationAccuracyCircle: Circle? = null
    private var userPositionMarkerBitmapDescriptor: BitmapDescriptor? = null
    private var runningPathPolyline: Polyline? = null
    private val polylineOptions = PolylineOptions()
    private val polylineWidth = 30

    internal var zoomable = true

    internal var zoomBlockingTimer: Timer? = null
    private var didInitialZoom: Boolean = false

    private var locationUpdateReceiver: BroadcastReceiver? = null
    private var predictedLocationReceiver: BroadcastReceiver? = null

    private var startButton: ImageButton? = null
    private var stopButton: ImageButton? = null

    /* Filter */
    private var predictionRange: Circle? = null
    private var oldLocationMarkerBitmapDescriptor: BitmapDescriptor? = null
    private var noAccuracyLocationMarkerBitmapDescriptor: BitmapDescriptor? = null
    private var inaccurateLocationMarkerBitmapDescriptor: BitmapDescriptor? = null
    private var kalmanNGLocationMarkerBitmapDescriptor: BitmapDescriptor? = null
    private var malMarkers = ArrayList<Marker>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        activityResultLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted && checkAccessFineLocationPermission()) {
                map?.let {
                    onLocationPermissionGranted(it)
                }
            } else {
                showLocationPermissionDialog(isFirstTime = false)
            }
        }

        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync { googleMap ->
            /**
             * Manipulates the map once available.
             * This callback is triggered when the map is ready to be used.
             * This is where we can add markers or lines, add listeners or move the camera. In this case,
             * we just add a marker near Sydney, Australia.
             * If Google Play services is not installed on the device, the user will be prompted to install
             * it inside the SupportMapFragment. This method will only be triggered once the user has
             * installed Google Play services and returned to the app.
             */
            googleMap.uiSettings.apply {
                isZoomControlsEnabled = false
                isCompassEnabled = true
                isMyLocationButtonEnabled = true
            }
            if (checkAccessFineLocationPermission()) {
                onLocationPermissionGranted(googleMap)
            } else {
                showLocationPermissionDialog(isFirstTime = true)
            }
            map = googleMap

            /* Start Location Service */
            val locationService = Intent(this.application, LocationService::class.java)
            this.application.startForegroundService(locationService)
            isServiceBound = this.application.bindService(locationService, serviceConnection, Context.BIND_AUTO_CREATE)
        }


        locationUpdateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                intent.getParcelableExtra<Location>("location")?.let{ newLocation ->
                    drawLocationAccuracyCircle(newLocation)
                    drawUserPositionMarker(newLocation)
                    this@MainActivity.locationService?.let{
                        if (it.isLogging) {
                            addPolyline()
                        }
                    }
                    zoomMapTo(newLocation)
                    /* Filter Visualization */
                    drawMalLocations()
                }
            }
        }

        predictedLocationReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                intent.getParcelableExtra<Location>("location")?.let{ predictedLocation ->
                    drawPredictionRange(predictedLocation)
                }
            }
        }

        locationUpdateReceiver?.let{
            @Suppress("DEPRECATION")
            LocalBroadcastManager.getInstance(this).registerReceiver(
                it,
                IntentFilter("LocationUpdated")
            )
        }

        predictedLocationReceiver?.let{
            @Suppress("DEPRECATION")
            LocalBroadcastManager.getInstance(this).registerReceiver(
                it,
                IntentFilter("PredictLocation")
            )
        }

        startButton = this.findViewById(R.id.start_button) as ImageButton
        stopButton = this.findViewById(R.id.stop_button) as ImageButton
        stopButton?.visibility = View.INVISIBLE


        startButton?.setOnClickListener {
            startButton?.visibility = View.INVISIBLE
            stopButton?.visibility = View.VISIBLE

            clearPolyline()
            clearMalMarkers()
            this@MainActivity.locationService?.startLogging()
        }

        stopButton?.setOnClickListener {
            startButton?.visibility = View.VISIBLE
            stopButton?.visibility = View.INVISIBLE

            this@MainActivity.locationService?.stopLogging()
        }


        oldLocationMarkerBitmapDescriptor = BitmapDescriptorFactory.fromResource(R.drawable.old_location_marker)
        noAccuracyLocationMarkerBitmapDescriptor =
                BitmapDescriptorFactory.fromResource(R.drawable.no_accuracy_location_marker)
        inaccurateLocationMarkerBitmapDescriptor =
                BitmapDescriptorFactory.fromResource(R.drawable.inaccurate_location_marker)
        kalmanNGLocationMarkerBitmapDescriptor =
                BitmapDescriptorFactory.fromResource(R.drawable.kalman_ng_location_marker)


    }

    private fun checkAccessFineLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun showLocationPermissionDialog(isFirstTime: Boolean) {
        if(isFirstTime){
            MaterialAlertDialogBuilder(this, R.style.DefaultAlertDialogStyle)
                .setTitle(R.string.map_dialog_ask_permission_title)
                .setMessage(R.string.map_dialog_ask_permission_description)
                .setPositiveButton(R.string.map_dialog_ask_permission_next) { _, _ ->
                    activityResultLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                .create()
                .show()
        }else{
            //From the second time (coming back from the default location permission dialog)
            MaterialAlertDialogBuilder(this, R.style.DefaultAlertDialogStyle)
                .setTitle(R.string.map_dialog_no_permission_title)
                .setMessage(R.string.map_dialog_no_permission_description)
                .setPositiveButton(R.string.map_dialog_no_permission_do_not_allow) { _, _ -> }
                .setNegativeButton(R.string.map_dialog_no_permission_open_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", this@MainActivity.packageName, null)
                    })
                }
                .create()
                .show()
        }

    }

    private fun onLocationPermissionGranted(map: GoogleMap) {
        if (checkAccessFineLocationPermission()) {
            map.isMyLocationEnabled = false
            map.setOnCameraMoveStartedListener { reason ->
                if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                    Log.d(TAG, "onCameraMoveStarted after user's zoom action")
                    zoomable = false
                    zoomBlockingTimer?.cancel()
                    val task = object : TimerTask() {
                        override fun run() {
                            Handler(Looper.getMainLooper()).post { // Update UI
                                zoomBlockingTimer = null
                                zoomable = true
                            }
                        }
                    }
                    zoomBlockingTimer = Timer()
                    zoomBlockingTimer?.schedule(task, (10 * 1000).toLong())
                    Log.d(TAG, "start blocking auto zoom for 10 seconds")
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            // This is called when the connection with the service has been
            // established, giving us the service object we can use to
            // interact with the service.  Because we have bound to a explicit
            // service that we know is running in our own process, we can
            // cast its IBinder to a concrete class and directly access it.
            val name = className.className

            if (name.endsWith("LocationService")) {
                locationService = (service as LocationService.LocationServiceBinder).service

                this@MainActivity.locationService?.startUpdatingLocation()
            }
        }

        override fun onServiceDisconnected(className: ComponentName) {
            // This is called when the connection with the service has been
            // unexpectedly disconnected -- that is, its process crashed.
            // Because it is running in our same process, we should never
            // see this happen.
            if (className.className == "LocationService") {
                this@MainActivity.locationService?.stopUpdatingLocation()
                locationService = null
            }
        }
    }


    public override fun onDestroy() {
        try {
            if (locationUpdateReceiver != null) {
                unregisterReceiver(locationUpdateReceiver)
            }

            if (predictedLocationReceiver != null) {
                unregisterReceiver(predictedLocationReceiver)
            }
        } catch (ex: IllegalArgumentException) {
            ex.printStackTrace()
        }
        stopLocationService()
        super.onDestroy()
    }

    private fun stopLocationService(){
        locationService?.let{
            if(it.isLogging){
                it.stopLogging()
            }
            it.stopUpdatingLocation()
            if(isServiceBound){
                this.application.unbindService(serviceConnection)
                isServiceBound = false
                it.stopForeground(true)
            }
        }
    }

    private fun zoomMapTo(location: Location) {
        val latLng = LatLng(location.latitude, location.longitude)

        if (!this.didInitialZoom) {
            try {
                map?.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17.5f))
                this.didInitialZoom = true
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
            //Toast.makeText(this.getActivity(), "Inital zoom in process", Toast.LENGTH_LONG).show();
        }

        if (zoomable) {
            try {
                zoomable = false
                map?.animateCamera(CameraUpdateFactory.newLatLng(latLng),
                    object : GoogleMap.CancelableCallback {
                        override fun onFinish() {
                            zoomable = true
                        }

                        override fun onCancel() {
                            zoomable = true
                        }
                    })
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }


    private fun drawUserPositionMarker(location: Location) {
        val latLng = LatLng(location.latitude, location.longitude)

        if (this.userPositionMarkerBitmapDescriptor == null) {
            userPositionMarkerBitmapDescriptor = BitmapDescriptorFactory.fromResource(R.drawable.user_position_point)
        }

        userPositionMarker?.let{
            it.position = latLng
        } ?: run{
            userPositionMarker = map?.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .flat(true)
                    .anchor(0.5f, 0.5f)
                    .icon(this.userPositionMarkerBitmapDescriptor)
            )
        }
    }


    private fun drawLocationAccuracyCircle(location: Location) {
        if (location.accuracy < 0) {
            return
        }

        val latLng = LatLng(location.latitude, location.longitude)

        locationAccuracyCircle?.let{
            it.center = latLng
        } ?: run{
            this.locationAccuracyCircle = map?.addCircle(
                CircleOptions()
                    .center(latLng)
                    .fillColor(Color.argb(64, 0, 0, 0))
                    .strokeColor(Color.argb(64, 0, 0, 0))
                    .strokeWidth(0.0f)
                    .radius(location.accuracy.toDouble())
            ) //set radius to horizonal accuracy in meter.
        }
    }


    private fun addPolyline() {
        locationService?.locationList?.let{locationList ->

            runningPathPolyline?.let{
                val toLocation = locationList[locationList.size - 1]
                val to = LatLng(
                    toLocation.latitude,
                    toLocation.longitude
                )
                val points = it.points
                points.add(to)
                it.points = points
            } ?: run{
                if (locationList.size > 1) {
                    val fromLocation = locationList[locationList.size - 2]
                    val toLocation = locationList[locationList.size - 1]

                    val from = LatLng(
                        fromLocation.latitude,
                        fromLocation.longitude
                    )

                    val to = LatLng(
                        toLocation.latitude,
                        toLocation.longitude
                    )

                    this.runningPathPolyline = map?.addPolyline(
                        polylineOptions
                            .add(from, to)
                            .width(polylineWidth.toFloat()).color(Color.parseColor("#801B60FE")).geodesic(true)
                    )
                }
            }
        }

    }

    private fun clearPolyline() {
        runningPathPolyline?.remove()
        runningPathPolyline = null
    }

    /* Filter Visualization */
    private fun drawMalLocations() {
        locationService?.let{
            drawMalMarkers(it.oldLocationList, oldLocationMarkerBitmapDescriptor!!)
            drawMalMarkers(it.noAccuracyLocationList, noAccuracyLocationMarkerBitmapDescriptor!!)
            drawMalMarkers(it.inaccurateLocationList, inaccurateLocationMarkerBitmapDescriptor!!)
            drawMalMarkers(it.kalmanNGLocationList, kalmanNGLocationMarkerBitmapDescriptor!!)
        }
    }

    private fun drawMalMarkers(locationList: ArrayList<Location>, descriptor: BitmapDescriptor) {
        for (location in locationList) {
            val latLng = LatLng(location.latitude, location.longitude)
            val marker = map?.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .flat(true)
                    .anchor(0.5f, 0.5f)
                    .icon(descriptor)
            )
            marker?.let{
                malMarkers.add(it)
            }
        }
    }

    private fun drawPredictionRange(location: Location) {
        val latLng = LatLng(location.latitude, location.longitude)

        predictionRange?.let{
            it.center = latLng
        } ?: run {
            predictionRange = map?.addCircle(
                CircleOptions()
                    .center(latLng)
                    .fillColor(Color.argb(50, 30, 207, 0))
                    .strokeColor(Color.argb(128, 30, 207, 0))
                    .strokeWidth(1.0f)
                    .radius(30.0)
            ) //30 meters of the prediction range
        }

        this.predictionRange?.isVisible = true
        Handler(Looper.getMainLooper()).postDelayed({
            this@MainActivity.predictionRange?.isVisible = false
        }, 2000)
    }

    private fun clearMalMarkers() {
        for (marker in malMarkers) {
            marker.remove()
        }
    }
}
