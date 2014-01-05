package org.sonardrone.navigator;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.sonardrone.R;
import org.sonardrone.ioio.IOIOControlService;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

/**
 * /** Waypoint navigation for autonomous boat Uses Kalman-filtering of:
 * GPS-position, compass, speed, rudder encoder and motor load Reads GPS
 * positions from file Controls steering, motors through IOIO Logs depth
 * readings using IOIO While active, a notification will appear on the
 * notification bar, enabling the user to stop the service.
 * 
 * @author David Segersson
 */

enum COMMAND {
	GET_STATUS,
	ADD_WP,
	ADD_SURVEY,
	CLEAR_WP,
	CLEAR_SURVEY,
	SET_RUDDER,
	SET_LOAD,
	ACTIVATE,
	DEACTIVATE,
	AUTOPILOT,
	MANUAL,
	OPERATE,
	SHUTDOWN,
	START_MOTOR,
	STOP_MOTOR
	};
	
class NavThread extends Thread {
	public	File projectDir; 						//path to project
	public Navigator nav; 							//navigator object
	public static Handler parentHandler;			//Handler for messages to parent service
	private Handler threadHandler = new Handler() {	//Handler for messages to this thread
		 public void handleMessage(Message msg) {
			 switch (COMMAND.valueOf(msg.what)) {
			 case START_MOTOR: 
				 nav.setMotorLoad(80.0);		 
			 case STOP_MOTOR:
				 nav.setMotorLoad(0.0);
			 case ACTIVATE:
				 nav.setActive(true);
			 case DEACTIVATE:
				 nav.setActive(false);
			 case SHUTDOWN:
				 NavigatorService.operative = false;
			 case SET_LOAD:
				 nav.setMotorLoad((double) msg.arg1);
			 case SET_RUDDER:
				 nav.setRudderAngle((double) msg.arg1);
			 case AUTOPILOT:
				 nav.setAutopilot(true);
			 case MANUAL:
				 nav.setAutopilot(false);
			 case ADD_WP:
				 Bundle posData = msg.getData();
				 nav.addWaypointWGS84(posData.getDouble("lon"),posData.getDouble("lat"));				 
			 }
		 }
	};
	
	public NavThread(String name,File projectDir, Handler parentHandler) {
		super(name);
		NavThread.parentHandler = parentHandler;
		this.projectDir=projectDir;
		NavigatorService.operative = false;
	}
	
	public Handler getHandler() {
		return this.threadHandler;
	}
	
    @Override
    public void run(){   			
    	// Create the navigator object
    	this.nav = new Navigator();
    	
    	// define paths for project files
    	File rf = new File(this.projectDir, "settings.rf");
    	File navLog = new File(this.projectDir, "nav.log");
    	File stateLog = new File(this.projectDir, "state.log");
    	File measLog = new File(this.projectDir, "meas.log");
    	File waypoints = new File(this.projectDir, "waypoints.txt");
    	
    	// init measurement logs
    	this.nav.initLogs(rf, navLog, stateLog, measLog);
    	
    	// init sensors, e.g. wait for GPS-fix
    	this.nav.initSensors();

    	// operation loop
    	while (NavigatorService.operative) {
    		//waits (polls) for waypoints if not using autopilot
   			this.nav.initWaypoints(waypoints);

   			//start from first wp or resume from last visited
   			this.nav.initNavigation();
   			
   			//Start navigation loop
   			this.nav.run();
   			
   			// After reaching last waypoint, shut off autoPilot
   			if (!this.nav.wpIter.hasNext())
   				this.nav.autoPilot = false;
   			}
   		this.nav.finish();

    	}    
    }

public class NavigatorService extends Service {
	public static volatile double[] pos = {0,0};
	public static volatile long pos_timestamp=0;
	public static volatile double pos_accuracy=100;
	public static volatile double phi_compass = 0;
	public static volatile double rudderangle = 0;
	public static volatile double load = 0;
	public static volatile boolean operative=false;
	private static final String TAG = "NavigatorService";
	
	private File projectDir = null;
	// Map with settings 
	private Map<String, String> settings = new HashMap<String, String>();
	// Map with row order for settings incl. comment rows
	private Map<String, String> rfRowNr = null;
	
	private NotificationManager mNM;
		
	//bound services
	private IOIOControlService boundIOIOControlService;
	private boolean ioioControlServiceIsBound = false;
	
	private NavThread navThread;
	private Handler navThreadHandler;
	
