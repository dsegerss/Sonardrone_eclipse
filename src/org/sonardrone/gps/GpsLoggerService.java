package org.sonardrone.gps;

import java.util.Timer;
import java.util.TimerTask;

import org.sonardrone.R;
import org.sonardrone.SonardroneActivity;
import org.sonardrone.proj.positions.SWEREF99Position;
import org.sonardrone.proj.positions.WGS84Position;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

public class GpsLoggerService extends Service {
	private LocationManager lm;
	private MyLocationListener locationListener;
	
	private SensorManager mSensorManager;
	private Sensor accelerometer;
	private Sensor magnetometer;	
	public OrientationListener orientationListener;
	
	private static long minTimeMillis = 500;
	private static long minDistanceMeters = 2;
	private static float minAccuracyMeters = 5;

	//private final DecimalFormat sevenSigDigits = new DecimalFormat("0.#######");
	//private final DateFormat timestampFormat = new SimpleDateFormat(
	//		"yyyyMMddHHmmss");

	private static final String tag = "GpsLoggerService";
	private final int ORIENTATION_UPDATE_PERIOD = 500;
	private final int ORIENTATION_STARTUP_DELAY = 2000;
	private int lastStatus = 0;
	private static boolean showingDebugToast = false;

	private NotificationManager mNM;
	
	private Timer orientationBroadcaster = new Timer();
	
	private class OrientationUpdatedBroadcaster extends TimerTask {
		public void run() {
			Intent intent = new Intent("ORIENTATION_UPDATED");
		    intent.putExtra("heading", GpsLoggerService.this.get_heading());
		    intent.putExtra("timestamp", GpsLoggerService.this.get_heading_timestamp());
		    LocalBroadcastManager.getInstance(GpsLoggerService.this).sendBroadcastSync(intent);
		}
	}
	
	public double get_heading() {
		return this.orientationListener.get_heading();
	}

	public long get_heading_timestamp() {
		return this.orientationListener.get_heading_timestamp();
	}
	
	@Override
	public void onCreate() {
		super.onCreate();
		startGpsLoggerService();
	    mSensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
	    accelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
	    magnetometer = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
	    mSensorManager.registerListener(this.orientationListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
	    mSensorManager.registerListener(this.orientationListener, magnetometer, SensorManager.SENSOR_DELAY_NORMAL);
	    this.orientationBroadcaster.scheduleAtFixedRate(new OrientationUpdatedBroadcaster(),
                ORIENTATION_STARTUP_DELAY, ORIENTATION_UPDATE_PERIOD);
		this.sendServiceStateBroadcast(true);
		mNM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		// Display a notification about us starting. We put an icon in the
		// status bar.
		showNotification();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		shutdownGpsLoggerService();
	    mSensorManager.unregisterListener(this.orientationListener);
		//update activity gui
		this.sendServiceStateBroadcast(false);
		// Cancel the persistent notification.
		mNM.cancel(0);

		// Tell the user we stopped.
		Toast.makeText(this, "GPS-Logger stopped!", Toast.LENGTH_SHORT).show();
	}

	/** Called when the service is first created. */
	private void startGpsLoggerService() {

		// ---use the LocationManager class to obtain GPS locations---
		lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
		locationListener = new MyLocationListener();
		lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, minTimeMillis,
				minDistanceMeters, locationListener);
		Log.d(tag, "Started location manager");
	}

	private void shutdownGpsLoggerService() {
		lm.removeUpdates(locationListener);
	}

	@Override
	public void onStart(Intent intent, int startId) {
		super.onStart(intent, startId);
	}

	private void sendServiceStateBroadcast(boolean state){
		Intent intent = new Intent("SERVICE_STATE");
	    intent.putExtra("SERVICE", "GpsLoggerService");
	    intent.putExtra("STATE", state);
	    LocalBroadcastManager.getInstance(GpsLoggerService.this).sendBroadcastSync(intent);
	}

	private class MyLocationListener implements LocationListener {
		// last fix
		private double[] pos = { 0, 0 };
		private long timestamp = 0;
		private float accuracy = 100;

		private double[] project(String projName, double[] point) {
			/*
			 * Project lat-lon to given projection
			 */
			WGS84Position wgsPos = new WGS84Position();
			wgsPos.setPos(point[0], point[1]);
			SWEREF99Position rtPos = new SWEREF99Position(wgsPos,
					SWEREF99Position.SWEREFProjection.sweref_99_tm);
			double[] projP = { (double) rtPos.getLongitude(),
					(double) rtPos.getLatitude() };
			return projP;
		}

