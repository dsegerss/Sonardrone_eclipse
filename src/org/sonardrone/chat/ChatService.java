package org.sonardrone.chat;

import org.jivesoftware.smack.Chat;
import org.jivesoftware.smack.ChatManager;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.SASLAuthentication;
import org.jivesoftware.smack.SmackAndroid;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.sonardrone.R;
import org.sonardrone.gps.GpsLoggerService;
import org.sonardrone.navigator.NavigatorService;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;
import android.widget.Toast;

/**
 * Service that handles communication with Sonardrone over XMPP and Google talk
 */
public class ChatService extends Service {
	// chat parameters
	private static final String LOG_TAG = "Dronechat";
	private static final String USERNAME = "sonardrone@gmail.com";
	private static final String PASSWORD = "S0narDr0n3";
	private static final String RESOURCE = "Some_resource";
	private static final String BUDDY_ADDRESS = "david.segersson@gmail.com";
	private static final String HOST = "talk.google.com";
	private static final int PORT = 5222;
	private static final String SERVICE = "gmail.com";
	private XMPPConnection conn1;
	private ChatManager chatmanager;
	private Chat chat;

	private NotificationManager mNM;

	private boolean navigatorIsBound = false;
	public NavigatorService boundNavigatorService;

	@Override
	public void onCreate() {
		super.onCreate();
		mNM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		// Display a notification about us starting. We put an icon in the
		// status bar.
		showNotification();
		this.connectToServer();
		this.login();
		this.createChat();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();

		// Disconnect nav service
		unbindNavigatorService();

		// Cancel the persistent notification.
		mNM.cancel(0);

		// Tell the user we stopped.
		Toast.makeText(this, "GPS-Logger stopped!", Toast.LENGTH_SHORT).show();
	}

	private void unbindNavigatorService() {
		if (navigatorIsBound) {
			// Detach our existing connection.
			unbindService(navigatorServiceConnection);
			navigatorIsBound = false;
		}
	}

	public void connectToServer() {
		// connecting to the server
		SmackAndroid.init(this);
		ConnectionConfiguration config = new ConnectionConfiguration(HOST,
				PORT, SERVICE);
		config.setTruststoreType("BKS");
		SASLAuthentication.supportSASLMechanism("PLAIN");
		config.setDebuggerEnabled(true);
		conn1 = new XMPPConnection(config);
		// connecting to the server
		String msg;
		try {
			conn1.connect();
			Log.v(LOG_TAG, "XMPP connected");
		} catch (XMPPException xe) {
			msg = "XMPPException during connect(): " + xe.getMessage();
			Log.v(LOG_TAG, msg);
		}
	}

	private void login() {
		// login
		String msg;
		try {
			this.conn1.login(USERNAME, PASSWORD, RESOURCE);
			msg = "login ok";
			Log.v(LOG_TAG, msg);
		} catch (XMPPException xe) {
			msg = "XMPPException login(): " + xe.getMessage();
			Log.v(LOG_TAG, msg);

			StackTraceElement[] st = xe.getStackTrace();
			for (int i = 0; i < st.length; i++) {
				msg = xe.getStackTrace()[i].toString();
				Log.v(LOG_TAG, msg);
			}
		}
	}

	private void createChat() {
		// creating a chat and sending messages
		try {
			this.chatmanager = conn1.getChatManager();
			Log.v(LOG_TAG, "creating chat");
			this.chat = this.chatmanager.createChat(BUDDY_ADDRESS,
					new DroneMessageListener(this)		
			);
		} catch (Exception e) {
			Log.v(LOG_TAG, "XMPPException during connect(): " + e.getMessage());
		}

	}
	
