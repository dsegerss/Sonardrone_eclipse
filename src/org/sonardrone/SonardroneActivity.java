package org.sonardrone;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.sonardrone.gps.GpsLoggerService;
import org.sonardrone.ioio.IOIOControlService;
import org.sonardrone.navigator.NavigatorService;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.gcm.GoogleCloudMessaging;

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
import android.os.Environment;
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

public class SonardroneActivity extends Activity {
	//GCM init
    public static final String EXTRA_MESSAGE = "message";
    public static final String PROPERTY_REG_ID = "registration_id";
    public static final String PROPERTY_APP_VERSION = "appVersion";
    public static final int PLAY_SERVICES_RESOLUTION_REQUEST = 9000;
    public static final String SENDER_ID = "539926077370"; //Project ID from API console
    public static final String DRONECENTRAL_URL = "drone_central_url";
    
    GoogleCloudMessaging gcm;
    AtomicInteger msgId = new AtomicInteger();
    SharedPreferences prefs;
    Context context;
    String regid;
    
	private static final String TAG = "SonardroneActivity";
	// Map with settings
	private Map<String, String> settings = new HashMap<String, String>();
	// Map with row order for settings incl. comment rows
	private Map<String, String> rfRowNr = null;
	File sdcard = Environment.getExternalStorageDirectory();
	private File rootDir = new File(sdcard, "/sonardrone");
	private File projectDir = null;
	private File settingsTemplate = new File(this.rootDir, ".settings.txt");
	static final int DELETE_PROJECT_ID = 1;

	private BroadcastReceiver locationReceiver = new BroadcastReceiver() {
	    @Override
	    public void onReceive(Context context, Intent intent) {
	    	SonardroneActivity.this.setGPS(
	    			intent.getDoubleArrayExtra("pos"),
	        		intent.getLongExtra("timestamp", 0),	
	        		intent.getFloatExtra("accuracy", 0));
	    }
	};
	
