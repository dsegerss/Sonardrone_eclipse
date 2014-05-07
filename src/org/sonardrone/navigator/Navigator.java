package org.sonardrone.navigator;

import static java.lang.Math.PI;
import static java.lang.Math.abs;
import static java.lang.Math.atan2;
import static java.lang.Math.cos;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.pow;
import static java.lang.Math.signum;
import static java.lang.Math.sin;
import static java.lang.Math.sqrt;
import static java.lang.Math.toRadians;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;

import org.ejml.data.DenseMatrix64F;
import org.ejml.ops.CommonOps;
import org.sonardrone.Project;
import org.sonardrone.navigator.kalman.KalmanFilter;
import org.sonardrone.navigator.kalman.NavFilter;
import org.sonardrone.proj.positions.SWEREF99Position;
import org.sonardrone.proj.positions.WGS84Position;

import android.os.Bundle;
import android.util.Log;


/**
 * Waypoint navigation for autonomous boat Uses Kalman-filtering of
 * GPS-position, compass, speed, rudder encoder and motor load
 * 
 * @author David Segersson
 */
public class Navigator {
	private static final String TAG = "Navigator";
	public static String projectName = "default";
	private Project prj;

	/*
	 * current state, corrected in order: X,Y,V,phi, turn_rate where X,Y is
	 * position in navigation frame phi is heading, north = 0 deg, positive in
	 * clockwise direction turn_rate is in deg/s, positive in clockwise
	 * direction
	 */
	public static double[] state = { 0, 0, 0, 0, 0 };

	/*
	 * current measurements in order X, Y, V, phi_GPS,phi_compass,load where X
	 * and Y is position in nav fram measure with GPS phi_GPS is heading
	 * measured with GPS phi_compass is heading measured with compass load is
	 * current engine load in % of full load
	 */
	private static double[] meas = { 0, 0, 0, 0, 0, 0, 0 };
	private double k = 3.14; // k=p/v³ , estimated from p = 85%,V = 3m/s
	private double load = 0; // motor load, in percentage
	private double rudder_angle = 0;
	private double compass_bias = 0;
	private long[] timestamps = { 0, 0, 0, 0, 0, 0, 0 }; // Latest measurement
														// time-stamps
	private double gpsAccuracy =25;

	// Generator for random numbers used to simulate measurement uncertainty
	public static Random generator = new Random(1);

	public boolean filterSwitch = true;
	public boolean compassSwitch = true;
	public boolean gpsPositionSwitch = true;
	public boolean gpsVelSwitch = true;
	public boolean gpsBearingSwitch = true;
	public boolean encoderVelSwitch = true;
	public boolean encoderTurnrateSwitch = true;
	public boolean updateKSwitch = true;
	public boolean simulateGPSSwitch = true;
	public KalmanFilter kf = new NavFilter();

	private double dt_default = 0.1;
	private final double dt = 0.1;
	private int measDOF = 7;
	private int stateDOF = 5;
	
	//times given in millisecs from start)
	private long lastTime = 0; // Time for current state, n, 
	private long predictionTime = 0; // Time for predicted state, n+1 
	private long timeBefore = 0; // Start-time given in milliseconds

	// waypoints and operation
	private boolean active = false;
	private boolean autoPilot = false;
	private List<double[]> wp = new ArrayList<double[]>(); // waypoint list
	private Iterator<double[]> wpIter = null;
	private double[] cwp = null; // next waypoint on the path
	private double[] lwp = null; // last waypoint on the path (just passed)
	private double look_ahead = 10; // look-ahead distance
	private double min_look_ahead = 2; // minimum look-ahead distance, when
										// distance adapted not to overshoot
										// waypoint
	public Integer resumeFromWp = 0;
	public double tolerance = 10; // Tolerance within which waypoint is
									// considered reached.
	public double max_rudder_angle = 75; // max allowed rudder angle [deg]
	public double min_turn_radius = 10; // minimum allowed turn radius
	// Process uncertainty estimations
	public int nsteps = 1;
	public double ax_max = 0.05; // max acceleration in body-frame x-direction
	public double ay_max = 0.5; // max acceleration in body-frame x-direction
	public double max_dir_change; // max dir change in one filter cycle [deg]
	public double tau; // timescale for changing from zero to full turn-rate [s]

	// Measurements uncertainty estimations
	public double sigmaX_GPS = 3; // GPS standard deviation for GPS-position
	public double sigmaV_GPS = 1.0; // GPS speed standard deviation
	public double sigmaPhi_GPS = 10; // std dev for phi estimated using GPS
	public double sigmaPhi_compass = 10; // std dev for phi using compass
	public double sigmaBeta_rudder = 5; // std dev for turn rate estimated from
										// rudder angle and speed
	public double sigmaV_load = 5; // Load std dev in %
	public DenseMatrix64F R; // Measurement noise matrix

	// Measurement parameters
	public double minBearingDist = 5; // Min traveled distance to set bearing
	public double[] lastBearingPos = null; // Last position where bearing from
											// GPS was set
	public double bearingTurnrateThreshold = 1; // turn-rate threshold to
												// estimate bearing from GPS-pos
	public double compassTurnrateThreshold = 1; // turn-rate threshold to
												// estimate bearing from GPS-pos
	public double minVelDist = 10;
	public double[] lastVelPos = { 0, 0 }; // last position when velocity from GPS was set
	public long lastVelTime = 0; // last time when velocity from GPS was set
	
	public void initProject() {
		this.prj = new Project(projectName);
	}
	
