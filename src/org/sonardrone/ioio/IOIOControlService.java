package org.sonardrone.ioio;

import ioio.lib.api.DigitalOutput;
import ioio.lib.api.IOIO;
import ioio.lib.api.PwmOutput;
import ioio.lib.api.DigitalOutput.Spec.Mode;
import ioio.lib.api.exception.ConnectionLostException;
import ioio.lib.util.BaseIOIOLooper;
import ioio.lib.util.IOIOLooper;
import ioio.lib.util.android.IOIOService;

import org.sonardrone.R;
import org.sonardrone.SonardroneActivity;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

/**
 * A service to interact with external electronics connected to android using IOIO
 * Functionality to turn a servo motor, handle a DC-motor, and reading input from a 
 * sonar transducer
 */
public class IOIOControlService extends IOIOService {
	private static final String TAG = "IOIOService";
	private static volatile int rudderAngle;
	private static volatile int load;
	private static volatile boolean active;	
	private NotificationManager mNM;
	
	@Override
	protected IOIOLooper createIOIOLooper() {
		return new BaseIOIOLooper() {
			//output signals
			private DigitalOutput led_;
			private PwmOutput servo_;
			//pins
			private final int SERVO_PIN = 3;
			
			private final int SERVO_PWM_FREQ = 100;
			private final int deltat=200;
			

			@Override
			protected void setup() throws ConnectionLostException,
					InterruptedException {
				Log.d(TAG,"IOIO setup");
				led_ = ioio_.openDigitalOutput(IOIO.LED_PIN);
				servo_ = ioio_.openPwmOutput(new DigitalOutput.Spec(SERVO_PIN, Mode.OPEN_DRAIN), SERVO_PWM_FREQ);
				//load_= ioio_.openPwmOutput(new DigitalOutput.Spec(SERVO_PIN, Mode.OPEN_DRAIN), PWM_FREQ);
			}

			private int angle2pw(int angle) {
				Log.d(TAG,"angle2pw");
				double pw = 1000.0 + ((double) angle + 90.0)/180.0 * 1000.0;

				if(pw>2000)
					pw=2000.0;
				if(pw<1000)
					pw=1000.0;
				return (int) pw;
				
			}
			
			
			@Override
			public void loop() throws ConnectionLostException,
					InterruptedException {
//				if (IOIOControlService.active) {
				int pw=angle2pw(IOIOControlService.rudderAngle);
				Log.d(TAG,String.format("Rudder pulse width: %d", pw));
				servo_.setPulseWidth(pw);
				//load_.setPulseWidth(angle2pw(IOIOControlService.load));
				if(IOIOControlService.load>0)
					led_.write(true);
				else
					led_.write(false);
					
				//}
				Thread.sleep(this.deltat);
			}
				
		};
	}
	
	

	@Override
	public void onStart(Intent intent, int startId) {
		super.onStart(intent, startId);
		mNM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		showNotification();
		sendServiceStateBroadcast(true);
	}
	
	private void showNotification() {
		// In this sample, we'll use the same text for the ticker and the
				// expanded notification
		CharSequence text = "IOIO service active!";
				
		// prepare intent which is triggered if the
		// notification is selected

		Intent intent = new Intent(this, SonardroneActivity.class);
		PendingIntent pIntent = PendingIntent.getActivity(this, 0, intent, 0);
				
		// build notification
		// the addAction re-use the same intent to keep the example short
		Notification n  = new NotificationCompat.Builder(this)
				.setContentTitle(text)
				.setContentText("IOIO service is running")
				.setSmallIcon(R.drawable.ic_launcher)
				.setContentIntent(pIntent)
				.addAction(R.drawable.gpslogger16, "Go to activity", pIntent).build();
		
		mNM.notify(0, n);	
	}
	
	@Override
	public void onDestroy() {
		super.onDestroy();
		// Tell the user we stopped.
		Toast.makeText(this, "IOIO service stopped!", Toast.LENGTH_SHORT).show();
		sendServiceStateBroadcast(false);
		mNM.cancel(0);
	}
	

	public void setRudderAngle(int angle) {
		rudderAngle=angle;
		Log.d(TAG,String.format("Setting rudder to %d", angle));
		
	}

	public int getRudderAngle() {
		return rudderAngle;
	}

	public void startMotor() {
		active = true;
		Log.d(TAG,"Started motor");
	}

	public void stopMotor() {
		active=false;
		Log.d(TAG,"Stopped motor");
	}
	
	// set motor load
	public void setMotorLoad(int loadPercentage) {
		load=loadPercentage;
	}


	@Override
	public IBinder onBind(Intent arg0) {
		return ioioBinder;
	}

	// This is the object that receives interactions from clients. See
	// RemoteService for a more complete example.
	private final IBinder ioioBinder = new LocalBinder();

	/*
	 * Class for clients to access. Because we know this service always runs in
	 * the same process as its clients, we don't need to deal with IPC.
	 */
	public class LocalBinder extends Binder {
		public IOIOControlService getService() {
			return IOIOControlService.this;
		}
	}
	
	private void sendServiceStateBroadcast(boolean state){
		Intent intent = new Intent("SERVICE_STATE");
	    intent.putExtra("SERVICE", "IOIOControlService");
	    intent.putExtra("STATE", state);
	    LocalBroadcastManager.getInstance(IOIOControlService.this).sendBroadcastSync(intent);
	}

}