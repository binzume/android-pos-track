package net.binzume.android.positiontracking;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.os.Environment;
import android.util.Log;

public class AccelerometerCalibrator {

	public interface OnFinishListener {
		public void onFinish(boolean ok, AccelerometerCalibrator calibrator);
	}

	private final static float G = SensorManager.GRAVITY_EARTH;

	private static final int START_DELAY = 1000;
	private static final int CALIBRATE_DURATION = 2000;

	public static final int MODE_READY = 0;
	public static final int MODE_PENDING = 1;
	public static final int MODE_WAIT_STABLE = 2;
	public static final int MODE_CALIBRATING = 3;

	private int mode = MODE_READY;
	private final Vector3f offset = new Vector3f();
	private final Vector3f scale = new Vector3f(1, 1, 1);

	private boolean calibrating = false;
	private long startTime;
	private OnFinishListener onFinishListener;

	private Vector3f sum = new Vector3f();
	private Vector3f sum2 = new Vector3f();
	private Vector3f[] calibrateArray = new Vector3f[6];
	private int samples;

	public void start(OnFinishListener onFinish) {
		calibrating = true;
		startTime = System.currentTimeMillis();
		onFinishListener = onFinish;
		mode = MODE_PENDING;
	}

	public void cancel() {
		calibrating = false;
		onFinishListener = null;
	}

	public void load() {
		try {
			load(Environment.getExternalStorageDirectory() + "/sensor_calib.txt");
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void load(String configPath) throws IOException {
		File f = new File(configPath);
		BufferedReader reader = new BufferedReader(new FileReader(f));
		String line;
		while ((line = reader.readLine()) != null) {
			String kv[] = line.split("=", 2);
			if (kv.length < 2)
				continue;
			if (kv[0].equals("accel_offset")) {
				offset.set(parseVec(kv[1]).array());
			}
			if (kv[0].equals("accel_scale")) {
				scale.set(parseVec(kv[1]).array());
			}
		}
		reader.close();
	}

	public void save(String configPath) throws IOException {
		File f = new File(configPath);
		BufferedWriter writer = new BufferedWriter(new FileWriter(f));
		writer.write("accel_offset=" + offset + "\n");
		writer.write("accel_scale=" + scale + "\n");
		writer.close();
	}

	private Vector3f parseVec(String v) {
		String a[] = v.replaceAll("[\\(\\)\\s]+", "").split(",");
		if (a.length == 3) {
			return new Vector3f(Float.parseFloat(a[0]), Float.parseFloat(a[1]), Float.parseFloat(a[2]));
		}
		return null;
	}

	public boolean isCalibrationg() {
		return calibrating;
	}

	public void onSensorEvent(SensorEvent event) {
		long t = System.currentTimeMillis() - startTime;

		if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) {
			return;
		}

		if (mode != MODE_CALIBRATING) {
			event.values[0] = (event.values[0] + offset.values[0]) * scale.values[0];
			event.values[1] = (event.values[1] + offset.values[1]) * scale.values[1];
			event.values[2] = (event.values[2] + offset.values[2]) * scale.values[2];
		}

		if (mode == MODE_PENDING && t >= START_DELAY) {
			mode = MODE_WAIT_STABLE;
			startTime = System.currentTimeMillis();
			return;
		}
		if (mode == MODE_WAIT_STABLE && t >= START_DELAY) {
			mode = MODE_CALIBRATING;
			startTime = System.currentTimeMillis();
			sum.set(0, 0, 0);
			sum2.set(0, 0, 0);
			samples = 0;
			return;
		}
		if (mode == MODE_CALIBRATING) {
			sum.values[0] += event.values[0];
			sum.values[1] += event.values[1];
			sum.values[2] += event.values[2];
			sum2.values[0] += event.values[0] * event.values[0];
			sum2.values[1] += event.values[1] * event.values[1];
			sum2.values[2] += event.values[2] * event.values[2];
			samples++;
		}
		if (mode == MODE_CALIBRATING && t > CALIBRATE_DURATION) {
			Log.d("AccelerometerCalibrator", "samples:" + samples);
			sum.scale(1.0f / samples);
			sum2.scale(1.0f / samples);
			sum2.values[0] -= sum.values[0] * sum.values[0];
			sum2.values[1] -= sum.values[1] * sum.values[1];
			sum2.values[2] -= sum.values[2] * sum.values[2];
			Log.d("AccelerometerCalibrator", " E:" + sum + " g:" + sum.length() + " sigma2:" + sum2 + "(" + sum2.length() + ")");

			if (onFinishListener != null) {
				if (sum2.length() < 0.002f) {
					for (int i = 0; i < 3; i++) {
						if (sum.values[i] > sum.length() * 0.7f) {
							calibrateArray[i * 2] = new Vector3f(sum);
						} else if (sum.values[i] < -sum.length() * 0.7f) {
							calibrateArray[i * 2 + 1] = new Vector3f(sum);
						}
					}
					if (calc()) {
						try {
							save(Environment.getExternalStorageDirectory() + "/sensor_calib.txt");
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				}
				onFinishListener.onFinish(sum2.length() < 0.002f, this);
				calibrating = false;
				mode = MODE_READY;
			}
		}
	}

	private boolean calc() {
		Vector3f d = new Vector3f();
		Vector3f v1 = new Vector3f();
		Vector3f v2 = new Vector3f();
		for (int k = 0; k < 10; k++) {
			for (int i = 0; i < 3; i++) {
				if (calibrateArray[i * 2 + 1] != null && calibrateArray[i * 2] != null) {
					Vector3f.add(v1, calibrateArray[i * 2], d);
					Vector3f.add(v2, calibrateArray[i * 2 + 1], d);
					d.values[i] += (v2.length2() - v1.length2()) / 2 / (v1.values[i] - v2.values[i]);
				}
			}
			Log.d("AccelerometerCalibrator", "d=" + d);
		}
		float scale = 0;
		int count = 0;
		for (int i = 0; i < 6; i++) {
			if (calibrateArray[i] != null) {
				scale += Vector3f.add(v1, calibrateArray[i], d).length();
				count++;
			}
		}
		scale = (count > 0) ? G / (scale / count) : 1.0f;
		Log.d("AccelerometerCalibrator", "scale=" + scale);

		if (count == 6) {
			this.scale.set(scale, scale, scale);
			this.offset.set(d.array());
			return true;
		}

		return false;
	}
}