	public void readResources() {
		String[] intParams = { "resumeFromWp" };

		String[] doubleParams = { "k", "load", "rudder_angle", "dt_default",
				"tolerance", "ax_max", "ay_max", "max_rudder_angle",
				"max_dir_change", "tau", "sigmaX_GPS", "sigmaV_GPS",
				"sigmaPhi_GPS", "sigmaPhi_compass", "sigmaBeta_rudder",
				"sigmaV_load", "min_look_ahead", "look_ahead", "minVelDist",
				"minBearingDist", "bearingTurnrateThreshold",
				"compassTurnrateThreshold", "min_turn_radius" };

		String[] boolParams = { "filterSwitch", "compassSwitch",
				"gpsPositionSwitch", "gpsVelSwitch", "gpsBearingSwitch",
				"encoderVelSwitch", "encoderTurnrateSwitch", "updateKSwitch",
				"simulateGPSSwitch", "autoPilot" };

		Class<Navigator> navClass = Navigator.class;
		Field field = null;
		for (int i = 0; i < doubleParams.length; i++) {
			try {
				field = navClass.getDeclaredField(doubleParams[i]);
			} catch (NoSuchFieldException e1) {
				Log.e(TAG,"No such field:" + doubleParams[i]);
				System.exit(1);
			}
			if (this.prj.containsKey(doubleParams[i])) {
				try {
					field.set(this,prj.getParameterAsDouble(doubleParams[i]));
				} catch (IllegalArgumentException e) {
					// TODO Auto-generated catch block
					Log.e(TAG, e.getMessage());
				} catch (IllegalAccessException e) {
					// TODO Auto-generated catch block
					Log.e(TAG, e.getMessage());
				}
	
			} else {
				Log.e(TAG,"Parameter " + doubleParams[i]
						+ " not found in resource file");
				System.exit(1);
			}
		}
		for (int i = 0; i < boolParams.length; i++) {
			try {
				field = navClass.getDeclaredField(boolParams[i]);
			} catch (NoSuchFieldException e1) {
				Log.e(TAG,"No such field: " + e1.getMessage());
				System.exit(1);
			}
			if (this.prj.containsKey(boolParams[i])) {
				Log.d(TAG,boolParams[i] + "=" + ""
						+ prj.getParameterAsBoolean(boolParams[i]));
				try {
					field.set(this, prj.getParameterAsBoolean(boolParams[i]));
				} catch (IllegalArgumentException e) {
					Log.e(TAG, e.getMessage());
				} catch (IllegalAccessException e) {
					Log.e(TAG, e.getMessage());
				}
			} 
			else {
				Log.e(TAG,"Parameter " + boolParams[i]
						+ " not found in resource file");
				System.exit(1);
			}
		}

		for (int i = 0; i < intParams.length; i++) {
			try {
				field = navClass.getDeclaredField(intParams[i]);
			} catch (NoSuchFieldException e1) {
				Log.e(TAG,"No such field: " + e1.getMessage());
				System.exit(1);
			}
			if (this.prj.containsKey(intParams[i])) {
				Log.d(TAG,intParams[i] + "=" + ""
						+ prj.getParameterAsInt(intParams[i]));
				try {
					field.set(this, prj.getParameterAsInt(intParams[i]));
				} catch (IllegalArgumentException e) {
					// TODO Auto-generated catch block
					Log.e(TAG, e.getMessage());
				} catch (IllegalAccessException e) {
					// TODO Auto-generated catch block
					Log.e(TAG, e.getMessage());
				}
				Log.e(TAG,"Error: could not parse value of "
						+ intParams[i] + " in resource file");
			}
			else {
				Log.e(TAG,"Error: parameter " + intParams[i]
						+ " not found in resource file");
				System.exit(1);
			}
		}

	}

	// simple vector algebra
	public static double dot(double[] vec1, double[] vec2) {
		// 2D dot-product
		return vec1[0] * vec2[0] + vec1[1] * vec2[1];
	}

	public static double mag(double[] vec) {
		// 2D vector magnitude
		return sqrt(pow(vec[0], 2) + pow(vec[1], 2));
	}

	public static double[] minus(double[] vec1, double[] vec2) {
		// 2D vector difference
		double[] vec = new double[2];
		vec[0] = vec2[0] - vec1[0];
		vec[1] = vec2[1] - vec1[1];
		return vec;
	}

	// set state variables
	public void set_pos(double[] p) {
		state[0] = p[0];
		state[1] = p[1];
	}

	public void set_V(double vel) {
		state[2] = vel;
	}

	public void set_phi(double p) {
		state[3] = p;
	}

	public void set_turn_rate(double tr) {
		state[4] = tr;
	}

	// set measurements
	public void set_pos_GPS(double[] p) {
		meas[0] = p[0];
		meas[1] = p[1];
	}

	public void set_GPS_accuracy(float accuracy) {
		gpsAccuracy = accuracy;
	}
	
	public void set_V_GPS(double vel) {
		meas[2] = vel;
	}

	public void set_phi_GPS(double p) {
		meas[3] = p;
	}

	public void set_phi_compass(double val) {
		//Bias-corrected compass reading is added as measurement
		meas[4] = val + this.compass_bias;
	}

	public void set_turn_rate_rudder(double tr) {
		meas[5] = tr;
	}

	public void set_V_load(double vel) {
		meas[6] = vel;
	}

	// get state variables
	public double[] pos() {
		double[] pos = { state[0], state[1] };
		return pos;
	}

	public double V() {
		return state[2];
	}

	public double phi() {
		return state[3];
	}

	public double turn_rate() {
		return state[4];
	}

	// get measurement variables
	public double[] pos_GPS() {
		double[] pos = { meas[0], meas[1] };
		return pos;
	}

	public double V_GPS() {
		return meas[2];
	}

	public double phi_GPS() {
		return meas[3];
	}

	public double phi_compass() {
		return meas[4];
	}

	public double turn_rate_rudder() {
		return meas[5];
	}

	public double V_load() {
		return meas[6];
	}
	
	public void log(String logName, String logStr) {
		prj.log(logName, logStr);
	}
	
