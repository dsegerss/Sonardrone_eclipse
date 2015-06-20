package org.sonardrone.navigator;

import ioio.lib.api.DigitalOutput;
import ioio.lib.api.IOIO;
import ioio.lib.api.PwmOutput;
import ioio.lib.api.DigitalOutput.Spec.Mode;
import ioio.lib.api.exception.ConnectionLostException;
import ioio.lib.util.BaseIOIOLooper;
import ioio.lib.util.IOIOLooper;
import ioio.lib.util.android.IOIOService;

import java.util.Timer;
import org.sonardrone.R;
import org.sonardrone.SonardroneActivity;
import org.sonardrone.proj.positions.SWEREF99Position;
import org.sonardrone.proj.positions.WGS84Position;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

/**
 * /** Waypoint navigation for autonomous boat Uses Kalman-filtering of:
 * GPS-position, compass, speed, rudder encoder and motor load Reads GPS
 * positions from file Controls steering, motors through IOIO Logs depth
 * readings using IOIO While active, a notification will appear on the
 * notification bar, enabling the user to stop the service.
 * 00
 * @author David Segersson
 */

public class NavigatorService extends IOIOService {

	private static final String TAG = "NavigatorService";
	private static boolean showingDebugToast = false;
	private NotificationManager mNM;
	private NavThread navThread;


	// global flag showing whether navigator is polling for commands or waypoints
	public static volatile boolean operative=false;

	// Android sensors, managers and listeners
	private LocationManager lm;
	private MyLocationListener locationListener;
	private SensorManager mSensorManager;
	private Sensor accelerometer;
	private Sensor magnetometer;	
	public OrientationListener orientationListener;
	
	// GPS update settings
	private static long minTimeMillis = 100;
	private static long minDistanceMeters = 0;
	private static float minAccuracyMeters = 100;

	// Magnetometer update settings
	private final int ORIENTATION_UPDATE_PERIOD = 500;
	private final int ORIENTATION_STARTUP_DELAY = 2000;
	private int lastStatus = 0;

	// Timer for reading and broadcasting orientation
	private Timer orientationBroadcaster = new Timer();


	// Broadcaster for orientation updating
	private Handler orientationUpdatedBroadcastHandler = new Handler();
//	private class OrientationUpdatedBroadcaster extends TimerTask {
//		public void run() {
//			Intent intent = new Intent("ORIENTATION_UPDATED");
//		    intent.putExtra("heading", NavigatorService.this.get_heading());
//		    intent.putExtra("timestamp", NavigatorService.this.get_heading_timestamp());
//		    LocalBroadcastManager.getInstance(NavigatorService.this).sendBroadcastSync(intent);
//		}
//	}
	
	private Runnable updateOrientationRunnable = new Runnable() {
		@Override
		public void run() {
			Intent intent = new Intent("ORIENTATION_UPDATED");
		    intent.putExtra("heading", NavigatorService.this.get_heading());
		    intent.putExtra("timestamp", NavigatorService.this.get_heading_timestamp());
		    LocalBroadcastManager.getInstance(NavigatorService.this).sendBroadcast(intent);
		    orientationUpdatedBroadcastHandler.postDelayed(this, ORIENTATION_UPDATE_PERIOD);
		}
	};
	
	public double get_heading() {
		return this.orientationListener.get_heading();
	}

	public long get_heading_timestamp() {
		return this.orientationListener.get_heading_timestamp();
	}

	// Broadcast receiver to toggle operation
	private BroadcastReceiver CommandReceiver = new BroadcastReceiver() {
	  @SuppressWarnings("incomplete-switch")
	  @Override
	  public void onReceive(Context context, Intent intent) {
	    // Get extra data included in the Intent
	    String command = intent.getStringExtra("command");
	    Log.d("CommandReceiver", "command: " + command);
	    String cmd = command.split(";")[0];
	    
	    switch (COMMAND.valueOf(cmd)) {
	    case SET_RUDDER:
	    	Navigator.setRudderAngle(intent.getIntExtra("value", 0));
	    	break;
	    case SET_LOAD:
	    	Navigator.setMotorLoad(intent.getIntExtra("value", 0));
	    	break;
	    case ACTIVATE:
	    	Navigator.setActive(true);
	    	break;
	    case DEACTIVATE:
	    	Navigator.setActive(false);
	    	break;
	    case OPERATE:
	    	NavigatorService.this.operate();
	    	break;
	    case SHUTDOWN:
	    	NavigatorService.this.shutdown();
	    	break;
	    }
	  }
	};
	
