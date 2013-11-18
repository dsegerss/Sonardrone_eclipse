package org.sonardrone.gps;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Map;

import org.sonardrone.R;
import org.sonardrone.navigator.NavigatorService;
import org.sonardrone.proj.positions.SWEREF99Position;
import org.sonardrone.proj.positions.WGS84Position;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

public class GpsLoggerService extends Service {
	private LocationManager lm;
	private MyLocationListener locationListener;
	private static long minTimeMillis = 1000;
	private static long minDistanceMeters = 2;
	private static float minAccuracyMeters = 15;

	private final DecimalFormat sevenSigDigits = new DecimalFormat("0.#######");
	private final DateFormat timestampFormat = new SimpleDateFormat(
			"yyyyMMddHHmmss");

	private static final String tag = "GpsLoggerService";
	private File projectDir = null;
	private FileWriter measlog = null;

	// Map with settings
	private Map<String, String> settings = new HashMap<String, String>();
	// Map with row order for settings incl. comment rows
	private Map<String, String> rfRowNr = null;

	private int lastStatus = 0;
	private static boolean showingDebugToast = false;

	private NotificationManager mNM;
	
	public NavigatorService boundNavigatorService;
	private boolean navigatorServiceIsBound = false;

	public double[] getPos() {
		return this.locationListener.pos;
	}

	public long getTimestamp() {
		return this.locationListener.timestamp;
	}

	public float getAccuracy() {
		return this.locationListener.accuracy;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		startGpsLoggerService();
		mNM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		// Display a notification about us starting. We put an icon in the
		// status bar.
		showNotification();
		
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		// Release bound services
		doUnbindServices();

		shutdownGpsLoggerService();

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
		System.out.println("Started location manager");
	}

	private void shutdownGpsLoggerService() {
		lm.removeUpdates(locationListener);
	}

	@Override
	public void onStart(Intent intent, int startId) {
		super.onStart(intent, startId);
		// File sdcard = Environment.getExternalStorageDirectory();
		// File rootDir = new File(sdcard, "sonardrone");

		this.projectDir = new File(
				(String) intent.getCharSequenceExtra("projectDir"));
		System.out.println(String.format("Project dir is: %s",
				this.projectDir.toString()));

		this.readConfig();

		// init measurement log
		File measLogPath = new File(this.projectDir, "meas.log");
		try {
			this.measlog = new FileWriter(measLogPath);
		} catch (Exception e) {// Catch exception if any
			System.err.println("Error: " + e.getMessage());
		}
		locationListener.initLog(this.measlog);
		
		if (this.settings.get("navigatorServiceSwitch") == "true") {
			this.doBindNavigatorService();
   		}


	}

	private void readConfig() {
		// resource file object
		File rf = new File(this.projectDir, "settings.txt");
		// Read resource file into settings hash map
		this.rfRowNr = new HashMap<String, String>();
		this.settings = new HashMap<String, String>();

		BufferedReader reader = null;
		System.out.println("Reading resources");
		try {
			reader = new BufferedReader(new FileReader(rf));
			String row;
			int rownr = 0;
			while ((row = reader.readLine()) != null) {
				if (row.startsWith("#") || row.trim() == "") {
					this.rfRowNr.put(String.valueOf(rownr), row);
					rownr += 1;
					continue;
				}
				String[] keyValuePair = row.split(":");
				// For rfRowNr, rownumber is key and value is settings key-word
				this.rfRowNr.put(String.valueOf(rownr), keyValuePair[0]);
				this.settings.put(keyValuePair[0], keyValuePair[1].trim());
				rownr += 1;
			}
		} catch (FileNotFoundException e) {
			System.err.println("Error: " + e.getMessage());
			System.exit(1);
		} catch (IOException e) {
			System.err.println("Error: " + e.getMessage());
			System.exit(1);
		} finally {
			try {
				if (reader != null) {
					reader.close();
				}
			} catch (IOException e) {
				System.err.println("Error: " + e.getMessage());
				System.exit(1);
			}
		}

		/*
		 * String[] doubleParams = {"k","load","rudder_angle","dt_default",
		 * "tolerance"
		 * ,"ax_max","ay_max","max_rudder_angle","max_dir_change","tau",
		 * "sigmaX_GPS","sigmaV_GPS","sigmaPhi_GPS","sigmaPhi_compass",
		 * "sigmaBeta_rudder"
		 * ,"sigmaV_load","min_look_ahead","look_ahead","minVelDist",
		 * "minBearingDist"
		 * ,"bearingTurnrateThreshold","compassTurnrateThreshold"
		 * ,"min_turn_radius"};
		 * 
		 * String[] boolParams ={"filterSwitch","compassSwitch",
		 * "gpsPositionSwitch","gpsVelSwitch",
		 * "gpsBearingSwitch","encoderVelSwitch","updateKSwitch"};
		 * 
		 * //todo: check for all required parameters
		 */
	}