	public void logState(double[] predState) {
		// Write string to measlog
		// X Y V Heading Turn-rate X_p Y_p V_p Heading_p Turn-rate_p
		this.log("state", String.format(
				"%d\t%f\t%f\t%f\t%f\t%f\t%f\t%f\t%f\t%f\t%f\n",
				this.predictionTime, this.pos()[0], this.pos()[1],
				this.V(), this.phi(), this.turn_rate(), predState[0],
				predState[1], predState[2], predState[3], predState[4]));
	}

	public void logMeas() {
		double[] logMeas = { -999, -999, -999, -999, -999, -999, -999 };
		for (int i = 0; i < timestamps.length; i++) {
			if (timestamps[i] > this.lastTime)
				logMeas[i] = meas[i];
		}
		this.log("meas", String.format(
				"%d\t%f\t%f\t%f\t%f\t%f\t%f\t%f\n", this.predictionTime,
				logMeas[0], logMeas[1], logMeas[2], logMeas[3], logMeas[4],
				logMeas[5], logMeas[6]));
	}

	// get measurement time-stamps
	public long pos_GPS_time() {
		return this.timestamps[0];
	}

	public long V_GPS_time() {
		return this.timestamps[2];
	}

	public long phi_GPS_time() {
		return this.timestamps[3];
	}

	public long phi_compass_time() {
		return this.timestamps[4];
	}

	public long turn_rate_rudder_time() {
		return this.timestamps[5];
	}

	public long V_load_time() {
		return this.timestamps[6];
	}

	// set measurement time-stamps
	public void set_pos_GPS_time(long t) {
		this.timestamps[0] = t;
		this.timestamps[1] = t;
	}

	public void set_V_GPS_time(long t) {
		this.timestamps[2] = t;
	}

	public void set_phi_GPS_time(long t) {
		this.timestamps[3] = t;
	}

	public void set_phi_compass_time(long t) {
		this.timestamps[4] = t;
	}

	public void set_turn_rate_rudder_time(long t) {
		this.timestamps[5] = t;
	}

	public void set_V_load_time(long t) {
		this.timestamps[6] = t;
	}

	// navigation methods
	public boolean nextWP() {

		// Set next wp to current, and current wp to last
		this.lwp = this.cwp;// set lwp to cwp
		this.resumeFromWp++;
		try {
			this.cwp = this.wpIter.next(); // set cwp to next wp
		} catch (NoSuchElementException e) {
			this.cwp = null;
			return false;
		}

		return true;
	}

	public double[] findGoalPoint(double cx, double cy, double radius,
			double[] point1, double[] point2, double[] b) {
		double dx, dy, A, B, C, det, t;
		dx = point2[0] - point1[0];
		dy = point2[1] - point1[1];
		A = dx * dx + dy * dy;
		B = 2 * (dx * (point1[0] - cx) + dy * (point1[1] - cy));
		C = (point1[0] - cx) * (point1[0] - cx) + (point1[1] - cy)
				* (point1[1] - cy) - radius * radius;
		det = B * B - 4 * A * C;
		if ((A <= 0.0000001) || (det < 0)) {
			// No real solutions.
			double[] intersection1 = null;
			return intersection1;
		} else if (det == 0) {
			// One solution.
			t = -B / (2 * A);
			double[] intersection1 = { point1[0] + t * dx, point1[1] + t * dy };
			return intersection1;
		} else {
			// Two solutions.
			t = (-1 * B + Math.sqrt(det)) / (2 * A);
			double[] intersection1 = { point1[0] + t * dx, point1[1] + t * dy };

			// Check if x-coordinate of intersection1 is between b (closest
			// point on path) and point2 (current waypoint)
			// otherwise return the other intersection
			if ((intersection1[0] > b[0] && intersection1[0] <= point2[0])
					|| (intersection1[0] < b[0] && intersection1[0] >= point2[0])) {
				return intersection1;
			} else {
				t = (-1 * B - Math.sqrt(det)) / (2 * A);
				double[] intersection2 = { point1[0] + t * dx,
						point1[1] + t * dy };
				return intersection2;
			}
		}
	}
	
	public boolean getAutopilot() {
		return this.autoPilot;
	}

	public double getRudderAngle() {
		return this.rudder_angle;
	}
	