	// IOIO Looper thread
	protected IOIOLooper createIOIOLooper() {
		return new BaseIOIOLooper() {
			//output signals
			private DigitalOutput led_;
			private PwmOutput servo_;
//			private PwmOutput load_;

			//pins
			private final int SERVO_PIN = 3;
			
			// Servo pwm frequency
			private final int SERVO_PWM_FREQ = 100;
			
			// ioio timestep
			private final int deltat=200;
			

			// setup of looper thread
			@Override
			protected void setup() throws ConnectionLostException,
					InterruptedException {
				Log.d(TAG,"IOIO setup");
				led_ = ioio_.openDigitalOutput(IOIO.LED_PIN);
				servo_ = ioio_.openPwmOutput(new DigitalOutput.Spec(SERVO_PIN, Mode.OPEN_DRAIN), SERVO_PWM_FREQ);
//				load_= ioio_.openPwmOutput(new DigitalOutput.Spec(SERVO_PIN, Mode.OPEN_DRAIN), 0);
			}

			// Calculate pwm frequency for a specific rudder angle
			private int angle2pw(int angle) {
				Log.d(TAG,"angle2pw");
				double pw = 1000.0 + ((double) angle + 90.0)/180.0 * 1000.0;

				if(pw>2000)
					pw=2000.0;
				if(pw<1000)
					pw=1000.0;
				return (int) pw;
				
			}
			

			// Looper for IOIO communication
			@Override
			public void loop() throws ConnectionLostException,
					InterruptedException {
				if (Navigator.getActive()) {
					int pw=angle2pw(Navigator.getRudderAngle());
					if (pw < 1750) {
						Navigator.setRudderAngle(Navigator.getRudderAngle() + 5);
						pw = angle2pw(Navigator.getRudderAngle());
					} else {
						Navigator.setRudderAngle(-45);
						pw = angle2pw(Navigator.getRudderAngle());
					}
					Log.d(TAG,String.format("Rudder pulse width: %d", pw));
					servo_.setPulseWidth(pw);
//				load_.setPulseWidth(angle2pw(Navigator.getMotorLoad()));
				if(Navigator.getMotorLoad()>0)
					led_.write(true);
				else
					led_.write(false);	
				}
				Thread.sleep(this.deltat);
			}
				
		};
	}

	
	
	@Override
	public void onCreate() {
		super.onCreate();
		mNM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		initLocationListener();
		initOrientationListener();		
		LocalBroadcastManager.getInstance(this).registerReceiver(CommandReceiver,
				new IntentFilter("COMMAND"));
		this.navThread = new NavThread("navigator",this);
	}
	
	public void initOrientationListener() {
		this.orientationListener = new OrientationListener();
	    mSensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
	    accelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
	    magnetometer = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
	    mSensorManager.registerListener(this.orientationListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
	    mSensorManager.registerListener(this.orientationListener, magnetometer, SensorManager.SENSOR_DELAY_NORMAL);
	    this.orientationUpdatedBroadcastHandler.postDelayed(updateOrientationRunnable,ORIENTATION_UPDATE_PERIOD);
	}
	/** Called when the service is first created. */
	private void initLocationListener() {

		// ---use the LocationManager class to obtain GPS locations---
		lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
		locationListener = new MyLocationListener();
		lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, minTimeMillis,
				minDistanceMeters, locationListener);
		Log.d(TAG, "started location manager");
	}

