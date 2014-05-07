package org.sonardrone.navigator;

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
	// Map with settings 
	private Map<String, String> settings = new HashMap<String, String>();
	// Map with row order for settings incl. comment rows
	private Map<String, String> rfRowNr = null;
	
	private NotificationManager mNM;
			
	private NavThread navThread;	
	
	// Our handler for received Intents. This will be called whenever an Intent
	// with an action named "custom-event-name" is broadcasted.
	private BroadcastReceiver CommandReceiver = new BroadcastReceiver() {
	  @SuppressWarnings("incomplete-switch")
	  @Override
	  public void onReceive(Context context, Intent intent) {
	    // Get extra data included in the Intent
	    String command = intent.getStringExtra("command");
	    Log.d("CommandReceiver", "command: " + command);
	    String cmd = command.split(";")[0];
	    
	    switch (COMMAND.valueOf(cmd)) {	    
	    case OPERATE:
	    	NavigatorService.this.operate();
	    	break;
	    case SHUTDOWN:
	    	NavigatorService.this.shutdown();
	    	break;
	    }
	  }
	};
		
	@Override
	public void onCreate() {
		super.onCreate();
		mNM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		// Display a notification about us starting. We put an icon in the
		// status bar.
		this.navThread = new NavThread("navigator",this);
		LocalBroadcastManager.getInstance(this).registerReceiver(CommandReceiver,
				new IntentFilter("COMMAND"));
	}
	
	@Override
	public void onStart(Intent intent, int startId) {
		super.onStart(intent, startId);
		showNotification();	
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
		
	@Override
	public void onDestroy() {
		broadcastNavCommand("SHUTDOWN");		
		// Cancel the persistent notification.
		// Tell the user we stopped.
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