	public double getTurnrate() {
		/*
		 * pure pursuit algorithm following "Path Tracking for unmanned vehicle
		 * navigation - implementation and adaptation of the pure pursuit
		 * algorithm" By Defense Research and Development Canada
		 */
		// Transform to body-frame, phi is in clockwise-direction
		double[] lwpb = this.nav2body(this.lwp);
		double[] cwpb = this.nav2body(this.cwp);
		double[] posb = this.nav2body(this.pos());

		/*
		 * debug settings double[] lwp = {5.443,6.506}; double[] cwp =
		 * {15.443,23.827}; double[] pos = {11.055,7.466};
		 * 
		 * double[] lwpb = this.nav2body(lwp); double[] cwpb =
		 * this.nav2body(cwp); double[] posb = this.nav2body(pos);
		 */

		// angle to cwp in body-frame
		// targetAngle is positive in counter-clockwise direction
		// atan2 gives angle from x-axis,
		// this is transfered to heading angle by mult. by -1 and adding PI/2
		// double targetAngle = PI/2.0 - atan2(cwpb[0],cwpb[1]);
		double targetAngle = atan2(cwpb[1], cwpb[0]) - PI / 2.0;

		/*
		 * if(cwpb[0]==0) { if(cwpb[1]>=0) targetAngle=0; else
		 * targetAngle=Math.PI; } else if(cwpb[1]==0) { if(cwpb[1]>=0)
		 * targetAngle=-0.5*Math.PI; else targetAngle=0.5*Math.PI; } else
		 * if(cwpb[1]>0) //first quadrant or fourth quadrant
		 * targetAngle=0.5*Math.PI+Math.atan(cwpb[1]/cwpb[0]); //second and
		 * third quadrant else
		 * targetAngle=Math.PI*0.5-Math.atan(Math.abs(cwpb[1]/cwpb[0]));
		 */
		// Assure that the smallest angle is given to the target
		if (targetAngle > PI)
			targetAngle -= 2 * PI;
		if (targetAngle < -1 * PI)
			targetAngle += 2 * PI;

		// if ship is oriented totally wrong direction,
		if (abs(targetAngle) > 0.5 * PI) {
			// a high number is returned to give the maximum turn-rate,
			// targetAngle is defined in counter-clockwise direction
			// the turn rate is returned in clockwise direction
			double maxTurnRate = this.V() / this.min_turn_radius;
			return -1 * maxTurnRate * signum(targetAngle);
		}
		double[] v = minus(lwpb, cwpb); // vector between waypoints
		double[] w = minus(lwpb, posb);

		double bmag = dot(w, v) / mag(v);
		double bnorm = bmag / mag(v);

		double[] b = new double[2];
		b[0] = lwpb[0] + bnorm * v[0];
		b[1] = lwpb[1] + bnorm * v[1];

		double Lcwp = mag(minus(posb, cwpb)); // distance to next waypoint,
		double Lerr = mag(minus(posb, b)); // distance to waypoint path
		// look-ahead is adapted to hit next waypoint and to be more stable (by
		// considering the distance to the path)
		double Ladapt = max(this.min_look_ahead,
				min(Lerr + this.look_ahead, Lcwp));

		double[] goal = this.findGoalPoint(0, 0, Ladapt, lwpb, cwpb, b);
		double x = Math.abs(goal[0]);
		// double x = mag(minus(posb,b));
		// double y = sqrt(pow(Ladapt,2)-pow(x,2));

		// goal[0]=b[0]+y*v[0]/mag(v);
		// goal[1]=b[1]+y*v[1]/mag(v);

		// double[] xvec =minus(posb,b);
		// double turnDirection = signum(xvec[0]); //turn direction is negative
		// if goal is on the left of the ship
		double turnDirection = signum(goal[0]); // turn direction is negative if
												// goal is on the left of the
												// ship
		double curvature = 2 * x / pow(Ladapt, 2);
		// Turn-rate is positive clockwise
		double new_turn_rate = curvature * this.V() * turnDirection;
		double radius = 1 / curvature;
		double[] centre = new double[2];
		centre[0] = posb[0] + radius * (b[0] - posb[0]) / x;
		centre[1] = posb[1] + radius * (b[1] - posb[1]) / x;

		b = this.body2nav(b);
		centre = this.body2nav(centre);
		goal = this.body2nav(goal);
		//double theta1 = this.phi(); // limit set for unit circle, 0 deg in
									// body-frame equals phi in nav-frame
		// sector limit, if goal is on the left, turnDirection
		// is negative
		//double theta2 = this.phi() - turnDirection * toRadians(90); 
		double[] dir = new double[2];
		dir[0] = 0;
		dir[1] = 1.0;
		dir = this.body2nav(dir);

		// double theta1=0;
		// double theta2=-1*turnDirection*toRadians(90);
		// logging algorithm geometry for plotting
		this.log("nav", String.format("time: %d\n", this.lastTime));
		this.log("nav", "points\tX\tY\n");
		// this.navlog.write(String.format("cwp\t%f\t%f\n",this.cwp[0],this.cwp[1]));
		// this.navlog.write(String.format("lwp\t%f\t%f\n",this.lwp[0],this.lwp[1]));
		this.log("nav", String.format("pos\t%f\t%f\n", this.pos()[0],
				this.pos()[1]));
		// this.navlog.write(String.format("goal\t%f\t%f\n",goal[0],goal[1]));
		// this.navlog.write(String.format("centre\t%f\t%f\n",centre[0],centre[1]));
		this.log("nav", "endpoints\n");
		this.log("nav", "lines\tX1\tY1\tX2\tY2\n");
		this.log("nav", String.format("v\t%f\t%f\t%f\t%f\n", this.lwp[0],
				this.lwp[1], this.cwp[0], this.cwp[1]));
		// this.navlog.write(String.format("w\t%f\t%f\t%f\t%f\n",this.lwp[0],this.lwp[1],this.pos()[0],this.pos()[1]));
		// this.navlog.write(String.format("b\t%f\t%f\t%f\t%f\n",this.lwp[0],this.lwp[1],b[0],b[1]));
		// this.navlog.write(String.format("d\t%f\t%f\t%f\t%f\n",centre[0],centre[1],b[0],b[1]));
		// this.navlog.write(String.format("x\t%f\t%f\t%f\t%f\n",this.pos()[0],this.pos()[1],b[0],b[1]));
		// this.navlog.write(String.format("y\t%f\t%f\t%f\t%f\n",b[0],b[1],goal[0],goal[1]));
		this.log("nav", String.format("Dir\t%f\t%f\t%f\t%f\n",
				this.pos()[0], this.pos()[1], dir[0], dir[1]));
		this.log("nav", String.format("Ladapt\t%f\t%f\t%f\t%f\n",
				this.pos()[0], this.pos()[1], goal[0], goal[1]));
		this.log("nav", "endlines\n");
		this.log("nav", "circless\tX\tY\tR\ttheta1\ttheta2\n");
		// this.navlog.write(String.format("curvature\t%f\t%f\t%f\t%f\t%f\n",centre[0],centre[1],radius,theta1,theta2));
		this.log("nav", "endcircles\n");

		return new_turn_rate;
	}

	public double[] nav2body(double[] vec) {
		/*
		 * Translates origo to the vehicle pos and rotates the Y axis to align
		 * with the body-frame
		 */
		double[] bfvec = new double[2];
		// debug setting: double[] pos = {11.055,7.466};

		// Translation and Rotation
		// Note that heading is defined positive in clockwise direction, thus
		// the minus in the rotations
		// phi is in radians!
		// debug setting: double phi=0.52333;
		bfvec[0] = cos(this.phi()) * (vec[0] - this.pos()[0]) - sin(this.phi())
				* (vec[1] - this.pos()[1]);
		bfvec[1] = sin(this.phi()) * (vec[0] - this.pos()[0]) + cos(this.phi())
				* (vec[1] - this.pos()[1]);
		return bfvec;
	}

