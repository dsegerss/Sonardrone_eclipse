package org.sonardrone.navigator;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

public class OrientationListener implements SensorEventListener {
	private double azimut = 0.0;  // View to draw a compass
	private double ALPHA = 0.9;
	private long timestamp = 0;  
	public double get_heading() {
		return azimut;
	}
	
	public long get_heading_timestamp() {
		return this.timestamp;
	}
	
	void lowPassFilter(double rawValue) {
		this.azimut = this.ALPHA * this.azimut +
				(1.0 - this.ALPHA) * rawValue;
		}
	  	 	 	 
	public void onAccuracyChanged(Sensor sensor, int accuracy) {  }
	  
	float[] mGravity;
	float[] mGeomagnetic;
	public void onSensorChanged(SensorEvent event) {
		if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER)
			mGravity = event.values;
		if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD)
			mGeomagnetic = event.values;
		if (mGravity != null && mGeomagnetic != null) {
			float R[] = new float[9];
			float I[] = new float[9];
			boolean success = SensorManager.getRotationMatrix(R, I, mGravity, mGeomagnetic);
			if (success) {
				float orientation[] = new float[3];
				SensorManager.getOrientation(R, orientation);
				// orientation contains: azimut, pitch and roll
				lowPassFilter(orientation[0]);
				this.timestamp = System.currentTimeMillis();
			}
		}
	}
}	