	private Handler mainHandler = new Handler() {
		 public void handleMessage(Message msg) {
			 switch (COMMAND.valueOf(msg.what)) {
			 case GET_STATUS:
				 postStatus();					 
			 case START_MOTOR:
				 if(ioioControlServiceIsBound)
					 boundIOIOControlService.startMotor();
			 case STOP_MOTOR:
				 if(ioioControlServiceIsBound)
					 boundIOIOControlService.stopMotor();
			 case SET_LOAD:
				 if(ioioControlServiceIsBound)
					 boundIOIOControlService.setMotorLoad(msg.arg1);
			 case SET_RUDDER: 
				 if(ioioControlServiceIsBound)
					 boundIOIOControlService.setRudderAngle(msg.arg1);
			 }
		 };
	};
	
	// Our handler for received Intents. This will be called whenever an Intent
	// with an action named "custom-event-name" is broadcasted.
	private BroadcastReceiver gcmMessageReceiver = new BroadcastReceiver() {
	  @Override
	  public void onReceive(Context context, Intent intent) {
	    // Get extra data included in the Intent
	    String message = intent.getStringExtra("message");
	    Log.d("gcmMessageReceiver", "got message: " + message);
	    String[] parts = message.split(";");
	    String cmd = parts[0];
	    String val = parts[1];

	    switch (COMMAND.valueOf(cmd)) {	    
	    case GET_STATUS:
	    	postStatus();
	    case ADD_WP:
	    	String[] posStrArray = val.split(" ");
	    	double[] lon = new double[posStrArray.length];
	    	double[] lat = new double[posStrArray.length];
	    	for(int i=0; i<posStrArray.length;i++) {
	    		lon[i] = Double.parseDouble(posStrArray[i].split(",")[0]);
	    		lat[i] = Double.parseDouble(posStrArray[i].split(",")[1]);
	    	}
	    	addWaypoints(lon, lat);
	    case ADD_SURVEY:
	    	String[] posStrArray = val.split(" ");
	    	double[] lon = new double[posStrArray.length];
	    	double[] lat = new double[posStrArray.length];
	    	for(int i=0; i<posStrArray.length;i++) {
	    		lon[i] = Double.parseDouble(posStrArray[i].split(",")[0]);
	    		lat[i] = Double.parseDouble(posStrArray[i].split(",")[1]);
	    	}
	    	addSurvey(lon, lat);
	    case SET_LOAD:
	    	int load = Integer.parseInt(val);
	    	setMotorLoad(load);
	    case SET_RUDDER:
	    	int angle = Integer.parseInt(val);
	    	setRudderAngle()
	  }
	};

	@Override
	public void onCreate() {
		super.onCreate();
		mNM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		// Display a notification about us starting. We put an icon in the
		// status bar.
		showNotification();
		
		this.readConfig();
   			
		if (this.settings.get("ioioServiceSwitch") == "true") {
			this.doBindIOIOControlService();
		}	
		this.navThread = new NavThread("navigator",this.projectDir,this.mainHandler);
		this.navThreadHandler = this.navThread.getHandler();
		LocalBroadcastManager.getInstance(this).registerReceiver(gcmMessageReceiver,
				new IntentFilter("gcm-intent"));
	}

	// onStop{}
	// try {
	// nav.statelog.close();
	// nav.measlog.close();
	// nav.navlog.close();
	// } catch (IOException e) {
	// // TODO Auto-generated catch block,
	// e.printStackTrace();
	// }
	
	public void sendNavMessage(Message msgToThread) {
        this.navThreadHandler.sendMessage(msgToThread);
	}

	@Override
	public void onStart(Intent intent, int startId) {
		super.onStart(intent, startId);
		NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		if (intent != null && intent.getAction() != null
				&& intent.getAction().equals("stop")) {
			// User clicked the notification. Need to stop the service.
			nm.cancel(0);
			stopSelf();
		} else {
			// Service starting. Create a notification.
			Notification notification = new Notification(
					R.drawable.ic_launcher, "Navigator running",
					System.currentTimeMillis());
			notification
					.setLatestEventInfo(this, "Navigator service",
							"Click to stop", PendingIntent.getService(
									this,
									0,
									new Intent("stop", null, this, this
											.getClass()), 0));
			notification.flags |= Notification.FLAG_ONGOING_EVENT;
			nm.notify(0, notification);
			this.projectDir = new File(
					(String) intent.getCharSequenceExtra("projectDir"));
		}
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
	
	@Override
	public void onDestroy() {
		// Release bound services
		doUnbindServices();

		// close logs and clean-up
		Message msg = new Message();
		msg.what=0;
		this.sendNavMessage(msg);

		// Cancel the persistent notification.
		mNM.cancel(0);

		// Tell the user we stopped.
		Toast.makeText(this, "Navigator stopped!", Toast.LENGTH_SHORT).show();
		LocalBroadcastManager.getInstance(this).unregisterReceiver(gcmMessageReceiver);
		
		super.onDestroy();
	}

	private void showNotification() {
		/* Show a notification while this service is running */

		// In this sample, we'll use the same text for the ticker and the
		// expanded notification
		CharSequence text = "Navigator running!";

		// Set the icon, scrolling text and timestamp
		Notification notification = new Notification(R.drawable.gpslogger16,
				text, System.currentTimeMillis());

		// The PendingIntent to launch our activity if the user selects this
		// notification
		PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
				new Intent(this, NavigatorService.class), 0);

		// Set the info for the views that show in the notification panel.
		notification.setLatestEventInfo(this, "NavigatorService", text,
				contentIntent);

		// Send the notification.
		// We use a layout id because it is a unique number. We use it later to
		// cancel.
		mNM.notify(0, notification);
	}

	@Override
	public IBinder onBind(Intent arg0) {
		return mBinder;
	}

	private ServiceConnection ioioControlServiceConnection = new ServiceConnection() {
		public void onServiceConnected(ComponentName className, IBinder service) {
			boundIOIOControlService = ((IOIOControlService.LocalBinder) service)
					.getService();
		}

		public void onServiceDisconnected(ComponentName className) {
			boundIOIOControlService = null;
		}
	};

	private void doBindIOIOControlService() {
		Intent intent = new Intent(this, IOIOControlService.class);
		bindService(intent, ioioControlServiceConnection,
				Context.BIND_AUTO_CREATE);
		ioioControlServiceIsBound = true;
	}
	
	private void doUnbindServices() {

		if (ioioControlServiceIsBound) {
			// Detach our existing connection.
			unbindService(ioioControlServiceConnection);
			ioioControlServiceIsBound = false;

		}
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
	}

	public void setMotorLoad(int loadPercentage) {
		// set motor power output in percentage
		if (this.ioioControlServiceIsBound)
			this.boundIOIOControlService.setMotorLoad(loadPercentage);
		// set state
		Message msg = new Message();
		msg.what = 6;
		msg.arg1 = loadPercentage;
		this.sendNavMessage(msg);
	}
	
	public void setRudderAngle(int rudderAngle) {
		// set motor power output in percentage
		if (this.ioioControlServiceIsBound)
			this.boundIOIOControlService.setRudderAngle(rudderAngle);
		// set state
		Message msg = new Message();
		msg.what = 6;
		msg.arg1 = rudderAngle;
		this.sendNavMessage(msg);
	}
	
	public void startMotor() {
		if (this.ioioControlServiceIsBound)
			this.boundIOIOControlService.startMotor();
		Message msg = new Message();
		msg.what = 1;
		this.sendNavMessage(msg);		
	}
	
	public void stopMotor(){
		if (this.ioioControlServiceIsBound)
			this.boundIOIOControlService.stopMotor();
		Message msg = new Message();
		msg.what = 2;
		this.sendNavMessage(msg);		
	}
	
	public void setAutopilot(boolean autoPilotActive) {
		// set state
		Message msg = new Message();
		if (autoPilotActive)			
			msg.what = 8;
		else
			msg.what=9;
		this.sendNavMessage(msg);
	}
	
	public void setActive(boolean navigatorActive) {
		// set state
		Message msg = new Message();
		if (navigatorActive)			
			msg.what = 3;
		else
			msg.what=4;
		this.sendNavMessage(msg);
	}

	public void shutDown() {
		// set state
		Message msg = new Message();
		msg.what = 5;
		this.sendNavMessage(msg);
	}
	
	public void postStatus() {

	}

	public void addWaypoints(double[] lon, double[] lat) {
		// set state
		Message msg = new Message();
		msg.what = 11;
		Bundle data = new Bundle();
		data.putDoubleArray("lon", lon);
		data.putDoubleArray("lat", lat);
		msg.setData(data);
		this.sendNavMessage(msg);		
	}

	public void updatePos(double[] pos,long timestamp,float accuracy) {
		NavigatorService.pos_timestamp=timestamp;
		NavigatorService.pos=pos;
		NavigatorService.pos_accuracy=(double) accuracy;
	}
		
	public void updatePhi(double phi_compass) {
		NavigatorService.phi_compass = phi_compass;
	}

	public void updateRudderAngle(double rudderAngle) {
		NavigatorService.rudderangle = rudderAngle;
	}
	
	public void updateLoad(double load) {
		NavigatorService.load = load;
	}	
}