	public double[] body2nav(double[] vec) {
		/*
		 * Rotates the coordinate system to align with the navigation frame,
		 * then translate origo
		 */
		// debug setting: double[] pos = {11.055,7.466};
		// debug setting: double phi=0.52333;

		double[] nfvec = new double[2];

		// Rotation
		// Note that heading is defined positive in clockwise direction, thus
		// the minus in the rotations
		nfvec[0] = cos(-1 * this.phi()) * vec[0] - sin(-1 * this.phi())
				* vec[1];
		nfvec[1] = sin(-1 * this.phi()) * vec[0] + cos(-1 * this.phi())
				* vec[1];

		// Translation
		nfvec[0] = nfvec[0] + this.pos()[0];
		nfvec[1] = nfvec[1] + this.pos()[1];
		return nfvec;

	}

	public boolean reachedWP() {
		double dist = sqrt(pow(this.cwp[0] - this.pos()[0], 2)
				+ pow(this.cwp[1] - this.pos()[1], 2));
		if (dist <= this.tolerance)
			return true;
		else
			return false;

	}
	
	public boolean reachedLastWP() {
		return wpIter.hasNext() == false;
	}

	public void setWaypoints() {
		this.wp = prj.read_waypoints();	
	}

	// Kalman filtering
	public void update_k(double vel, double load) {
		// k=P/V³
		this.k = load / pow(vel, 3);
	}

	public DenseMatrix64F createR() {
		DenseMatrix64F R = new DenseMatrix64F(this.measDOF, this.measDOF);
		R.set(0, 0, pow(this.sigmaX_GPS, 2));
		R.set(1, 1, pow(this.sigmaX_GPS, 2));
		R.set(2, 2, pow(this.sigmaV_GPS, 2));
		R.set(3, 3, pow(toRadians(this.sigmaPhi_GPS), 2));
		R.set(4, 4, pow(toRadians(this.sigmaPhi_compass), 2));
		R.set(5, 5, pow(this.sigmaBeta_rudder, 2));
		R.set(6, 6, pow(this.sigmaV_load, 2));
		return R;
	}

	public DenseMatrix64F createF() {
		// state transition matrix
		DenseMatrix64F F = new DenseMatrix64F(this.stateDOF, this.stateDOF);
		// set diagonal to 1
		for (int i = 0; i < 5; i++)
			F.set(i, i, 1);
		F.set(0, 2, sin(this.phi()) * this.dt); // X
		F.set(1, 2, cos(phi()) * this.dt); // Y
		F.set(3, 4, this.dt); // phi
		return F;
	}

	public DenseMatrix64F createQ() {
		// dead reckoning uncertainty correlation matrix
		DenseMatrix64F Q = new DenseMatrix64F(this.stateDOF, this.stateDOF);

		// estimate variance in body-frame x coordinate
		// increases for each step using dead-reckoning
		double sigmaX = 0.5 * this.ax_max * this.dt * this.dt
				* sqrt(this.nsteps);

		// increases for each step using dead-reckoning
		double sigmaY = 0.5 * this.ay_max * this.dt * this.dt
				* sqrt(this.nsteps);

		// estimate variance in speed
		// double a_max=0.5;
		double sigmaV = 0.5 * this.ay_max * this.dt * sqrt(this.nsteps);

		// estimate variance in heading
		// increases for each step using dead-reckoning
		double sigmaPhi = PI * toRadians(this.max_dir_change) * this.dt
				* sqrt(this.nsteps);

		// estimate variance in turn rate
		// Turn-rate change is not modelled,variance can probably
		// be set to any value given that it's higher than the measurement
		// variance
		// this.tau = 1; //time-scale for change from zero to max turn rate, set
		// to one second
		// double turn_rate_change = toRadians(this.max_dir_change) / this.tau;
		// double sigmaBeta=turn_rate_change*this.dt;
		double sigmaBeta = 1000;
		Q.set(0, 0, sigmaX * sigmaX * cos(this.phi()) + sigmaY * sigmaY
				* sin(this.phi()));
		Q.set(1, 1, sigmaX * sigmaX * sin(this.phi()) + sigmaY * sigmaY
				* cos(this.phi()));
		Q.set(2, 2, sigmaV * sigmaV);
		Q.set(3, 3, sigmaPhi * sigmaPhi);
		Q.set(4, 4, sigmaBeta * sigmaBeta);
		return Q;
	}

	public DenseMatrix64F createZ() {
		// return measurements as DenseMatrix64F
		DenseMatrix64F z = new DenseMatrix64F(this.measDOF, 1);
		for (int i = 0; i < this.measDOF; i++)
			z.set(i, 0, meas[i]);
		return z;
	}

	public DenseMatrix64F createX() {
		// return state as DenseMatrix64F
		DenseMatrix64F X = new DenseMatrix64F(this.stateDOF, 1);
		for (int i = 0; i < this.stateDOF; i++)
			X.set(i, 0, state[i]);
		return X;
	}

	public DenseMatrix64F createH() {
		// set measurement transition matrix
		DenseMatrix64F H = new DenseMatrix64F(this.measDOF, this.stateDOF);
		for (int i = 0; i < 4; i++)
			H.set(i, i, 1);
		H.set(4, 3, 1);
		H.set(5, 4, 1);
		H.set(6, 2, 1); // speed estimated from motor load and k (from p=k*v³)
		return H;
	}