	private BroadcastReceiver serviceStatusReceiver = new BroadcastReceiver() {
	    @Override
	    public void onReceive(Context context, Intent intent) {
	    	String serviceName = intent.getStringExtra("SERVICE");
	    	boolean running = intent.getBooleanExtra("STATE", false);
	    	
	    	
	    	CompoundButton toggleButton = null;
	    	if (serviceName == "GpsLoggerService")
	    		toggleButton = (CompoundButton) findViewById(R.id.gpsToggleButton);
	    	if (serviceName == "IOIOControlService")
	    			toggleButton = (CompoundButton) findViewById(R.id.ioioToggleButton);
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
		LocalBroadcastManager.getInstance(this.context).registerReceiver(locationReceiver,
				new IntentFilter("LOCATION_UPDATED"));
		LocalBroadcastManager.getInstance(this.context).registerReceiver(serviceStatusReceiver,
				new IntentFilter("SERVICE_STATE"));
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
		if (!this.rootDir.exists())
			this.rootDir.mkdir();
		if (!this.settingsTemplate.exists())
			this.writeSettingsTemplate();
		if (this.listProjects().length == 0)
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
		this.updateServiceSwitches();
	}
	
	public void updateServiceSwitches() {
		CompoundButton toggleButton = (CompoundButton) findViewById(R.id.gpsToggleButton);
		if (!toggleButton.isChecked() && this.gpsLoggerServiceIsRunning())
			toggleButton.setChecked(true);
		else {
			if (toggleButton.isChecked() && !this.gpsLoggerServiceIsRunning()) 
				toggleButton.setChecked(false);
		}
		
		
		toggleButton = (CompoundButton) findViewById(R.id.ioioToggleButton);
		if (!toggleButton.isChecked() && this.ioioControlServiceIsRunning())
			toggleButton.setChecked(true);
		else {
			if (toggleButton.isChecked() && !this.ioioControlServiceIsRunning()) 
				toggleButton.setChecked(false);
		}
		
		toggleButton = (CompoundButton) findViewById(R.id.navToggleButton);
		if (!toggleButton.isChecked() && this.navigatorServiceIsRunning())
			toggleButton.setChecked(true);
		else {
			if (toggleButton.isChecked() && !this.navigatorServiceIsRunning()) 
				toggleButton.setChecked(false);
		}
	}
	
	private boolean gpsLoggerServiceIsRunning() {
	    ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
	    for (RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
	        if (GpsLoggerService.class.getName().equals(service.service.getClassName())) {
	            return true;
	        }
	    }
	    return false;
	}
	
	private boolean ioioControlServiceIsRunning() {
	    ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
	    for (RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
	        if (IOIOControlService.class.getName().equals(service.service.getClassName())) {
	            return true;
	        }
	    }
	    return false;
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
	
	private boolean isExternalStoragePresent() {
		boolean mExternalStorageAvailable = false;
		boolean mExternalStorageWriteable = false;
		String state = Environment.getExternalStorageState();

		if (Environment.MEDIA_MOUNTED.equals(state)) {
			// We can read and write the media
			mExternalStorageAvailable = mExternalStorageWriteable = true;
		} else if (Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
			// We can only read the media
			mExternalStorageAvailable = true;
			mExternalStorageWriteable = false;
		} else {
			// Something else is wrong. It may be one of many other states, but
			// all we need
			// to know is we can neither read nor write
			mExternalStorageAvailable = mExternalStorageWriteable = false;
		}
		if (!((mExternalStorageAvailable) && (mExternalStorageWriteable))) {
			Log.d(TAG, String.format(
					"SD card available: %s, writable: %s",
					String.valueOf(mExternalStorageAvailable),
					String.valueOf(mExternalStorageWriteable)));
			Toast.makeText(getBaseContext(), "SD card not present",
					Toast.LENGTH_LONG).show();
			return false;
		}
		return true;
	}

	public void writeSettingsTemplate() {
		BufferedWriter writer = null;
		if (!this.isExternalStoragePresent())
			System.exit(1);

		Log.d(TAG,"Creating default settings");
		try {
			this.settingsTemplate.createNewFile();
			writer = new BufferedWriter(new FileWriter(this.settingsTemplate));
			writer.write("#Sonar drone, resource file\n"
					+ "####Initialization of model#####\n"
					+ "#k=p/v³ , estimated from p = 85%,V = 4m/s\n"
					+ "k: 3.14\n"
					+ "#motor load, in percentage\n"
					+ "load: 85\n"
					+ "#angle of rudder in degrees\n"
					+ "rudder_angle: 0\n"
					+ "#Default time-step\n"
					+ "dt_default: 0.5\n"
					+ "#\n"
					+ "#Activate Kalman filtering\n"
					+ "filterSwitch: true\n"
					+ "compassSwitch: true\n"
					+ "gpsPositionSwitch: true\n"
					+ "gpsVelSwitch: true\n"
					+ "gpsBearingSwitch: true\n"
					+ "encoderVelSwitch: true\n"
					+ "encoderTurnrateSwitch: true\n"
					+ "updateKSwitch: true\n"
					+ "navServiceSwitch: true\n"
					+ "ioioServiceSwitch: true\n"
					+ "simulateGPSSwitch: true\n"
					+ "appendLogs: true\n"
					+ "gpsServiceSwitch: true\n"
					+ "debugSwitch: false\n"
					+ "autoPilot: false\n"
					+ "#####Pure-pursuit parameters#####\n"
					+ "#Look-ahead distance\n"
					+ "look_ahead: 5\n"
					+ "#minimum look-ahead distance, when distance adapted not to overshoot waypoint\n"
					+ "min_look_ahead: 3\n"
					+ "#Tolerance within which waypoint is considered reached\n"
					+ "tolerance: 10\n"
					+ "wpIndex: 0\n"
					+ "#max allowed rudder angle\n"
					+ "max_rudder_angle: 75\n"
					+ "#Minimum turn radius, used to estimate minimum turn-rate\n"
					+ "min_turn_radius: 10\n"
					+ "#\n"
					+ "#######Model uncertainty estimations########\n"
					+ "#max acceleration in body-frame x-direction [m/s²]\n"
					+ "ax_max: 0.05\n"
					+ "#max acceleration in body-frame y-direction [m/s²]\n"
					+ "ay_max: 0.2\n"
					+ "#max turn-rate change in one filter cycle [deg]\n"
					+ "max_dir_change: 2\n"
					+ "#time-scale for zero to max turn-rate [s]\n"
					+ "tau: 2\n"
					+ "####Measurements uncertainty estimations#####\n"
					+ "#GPS standard deviation for GPS-position\n"
					+ "sigmaX_GPS: 0.75\n"
					+ "#GPS speed standard deviation\n"
					+ "sigmaV_GPS: 0.3\n"
					+ "#std dev for phi estimated using GPS [deg]\n"
					+ "sigmaPhi_GPS: 10\n"
					+ "#std dev for phi using compass [deg]\n"
					+ "sigmaPhi_compass: 30\n"
					+ "#std dev for turn rate estimated from rudder angle and speed\n"
					+ "sigmaBeta_rudder: 0.001\n"
					+ "#Load std dev in %\n"
					+ "sigmaV_load: 0.5\n"
					+ "####Measurement controls####\n"
					+ "#Traveled distance required to estimate velocity from GPS-positions\n"
					+ "minVelDist: 10\n"
					+ "#Traveled distance with turnrate<bearingTurnrateThreshold\n"
					+ "#required to estimate bearing from GPS-positions\n"
					+ "minBearingDist: 5\n"
					+ "#Threshold for turn-rate to estimate bearing from GPS [deg/s]\n"
					+ "bearingTurnrateThreshold: 2\n"
					+ "#Threshold for turn-rate to estimate bearing from compass [deg/s]\n"
					+ "compassTurnrateThreshold: 2\n");
		} catch (FileNotFoundException e) {
			Log.e(TAG, "Error: " + e.getMessage());
			System.exit(1);
		} catch (IOException e) {
			Log.e(TAG, "Error: " + e.getMessage());
			System.exit(1);
		} finally {
			try {
				if (writer != null) {
					writer.close();
				}
			} catch (IOException e) {
				Log.e(TAG, "Error: " + e.getMessage());
				System.exit(1);
			}
		}
	}

	public String[] listProjects() {
		// List project directories
		ArrayList<String> projectDirs = new ArrayList<String>();
		File[] files = this.rootDir.listFiles();
		for (int i = 0; i < files.length; i++)
			if (files[i].isDirectory())
				projectDirs.add(files[i].getName());
		String[] projectNames = new String[projectDirs.size()];
		if (!projectDirs.isEmpty())
			projectDirs.toArray(projectNames);
		return projectNames;
	}

	public void updateSpinner() {
		// Update spinner with project names
		String[] projects = this.listProjects();
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

					File project = new File(
							Environment.getExternalStorageDirectory(),
							"sonardrone/" + projectName);
					String[] children = project.list();
					for (int i = 0; i < children.length; i++) {
						new File(project, children[i]).delete();
					}
					project.delete();
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
				this.projectDir.toString());
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setMessage(msg).setPositiveButton("Yes", dialogClickListener)
				.setNegativeButton("No", dialogClickListener).show();
		builder.create();
	}

	private void loadProject() {
		// Load configuration from project selected in spinner
		Spinner projectSpinner = (Spinner) findViewById(R.id.projectSpinner);
		this.projectDir = new File(this.rootDir,
				(String) projectSpinner.getSelectedItem());
		this.readConfig();
		this.setUIConfig();
		File projectFile = new File(this.rootDir, ".project");
		if (projectFile.exists())
			projectFile.delete();
		try {
			projectFile.createNewFile();
		} catch (IOException e) {
			Log.e(TAG,"Error copying file");
		}
	}

	public void newProjectButtonClickHandler(View view) {
		// Create new project dir with a copy of .settings.txt
		Editable projectName = ((EditText) findViewById(R.id.newProjectEditText))
				.getEditableText();
		File projectDirName = new File(this.rootDir, projectName.toString());
		File projectSettings = new File(projectDirName, "settings.txt");
		if (!projectDirName.exists()) {
			projectDirName.mkdir();
			try {
				FileInputStream in = new FileInputStream(this.settingsTemplate);
				FileOutputStream out = new FileOutputStream(projectSettings);
				byte[] buf = new byte[1024];
				int i = 0;
				while ((i = in.read(buf)) != -1) {
					out.write(buf, 0, i);
				}
				in.close();
				out.close();
			} catch (IOException e) {
				Log.e(TAG,"Error copying file");
			}
		}
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

	public void readConfig() {
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
			Log.e(TAG, "Error: " + e.getMessage());
			System.exit(1);
		} catch (IOException e) {
			Log.e(TAG, "Error: " + e.getMessage());
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

	public void writeConfig() {
		// resource file object
		File sdcard = Environment.getExternalStorageDirectory();
		File rf = new File(sdcard, "/sonardrone/settings.txt");
		BufferedWriter writer = null;
		Log.d(TAG,"Reading resources");
		try {
			writer = new BufferedWriter(new FileWriter(rf));
			for (int i = 0; i < this.rfRowNr.size(); i++) {
				String row = this.rfRowNr.get(String.valueOf(i));
				if (row.trim().startsWith("#") || row.trim() == "") {
					writer.write(row);
				} else {
					String val = this.settings.get(row);
					writer.write(String.format("%s: %s\n", row, val));
				}
			}
		} catch (FileNotFoundException e) {
			Log.e(TAG, "Error: " + e.getMessage());
			System.exit(1);
		} catch (IOException e) {
			Log.e(TAG, "Error: " + e.getMessage());
			System.exit(1);
		} finally {
			try {
				if (writer != null) {
					writer.close();
				}
			} catch (IOException e) {
				Log.e(TAG, "Error: " + e.getMessage());
				System.exit(1);
			}
		}
	}

	public void checkBoxClickHandler(View view) {
		CheckBox cb = (CheckBox) view;
		switch (view.getId()) {
		case R.id.debugCheckBox:
			if (cb.isChecked())
				this.settings.put("debugSwitch", "true");
			else
				this.settings.put("debugSwitch", "false");
			break;
		case R.id.gpsServiceCheckBox:
			if (cb.isChecked())
				this.settings.put("gpsServiceSwitch", "true");
			else
				this.settings.put("gpsServiceSwitch", "false");
			break;
		case R.id.ioioServiceCheckBox:
			if (cb.isChecked())
				this.settings.put("ioioServiceSwitch", "true");
			else
				this.settings.put("ioioServiceSwitch", "false");
			break;
		case R.id.appendCheckBox:
			if (cb.isChecked())
				this.settings.put("appendLogs", "true");
			else
				this.settings.put("appendLogs", "false");
			break;
		case R.id.simulateGPSCheckBox:
			if (cb.isChecked())
				this.settings.put("simulateGPSSwitch", "true");
			else
				this.settings.put("simulateGPSSwitch", "false");
			break;
		}
	}

	public void setUIConfig() {
		// set checkBoxes
		((CheckBox) findViewById(R.id.debugCheckBox)).setChecked(Boolean
				.valueOf(this.settings.get("debugSwitch")));
		((CheckBox) findViewById(R.id.gpsServiceCheckBox)).setChecked(Boolean
				.valueOf(this.settings.get("gpsServiceSwitch")));
		((CheckBox) findViewById(R.id.ioioServiceCheckBox)).setChecked(Boolean
				.valueOf(this.settings.get("ioioServiceSwitch")));
		((CheckBox) findViewById(R.id.appendCheckBox)).setChecked(Boolean
				.valueOf(this.settings.get("appendLogs")));
		((CheckBox) findViewById(R.id.simulateGPSCheckBox)).setChecked(Boolean
				.valueOf(this.settings.get("simulateGPSSwitch")));
		// set floats

		// set integers

	}

	public void getUIConfig() {
		// get checkBoxes
		this.settings.put("debugSwitch", String
				.valueOf(((CheckBox) findViewById(
						R.id.debugCheckBox)).isChecked()));
		this.settings.put("gpsServiceSwitch", String.valueOf(
				((CheckBox) findViewById(
						R.id.gpsServiceCheckBox)).isChecked()));
		this.settings.put("ioioServiceSwitch", String.valueOf(
				((CheckBox) findViewById(
						R.id.ioioServiceCheckBox)).isChecked()));
		this.settings.put("filterSwitch", String.valueOf(
				((CheckBox) findViewById(
						R.id.kalmanCheckBox)).isChecked()));
		this.settings.put("compassSwitch",String.valueOf(
				((CheckBox) findViewById(
						R.id.compassMeasurementCheckBox)).isChecked()));
		this.settings.put("gpsVelSwitch",String.valueOf(
				((CheckBox) findViewById(
						R.id.gpsVelMeasurementCheckBox)).isChecked()));
		this.settings.put("gpsBearingSwitch",String.valueOf(
				((CheckBox) findViewById(
						R.id.gpsBearingMeasurementCheckBox)).isChecked()));
		this.settings.put("gpsPositionSwitch",String.valueOf(
				((CheckBox) findViewById(
						R.id.gpsPosMeasurementCheckBox)).isChecked()));
		this.settings.put("encoderVelSwitch",String.valueOf(
				((CheckBox) findViewById(
						R.id.encoderVelMeasurementCheckBox)).isChecked()));
		this.settings.put("encoderTurnrateSwitch",String.valueOf(
				((CheckBox) findViewById(
						R.id.encoderTurnrateMeasurementCheckBox)).isChecked()));
		this.settings.put("tolerance",String.valueOf(
				((EditText) findViewById(
						R.id.waypointToleranceEditText)).getEditableText()));
		this.settings.put("dt_default", String.valueOf(
				((EditText) findViewById(
						R.id.dtEditText)).getEditableText()));

		// get floats

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
		case R.id.gpsToggleButton:
			toggleButton = (CompoundButton) findViewById(R.id.gpsToggleButton);
			intent = new Intent(SonardroneActivity.this, GpsLoggerService.class);
			break;
		case R.id.ioioToggleButton:
			toggleButton = (CompoundButton) findViewById(R.id.ioioToggleButton);
			intent = new Intent(SonardroneActivity.this,
					IOIOControlService.class);
			break;
		}

		intent.putExtra("projectDir", this.projectDir.toString());
		
		// PendingIntent pendingIntent = PendingIntent.getService(
		// SonardroneActivity.this, 0, intent, 0);

		if (toggleButton.isChecked()) {
			this.getUIConfig();
			this.writeConfig();
			startService(intent);
		} else {
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
		// Check if ioio service is running, if not - toggle service button
		CompoundButton toggleButton = (CompoundButton) findViewById(R.id.ioioToggleButton);
		if (!toggleButton.isChecked()) {
			toggleButton.setChecked(true);
			runButtonClickHandler(findViewById(R.id.ioioToggleButton));
		}
		Integer rudderAngle = Integer
				.valueOf(((EditText) findViewById(R.id.rudderAngleEditText))
						.getEditableText().toString());

		this.broadcastNavCommandDouble("SET_RUDDER", rudderAngle);
	}
	
	public void testMotor(View view) {
		// Check if ioio service is running, if not - toggle service button
		CompoundButton toggleButton = (CompoundButton) findViewById(R.id.ioioToggleButton);
		if (!toggleButton.isChecked()) {
			toggleButton.setChecked(true);
			runButtonClickHandler(findViewById(R.id.ioioToggleButton));
		}
		Integer motorLoad = Integer
				.valueOf(((EditText) findViewById(R.id.motorLoadEditText))
						.getEditableText().toString());
		this.broadcastNavCommandDouble("SET_LOAD", motorLoad);
	}

	public void setGPS(double[] pos, long timestamp, float accuracy) {
		Log.d(TAG,"setGPS");
		TextView textview = (TextView) findViewById(R.id.XYAccuracyTextView);

		String gpsStatus;
		gpsStatus = String.format("X:%d,Y:%d,a:%s,t:%s",
				(int) pos[0],
				(int) pos[1],
				String.valueOf(accuracy),
				String.valueOf(timestamp));
		textview.setText((CharSequence) gpsStatus);
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
					
		toggleButton = (CompoundButton) findViewById(R.id.gpsToggleButton);
		intent = new Intent(SonardroneActivity.this, GpsLoggerService.class);
		if(toggleButton.isChecked())
			stopService(intent);
		
		toggleButton = (CompoundButton) findViewById(R.id.ioioToggleButton);
		intent = new Intent(SonardroneActivity.this,IOIOControlService.class);
		if(toggleButton.isChecked())
			stopService(intent);

	}

}