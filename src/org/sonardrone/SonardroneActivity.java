package org.sonardrone;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.sonardrone.navigator.Navigator;
import org.sonardrone.navigator.NavigatorService;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.PowerManager;
import android.support.v4.content.LocalBroadcastManager;
import android.text.Editable;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.gcm.GoogleCloudMessaging;

public class SonardroneActivity extends Activity {
	//GCM init
    public static final String EXTRA_MESSAGE = "message";
    public static final String PROPERTY_REG_ID = "registration_id";
    public static final String PROPERTY_APP_VERSION = "appVersion";
    public static final int PLAY_SERVICES_RESOLUTION_REQUEST = 9000;
    public static final String SENDER_ID = "539926077370"; //Project ID from API console
    public static final String DRONECENTRAL_URL = "drone_central_url";

    private Project prj = null;
    
    GoogleCloudMessaging gcm;
    AtomicInteger msgId = new AtomicInteger();
    SharedPreferences prefs;
    Context context;
    String regid;
	private static volatile PowerManager.WakeLock lockStatic = null;
    
	private static final String TAG = "SonardroneActivity";
	static final int DELETE_PROJECT_ID = 1;

	private BroadcastReceiver locationReceiver = null;
	
	private BroadcastReceiver orientationReceiver = null;
	
	private BroadcastReceiver serviceStatusReceiver = null;
	
	private boolean checkPlayServices() {
	    int resultCode = GooglePlayServicesUtil.isGooglePlayServicesAvailable(this);
	    if (resultCode != ConnectionResult.SUCCESS) {
	        if (GooglePlayServicesUtil.isUserRecoverableError(resultCode)) {
	            GooglePlayServicesUtil.getErrorDialog(resultCode, this,
	                    PLAY_SERVICES_RESOLUTION_REQUEST).show();
	        } else {
	            Log.i(TAG, "This device is not supported.");
	            finish();
	        }
	        return false;
	    }
	    return true;
	}
	
	 synchronized private static PowerManager.WakeLock getLock(Context context) {
			if (lockStatic == null) {
				PowerManager mgr=
						(PowerManager)context.getSystemService(Context.POWER_SERVICE);
		      lockStatic=mgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
		    }
		    return(lockStatic);
		  }

	
	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		context = getApplicationContext();
		if (checkPlayServices()) {
            gcm = GoogleCloudMessaging.getInstance(this);
            regid = getRegistrationId(context);

            if (regid.length() == 0) {
                registerInBackground();
            }
        } else {
            Log.i(TAG, "No valid Google Play Services APK found.");
        }
		