	public void run() {
		double turn_rate = 0;
		int iter = 0;

		// 1. While simulation is used prediction is made first
		// The predicted value is used to create simulated measurements
		// 2. For real conditions, the measurements are made first and the
		// prediction is made just before updating the filter
		// This way a time-step matching the measurements can be choosen
		
		NavThread.boundIOIOControlService.startMotor();
		
		while (this.active) {

			// Check if waypoint is reached
			if (this.reachedWP()) {
				Log.i(TAG, String.format("Reached wp: %f,%f",
						this.cwp[0], this.cwp[1]));
				// Move on to next waypoint
				if (!this.nextWP())
					break; // leave waypoint loop
			}

			// Step dt to prediction time
			this.updateTime();
			Log.d(TAG, String.format(
					"step %d, time %f7.1, x: %f, y %f, V %f, phi %f, beta %f",
					iter, this.predictionTime / 1000.0, state[0], state[1],
					state[2], state[3], state[4]));

			// Run Kalman prediction (move down after GPS-reading for real nav)
			this.kf.predict();
			
			this.nsteps += 1; // increment dead-reckoning step counter

			// Save predicted state from Kalman filter
			double[] predictedState = this.getState();

			// predict state
			state = this.getState();

			// Update heading measurement from compass
			//if (this.compassSwitch)
			//	this.updateCompass();

			this.logMeas();

			boolean[] newMeas = { false, false, false, false, false, false,
					false };
			for (int i = 0; i < timestamps.length; i++) {
				if (timestamps[i] > this.lastTime)
					newMeas[i] = true;
			}

			if (this.filterSwitch)
				this.kf.partialUpdate(newMeas, this.createZ(), this.R);

			// get updated state from Kalman filter or from measurements
			state = this.getState();

			this.logState(predictedState);

			// update resources for remote control params
			if (prj.settings_updated()) {
				this.readResources();
			}

			// Navigation - calculate wanted turn rate
			turn_rate = this.getTurnrate();

			// update velocity and heading measurements from load and rudder
			// This is done here since filter has just been updated and
			// variables are up-to-date
			this.updateEncoders(this.load, turn_rate);

			// k should only be updated if ship is cruising at steady speed
			if (this.V() > 0.5 & this.load > 0 & this.updateKSwitch)
				this.update_k(3.0, this.load);
			
			// update compass bias using GPS heading measurement

			// Uncertainty matrices are updated using the current readings
			this.configureFilter();
			iter++;
		}
		
		NavThread.boundIOIOControlService.stopMotor();

		// write last waypoint index to resume later
		// If last waypoints has been reached, no resume is wanted
		if (this.autoPilot && (this.resumeFromWp < this.wp.size() - 1)) {
			prj.setInt("resumeFromWp", this.resumeFromWp);
			prj.write();
		}
		// clear waypoint list, to prepare for new instructions
		this.clearWaypointList();
		// Switch to manual drive to wait for new instructions
		this.setAutopilot(false);
	}

	public double progressEstimate() {
		//returns the percentag of planned route that has been covered
		if (this.wp.size() > 1) {
			Iterator<double[]> itr = null;
			double[] p1 = null;
			double[] p2 = null;
			double doneDist = 0;
			double totDist = 0;
			int ind=0;
			itr=wp.iterator();
			p2=itr.next();
			while(itr.hasNext()) {
				p1=p2;
				p2=itr.next();
				ind++;
				double segment = mag(minus(p1, p2)); 
				if(ind<=this.resumeFromWp)
					doneDist+=segment;
				totDist+= segment;
			}
			return doneDist/totDist*100.0;
		}
			
		return this.resumeFromWp / ((double) this.wp.size()) * 100;
		
	}

	public double[] getState() {
		double[] sVec = new double[this.stateDOF];
		// Return state from kalman-filter
		DenseMatrix64F sMatrix = kf.getState();
		for (int i = 0; i < sMatrix.numRows; i++)
			sVec[i] = sMatrix.get(i);
		return sVec;
	}

	public void configureFilter() {
		// if GPS-position jumps over ~ 2 meters, the variance is
		// set to a large number
		// this is gradually decreased to the normal value
		// update measurement uncertainty matrix

		// this.R.set(0,0,pow(this.sigmaX_GPS,2));
		// this.R.set(1,1,pow(this.sigmaX_GPS,2));
		// this.R.set(2,2,pow(sigmaV_GPS,2));
		// this.R.set(3,3,pow(this.sigmaPhi_GPS,2));

		// Note: for each step of pure dead-reckoning,
		// the variance is increased by incrementing nsteps
		// Which is used in createQ that returns the process uncertainty matrix

		// Set transition matrix
		DenseMatrix64F F = this.createF();
		// Set process covariance matrix
		DenseMatrix64F Q = this.createQ();
		// Set measurement transition matrix
		DenseMatrix64F H = this.createH();
		kf.configure(F, Q, H);

		if (this.filterSwitch != true) {
			this.kf.bypass(state);
		}
	}

	private void updateCompassBias() {
		this.compass_bias = this.phi_GPS() - (this.phi_compass() - this.compass_bias);
	}

	public boolean updateGPSBearing() {
		
		if (this.turn_rate() > toRadians(this.bearingTurnrateThreshold)) {
			this.lastBearingPos = null;
			return false;
		} else if (this.lastBearingPos == null) {
			this.lastBearingPos = this.pos(); 
			return false;
		} else {
			double dist = mag(minus(this.lastBearingPos, this.pos_GPS()));
			if (dist >= this.minBearingDist) {
				double[] dirVec = minus(this.lastBearingPos, this.pos_GPS());
				double heading = PI / 2.0 - atan2(dirVec[1], dirVec[0]);

				// A sign change in phi, when not close to zero headinf
				// causes a crash in the Kalman filter
				// To avoid this, the closest representation to the current
				// state is chosen
				if (Math.abs(state[3] - heading - 2 * PI) < Math.abs(state[3]
						- heading))
					heading -= 2 * PI;
				else if (Math.abs(state[3] - heading + 2 * PI) < Math
						.abs(state[3] - heading))
					heading += 2 * PI;

				this.set_phi_GPS(heading);
				this.set_phi_GPS_time(this.pos_GPS_time());
				this.lastBearingPos = this.pos(); 
				return true;
			} else
				return false;
		}
	}

