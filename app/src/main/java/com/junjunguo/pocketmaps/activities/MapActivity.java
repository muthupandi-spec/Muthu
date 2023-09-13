package com.junjunguo.pocketmaps.activities;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.junjunguo.pocketmaps.R;
import com.junjunguo.pocketmaps.fragments.Dialog;
import com.junjunguo.pocketmaps.map.Destination;
import com.junjunguo.pocketmaps.map.MapHandler;
import com.junjunguo.pocketmaps.map.Navigator;
import com.junjunguo.pocketmaps.map.Tracking;
import com.junjunguo.pocketmaps.util.SetStatusBarColor;
import com.junjunguo.pocketmaps.util.Variable;
import com.junjunguo.pocketmaps.navigator.NaviEngine;

import com.villoren.android.kalmanlocationmanager.lib.KalmanLocationManager;

import java.io.File;

import org.oscim.android.MapView;
import org.oscim.core.GeoPoint;

import com.villoren.android.kalmanlocationmanager.lib.KalmanLocationManager.UseProvider;

/**
 * This file is part of PocketMaps
 * <p/>
 * Created by GuoJunjun <junjunguo.com> on July 04, 2015.
 */
public class MapActivity extends Activity implements LocationListener {
    enum PermissionStatus { Enabled, Disabled, Requesting, Unknown };
    private MapView mapView;
    private static Location mCurrentLocation;
    private static boolean mapAlive = false;
    private Location mLastLocation;
    private MapActions mapActions;
    private LocationManager locationManager;
    private KalmanLocationManager kalmanLocationManager;
    private PermissionStatus locationListenerStatus = PermissionStatus.Unknown;
    private String lastProvider;
    /**
     * Request location updates with the highest possible frequency on gps.
     * Typically, this means one update per second for gps.
     */
    private static final long GPS_TIME = 1000;
    /**
     * For the network provider, which gives locations with less accuracy (less reliable),
     * request updates every 5 seconds.
     */
    private static final long NET_TIME = 5000;
    /**
     * For the filter-time argument we use a "real" value: the predictions are triggered by a timer.
     * Lets say we want ~25 updates (estimates) per second = update each 40 millis (to make the movement fluent).
     */
    private static final long FILTER_TIME = 40;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lastProvider = null;
        setContentView(R.layout.activity_map);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        kalmanLocationManager = new KalmanLocationManager(this);
        kalmanLocationManager.setMaxPredictTime(10000);
        Variable.getVariable().setContext(getApplicationContext());
        mapView = new MapView(this);
        mapView.setClickable(true);
        MapHandler.getMapHandler()
                .init(mapView, Variable.getVariable().getCountry(), Variable.getVariable().getMapsFolder());
        try
        {
          MapHandler.getMapHandler().loadMap(new File(Variable.getVariable().getMapsFolder().getAbsolutePath(),
                Variable.getVariable().getCountry() + "-gh"), this);
          getIntent().putExtra("com.junjunguo.pocketmaps.activities.MapActivity.SELECTNEWMAP", false);
        }
        catch (Exception e)
        {
            System.out.println("failure");

          logUser("Map file seems corrupt!\nPlease try to re-download.");
          log("Error while loading map!");
          e.printStackTrace();
          finish();
          Intent intent = new Intent(this, MainActivity.class);
          intent.putExtra("com.junjunguo.pocketmaps.activities.MapActivity.SELECTNEWMAP", true);
          startActivity(intent);
          return;
        }
        customMapView();
        checkGpsAvailability();
        ensureLastLocationInit();
        updateCurrentLocation(null);
          mapAlive = true;
        NaviEngine.getNaviEngine().naviVoiceInit(this, false);
    }

    public void ensureLocationListener(boolean showMsgEverytime)
    {
      if (locationListenerStatus == PermissionStatus.Disabled) { return; }
      if (locationListenerStatus != PermissionStatus.Enabled)
      {
        boolean f_loc = Permission.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION, this);
        if (!f_loc)
        {
          if (locationListenerStatus == PermissionStatus.Requesting)
          {
            locationListenerStatus = PermissionStatus.Disabled;
            return;
          }
          locationListenerStatus = PermissionStatus.Requesting;
          String[] permissions = new String[2];
          permissions[0] = android.Manifest.permission.ACCESS_FINE_LOCATION;
          permissions[1] = android.Manifest.permission.ACCESS_COARSE_LOCATION;
          Permission.startRequest(permissions, false, this);
          return;
        }
      }
      try
      {
        if (Variable.getVariable().isSmoothON()) {
          locationManager.removeUpdates(this);
          kalmanLocationManager.requestLocationUpdates(UseProvider.GPS, FILTER_TIME, GPS_TIME, NET_TIME, this, false);
          lastProvider = KalmanLocationManager.KALMAN_PROVIDER;
          logUser("LocationProvider: " + lastProvider);
        } else {
          kalmanLocationManager.removeUpdates(this);
          Criteria criteria = new Criteria();
          criteria.setAccuracy(Criteria.ACCURACY_FINE);
          String provider = locationManager.getBestProvider(criteria, true);
          if (provider == null) {
            lastProvider = null;
            locationManager.removeUpdates(this);
            logUser("LocationProvider is off!");
            return;
          } else if (provider.equals(lastProvider)) {
            if (showMsgEverytime) {
              logUser("LocationProvider: " + provider);
            }
            return;
          }
          locationManager.removeUpdates(this);
          lastProvider = provider;
          locationManager.requestLocationUpdates(provider, 3000, 5, this);
          logUser("LocationProvider: " + provider);
        }
        locationListenerStatus = PermissionStatus.Enabled;
      }
      catch (SecurityException ex)
      {
        logUser("Location_Service not allowed by user!");
      }
    }

    /**
     * inject and inflate activity map content to map activity context and bring it to front
     */
    private void customMapView() {
        ViewGroup inclusionViewGroup = (ViewGroup) findViewById(R.id.custom_map_view_layout);
        View inflate = LayoutInflater.from(this).inflate(R.layout.activity_map_content, null);
        inclusionViewGroup.addView(inflate);

        inclusionViewGroup.getParent().bringChildToFront(inclusionViewGroup);
        new SetStatusBarColor().setSystemBarColor(findViewById(R.id.statusBarBackgroundMap),
                getResources().getColor(R.color.my_primary_dark_transparent), this);
        mapActions = new MapActions(this, mapView);

    }

    /**
     * check if GPS enabled and if not send user to the GSP settings
     */
    private void checkGpsAvailability() {

        boolean enabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        if (!enabled) {
            Dialog.showGpsSelector(this);
            System.out.println("print2");
        }
    }

    /**
     * Updates the users location based on the location
     *
     * @param location Location
     */
    private void updateCurrentLocation(Location location) {
        if (location != null) {
            mCurrentLocation = location;
            System.out.println("print6"+mCurrentLocation);
        } else if (mLastLocation != null && mCurrentLocation == null) {
            mCurrentLocation = mLastLocation;
        }
        if (mCurrentLocation != null) {
            GeoPoint mcLatLong = new GeoPoint(mCurrentLocation.getLatitude(), mCurrentLocation.getLongitude());
            if (Tracking.getTracking(getApplicationContext()).isTracking()) {
                MapHandler.getMapHandler().addTrackPoint(this, mcLatLong);
                Tracking.getTracking(getApplicationContext()).addPoint(mCurrentLocation, mapActions.getAppSettings());
                System.out.println("print7");

            }
            if (NaviEngine.getNaviEngine().isNavigating())
            {
              NaviEngine.getNaviEngine().updatePosition(this, mCurrentLocation);
                System.out.println("print8");
            }
            MapHandler.getMapHandler().setCustomPoint(this, mcLatLong);
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(9.9179959,78.0404215));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.198458,	77.438853));    
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.199831,77.441223));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.200522,77.441846));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.201434,77.442335));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.202351,77.442732));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.205054,77.443984));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.208994,77.449346));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.212399,77.459418));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.211319,77.458547));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.2161,77.458775));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.216713,77.457964));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.218378,77.456589));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.221721,77.462114));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.220416,77.464719));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.220214,77.465761));







            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.230843,	77.484136));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.232115,	77.485747));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.236154,	77.489422));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.237971,	77.491113));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.240206,	77.493229));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.241208,	77.494174));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.241208,	77.494174));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.241404,	77.494361));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.243112,	77.495298));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.249331,	77.49484 ));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.251613,	77.50067 ));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.251925,	77.501827));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.25884,	77.512225));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.260503,	77.514193));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.263377,	77.518944));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.268869,	77.522878));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.271911 , 77.524507));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.279374,	77.531164));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.281968,	77.533689));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.286336,	77.538211));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.291553,	77.541008));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.292934,	77.541626));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.301205,	77.554456));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.302669,	77.556133));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.306403,	77.560412));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.307011,	77.561109));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.310027,	77.564572));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.31356,	77.568629));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.322346,	77.577851));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.325522,	77.585148));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.328938,	77.589416));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.331898,	77.595072));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.332993,	77.597305));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.33452,	78.000353));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.342543,	78.013734));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.343813,	78.015717));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.346866,	78.020477));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.353113,	78.033251));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.354403,	78.036234));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.356715,	78.041549));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.359505,	78.047079));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.360555,	78.048784));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.362754,	78.052265));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.363319,	78.053154));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.367111,	78.05748 ));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.368692,	78.058668));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.373423,	78.061531));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.378588,	78.064622));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.379383,	78.065097));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.388238,	78.069901));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.390392,	78.0702  ));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.397143,	78.070098));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.404199,	78.067008));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.407622,	78.065023));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.408886,	78.064127));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.410633,	78.062874));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.415117,	78.059669));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.421463,	78.056137));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.423399,	78.055845));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.428522,	78.055084));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.43561,	78.053915));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.439178,	78.053617));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.447933,	78.057616));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.451206,	78.058247));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.456668,	78.057856));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.473004,	78.056601));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.47532,	78.056423));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.48105,	78.056175));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.481966,	78.056346));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.488948,	78.059984));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.490816,	78.061044));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.497361,	78.06677 ));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.503888,	78.074465));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.507163,	78.075512));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.512403,	78.077209));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.51464,	78.08137 ));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.515346,	78.08283 ));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.517055,	78.084913));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.539169,	78.098677));



            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.197228,77.436489));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(11.198458	,77.438853));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(12.359396,	78.344347));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(12.353869	,78.34535	));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(12.348966	,78.345812));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(12.34766	,78.346055	));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(12.346627	,78.34617));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(12.338655	,78.346555	));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(12.33021,	78.345642	));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(12.306575,	78.33918	));
            MapHandler.getMapHandler().setCustomPoint1(this, new GeoPoint(12.304404	,78.338717));



            mapActions.showPositionBtn.setImageResource(R.drawable.ic_my_location_white_24dp);
        } else {
            mapActions.showPositionBtn.setImageResource(R.drawable.ic_location_searching_white_24dp);
        }
    }

    public MapActions getMapActions() { return mapActions; }

    @Override public void onBackPressed() {
        boolean back = mapActions.homeBackKeyPressed();
        if (back) {
            moveTaskToBack(true);
        }
        // if false do nothing
    }

    @Override protected void onStart() {
        super.onStart();
    }

    @Override public void onResume() {
        super.onResume();
        mapView.onResume();
        ensureLocationListener(true);
        ensureLastLocationInit();
    }

    @Override protected void onPause() {
        super.onPause();
        mapView.onPause();
    }

    @Override protected void onStop() {
        super.onStop();
        // Remove location updates is not needed for tracking
        if (!Tracking.getTracking(getApplicationContext()).isTracking()) {
          locationManager.removeUpdates(this);
          kalmanLocationManager.removeUpdates(this);
          lastProvider = null;
        }
        if (mCurrentLocation != null) {
            GeoPoint geoPoint = mapView.map().getMapPosition().getGeoPoint();
            Variable.getVariable().setLastLocation(geoPoint);
        }
        if (mapView != null) Variable.getVariable().setLastZoomLevel(mapView.map().getMapPosition().getZoomLevel());
        Variable.getVariable().saveVariables(Variable.VarType.Base);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        mapAlive = false;
        locationManager.removeUpdates(this);
        kalmanLocationManager.removeUpdates(this);
        lastProvider = null;
        mapView.onDestroy();
        if (MapHandler.getMapHandler().getHopper() != null) MapHandler.getMapHandler().getHopper().close();
        MapHandler.getMapHandler().setHopper(null);
        Navigator.getNavigator().setOn(false);
        MapHandler.reset();
        Destination.getDestination().setStartPoint(null, null);
        Destination.getDestination().setEndPoint(null, null);
        System.gc();
    }

    /**
     * @return my currentLocation
     */
    public static Location getmCurrentLocation() {
        return mCurrentLocation;
    }

    private void ensureLastLocationInit()
    {
      if (mLastLocation != null) { return; }
        System.out.println("currentlocation"+getmCurrentLocation());

      try
      {
        Location lonet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        if (lonet != null) { mLastLocation = lonet; return; }
          System.out.println("print3");
      }
      catch (SecurityException|IllegalArgumentException e)
      {
        log("NET-Location is not supported: " + e.getMessage());
      }
      try
      {
        Location logps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (logps != null) { mLastLocation = logps; return; }
          System.out.println("print4"+mLastLocation);
      }
      catch (SecurityException|IllegalArgumentException e)
      {
        log("GPS-Location is not supported: " + e.getMessage());
      }
    }

    /**
     * Called when the location has changed.
     * <p/>
     * <p> There are no restrictions on the use of the supplied Location object.
     *
     * @param location The new location, as a Location object.
     */
    @Override public void onLocationChanged(Location location) {
        updateCurrentLocation(location);
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {

    }

    @Override public void onProviderEnabled(String provider) {
        logUser("LocationService is turned on!!");
    }

    @Override public void onProviderDisabled(String provider) {
        logUser("LocationService is turned off!!");
    }

    /** Map was startet and until now not stopped! **/
    public static boolean isMapAlive() { return mapAlive; }
    public static void isMapAlive_preFinish() { mapAlive = false; }

    /**
     * send message to logcat
     *
     * @param str
     */
    private void log(String str) {
        Log.i(this.getClass().getName(), str);
    }

    private void logUser(String str) {
      Log.i(this.getClass().getName(), str);
      try
      {
        Toast.makeText(getBaseContext(), str, Toast.LENGTH_SHORT).show();
      }
      catch (Exception e) { e.printStackTrace(); }
    }
}































































































