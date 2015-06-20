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
import org.sonardrone.Project;
import org.sonardrone.SonardroneActivity;
import org.sonardrone.navigator.NavigatorService;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

public class NavThread extends Thread {
	private Context context = null;
	private Project prj = null;
	public Navigator nav = null;
    public String DRONECENTRAL_URL = "drone_central_url";
    public String TAG = "NavThread";

    public NavThread(String name, Context context) {
		super(name);
		this.context = context;
		NavigatorService.operative = false;
		this.prj = new Project(Navigator.projectName);
		this.nav = new Navigator();
		this.nav.initProject();
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
				Boolean.toString(Navigator.getActive())));
		nameValuePairs.add(new BasicNameValuePair("rudder_angle",
				Double.toString(Navigator.getRudderAngle())));
		nameValuePairs.add(new BasicNameValuePair("auto_pilot",
				Boolean.toString(Navigator.getAutopilot())));
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
	    	NavThread.this.nav.updateGPS(
	    			intent.getDoubleArrayExtra("pos"),
	    			intent.getLongExtra("timestamp", 0),
	    			intent.getFloatExtra("accuracy", 0));
	    }
	};
	
	private BroadcastReceiver orientationReceiver = new BroadcastReceiver() {
	    @Override
	    public void onReceive(Context context, Intent intent) {
	    	NavThread.this.nav.updateCompass(
	    			intent.getDoubleExtra("heading", 0.0),
	    			intent.getLongExtra("timestamp", 0));
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
	    	Navigator.setAutopilot(false);
	    	break;
	    case AUTOPILOT:
	    	Navigator.setAutopilot(true);
	    	break;
	    case DEACTIVATE:
	    	Navigator.setActive(false);
	    	break;
	    case ACTIVATE:
	    	Navigator.setActive(true);
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
	    	Navigator.setActive(false);
	    	NavThread.this.writeSurvey(val);
	    	break;	    	
	    case SET_RUDDER:
	    	int angle = Integer.parseInt(val);
	    	Navigator.setRudderAngle(angle);
	    	break;
	    case START_MOTOR:
	    	Navigator.setMotorLoad(80);
	    	break;
	    case STOP_MOTOR:
	    	Navigator.setMotorLoad(0);
	    	break;
	    case SET_LOAD:
	    	int load = Integer.parseInt(val);
	    	Navigator.setMotorLoad(load);
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
		File waypoint = new File(
				this.prj.getProjectDir(),
				"waypoints.txt");
		BufferedWriter writer = null;				
		try {
			writer = new BufferedWriter(new FileWriter(waypoint));
		} catch (IOException e1) {
			Log.e(TAG, "Error: " + e1.getMessage());
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
    	
		LocalBroadcastManager.getInstance(this.context).registerReceiver(gcmMessageReceiver,
				new IntentFilter("COMMAND"));
		
		LocalBroadcastManager.getInstance(this.context).registerReceiver(locationReceiver,
				new IntentFilter("LOCATION_UPDATED"));
		
		LocalBroadcastManager.getInstance(this.context).registerReceiver(orientationReceiver,
				new IntentFilter("ORIENTATION_UPDATED"));
    	    	
    	//read parameters from settings.rf
    	this.nav.readResources();

    	// init measurement logs
    	this.prj.initLogs();
    	
    	// init sensors, e.g. wait for GPS-fix
    	this.nav.initSensors();
    	
    	// init navigation time
    	this.nav.initTime();

    	// operation loop
    	while (NavigatorService.operative) {
    		//waits (polls) for waypoints if not using autopilot
   			this.nav.initWaypoints();
   			
   			if (NavigatorService.operative)
   				this.nav.initNavigation();
   			
   			if (NavigatorService.operative)
   				this.nav.run();
   			
   		}
   		this.nav.finish();
   	    LocalBroadcastManager.getInstance(this.context).unregisterReceiver(gcmMessageReceiver);
   		LocalBroadcastManager.getInstance(this.context).unregisterReceiver(locationReceiver);
   		LocalBroadcastManager.getInstance(this.context).unregisterReceiver(orientationReceiver);
		}
    };
