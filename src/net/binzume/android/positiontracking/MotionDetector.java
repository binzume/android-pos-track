package net.binzume.android.positiontracking;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;

public class MotionDetector {
	private final static float G = SensorManager.GRAVITY_EARTH;
	private float[] gravHistory = new float[10];
	private int gravHistoryPos = 0;
	private Vector3f acc = new Vector3f();

	public boolean jump = false;

	public void onSensorEvent(SensorEvent event) {
		if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
			acc.set(event.values);
			gravHistoryPos = (gravHistoryPos + 1) % gravHistory.length;
			gravHistory[gravHistoryPos] = acc.length() / G;

			// detect jump.
			jump = false;
			if (gravHistory[gravHistoryPos] < 0.4f) {
				if (gravHistory[(gravHistoryPos + 5) % gravHistory.length] > 1.5f) {
					// jump!
					jump = true;
				}
			}
		}

	}
}