		this.init(); // check that app dir exist, and default settings exist
		this.updateSpinner(); // List project directories
		this.loadProject(); // Set project directory to currently selected dir
	}

	/**
	 * Gets the current registration ID for application on GCM service.
	 * <p>
	 * If result is empty, the app needs to register.
	 *
	 * @return registration ID, or empty string if there is no existing
	 *         registration ID.
	 */
	private String getRegistrationId(Context context) {
	    final SharedPreferences prefs = getGCMPreferences(context);
	    String registrationId = prefs.getString(PROPERTY_REG_ID, "");
	    if (registrationId.length() == 0 ){
	        Log.i(TAG, "Registration not found.");
	        return "";
	    }
	    // Check if app was updated; if so, it must clear the registration ID
	    // since the existing regID is not guaranteed to work with the new
	    // app version.
	    int registeredVersion = prefs.getInt(PROPERTY_APP_VERSION, Integer.MIN_VALUE);
	    int currentVersion = getAppVersion(context);
	    if (registeredVersion != currentVersion) {
	        Log.i(TAG, "App version changed.");
	        return "";
	    }
	    return registrationId;
	}
	
	/**
	 * @return Application's {@code SharedPreferences}.
	 */
	private SharedPreferences getGCMPreferences(Context context) {
	    // This sample app persists the registration ID in shared preferences, but
	    // how you store the regID in your app is up to you.
	    return getSharedPreferences(SonardroneActivity.class.getSimpleName(),
	            Context.MODE_PRIVATE);
	}
	
	/**
	 * @return Application's version code from the {@code PackageManager}.
	 */
	private static int getAppVersion(Context context) {
	    try {
	        PackageInfo packageInfo = context.getPackageManager()
	                .getPackageInfo(context.getPackageName(), 0);
	        return packageInfo.versionCode;
	    } catch (NameNotFoundException e) {
	        // should never happen
	        throw new RuntimeException("Could not get package name: " + e);
	    }
	}
	
	/**
	 * Registers the application with GCM servers asynchronously.
	 * <p>
	 * Stores the registration ID and app versionCode in the application's
	 * shared preferences.
	 */

	private void registerInBackground() {
	    new AsyncTask<Void,Void,String>() {
	    	@Override
	        protected String doInBackground(Void... params) {
	            String msg = "";
	            try {
	                if (gcm == null) {
	                    gcm = GoogleCloudMessaging.getInstance(context);
	                }
	                regid = gcm.register(SENDER_ID);
	                msg = "Device registered, registration ID=" + regid;

	                // You should send the registration ID to your server over HTTP,
	                // so it can use GCM/HTTP or CCS to send messages to your app.
	                // The request to your server should be authenticated if your app
	                // is using accounts.
	                sendRegistrationIdToBackend();

	                // For this demo: we don't need to send it because the device
	                // will send upstream messages to a server that echo back the
	                // message using the 'from' address in the message.

	                // Persist the regID - no need to register again.
	                storeRegistrationId(context, regid);
	            } catch (IOException ex) {
	                msg = "Error :" + ex.getMessage();
	                // If there is an error, don't just keep trying to register.
	                // Require the user to click a button again, or perform
	                // exponential back-off.
	            }
	            return msg;
	        }

	        protected void onPostExecute(String msg) {
	            Log.i(TAG, msg);
	        }

			
	    }.execute(null, null, null);
	}
	
	/**
	 * Sends the registration ID to your server over HTTP, so it can use GCM/HTTP
	 * or CCS to send messages to your app. Not needed for this demo since the
	 * device sends upstream messages to a server that echoes back the message
	 * using the 'from' address in the message.
	 */
	private void sendRegistrationIdToBackend() {
		HttpClient httpclient = new DefaultHttpClient();
	    HttpPost httppost = new HttpPost(DRONECENTRAL_URL);

	    try {
	        // Add your data
	        List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>(2);
	        nameValuePairs.add(new BasicNameValuePair("device_id", "12345"));
	        httppost.setEntity(new UrlEncodedFormEntity(nameValuePairs));

	        // Execute HTTP Post Request
	        HttpResponse response = httpclient.execute(httppost);
	        
	    } catch (ClientProtocolException e) {
	    	Log.e(TAG, "Error when registring to dronecentral: " + e.getMessage());
	    } catch (IOException e) {
	    	Log.e(TAG, "Error when registring to dronecentral: " + e.getMessage());
	    }
	    catch (Exception e) {
	    	Log.e(TAG, "Error when registering to dronecentral");
	    }
	}
	
	/**
	 * Stores the registration ID and app versionCode in the application's
	 * {@code SharedPreferences}.
	 *
	 * @param context application's context.
	 * @param regId registration ID
	 */
	private void storeRegistrationId(Context context, String regId) {
	    final SharedPreferences prefs = getGCMPreferences(context);
	    int appVersion = getAppVersion(context);
	    Log.i(TAG, "Saving regId on app version " + appVersion);
	    SharedPreferences.Editor editor = prefs.edit();
	    editor.putString(PROPERTY_REG_ID, regId);
	    editor.putInt(PROPERTY_APP_VERSION, appVersion);
	    editor.commit();
	}
	
	public void init() {
		this.prj = new Project(Navigator.projectName);
		if (this.prj.listProjects().length == 0)
			this.newProjectButtonClickHandler(null);
	}

	public void onStop(){
		super.onStop();		
	}
	
	public void onStart(){
		super.onStart();
	}
	
	public void onResume() {
		super.onResume();
		orientationReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				SonardroneActivity.this.setCompass(
		    			intent.getDoubleExtra("heading", 0.0),
		    			intent.getLongExtra("timestamp", 0));
			}
		};

		locationReceiver = 	new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				SonardroneActivity.this.setGPS(
						intent.getDoubleArrayExtra("pos"),
						intent.getLongExtra("timestamp", 0),	
						intent.getFloatExtra("accuracy", 0));
			}
		};
			
		serviceStatusReceiver = new BroadcastReceiver() {
		    @Override
		    public void onReceive(Context context, Intent intent) {
		    	String serviceName = intent.getStringExtra("SERVICE");
		    	boolean running = intent.getBooleanExtra("STATE", false);
		    	
		    	CompoundButton toggleButton = null;
		    	if (serviceName == "NavigatorService")
	    			toggleButton = (CompoundButton) findViewById(R.id.navToggleButton);
		    	
		    	if (running && !toggleButton.isChecked() ) {	    	
		    		toggleButton.setChecked(true);
		    	}
		    	else {
		    		if (!running && toggleButton.isChecked()) {
		    			toggleButton.setChecked(true);
		    		}
		    	}
		    }
		};
		    
		LocalBroadcastManager.getInstance(this.context).registerReceiver(locationReceiver,
				new IntentFilter("LOCATION_UPDATED"));
		LocalBroadcastManager.getInstance(this.context).registerReceiver(orientationReceiver,
				new IntentFilter("ORIENTATION_UPDATED"));
		LocalBroadcastManager.getInstance(this.context).registerReceiver(serviceStatusReceiver,
				new IntentFilter("SERVICE_STATE"));

		this.updateServiceSwitches();
	}
	
	public void onPause() {
		super.onPause();
		if (this.serviceStatusReceiver != null)
				LocalBroadcastManager.getInstance(this.context).unregisterReceiver(serviceStatusReceiver);
		if (this.locationReceiver!= null)
			LocalBroadcastManager.getInstance(this.context).unregisterReceiver(locationReceiver);
   		if (this.orientationReceiver!=null)
		LocalBroadcastManager.getInstance(this.context).unregisterReceiver(orientationReceiver);
	}
	
	public void updateServiceSwitches() {
		CompoundButton toggleButton = (CompoundButton) findViewById(R.id.navToggleButton);
		toggleButton = (CompoundButton) findViewById(R.id.navToggleButton);
		if (!toggleButton.isChecked() && this.navigatorServiceIsRunning())
			toggleButton.setChecked(true);
		else {
			if (toggleButton.isChecked() && !this.navigatorServiceIsRunning()) 
				toggleButton.setChecked(false);
		}
	}
		
	private boolean navigatorServiceIsRunning() {
	    ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
	    for (RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
	        if (NavigatorService.class.getName().equals(service.service.getClassName())) {
	            return true;
	        }
	    }
	    return false;
	}

	public void updateSpinner() {
		// Update spinner with project names
		String[] projects = this.prj.listProjects();
		Spinner projectSpinner = (Spinner) findViewById(R.id.projectSpinner);
		ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
				android.R.layout.simple_spinner_item, projects);
		projectSpinner.setAdapter(adapter);
	}

	public void deleteProjectButtonClickHandler(View view) {
		DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				switch (which) {
				case DialogInterface.BUTTON_POSITIVE:
					// Yes button clicked
					Spinner projectSpinner = (Spinner) findViewById(R.id.projectSpinner);
					String projectName = (String) projectSpinner
							.getSelectedItem();
					if (projectName.equals("default")) {
						Toast.makeText(getBaseContext(),
								"Not allowed to delete default project",
								Toast.LENGTH_LONG).show();
						dialog.cancel();
						break;
					}
					if (projectName.equals(SonardroneActivity.this.prj.getProjectName())) {
						Toast.makeText(getBaseContext(),
								"Not allowed to delete active project",
								Toast.LENGTH_LONG).show();
						dialog.cancel();
						break;
					}
					SonardroneActivity.this.prj.deleteProject(projectName);
					SonardroneActivity.this.updateSpinner();
					SonardroneActivity.this.loadProject();
					dialog.cancel();
					break;
				case DialogInterface.BUTTON_NEGATIVE:
					// No button clicked
					dialog.cancel();
					break;
				}
			}
		};

		String msg = String.format("Delete project %s?",
				this.prj.getProjectName());
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setMessage(msg).setPositiveButton("Yes", dialogClickListener)
				.setNegativeButton("No", dialogClickListener).show();
		builder.create();
	}

	private void loadProject() {
		// Load configuration from project selected in spinner
		Spinner projectSpinner = (Spinner) findViewById(R.id.projectSpinner);
		Navigator.projectName = (String) projectSpinner.getSelectedItem();
		this.prj = new Project(Navigator.projectName);
		this.setUIConfig();
	}

	public void newProjectButtonClickHandler(View view) {
		// Create new project dir with a copy of .settings.txt
		Editable projectName = ((EditText) findViewById(R.id.newProjectEditText))
				.getEditableText();
		Navigator.projectName = projectName.toString();
	
		this.updateSpinner();
		Spinner projectSpinner = (Spinner) findViewById(R.id.projectSpinner);
		@SuppressWarnings("unchecked")
		ArrayAdapter<String> adapter = (ArrayAdapter<String>) projectSpinner
				.getAdapter();
		projectSpinner
				.setSelection(adapter.getPosition(projectName.toString()));
		projectName.clear();
		this.loadProject();
	}

	public void loadProjectButtonClickHandler(View view) {
		// Click handler for load project button
		this.loadProject();
	}
	

	public void checkBoxClickHandler(View view) {
		CheckBox cb = (CheckBox) view;
		switch (view.getId()) {
		case R.id.debugCheckBox:
			if (cb.isChecked())
				this.prj.setBoolean("debugSwitch", true);
			else
				this.prj.setBoolean("debugSwitch", false);
			break;
		case R.id.appendCheckBox:
			if (cb.isChecked())
				this.prj.setBoolean("appendLogs", true);
			else
				this.prj.setBoolean("appendLogs", false);
			break;
		case R.id.simulateGPSCheckBox:
			if (cb.isChecked())
				this.prj.setBoolean("simulateGPSSwitch", true);
			else
				this.prj.setBoolean("simulateGPSSwitch", false);
			break;
		}
	}

	public void setUIConfig() {
		// set checkBoxes
		((CheckBox) findViewById(R.id.debugCheckBox)).setChecked(
				this.prj.getParameterAsBoolean("debugSwitch"));
		
		((CheckBox) findViewById(R.id.kalmanCheckBox)).setChecked(
				this.prj.getParameterAsBoolean("filterSwitch"));
		
		((CheckBox) findViewById(R.id.compassMeasurementCheckBox)).setChecked(
				this.prj.getParameterAsBoolean("compassSwitch"));
		
		((CheckBox) findViewById(R.id.gpsVelMeasurementCheckBox)).setChecked(
				this.prj.getParameterAsBoolean("gpsVelSwitch"));
		
		((CheckBox) findViewById(R.id.gpsPosMeasurementCheckBox)).setChecked(
				this.prj.getParameterAsBoolean("gpsPositionSwitch"));
		
		((CheckBox) findViewById(R.id.gpsBearingMeasurementCheckBox)).setChecked(
				this.prj.getParameterAsBoolean("gpsBearingSwitch"));
		
		((CheckBox) findViewById(R.id.encoderVelMeasurementCheckBox)).setChecked(
				this.prj.getParameterAsBoolean("encoderVelSwitch"));
		
		((CheckBox) findViewById(R.id.appendCheckBox)).setChecked(
				this.prj.getParameterAsBoolean("appendLogs"));
		
		((CheckBox) findViewById(R.id.encoderTurnrateMeasurementCheckBox)).setChecked(
				this.prj.getParameterAsBoolean("encoderTurnrateSwitch"));
		
		((CheckBox) findViewById(R.id.simulateGPSCheckBox)).setChecked(
				this.prj.getParameterAsBoolean("simulateGPSSwitch"));
	
		// set floats
		((EditText) findViewById(R.id.waypointToleranceEditText)).setText(
					this.prj.getParameterAsString("tolerance"));
		
		((EditText) findViewById(R.id.dtEditText)).setText(
				this.prj.getParameterAsString("dt_default"));
		
		((EditText) findViewById(
				R.id.waypointToleranceEditText)).setText(
						this.prj.getParameterAsString("tolerance"));
		
		((EditText) findViewById(
				R.id.dtEditText)).setText(
						this.prj.getParameterAsString("dt_default"));
		
		// set integers

	}

	public void getUIConfig() {
		// get checkBoxes
		this.prj.setBoolean("debugSwitch",
				((CheckBox) findViewById(R.id.debugCheckBox)).isChecked());
		
		this.prj.setBoolean("filterSwitch",
				((CheckBox) findViewById(
						R.id.kalmanCheckBox)).isChecked());
		this.prj.setBoolean("compassSwitch",
				((CheckBox) findViewById(
						R.id.compassMeasurementCheckBox)).isChecked());
		this.prj.setBoolean("gpsVelSwitch",
				((CheckBox) findViewById(
						R.id.gpsVelMeasurementCheckBox)).isChecked());
		this.prj.setBoolean("gpsBearingSwitch",
				((CheckBox) findViewById(
						R.id.gpsBearingMeasurementCheckBox)).isChecked());
		this.prj.setBoolean("gpsPositionSwitch",
				((CheckBox) findViewById(
						R.id.gpsPosMeasurementCheckBox)).isChecked());
		this.prj.setBoolean("encoderVelSwitch",
				((CheckBox) findViewById(
						R.id.encoderVelMeasurementCheckBox)).isChecked());
		this.prj.setBoolean("encoderTurnrateSwitch",
				((CheckBox) findViewById(
						R.id.encoderTurnrateMeasurementCheckBox)).isChecked());
		
		// get doubles
		this.prj.setDouble("tolerance", Double.parseDouble( String.valueOf(
				((EditText) findViewById(
						R.id.waypointToleranceEditText)).getEditableText())));
		this.prj.setDouble("dt_default", Double.parseDouble(String.valueOf(
				((EditText) findViewById(
						R.id.dtEditText)).getEditableText())));


		// get integers
	}

	public void operate(View view) {
		CompoundButton toggleButton = (CompoundButton) view;
		if(toggleButton.isChecked())
			this.broadcastNavCommand("OPERATE");
		else
			this.broadcastNavCommand("SHUTDOWN");
	}
	
	public void activate(View view) {
		CompoundButton toggleButton = (CompoundButton) view;
		if(toggleButton.isChecked())
			this.broadcastNavCommand("ACTIVATE");
		else
			this.broadcastNavCommand("DEACTIVATE");		
	}
	
	
	
	public void runButtonClickHandler(View view) {
		CompoundButton toggleButton = null;
		Intent intent = null;
		
		switch (view.getId()) {
		case R.id.navToggleButton:
			toggleButton = (CompoundButton) findViewById(R.id.navToggleButton);
			intent = new Intent(SonardroneActivity.this, NavigatorService.class);
			break;
		}
		
		if (toggleButton.isChecked()) {
			this.getUIConfig();
			this.prj.write();
			getLock(this).acquire();
			startService(intent);
		} else {
			if (lockStatic.isHeld()) {
				lockStatic.release();
			}
			stopService(intent);
		}

	}
	
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

	public void broadcastNavCommandInt(String command, int value) {
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
	public void testRudder(View view) {
		// Check if nav service is running, if not - toggle service button
		CompoundButton toggleButton = (CompoundButton) findViewById(R.id.navToggleButton);
		if (!toggleButton.isChecked()) {
			toggleButton.setChecked(true);
			runButtonClickHandler(findViewById(R.id.navToggleButton));
		}
		int rudderAngle = Integer
				.valueOf(((EditText) findViewById(R.id.rudderAngleEditText))
						.getEditableText().toString());

		this.broadcastNavCommandInt("SET_RUDDER", rudderAngle);
	}
	
	public void testMotor(View view) {
		CompoundButton toggleButton = (CompoundButton) findViewById(R.id.navToggleButton);
		if (!toggleButton.isChecked()) {
			toggleButton.setChecked(true);
			runButtonClickHandler(findViewById(R.id.navToggleButton));
		}
		int motorLoad = Integer
				.valueOf(((EditText) findViewById(R.id.motorLoadEditText))
						.getEditableText().toString());
		this.broadcastNavCommandInt("SET_LOAD", motorLoad);
	}

	public void setGPS(double[] pos, long timestamp, float accuracy) {
		TextView textview = (TextView) findViewById(R.id.XYAccuracyTextView);

		String gpsStatus;
		gpsStatus = String.format("X:%d,Y:%d,a:%s,t:%s",
				(int) pos[0],
				(int) pos[1],
				String.valueOf(accuracy),
				String.valueOf(timestamp));
		textview.setText((CharSequence) gpsStatus);
	}
	
	public void setCompass(double heading, long timestamp) {
		TextView textview = (TextView) findViewById(R.id.HeadingTextView);
		textview.setText((CharSequence) String.valueOf(heading));
	}

	public void onDestroy() {
		super.onDestroy();
			

		//bind to running services
		CompoundButton toggleButton = null;
		Intent intent = null;
			
		toggleButton = (CompoundButton) findViewById(R.id.navToggleButton);
		intent = new Intent(SonardroneActivity.this, NavigatorService.class);
		if(toggleButton.isChecked())
			stopService(intent);
					
	}

}