	public void sendMessage(String msg) {
		try {
			this.chat.sendMessage(msg);
		} catch (XMPPException e) {
			msg = "XMPPException while sending message: " + e.getMessage();
			Log.v(LOG_TAG, msg);
		}
	}
	
	
	public void sendStatus() {
		if (this.navigatorIsBound){
			Message msg = new Message();
			msg.what = 10; // add way-point
			this.boundNavigatorService.sendNavMessage(msg);
		}
	}
	
	public void forwardStatus(Bundle data) {
		String statusMsg = String.format("lon: %d,lat: %d, speed: %d, bearing: %d, turnrate: %d, accuracy: %d,progress: %d",
				data.getDouble("lon"),
				data.getDouble("lat"),
				data.getDouble("speed"),
				data.getDouble("bearing"),
				data.getDouble("turnrate"),
				data.getDouble("accuracy"),
				data.getDouble("progress"));
		this.sendMessage(statusMsg);
	}

	public void addWaypoint(double lon, double lat) {
		// Adds new waypoint to waypoint list
		if (this.navigatorIsBound) {
			Message msg = new Message();
			msg.what = 11; // add way-point
			Bundle data = new Bundle();
			data.putDouble("lon", lon);
			data.putDouble("lat", lat);
			msg.setData(data);
			this.boundNavigatorService.sendNavMessage(msg);
			Log.v(LOG_TAG, "Sent addWaypoint msg to navigator");
		}
	}

	public void setMotorLoad(int loadPercentage) {
		if (this.navigatorIsBound)
			this.boundNavigatorService.setMotorLoad(loadPercentage);
	}

	// Command to test the rudder
	public void setRudderAngle(int rudderAngle) {
		if (this.boundNavigatorService!=null)
			this.boundNavigatorService.setRudderAngle(rudderAngle);
	}

	// Command to test the motor
	public void startMotor() {
		if (this.navigatorIsBound)
			this.boundNavigatorService.startMotor();
	}

	public void stopMotor() {
		if (this.navigatorIsBound)
			this.boundNavigatorService.stopMotor();
	}

	// Start navigation using waypoints from file
	public void setAutopilot(boolean autoPilotActive) {
		if (this.navigatorIsBound)
			this.boundNavigatorService.setAutopilot(autoPilotActive);
	}

	// Start navigation loop (motor starts etc.)
	public void setActive(boolean navigatorActive) {
		if (this.navigatorIsBound)
			this.boundNavigatorService.setActive(navigatorActive);
	}
	
	// Start navigation loop (motor starts etc.)
	public void shutDown() {
		if (this.navigatorIsBound)
			this.boundNavigatorService.shutDown();
	}


	private void showNotification() {
		// In this sample, we'll use the same text for the ticker and the
		// expanded notification
		CharSequence text = "GPS-logger active!";

		// Set the icon, scrolling text and timestamp
		Notification notification = new Notification(R.drawable.gpslogger16,
				text, System.currentTimeMillis());

		// The PendingIntent to launch our activity if the user selects this
		// notification
		PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
				new Intent(this, GpsLoggerService.class), 0);

		// Set the info for the views that show in the notification panel.
		notification.setLatestEventInfo(this, "GpsLoggerService", text,
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

	/*
	 * Class for clients to access. Because we know this service always runs in
	 * the same process as its clients, we don't need to deal with IPC.
	 */
	public class LocalBinder extends Binder {
		public ChatService getService() {
			return ChatService.this;
		}
	}
	
	// This is the object that receives interactions from clients. See
	// RemoteService for a more complete example.
	private final IBinder mBinder = new LocalBinder();


	private ServiceConnection navigatorServiceConnection = new ServiceConnection() {
		public void onServiceConnected(ComponentName className, IBinder service) {
			boundNavigatorService = ((NavigatorService.LocalBinder) service)
					.getService();
		}

		public void onServiceDisconnected(ComponentName className) {
			// called when the connection with the service has been
			// unexpectedly disconnected -- that is, its process crashed.
			// Because it is running in our same process, we should never
			// see this happen.
			boundNavigatorService = null;
		}
	};

}