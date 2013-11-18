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

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

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
		NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		if (intent != null && intent.getAction() != null
				&& intent.getAction().equals("stop")) {
			// User clicked the notification. Need to stop the service.
			nm.cancel(0);
			stopSelf();
		} else {
			// Service starting. Create a notification.
			Notification notification = new Notification(
					R.drawable.ic_launcher, "IOIO service running",
					System.currentTimeMillis());
			notification
					.setLatestEventInfo(this, "IOIO Service", "Click to stop",
							PendingIntent.getService(this, 0, new Intent(
									"stop", null, this, this.getClass()), 0));
			notification.flags |= Notification.FLAG_ONGOING_EVENT;
			nm.notify(0, notification);
		}
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
		active=true;
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

}