	private void shutdownLocationListener() {
		lm.removeUpdates(locationListener);
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
				try {
					if (loc.hasAccuracy()
							&& loc.getAccuracy() <= minAccuracyMeters) {
						if (true) {
							// Log.d(TAG, "Position updated");
							double[] geo_pos = { loc.getLongitude(),
									loc.getLatitude() };
							double[] proj_pos = this.project("EPSG:32633", geo_pos);

							this.pos[0] = proj_pos[0];
							this.pos[1] = proj_pos[1];
							this.timestamp = System.currentTimeMillis();
							this.accuracy = loc.getAccuracy();
							sendLocationUpdatedBroadcast();
						}
					}
					else {
					Log.d(TAG, String.format("Position discarded accuracy: %f < %f", loc.getAccuracy(), minAccuracyMeters));
					}
				} catch (Exception e) {
					Log.e(TAG, e.toString());
				}
			}
		}
		
		private void sendLocationUpdatedBroadcast(){
			Intent intent = new Intent("LOCATION_UPDATED");
		    intent.putExtra("pos", this.pos);
		    intent.putExtra("accuracy", this.accuracy);
		    intent.putExtra("timestamp", this.timestamp);
		    LocalBroadcastManager.getInstance(NavigatorService.this).sendBroadcastSync(intent);
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
				Toast.makeText(NavigatorService.this, "new status: " + showStatus,
						Toast.LENGTH_SHORT).show();
			}
			lastStatus = status;
		}

	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		int result = super.onStartCommand(intent, flags, startId);
		showNotification();	
		this.sendServiceStateBroadcast(true);
		return result;
	}
	
	private void showNotification() {
		// In this sample, we'll use the same text for the ticker and the
				// expanded notification
		CharSequence text = "Navigator service active!";
				
		// prepare intent which is triggered if the
		// notification is selected

		Intent intent = new Intent(this, SonardroneActivity.class);
		PendingIntent pIntent = PendingIntent.getActivity(this, 0, intent, 0);

		// build notification
		// the addAction re-use the same intent to keep the example short
		Notification n  = new NotificationCompat.Builder(this)
				.setContentTitle(text)
				.setContentText("Navigator service is running")
				.setSmallIcon(R.drawable.ic_stat_device_access_location_found)
				.setContentIntent(pIntent)
				.addAction(R.drawable.ic_stat_action_settings, "Go to activity", pIntent).build();
		
		mNM.notify(0, n);	
	}
	
	private void sendServiceStateBroadcast(boolean state){
		Intent intent = new Intent("SERVICE_STATE");
	    intent.putExtra("SERVICE", "NavigatorService");
	    intent.putExtra("STATE", state);
	    LocalBroadcastManager.getInstance(this).sendBroadcastSync(intent);
	}
	public void operate() {
		NavigatorService.operative=true;
		this.navThread.run();
	}
	
	public void shutdown(){
		NavigatorService.operative=false;
	}
		
	@Override
	public void onDestroy() {
		broadcastNavCommand("SHUTDOWN");		
		// Cancel the persistent notification.
		// Tell the user we stopped.
		shutdownLocationListener();
		orientationUpdatedBroadcastHandler.removeCallbacks(updateOrientationRunnable);
	    mSensorManager.unregisterListener(this.orientationListener);
		Toast.makeText(this, "Navigator stopped!", Toast.LENGTH_SHORT).show();
		LocalBroadcastManager.getInstance(this).unregisterReceiver(CommandReceiver);		
		super.onDestroy();
		this.sendServiceStateBroadcast(false);
		mNM.cancel(0);
	}

	
	@Override
	public IBinder onBind(Intent arg0) {
		return mBinder;
	}


	// This is the object that receives interactions from clients. See
	// RemoteService for a more complete example.
	private final IBinder mBinder = new LocalBinder();

	/*
	 * Class for clients to access. Because we know this service always runs in
	 * the same process as its clients, we don't need to deal with IPC.
	 */
	public class LocalBinder extends Binder {
		public NavigatorService getService() {
			return NavigatorService.this;
		}
	};
	
	private void broadcastNavCommand(String command){
		Intent intent = new Intent("COMMAND");
	    intent.putExtra("command", command);
	    LocalBroadcastManager.getInstance(this).sendBroadcastSync(intent);
	}
	
	public void broadcastNavCommandDouble(String command, double value) {
		Intent intent = new Intent("COMMAND");
	    intent.putExtra("command", command);
	    intent.putExtra("value", value);
	    LocalBroadcastManager.getInstance(this).sendBroadcastSync(intent);
	}
	
	public void broadcastNavCommandBoolean(String command, boolean value) {
		Intent intent = new Intent("COMMAND");
	    intent.putExtra("command", command);
	    intent.putExtra("value", value);
	    LocalBroadcastManager.getInstance(this).sendBroadcastSync(intent);
	}
	
	public void broadcastNavCommandPosArray(String command, double[] lon, double[] lat) {
		Intent intent = new Intent("COMMAND");
	    intent.putExtra("command", command);
	    intent.putExtra("lon", lon);
	    intent.putExtra("lat", lat);
	    LocalBroadcastManager.getInstance(this).sendBroadcastSync(intent);
	}
	
}
