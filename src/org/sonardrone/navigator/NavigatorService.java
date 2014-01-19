package org.sonardrone.navigator;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;


import org.sonardrone.R;
import org.sonardrone.SonardroneActivity;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import android.os.Binder;
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

public class NavigatorService extends Service {
	public static volatile boolean operative=false;
	private static final String TAG = "NavigatorService";
	
	private File projectDir = null;
	// Map with settings 
	private Map<String, String> settings = new HashMap<String, String>();
	// Map with row order for settings incl. comment rows
	private Map<String, String> rfRowNr = null;
	
	private NotificationManager mNM;
			
	private NavThread navThread;	
	
	// Our handler for received Intents. This will be called whenever an Intent
	// with an action named "custom-event-name" is broadcasted.
	private BroadcastReceiver gcmOperationReceiver = new BroadcastReceiver() {
	  @SuppressWarnings("incomplete-switch")
	  @Override
	  public void onReceive(Context context, Intent intent) {
	    // Get extra data included in the Intent
	    String message = intent.getStringExtra("message");
	    Log.d("gcmOperationReceiver", "got message: " + message);
	    String cmd = message.split(";")[0];
	    
	    switch (COMMAND.valueOf(cmd)) {	    
	    case OPERATE:
	    	Log.d(TAG, "Broadcasted OPERATE from Navigator Service");
	    	NavigatorService.this.broadcastNavCommand("OPERATE");
	    	break;
	    case SHUTDOWN:
	    	Log.d(TAG, "Broadcasted SHUTDOWN from Navigator Service");
	    	NavigatorService.this.broadcastNavCommand("SHUTDOWN");
	    	break;
	    }
	  }
	};
	
	@Override
	public int onStartCommand (Intent intent, int flags, int startId){
	    this.projectDir = new File(intent.getStringExtra("projectDir"));
	    return START_STICKY;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		mNM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		// Display a notification about us starting. We put an icon in the
		// status bar.

		this.readConfig();
		this.navThread = new NavThread("navigator",this.projectDir,this);
		LocalBroadcastManager.getInstance(this).registerReceiver(gcmOperationReceiver,
				new IntentFilter("GCM_COMMAND"));
	}
	
	@Override
	public void onStart(Intent intent, int startId) {
		super.onStart(intent, startId);
		showNotification();	
		this.projectDir = new File(
				(String) intent.getCharSequenceExtra("projectDir"));
		this.sendServiceStateBroadcast(true);
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
	
	private void readConfig() {
		// resource file object
		File rf = new File(this.projectDir, "settings.txt");
		// Read resource file into settings hash map
		this.rfRowNr = new HashMap<String, String>();
		this.settings = new HashMap<String, String>();

		BufferedReader reader = null;
		Log.d(TAG,"Reading resources");
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
			Log.e(TAG,"Error: " + e.getMessage());
			System.exit(1);
		} catch (IOException e) {
			Log.e(TAG,"Error: " + e.getMessage());
			System.exit(1);
		} finally {
			try {
				if (reader != null) {
					reader.close();
				}
			} catch (IOException e) {
				Log.e(TAG, "Error: " + e.getMessage());
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
	
	@Override
	public void onDestroy() {
		broadcastNavCommand("SHUTDOWN");		
		// Cancel the persistent notification.
		// Tell the user we stopped.
		Toast.makeText(this, "Navigator stopped!", Toast.LENGTH_SHORT).show();
		LocalBroadcastManager.getInstance(this).unregisterReceiver(gcmOperationReceiver);		
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