	public boolean updateGPSVel() {
		double dist = mag(minus(this.lastVelPos, this.pos_GPS()));
		if (dist < this.minVelDist)
			return false;
		double pastTime = (this.pos_GPS_time() - this.V_GPS_time()) / 1000.0;
		double vel = dist / pastTime;
		this.set_V_GPS(vel);
		this.set_V_GPS_time(this.pos_GPS_time());
		this.lastVelPos = this.pos();
		return true;
	}

	
	// simulation and measurements
	public boolean updateGPS(double[] new_pos, long t) {
		if (this.simulateGPSSwitch) {
			// Assumes prediction has been made of new position
			double r1 = generator.nextGaussian();
			double r2 = generator.nextGaussian();
			new_pos = this.pos();
			new_pos[0] += r1 * this.sigmaX_GPS;
			new_pos[1] += r2 * this.sigmaX_GPS;
		}
		
		if (this.gpsPositionSwitch) {
			this.set_pos_GPS(new_pos);
			this.set_pos_GPS_time(t);
			if (this.gpsVelSwitch)
				this.updateGPSVel();
			if (this.gpsBearingSwitch)
				this.updateGPSBearing();
		}
		this.nsteps = 1; // reset counter for dead-reckoning steps
		
		return true; // indicates that GPS has been updated
	}

	public void updateCompass() {
		// assumes state has been predicted
		// TODO: Time should be measured here instead of
		// being taken from predictedTime
		// long t = System.currentTimeMillis();
		if (this.turn_rate() < toRadians(this.compassTurnrateThreshold)) {
			long t = this.predictionTime;
			double r = generator.nextGaussian() - 0.5;
			double heading = this.phi() + r * toRadians(this.sigmaPhi_compass);

			// A sign change in phi, when not close to zero headinf
			// causes a crash in the Kalman filter
			// To avoid this, the closest representation to the current state is
			// chosen
			if (Math.abs(state[3] - heading - 2 * PI) < Math.abs(state[3]
					- heading))
				heading -= 2 * PI;
			else if (Math.abs(state[3] - heading + 2 * PI) < Math.abs(state[3]
					- heading))
				heading += 2 * PI;

			this.set_phi_compass(heading);
			this.set_phi_compass_time(t);
		}
	}

	public void updateEncoders(double load, double turn_rate) {
		long t;
		if (this.simulateGPSSwitch)
			t = this.predictionTime;
		else
			t = System.currentTimeMillis();


		if (this.encoderVelSwitch) {
			this.load = 85;
			this.set_V_load(pow(this.load / this.k, 1 / 3.0));
			this.set_V_load_time(t);
			//TODO: set load
			NavThread.boundIOIOControlService.setMotorLoad((int) load);
		}

		if (this.encoderTurnrateSwitch) {
			if (this.V() == 0)
				this.rudder_angle = 0;
			else
				this.rudder_angle = turn_rate2angle(turn_rate, this.V());
			
			this.set_turn_rate_rudder(turn_rate);
			this.set_turn_rate_rudder_time(t);
			NavThread.boundIOIOControlService.setRudderAngle(
					(int) this.getRudderAngle());
		}
	}

	public void updateTime() {
		// Set current time to the predicted time
		// Take a time-step dt for the predicted time
		this.lastTime = this.predictionTime;
		
		try {
			Thread.sleep(this.predictionTime - System.currentTimeMillis());
		} catch (InterruptedException e) {
			Log.e(TAG, "Error while waiting for time update");
			e.printStackTrace();
		}

		// this.dt converted from seconds to milliseconds
		this.predictionTime += this.dt * 1000;
	}

	public void initGPS() {
		if (this.simulateGPSSwitch) {
			this.set_pos(this.cwp);
		}
		else {
			while(this.get_GPS_timestamp() == 0) {
				Log.i(TAG, "Waiting for GPS-fix");
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {					
					Log.e(TAG, "Error while waiting for GPS fix");
				}
			}
				
		}
		
		this.lastVelPos = this.pos();
	}

	public void initCompass() {
		// TODO: exchange against real compass readings
		this.set_phi(0.0);
	}

	double turn_rate2angle(double turn_rate, double vel) {
		// degrees/m instead of deg/s
		double curvature = turn_rate / vel;
		if (curvature == 0)
			return 0;
		double radius = abs(1.0 / curvature);

		// radius to angle, should be calibrated
		double[][] rad2angle = { { 1, 90 }, { 2, 85 }, { 3, 80 }, { 4, 75 },
				{ 5, 65 }, { 6, 60 }, { 7, 55 }, { 8, 50 }, { 9, 45 },
				{ 10, 40 }, { 11, 35 }, { 12, 30 }, { 13, 25 }, { 14, 20 },
				{ 15, 25 }, { 16.5, 20 }, { 19, 15 }, { 25, 10 }, { 40, 5 },
				{ 100, 1 }, { 1000, 0.5 } };

		if (radius <= rad2angle[0][0])
			return rad2angle[0][1];
		if (radius >= rad2angle[rad2angle.length - 1][0])
			return rad2angle[rad2angle.length - 1][1];

		int i;
		for (i = 0; i < rad2angle.length; ++i) {
			if (rad2angle[i][0] >= radius)
				break;
		}
		if (i == rad2angle.length)
			i = rad2angle.length - 1;

		// Set the turn direction using the sign of estimated turn rate
		if (i == rad2angle.length - 1) {
			return signum(turn_rate) * rad2angle[rad2angle.length - 1][1];
		} else {
			double diff = rad2angle[i][0] - rad2angle[i + 1][0];
			double weight1 = 1 - (radius - rad2angle[i][0]) / diff;
			double weight2 = 1 - (rad2angle[i + 1][0] - radius) / diff;
			double angle = weight1 * rad2angle[i][1] + weight2
					* rad2angle[i + 1][1];

			// Set the turn direction using the sign of estimated turn rate
			return signum(turn_rate) * angle;
		}
	}

	
	public void initTime() {
		// write starting time to logs
		Calendar calendar = new GregorianCalendar(); // Get starting time
		// nav time is time from start
		this.lastTime = 0;
		// The starting time is saved
		this.timeBefore = System.currentTimeMillis();

		// Log starting-time
		String timeString = String.format(
				"#Start time: %1$ty-%1$tm-%1$td %1$tH:%1$tM:%1$tS:%1$tL\n",
				calendar);
		this.log("nav", timeString);
		this.log("state", timeString);
		this.log("meas", timeString);
		this.log("state",
				"Time\tX\tY\tV\tHeading\tTurn-rate\tX_p\tY_p\tV_p\tHeading_p\tTurn-rate_p\n");
		this.log(
				"meas",
				"Time\tX_GPS\tY_GPS\tV_GPS\tHeading_GPS\tHeading_compass\tTurn-rate_rudder\tV_load\n");

	}