	public class MyLocationListener implements LocationListener {
		// last fix
		public double[] pos = { 0, 0 };
		public long timestamp = 0;
		public float accuracy = 100;

		public FileWriter gpslog = null;

		public void initLog(FileWriter log) {
			this.gpslog = log;
		}

		public double[] project(String projName, double[] point) {
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
						pointIsRecorded = true;

						double[] geo_pos = { loc.getLongitude(),
								loc.getLatitude() };
						double[] proj_pos = this.project("EPSG:32633", geo_pos);

						this.pos[0] = proj_pos[0];
						this.pos[1] = proj_pos[1];
						this.timestamp = System.currentTimeMillis();
						this.accuracy = loc.getAccuracy();
						updatePos();

						GregorianCalendar greg = new GregorianCalendar();
						// TimeZone tz = greg.getTimeZone();
						// int offset =tz.getOffset(System.currentTimeMillis());
						// greg.add(Calendar.SECOND, (offset / 1000) * -1);
						String msg = timestampFormat.format(greg.getTime())
								+ "\t"
								+ proj_pos[0]
								+ "\t"
								+ proj_pos[1]
								+ "\t"
								+ (loc.hasAccuracy() ? loc.getAccuracy()
										: "NULL")
								+ "\t"
								+ (loc.hasSpeed() ? loc.getSpeed() : "NULL")
								+ ","
								+ (loc.hasBearing() ? loc.getBearing() : "NULL")
								+ "\n";

						this.gpslog.write(msg);
						this.gpslog.flush();
						System.out.print(msg);
					}
				} catch (Exception e) {
					Log.e(tag, e.toString());
				}

				if (pointIsRecorded) {
					if (showingDebugToast)
						Toast.makeText(
								getBaseContext(),
								"Location stored: \nLat: "
										+ sevenSigDigits.format(loc
												.getLatitude())
										+ " \nLon: "
										+ sevenSigDigits.format(loc
												.getLongitude())
										+ " \nAlt: "
										+ (loc.hasAltitude() ? loc
												.getAltitude() + "m" : "?")
										+ " \nAcc: "
										+ (loc.hasAccuracy() ? loc
												.getAccuracy() + "m" : "?"),
								Toast.LENGTH_SHORT).show();
				} else {
					if (showingDebugToast)
						Toast.makeText(
								getBaseContext(),
								"Location not accurate enough: \nLat: "
										+ sevenSigDigits.format(loc
												.getLatitude())
										+ " \nLon: "
										+ sevenSigDigits.format(loc
												.getLongitude())
										+ " \nAlt: "
										+ (loc.hasAltitude() ? loc
												.getAltitude() + "m" : "?")
										+ " \nAcc: "
										+ (loc.hasAccuracy() ? loc
												.getAccuracy() + "m" : "?"),
								Toast.LENGTH_SHORT).show();
				}
			}
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
				Toast.makeText(getBaseContext(), "new status: " + showStatus,
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
	
	private ServiceConnection navigatorServiceConnection = new ServiceConnection() {
		public void onServiceConnected(ComponentName className, IBinder service) {
			boundNavigatorService = ((NavigatorService.LocalBinder) service)
					.getService();
		}
		public void onServiceDisconnected(ComponentName className) {
			boundNavigatorService = null;
		}
	};

	private void doBindNavigatorService() {
		Intent intent = new Intent(this, NavigatorService.class);
		bindService(intent, navigatorServiceConnection,
				Context.BIND_AUTO_CREATE);
		navigatorServiceIsBound = true;
	}
	
	private void doUnbindServices() {
		if (navigatorServiceIsBound) {
			// Detach our existing connection.
			unbindService(navigatorServiceConnection);
			navigatorServiceIsBound = false;
		}
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
		// In this sample, we'll use the same text for the ticker and the
		// expanded notification
		CharSequence text = "GPS-logger active!";

		// Set the icon, scrolling text and timestamp
		Notification notification = new Notification(R.drawable.gpslogger16,
				text, System.currentTimeMillis());

		notification.setLatestEventInfo(this, "GPS-Service", "Click to stop",
				PendingIntent.getService(this, 0, new Intent(
						"stop", null, this, this.getClass()), 0));
		
		// The PendingIntent to launch our activity if the user selects this
		// notification
		PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
				new Intent(this, GpsLoggerService.class), 0);

		// Set the info for the views that show in the notification panel.
		//notification.setLatestEventInfo(this, "GpsLoggerService", text,
		//		contentIntent);
		
		notification.flags |= Notification.FLAG_ONGOING_EVENT;

		// Send the notification.
		// We use a layout id because it is a unique number. We use it later to
		// cancel.
		mNM.notify(0, notification);
	}
	
	public void updatePos() {
		if(this.navigatorServiceIsBound) {
			this.boundNavigatorService.updatePos(
					this.locationListener.pos,
					this.locationListener.timestamp,
					this.locationListener.accuracy);
		}
		
	}
}
