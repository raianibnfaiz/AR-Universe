/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.core.examples.kotlin.helloar

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.ar.core.Config
import com.google.ar.core.Config.InstantPlacementMode
import com.google.ar.core.Session
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper
import com.google.ar.core.examples.java.common.helpers.DepthSettings
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper
import com.google.ar.core.examples.java.common.helpers.InstantPlacementSettings
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.examples.kotlin.common.helpers.ARCoreSessionLifecycleHelper
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.codelabs.findnearbyplacesar.api.NearbyPlacesResponse
import com.google.codelabs.findnearbyplacesar.api.PlacesService
import com.google.codelabs.findnearbyplacesar.model.Place
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * This is a simple example that shows how to create an augmented reality (AR) application using the
 * ARCore API. The application will display any detected planes and will allow the user to tap on a
 * plane to place a 3D model.
 */
class HelloArActivity : AppCompatActivity() {
  private var map: GoogleMap? = null
  private var cameraPosition: CameraPosition? = null

  // The entry point to the Places API.
  private lateinit var placesClient: PlacesClient


  // The entry point to the Fused Location Provider.
  private lateinit var fusedLocationClient: FusedLocationProviderClient
  private var markers: MutableList<Marker> = emptyList<Marker>().toMutableList()
  // A default location (Sydney, Australia) and default zoom to use when location permission is
  // not granted.
  private val defaultLocation = LatLng(-33.8523341, 151.2106085)
  private var locationPermissionGranted = false
  private lateinit var mapFragment: SupportMapFragment
  // The geographical location where the device is currently located. That is, the last-known
  // location retrieved by the Fused Location Provider.
  private var lastKnownLocation: Location? = null
  private var likelyPlaceNames: Array<String?> = arrayOfNulls(0)
  private var likelyPlaceAddresses: Array<String?> = arrayOfNulls(0)
  private var likelyPlaceAttributions: Array<List<*>?> = arrayOfNulls(0)
  private var likelyPlaceLatLngs: Array<LatLng?> = arrayOfNulls(0)

  private var places: List<Place>? = null
  companion object {

    private const val TAG = "HelloArActivity"
  }

  lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper
  lateinit var view: HelloArView
  lateinit var renderer: HelloArRenderer
  private lateinit var placesService: PlacesService
  val instantPlacementSettings = InstantPlacementSettings()
  val depthSettings = DepthSettings()
  private var currentLocation: Location? = null
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (savedInstanceState != null) {
      lastKnownLocation = savedInstanceState.getParcelable("location")
      cameraPosition = savedInstanceState.getParcelable( "camera_position")
    }
    // Setup ARCore session lifecycle helper and configuration.
    arCoreSessionHelper = ARCoreSessionLifecycleHelper(this)
    // If Session creation or Session.resume() fails, display a message and log detailed
    // information.
    arCoreSessionHelper.exceptionCallback =
      { exception ->
        val message =
          when (exception) {
            is UnavailableUserDeclinedInstallationException ->
              "Please install Google Play Services for AR"
            is UnavailableApkTooOldException -> "Please update ARCore"
            is UnavailableSdkTooOldException -> "Please update this app"
            is UnavailableDeviceNotCompatibleException -> "This device does not support AR"
            is CameraNotAvailableException -> "Camera not available. Try restarting the app."
            else -> "Failed to create AR session: $exception"
          }
        Log.e(TAG, "ARCore threw an exception", exception)
        view.snackbarHelper.showError(this, message)
      }

    // Configure session features, including: Lighting Estimation, Depth mode, Instant Placement.
    arCoreSessionHelper.beforeSessionResume = ::configureSession
    lifecycle.addObserver(arCoreSessionHelper)

    // Set up the Hello AR renderer.
    renderer = HelloArRenderer(this)
    lifecycle.addObserver(renderer)

    // Set up Hello AR UI.
    view = HelloArView(this)
    lifecycle.addObserver(view)
    setContentView(view.root)
    placesService = PlacesService.create()
    fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    // Sets up an example renderer using our HelloARRenderer.
    SampleRender(view.surfaceView, renderer, assets)

