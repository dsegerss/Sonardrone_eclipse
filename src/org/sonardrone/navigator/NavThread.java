package org.sonardrone.navigator;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.sonardrone.SonardroneActivity;
import org.sonardrone.ioio.IOIOControlService;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

public class NavThread extends Thread {
	private Context context = null;
	public	File projectDir;
	public Navigator nav = new Navigator();
    public String DRONECENTRAL_URL = "drone_central_url";
    public String TAG = "NavThread";

	//bound services
	public static IOIOControlService boundIOIOControlService;
	public static boolean ioioControlServiceIsBound = false;

    public NavThread(String name,File projectDir, Context context) {
		super(name);
		this.projectDir=projectDir;
		this.context = context;
		NavigatorService.operative = false;
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
		Intent intent = new Intent(this.context, IOIOControlService.class);
		this.context.bindService(intent, ioioControlServiceConnection,
				Context.BIND_AUTO_CREATE);	
		ioioControlServiceIsBound = true;
	}
	
	private void doUnbindServices() {
		if (ioioControlServiceIsBound) {
			// Detach our existing connection.
			this.context.unbindService(ioioControlServiceConnection);
			ioioControlServiceIsBound = false;
		}
	}

	public void postStatus() {
		List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>(2);
		double[] pos = NavThread.this.nav.getPosWGS84();
		double[] cwp = NavThread.this.nav.getCWPWGS84();
		nameValuePairs.add(new BasicNameValuePair("lon",
					Double.toString(pos[0])));
		nameValuePairs.add(new BasicNameValuePair("lat",
					Double.toString(pos[1])));
		nameValuePairs.add(new BasicNameValuePair("speed",
					Double.toString(NavThread.this.nav.V())));
		nameValuePairs.add(new BasicNameValuePair("turn_rate",
				Double.toString(NavThread.this.nav.turn_rate())));
		nameValuePairs.add(new BasicNameValuePair("heading",
				Double.toString(NavThread.this.nav.phi())));
		nameValuePairs.add(new BasicNameValuePair("active",
				Boolean.toString(NavThread.this.nav.getActive())));
		nameValuePairs.add(new BasicNameValuePair("rudder_angle",
				Double.toString(NavThread.this.nav.getRudderAngle())));
		nameValuePairs.add(new BasicNameValuePair("auto_pilot",
				Boolean.toString(NavThread.this.nav.getAutopilot())));
		nameValuePairs.add(new BasicNameValuePair("cwp_lon",
				Double.toString(cwp[0])));
		nameValuePairs.add(new BasicNameValuePair("cwp_lat",
				Double.toString(cwp[1])));
		
		this.post(nameValuePairs);
	}
	
	public void post(List<NameValuePair> nameValuePairs) {
        nameValuePairs.add(new BasicNameValuePair("device_id", "12345"));
		HttpClient httpclient = new DefaultHttpClient();
	    HttpPost httppost = new HttpPost(DRONECENTRAL_URL);

	    try {
	        // Add your data	    
	        httppost.setEntity(new UrlEncodedFormEntity(nameValuePairs));

	        // Execute HTTP Post Request
	        HttpResponse response = httpclient.execute(httppost);
	        
	    } catch (ClientProtocolException e) {
	    	Log.i(TAG, "Error when registring to dronecentral: " + e.getMessage());
	    } catch (IOException e) {
	    	Log.i(TAG, "Error when registring to dronecentral: " + e.getMessage());
	    }	
	}
	
	private String getRegistrationId(Context context) {
	    final SharedPreferences prefs = getGCMPreferences(context);
	    String registrationId = prefs.getString(SonardroneActivity.PROPERTY_REG_ID, "");
	    if (registrationId.length() == 0 ){
	        Log.i(TAG, "Registration not found.");
	        return "";
	    }
	    return registrationId;
	}
	
	private SharedPreferences getGCMPreferences(Context context) {
	    // This sample app persists the registration ID in shared preferences, but
	    // how you store the regID in your app is up to you.
	    return this.context.getSharedPreferences(SonardroneActivity.class.getSimpleName(),
	            Context.MODE_PRIVATE);
	}
	
