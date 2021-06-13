package com.goldrushcomputing.androidlocationstarterkitinkotlin

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.graphics.Color
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import permissions.dispatcher.NeedsPermission
import permissions.dispatcher.RuntimePermissions
import java.util.*

@RuntimePermissions
class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"

    private lateinit var map: GoogleMap

    var locationService: LocationService? = null

    private var userPositionMarker: Marker? = null
    private var locationAccuracyCircle: Circle? = null
    private var userPositionMarkerBitmapDescriptor: BitmapDescriptor? = null
    private var runningPathPolyline: Polyline? = null
    private val polylineOptions: PolylineOptions? = null
    private val polylineWidth = 30

    internal var zoomable = true

    internal var zoomBlockingTimer: Timer? = null
    internal var didInitialZoom: Boolean = false
    private var handlerOnUIThread: Handler? = null


    private var locationUpdateReceiver: BroadcastReceiver? = null

    private var startButton: ImageButton? = null
    private var stopButton: ImageButton? = null

    /* Filter */
    internal val handler = Handler()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
            setupGoogleMapWithPermissionCheck(googleMap)
        }


        locationUpdateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newLocation = intent.getParcelableExtra<Location>("location")
                drawLocationAccuracyCircle(newLocation)
                drawUserPositionMarker(newLocation)
                this@MainActivity.locationService?.let{
                    if (it.isLogging) {
                        addPolyline()
                    }
                }
                zoomMapTo(newLocation)
            }
        }

        locationUpdateReceiver?.let{
            LocalBroadcastManager.getInstance(this).registerReceiver(
                it,
                IntentFilter("LocationUpdated")
            )
        }

        startButton = this.findViewById(R.id.start_button) as ImageButton
        stopButton = this.findViewById(R.id.stop_button) as ImageButton
        stopButton?.visibility = View.INVISIBLE


        startButton?.setOnClickListener {
            startButton?.visibility = View.INVISIBLE
            stopButton?.visibility = View.VISIBLE

            clearPolyline()
            this@MainActivity.locationService?.startLogging()
        }

        stopButton?.setOnClickListener {
            startButton?.visibility = View.VISIBLE
            stopButton?.visibility = View.INVISIBLE

            this@MainActivity.locationService?.stopLogging()
        }
    }

    @SuppressLint("MissingPermission")
    @NeedsPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun setupGoogleMap(googleMap: GoogleMap) {
        map = googleMap
        map.uiSettings.isZoomControlsEnabled = false
        map.isMyLocationEnabled = false
        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isMyLocationButtonEnabled = true

        map.setOnCameraMoveStartedListener { reason ->
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                Log.d(TAG, "onCameraMoveStarted after user's zoom action")
                zoomable = false
                zoomBlockingTimer?.cancel()
                handlerOnUIThread = Handler()
                val task = object : TimerTask() {
                    override fun run() {
                        handlerOnUIThread?.post {
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

        /* Start Location Service */
        val locationService = Intent(this.application, LocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            this.application.startForegroundService(locationService)
        } else {
            this.application.startService(locationService)
        }
        this.application.bindService(locationService, serviceConnection, Context.BIND_AUTO_CREATE)
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
        } catch (ex: IllegalArgumentException) {
            ex.printStackTrace()
        }

        super.onDestroy()
    }


    private fun zoomMapTo(location: Location) {
        val latLng = LatLng(location.latitude, location.longitude)

        if (this.didInitialZoom == false) {
            try {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17.5f))
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
                map.animateCamera(CameraUpdateFactory.newLatLng(latLng),
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
            it.setPosition(latLng)
        } ?: run{
            userPositionMarker = map.addMarker(
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
            this.locationAccuracyCircle = map.addCircle(
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

                    this.runningPathPolyline = map.addPolyline(
                        PolylineOptions()
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

    @SuppressLint("NeedOnRequestPermissionsResult")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // NOTE: delegate the permission handling to generated function
        onRequestPermissionsResult(requestCode, grantResults)
    }
}