	// initialize sensors and logging etc.
	

	void initSensors() {
		// wait for GPS-fix and set position
		this.initGPS();
		// initialize heading using compass reading
		this.initCompass();

		// Set initial speed and bearing
		this.set_phi(0.1);
		this.set_V(3.0);
	}

	void initWaypoints() {
		//if autopilate is on, wp are read from file
		//if autopilot is off, 
		
		// read way-points from file
		if (this.autoPilot) {
			this.setWaypoints();
		} else {
			while (this.wp.size() == 0 && NavigatorService.operative) {
				// Waiting for waypoints
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
				}

			}

		}

	}

	/*
	 * When a route is set, the pure-pursuit waypoints are initialized and the
	 * Kalman filter matrices are calculated
	 */
	void initNavigation() {
		// If auto-pilot, resume navigation from last visited waypoint
		// Checks if resume is < than length of waypoint list
		if (this.resumeFromWp > 0 && this.autoPilot
				&& this.resumeFromWp < this.wp.size() - 1) {
			this.wp.add(this.resumeFromWp, this.pos());
			this.wpIter = this.wp.iterator();

			// step up to resume position
			for (int i = 0; i < this.resumeFromWp; i++)
				this.wpIter.next();
		} else {
			this.wpIter = this.wp.iterator();
		}

		this.lwp = this.pos();// set lwp to current pos
		this.cwp = this.wpIter.next(); // set cwp to next wp

		// Navigation - calculate wanted turn rate
		double turn_rate = this.getTurnrate();

		// update velocity and heading measurements from load and rudder
		this.updateEncoders(this.load, turn_rate);

		// initialize state-vector
		DenseMatrix64F priorX = new DenseMatrix64F(this.stateDOF, 1, true,
				this.pos()[0], this.pos()[1], this.V(), this.phi(),
				this.turn_rate());

		// initialize process covariace matrix
		DenseMatrix64F priorP = CommonOps.identity(this.stateDOF);

		// Set transition matrix
		DenseMatrix64F F = createF();

		// Set process covariance matrix
		DenseMatrix64F Q = createQ();

		// Set measurement transition matrix
		DenseMatrix64F H = createH();

		kf.configure(F, Q, H);
		kf.setState(priorX, priorP);

		this.R = this.createR();
	}

	public void finish() {
		NavThread.boundIOIOControlService.stopMotor();
		prj.close();
		// Finished
		Log.i(TAG, "Finished waypoint navigation!");
	}

	public void clearWaypointList() {
		this.wp = new ArrayList<double[]>();
	}
	
	//Control functions
	public void setRudderAngle(double angle) {
		this.rudder_angle=angle;		
	}
	
	public void setMotorLoad(double load) {
		this.load=load;
	}
	
	public void setActive(boolean status) {
		this.active = status;
	}
	
	public boolean getActive() {
		return this.active;
	}
	
	public long get_GPS_timestamp() {
		return this.timestamps[0];
	}
	
	public void setAutopilot(boolean status) {
		this.autoPilot = status;
	}
	
	public Bundle getStatus() {
		Bundle data = new Bundle();
		double[] posWgs84 = this.getPosWGS84();
		data.putDouble("lon",posWgs84[0]);
		data.putDouble("lat",posWgs84[1]);
		data.putDouble("speed", this.V());
		data.putDouble("bearing",this.phi());
		data.putDouble("turnrate", this.turn_rate());
		data.putDouble("progress", this.progressEstimate());
		data.putDouble("accurracy",this.getGpsAccuracy());		
		return data;				
	}
	
	public void addWaypointWGS84(double lon, double lat) {
		if (this.autoPilot)
			this.setAutopilot(false);
		WGS84Position wgsPos = new WGS84Position();
		wgsPos.setPos(lon, lat);
		SWEREF99Position rtPos = new SWEREF99Position(wgsPos,
				SWEREF99Position.SWEREFProjection.sweref_99_tm);
		double[] projP = { (double) rtPos.getLongitude(),
				(double) rtPos.getLatitude() };
		this.wp.add(projP);
		}

	// Return position in WGS84 lon,lat
	public double[] getPosWGS84() {
		SWEREF99Position rtPos = new SWEREF99Position(this.pos()[0], this.pos()[1]);
		WGS84Position wgsPos = rtPos.toWGS84();
		double[] projp = { wgsPos.getLongitude(), wgsPos.getLatitude() };
		return projp;
	}
	
	public double[] getCWPWGS84() {
		SWEREF99Position rtPos = new SWEREF99Position(this.cwp[0], this.cwp[1]);
		WGS84Position wgsPos = rtPos.toWGS84();
		double[] projp = { wgsPos.getLongitude(), wgsPos.getLatitude() };
		return projp;
	}
	
	public double getGpsAccuracy() {
		return this.gpsAccuracy;
	}

	
}