	private BroadcastReceiver locationReceiver = new BroadcastReceiver() {
	    @Override
	    public void onReceive(Context context, Intent intent) {
	    	if (!NavThread.this.nav.simulateGPSSwitch) {
	    		NavThread.this.nav.set_pos_GPS(
	    				intent.getDoubleArrayExtra("pos"));
	    		NavThread.this.nav.set_pos_GPS_time(
	    				intent.getLongExtra("timestamp", 0));
	    		NavThread.this.nav.set_GPS_accuracy(
	    				intent.getFloatExtra("accuracy", 0));
	    	}
	    }
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
	    String val = null;
	    if(message.contains(";"))
	    	val = parts[1];

	    switch (COMMAND.valueOf(cmd)) {
	    case OPERATE:
	    	break;
	    case MANUAL:
	    	NavThread.this.nav.setAutopilot(false);
	    	break;
	    case AUTOPILOT:
	    	NavThread.this.nav.setAutopilot(true);
	    	break;
	    case DEACTIVATE:
	    	NavThread.this.nav.setActive(false);
	    	break;
	    case ACTIVATE:
	    	NavThread.this.nav.setActive(true);
	    	break;
	    case GET_STATUS:
	    	NavThread.this.postStatus();
	    	break;
	    case ADD_WP:
	    	String[] posStrArray = val.split(" ");
	    	for(int i=0; i<posStrArray.length;i++) {
	    		double lon = Double.parseDouble(posStrArray[i].split(",")[0]);
	    		double lat = Double.parseDouble(posStrArray[i].split(",")[1]);
		    	NavThread.this.nav.addWaypointWGS84(lon, lat);	    		
	    	}
	    	break;
	    case ADD_SURVEY:
	    	NavThread.this.nav.setActive(false);
	    	NavThread.this.writeSurvey(val);
	    	break;	    	
	    case SET_RUDDER:
	    	int angle = Integer.parseInt(val);
	    	NavThread.this.nav.setRudderAngle(angle);
	    	break;
	    case START_MOTOR:
	    	NavThread.this.nav.setMotorLoad(80.0);
	    	break;
	    case STOP_MOTOR:
	    	NavThread.this.nav.setMotorLoad(0.0);
	    	break;
	    case SET_LOAD:
	    	int load = Integer.parseInt(val);
	    	NavThread.this.nav.setMotorLoad((double) load);
	    	break;
	    case SHUTDOWN:
	    	NavThread.this.nav.finish();
	    	break;
	    }
	  }
	};
	
	public void writeSurvey(String wpStr) {
		wpStr = wpStr.replace(" ", "\n");
		wpStr = wpStr.replace(",", "\t");
		wpStr = "X\tY\n" + wpStr + "\n";
		File waypoint = new File(this.projectDir, "waypoints.txt");
		BufferedWriter writer = null;				
		try {
			writer = new BufferedWriter(new FileWriter(waypoint));
		} catch (IOException e1) {
			System.err.println("Error: " + e1.getMessage());
			System.exit(1);
		}
		try {
			writer.write(wpStr);
		} catch (IOException e) {
			Log.e(TAG, "Could not write survey to file");
			e.printStackTrace();
		}
		try {
			writer.close();
		} catch (IOException e) {
			Log.e(TAG, "Could not close survey file");
			e.printStackTrace();
		}
	}

    @Override
    public void run(){
    	Log.d(TAG, "Binding to IOIO service");
    	doBindIOIOControlService();
    	
		LocalBroadcastManager.getInstance(this.context).registerReceiver(gcmMessageReceiver,
				new IntentFilter("GCM_COMMAND"));
		
		LocalBroadcastManager.getInstance(this.context).registerReceiver(locationReceiver,
				new IntentFilter("LOCATION_UPDATED"));
    	
    	    	
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
   			
   			if (NavigatorService.operative)
   				this.nav.initNavigation();
   			
   			if (NavigatorService.operative)
   				this.nav.run();
   			
   		}
   		this.nav.finish();
   	    LocalBroadcastManager.getInstance(this.context).unregisterReceiver(gcmMessageReceiver);
   		LocalBroadcastManager.getInstance(this.context).unregisterReceiver(locationReceiver);
		doUnbindServices();
		}
    };