		public void onLocationChanged(Location loc) {
			if (loc != null) {
				boolean pointIsRecorded = false;
				try {
					// TODO: reactivate accuracy check
					// if (loc.hasAccuracy()
					// && loc.getAccuracy() <= minAccuracyMeters) {
					if (true) {
						Log.d(tag, "Position updated");
						pointIsRecorded = true;

						double[] geo_pos = { loc.getLongitude(),
								loc.getLatitude() };
						double[] proj_pos = this.project("EPSG:32633", geo_pos);

						this.pos[0] = proj_pos[0];
						this.pos[1] = proj_pos[1];
						this.timestamp = System.currentTimeMillis();
						this.accuracy = loc.getAccuracy();
						sendLocationUpdatedBroadcast();
					}
				} catch (Exception e) {
					Log.e(tag, e.toString());
				}
			}
		}
		
		private void sendLocationUpdatedBroadcast(){
			Intent intent = new Intent("LOCATION_UPDATED");
		    intent.putExtra("pos", this.pos);
		    intent.putExtra("accuracy", this.accuracy);
		    intent.putExtra("timestamp", this.timestamp);
		    LocalBroadcastManager.getInstance(GpsLoggerService.this).sendBroadcastSync(intent);
		}
				
		public void onProviderDisabled(String provider) {
			if (showingDebugToast)
				Toast.makeText(getBaseContext(),
						"onProviderDisabled: " + provider, Toast.LENGTH_SHORT)
						.show();

		}

		public void onProviderEnabled(String provider) {
			if (showingDebugToast)
				Toast.makeText(getBaseContext(),
						"onProviderEnabled: " + provider, Toast.LENGTH_SHORT)
						.show();

		}

		public void onStatusChanged(String provider, int status, Bundle extras) {
			String showStatus = null;
			if (status == LocationProvider.AVAILABLE)
				showStatus = "Available";
			if (status == LocationProvider.TEMPORARILY_UNAVAILABLE)
				showStatus = "Temporarily Unavailable";
			if (status == LocationProvider.OUT_OF_SERVICE)
				showStatus = "Out of Service";
			if (status != lastStatus && showingDebugToast) {
				Toast.makeText(GpsLoggerService.this, "new status: " + showStatus,
						Toast.LENGTH_SHORT).show();
			}
			lastStatus = status;
		}

	}

	// This is the object that receives interactions from clients. See
	// RemoteService for a more complete example.
	private final IBinder mBinder = new LocalBinder();

	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}
	
	/*
	 * Class for clients to access. Because we know this service always runs in
	 * the same process as its clients, we don't need to deal with IPC.
	 */
	public class LocalBinder extends Binder {
		public GpsLoggerService getService() {
			return GpsLoggerService.this;
		}
	}

	/**
	 * Show a notification while this service is running.
	 */
	private void showNotification() {
		CharSequence text = "GPS-logger active!";
		
		// prepare intent which is triggered if the
		// notification is selected
		Intent intent = new Intent(this, SonardroneActivity.class);
		PendingIntent pIntent = PendingIntent.getActivity(this, 0, intent, 0);
		
		// build notification
		// the addAction re-use the same intent to keep the example short
		Notification n  = new NotificationCompat.Builder(this)
		        .setContentTitle(text)
		        .setContentText("GpsLoggerService is logging positions")
		        .setSmallIcon(R.drawable.gpslogger16)
		        .setContentIntent(pIntent)
		        .addAction(R.drawable.gpslogger16, "Go to activity", pIntent).build();
		    		  
		this.mNM.notify(0, n);

		// Set the icon, scrolling text and timestamp
		//Notification notification = new Notification(R.drawable.gpslogger16,
		//		text, System.currentTimeMillis());

		//notification.setLatestEventInfo(this, "GPS-Service", "Click to stop",
		//		PendingIntent.getService(this, 0, new Intent(
		//				"stop", null, this, this.getClass()), 0));
		
		// The PendingIntent to launch our activity if the user selects this
		// notification
		//PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
		//new Intent(this, GpsLoggerService.class), 0);

		// Set the info for the views that show in the notification panel.
		//notification.setLatestEventInfo(this, "GpsLoggerService", text,
		//		contentIntent);
		
		//notification.flags |= Notification.FLAG_ONGOING_EVENT;

		// Send the notification.
		// We use a layout id because it is a unique number. We use it later to
		// cancel.
		//mNM.notify(0, notification);
	}	
}
