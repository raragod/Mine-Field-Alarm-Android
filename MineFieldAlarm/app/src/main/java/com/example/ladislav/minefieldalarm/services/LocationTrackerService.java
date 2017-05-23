package com.example.ladislav.minefieldalarm.services;

import android.Manifest;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.example.ladislav.minefieldalarm.model.MineField;
import com.example.ladislav.minefieldalarm.model.MineFieldTable;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;

import java.util.List;

/**
 * Background user location tracker. Uses Google API client to track and update user location,
 * Compares location coordinates to mine field coordinates and dynamically makes geofences.
 * On geofence enter, it turns on alarm and re-starts main activity showing location and minefield !
 */

// TODO Manage life cycle of service: make option to restart itself if killed !

public class LocationTrackerService extends Service
        implements GoogleApiClient.ConnectionCallbacks,
        LocationListener,
        ResultCallback<Status>,
        GoogleApiClient.OnConnectionFailedListener {

    private static String TAG = "MineFieldAlarm";

    public static final int UPDATE_INTERVAL = 10000;
    public static final int FASTEST_UPDATE_INTERVAL = 1000;

    private MineFieldTable mineFields;
    private List<MineField> closestFields;

    private GoogleApiClient googleApiClient;
    private LocationRequest locationRequest;
    private PendingIntent pendingIntent;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "LocationTrackerService onStartCommand");

        buildGoogleApiClient();
        createLocationRequest();
        googleApiClient.connect();

        return START_STICKY;
    }

    @Override
    public void onCreate() {
        Log.i(TAG, "LocationTrackerService onCreate");
        mineFields = MineFieldTable.getInstance();
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        Log.i(TAG, "Connected to GoogleApiClient, starting location updates... ");

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling !  !  !
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        LocationServices.FusedLocationApi.requestLocationUpdates(
                googleApiClient, locationRequest, this);
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.i(TAG, "Connection suspended");

        googleApiClient.connect();
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        Log.i(TAG, "Connection failed: " + connectionResult.getErrorCode());
    }

    @Override
    public void onLocationChanged(Location location) {
        Log.i(TAG, "LocationTrackerService: Location changed to: " + location.toString());

        updateGeofences(location);
        notifyMapFragment(location);
    }

    public void notifyMapFragment(Location location) {
        Log.i(TAG, "LocationTrackerService: Notifying map fragment for location update.");

        Intent lbcIntent = new Intent("UserLocationChange");

        lbcIntent.putExtra("latitude", location.getLatitude());
        lbcIntent.putExtra("longitude", location.getLongitude());

        LocalBroadcastManager.getInstance(this).sendBroadcast(lbcIntent);
    }
    /**
     * Updates geofences that needs to be tracked based on
     * distance between user and mine fields.
     * It adds all the minefield in are of 5 kilometers in perimeter
     * to active geofences.
     * If geofence number passes 100, it calculates 100 nearest and
     * adds them. Method is being called on every location change.
     *
     * @param location used to get users latitude and longitude
     */
    private void updateGeofences(Location location) {
        Log.d(TAG, "LocationTrackerService: Updating geofences");

        double userLatitude = location.getLatitude();
        double userLongitude = location.getLongitude();

        if (closestFields != null && !closestFields.isEmpty()) {
            Log.i(TAG, "LocationTrackerService: removing geofences.");
            LocationServices.GeofencingApi.removeGeofences(googleApiClient,
                    requestPendingIntent()).setResultCallback(this);
        }

        closestFields = MineFieldTable.getInstance().getClosestFieldsTo(userLatitude, userLongitude);

        pendingIntent = requestPendingIntent();

        GeofencingRequest.Builder geofencingRequestBuilder = new GeofencingRequest.Builder();

        for (MineField mineField : closestFields) {
            geofencingRequestBuilder.addGeofence(mineField.toGeofence());
        }

        Log.i(TAG, "LocationTrackerService: building Geofencing request ");
        GeofencingRequest geofencingRequest = geofencingRequestBuilder.build();

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "LocationTrackerService: location permission NOT granted ");
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        Log.i(TAG, "LocationTrackerService: adding geofences. ");
        LocationServices.GeofencingApi.addGeofences(googleApiClient,
                geofencingRequest, pendingIntent).setResultCallback(this);
    }

    private PendingIntent requestPendingIntent() {
        Log.i(TAG, "LocationTrackerService: requesting pending intent.");
        if (null != pendingIntent) {
            return pendingIntent;
        }

        Intent intent = new Intent(this, GeofenceTransitionsIntentService.class);
        return PendingIntent.getService(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);

    }

    // TODO implement me ! ! !
    @Override   //result callback
    public void onResult(@NonNull Status status) {

    }

    /**
     * Helper method that uses GoogleApiClient Builder to instantiate
     * the client.
     */
    private synchronized void buildGoogleApiClient() {
        Log.i(TAG, "Building GoogleApiClient");
        googleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API).build();
    }

    /**
     * Helper method that instantiates LocationRequest
     * and sets update interval and location accuracy.
     */
    private void createLocationRequest() {
        Log.i(TAG, "LocationTrackerService: Creating location request.");

        locationRequest = new LocationRequest();
        locationRequest.setInterval(UPDATE_INTERVAL);
        locationRequest.setFastestInterval(FASTEST_UPDATE_INTERVAL);
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
    }

    @Nullable
    @Override    // service
    public IBinder onBind(Intent intent) {
        return null;
    }
}