    depthSettings.onCreate(this)
    instantPlacementSettings.onCreate(this)
    mapFragment =
      supportFragmentManager.findFragmentById(R.id.maps_fragment) as SupportMapFragment
    setUpMaps()
  }
  private fun setUpMaps() {
    mapFragment.getMapAsync { googleMap ->
      if (ActivityCompat.checkSelfPermission(
          this,
          Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
          this,
          Manifest.permission.ACCESS_COARSE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED
      ) {
        // TODO: Consider calling
        //    ActivityCompat#requestPermissions
        // here to request the missing permissions, and then overriding
        //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
        //                                          int[] grantResults)
        // to handle the case where the user grants the permission. See the documentation
        // for ActivityCompat#requestPermissions for more details.
        return@getMapAsync
      }
      googleMap.isMyLocationEnabled = true

      getCurrentLocation {
        val pos = CameraPosition.fromLatLngZoom(it.latLng, 13f)
        googleMap.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
        getNearbyPlaces(it)
      }
      googleMap.setOnMarkerClickListener { marker ->
        val tag = marker.tag
        Log.d(TAG, "setUpMaps: $tag")
        if (tag !is Place) {
          return@setOnMarkerClickListener false
        }
        showInfoWindow(tag)
        return@setOnMarkerClickListener true
      }
      map = googleMap
    }
  }

  private fun getCurrentLocation(onSuccess: (Location) -> Unit) {
    if (ActivityCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION
      ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_COARSE_LOCATION
      ) != PackageManager.PERMISSION_GRANTED
    ) {
      // TODO: Consider calling
      //    ActivityCompat#requestPermissions
      // here to request the missing permissions, and then overriding
      //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
      //                                          int[] grantResults)
      // to handle the case where the user grants the permission. See the documentation
      // for ActivityCompat#requestPermissions for more details.
      return
    }
    fusedLocationClient.lastLocation.addOnSuccessListener { location ->
      currentLocation = location
      onSuccess(location)
    }.addOnFailureListener {
      Log.e(TAG, "Could not get location")
    }
  }
  private fun getNearbyPlaces(location: Location) {
    val apiKey = this.getString(R.string.google_maps_key)
    placesService.nearbyPlaces(
      apiKey = apiKey,
      location = "${location.latitude},${location.longitude}",
      radiusInMeters = 2000,
      placeType = "park"
    ).enqueue(
      object : Callback<NearbyPlacesResponse> {
        override fun onFailure(call: Call<NearbyPlacesResponse>, t: Throwable) {
          Log.e(TAG, "Failed to get nearby places", t)
        }

        override fun onResponse(
          call: Call<NearbyPlacesResponse>,
          response: Response<NearbyPlacesResponse>
        ) {
          if (!response.isSuccessful) {
            Log.e(TAG, "Failed to get nearby places")
            return
          }

          val places = response.body()?.results ?: emptyList()
          Log.d(TAG, "onResponse: $places")
          this@HelloArActivity.places = places
        }
      }
    )
  }
  private fun showInfoWindow(place: Place) {
    /*// Show in AR
    val matchingPlaceNode = anchorNode?.children?.filter {
      it is PlaceNode
    }?.first {
      val otherPlace = (it as PlaceNode).place ?: return@first false
      return@first otherPlace == place
    } as? PlaceNode
    matchingPlaceNode?.showInfoWindow()
*/
    // Show as marker
    val matchingMarker = markers.firstOrNull {
      val placeTag = (it.tag as? Place) ?: return@firstOrNull false
      return@firstOrNull placeTag == place
    }
    matchingMarker?.showInfoWindow()
  }
  // Configure the session, using Lighting Estimation, and Depth mode.
  fun configureSession(session: Session) {
    session.configure(
      session.config.apply {
        lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR

        // Depth API is used if it is configured in Hello AR's settings.
        depthMode =
          if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            Config.DepthMode.AUTOMATIC
          } else {
            Config.DepthMode.DISABLED
          }

        // Instant Placement is used if it is configured in Hello AR's settings.
        instantPlacementMode =
          if (instantPlacementSettings.isInstantPlacementEnabled) {
            InstantPlacementMode.LOCAL_Y_UP
          } else {
            InstantPlacementMode.DISABLED
          }
      }
    )
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    results: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, results)
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      // Use toast instead of snackbar here since the activity will exit.
      Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
        .show()
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        // Permission denied with checking "Do not ask again".
        CameraPermissionHelper.launchPermissionSettings(this)
      }
      finish()
    }
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
  }
}
val Location.latLng: LatLng
  get() = LatLng(this.latitude, this.longitude)
