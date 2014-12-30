package net.binzume.android.positiontracking;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.opengl.Matrix;
import android.util.Log;

public class OrientationEstimater {

	private final static float G = SensorManager.GRAVITY_EARTH;
	private final static float PI = (float) Math.PI;

	private final float[] outputRotationMatrix = new float[16];
	public float[] rotationMatrix = new float[16];
	public final float[] rotationMatrix_t1 = new float[16];
	public float[] rotationMatrix_t2 = new float[16];

	public float[] rotationMatrix_d = new float[16];

	// configurations
	private boolean landscape = true; // swapXY
	private boolean zeroSnap = true;
	private boolean applyPressureHeight = false;

	private float[] mag = new float[3];
	private long lastGyroTime = 0;
	private long lastAccelTime = 0;
	private long resetTime = 0;

	private final Vector3f gravityVecI = new Vector3f(0, 1, 0);
	private final Vector3f tmpVec = new Vector3f();
	private final Vector3f accVec = new Vector3f();
	private final Vector3f accVecN = new Vector3f();
	private final Vector3f vVec = new Vector3f();
	private final Vector3f posVec = new Vector3f();
	private final Vector3f gyroVec = new Vector3f();
	public float posIntegretedError = 0;

	private float[] outputPosition = new float[3];
	private float[] orientation = new float[3]; // [yaw roll pitch] (rad)
	private float[] position = new float[3]; // beta

	private float pressureHeightErrorHigh = 250f; // mm
	public float pressureHeightCurrent = 0; // mm (from HeightBase)
	private float pressureHeightErrorLow = 250f; // mm
	private float pressureHeightErrorFactor = 0.000f; // m/s
	private float pressureHeightBase = 1013; // hPa
	private long pressureHeightErrorBaseTime = 0; // ns

	private final float[] pressHistory = new float[16];
	private int pressHistoryCount = 0;

	private final float[] accHistory = new float[8];
	private int accHistoryCount = 0;

	private int eventCount = 0;

	public OrientationEstimater() {
		reset();
	}

	public void reset() {
		Log.d("OrientationEstimater", "reset");
		resetTime = System.currentTimeMillis();
		posIntegretedError = 0;
		Matrix.setIdentityM(rotationMatrix, 0);
		Matrix.setIdentityM(rotationMatrix_d, 0);
		Matrix.setIdentityM(outputRotationMatrix, 0);
		position[0] = 0;
		position[1] = 0;
		position[2] = 0;
		posVec.set(0, 0, 0);
		vVec.set(0, 0, 0);
		pressureHeightErrorBaseTime = 0;
	}

	/**
	 * current orientation array If require matrix of OpenGL, it is necessary to
	 * rotate in the following order: 1. roll 2. pitch 3. yaw
	 * 
	 * @return float array [x,y,z]
	 */
	public float[] getCurrentOrientation() {
		SensorManager.getOrientation(rotationMatrix, orientation);
		return orientation;
	}

	/**
	 * Current rotation matrix.
	 * 
	 * @return
	 */
	public float[] getRotationMatrix() {
		Matrix.invertM(rotationMatrix_t1, 0, rotationMatrix, 0);
		Matrix.multiplyMM(outputRotationMatrix, 0, rotationMatrix_t1, 0, rotationMatrix_d, 0);
		// Matrix.translateM(outputRotationMatrix, 0, 0f, posVec.values[1] * -0.01f, 0f);
		return outputRotationMatrix;
	}

	/**
	 * @return float array [x,y,z] unit:mm
	 */
	public float[] getPosition() {
		outputPosition[0] = position[0] + posVec.values[0];
		outputPosition[1] = position[1] + posVec.values[1];
		outputPosition[2] = position[2] + posVec.values[2];
		return outputPosition;
	}

	/**
	 * @return float array [x,y,z] unit:mm
	 */
	public float[] getTranslation() {
		outputPosition[0] = position[0];
		outputPosition[1] = position[1];
		outputPosition[2] = position[2];
		return outputPosition;
	}

	public void rotateInDisplay(float dx, float dy) {

		float l = (float) Math.sqrt(dx * dx + dy * dy) * 0.002f;
		if (l > 0.001f) {
			//  OutRot = a * Rot * D = Rot * b * D
			//  b * D = Rot^-1 * a * OutRot
			//Matrix.invertM(rotationMatrix_t1, 0, rotationMatrix, 0);
			System.arraycopy(rotationMatrix, 0, rotationMatrix_t1, 0, 16);
			Matrix.rotateM(rotationMatrix_t1, 0, l * 180 / PI, dy, dx, 0);
			Matrix.multiplyMM(rotationMatrix_t2, 0, rotationMatrix_t1, 0, outputRotationMatrix, 0);

			// swap(d,t2)
			float tm[] = rotationMatrix_t2;
			rotationMatrix_t2 = rotationMatrix_d;
			rotationMatrix_d = tm;
		}
	}

	public void translateInDisplay(float[] pos, float dx, float dy, float dz) {
		float scale = 0.1f;

		float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) * scale;
		if (l > 0.001f) {
			float[] a = new float[] { -dx * scale, dy * scale, -dz * scale, 1 };
			float[] b = new float[4];
			Matrix.invertM(rotationMatrix_t1, 0, outputRotationMatrix, 0);
			Matrix.multiplyMV(b, 0, rotationMatrix_t1, 0, a, 0);

			pos[0] += b[0];
			pos[1] += b[1];
			pos[2] += b[2];
		}
	}

	public void rotate(float x, float y) {
		Matrix.rotateM(rotationMatrix_d, 0, x, 1, 0, 0);
		Matrix.rotateM(rotationMatrix_d, 0, y, 0, 1, 0);
	}

	public boolean isReady() {
		return lastAccelTime != 0 && lastGyroTime != 0;
	}

	public void onSensorEvent(SensorEvent event) {

		if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
			if (landscape) {
				accVecN.set(-event.values[1], event.values[0], -event.values[2]);
			} else {
				accVecN.set(event.values[0], event.values[1], event.values[2]);
			}

			float dt = (event.timestamp - lastAccelTime) * 0.000000001f; // dt(sec)
			if (lastAccelTime > 0 && dt < 0.5f && System.currentTimeMillis() - resetTime > 500) {

				// m/s^2
				Matrix.multiplyMV(accVec.values, 0, rotationMatrix, 0, accVecN.values, 0); // rotMatrix * groundA
				accVec.values[1] -= G;

				// velocity(mm/s)
				vVec.values[0] += accVec.values[0] * dt * 1000;
				vVec.values[1] += accVec.values[1] * dt * 1000;
				vVec.values[2] += accVec.values[2] * dt * 1000;

				// velocity limit
				if (vVec.length() > 5000) {
					vVec.scale(0.95f);
				}

				boolean resting = false;
				accHistory[(accHistoryCount++) % accHistory.length] = accVec.length();
				if (accHistoryCount > accHistory.length) {
					final float l = accVec.length();
					float min = l, max = l, sum = 0;
					for (float a : accHistory) {
						sum += a;
						if (a > max)
							max = a;
						if (a < min)
							min = a;
					}
					if (sum < 2.5f && max - min < 0.2f) {
						resting = true;
						vVec.scale(0.9f);
						if (max - min < 0.1f) {
							vVec.set(0, 0, 0);
						}
					}
				}

				// position(mm)
				if (vVec.length() > 0.5f) {
					posVec.values[0] += vVec.values[0] * dt;
					posVec.values[1] += vVec.values[1] * dt;
					posVec.values[2] += vVec.values[2] * dt;
				}
				posIntegretedError += vVec.length() * 0.0001f + accVec.length() * 0.1f;

				// position limit
				if (posVec.values[0] > 1000) {
					posVec.values[0] *= 0.9f;
				} else if (posVec.values[0] < -1000) {
					posVec.values[0] *= 0.9f;
				}

				if (posVec.values[2] > 1000) {
					posVec.values[2] *= 0.9f;
				} else if (posVec.values[2] < -1000) {
					posVec.values[2] *= 0.9f;
				}

				if (posVec.values[1] < -1800) {
					posVec.values[1] *= 0.8f;
				} else if (posVec.values[1] > 1000) {
					posVec.values[1] *= 0.8f;
				}

				// snap to 0
				if (resting && zeroSnap && posIntegretedError > 0) {
					if (posIntegretedError > 0) {
						tmpVec.set(posVec.array());
						posVec.scale(0.995f);
						posIntegretedError -= tmpVec.sub(posVec).length();
					}
				}

				eventCount++;
				if (eventCount % 20 == 0) {
					Log.d("OrientationEstimater", "" + event.timestamp + ", " + posVec + ", " + vVec + ", " + accVec + ", AL:" + accVec.length()
							+ (resting ? " R" : ""));
				}

				// Log.d("Sensor", "TYPE_PRESSURE pressureHeightCurrent: " + pressureHeightCurrent + ", " + posVec.values[1]);
				if (pressureHeightErrorBaseTime > 0 && applyPressureHeight) {
					float eh = pressureHeightErrorHigh + pressureHeightErrorFactor * ((event.timestamp - pressureHeightErrorBaseTime) * 0.000001f);
					float el = pressureHeightErrorLow + pressureHeightErrorFactor * ((event.timestamp - pressureHeightErrorBaseTime) * 0.000001f);
					if (posVec.values[1] > pressureHeightCurrent + eh) {
						posVec.values[1] += (pressureHeightCurrent + eh - posVec.values[1]) * 0.1;
						if (vVec.values[1] > 0) {
							vVec.values[1] -= Math.abs(vVec.values[1]) * 0.3f;
						}
					}
					if (posVec.values[1] < pressureHeightCurrent - el) {
						posVec.values[1] += (pressureHeightCurrent - el - posVec.values[1]) * 0.1;
						if (vVec.values[1] < 0) {
							vVec.values[1] += Math.abs(vVec.values[1]) * 0.3f;
						}
					}
				}
			}

			accVec.set(accVecN.values);
			accVec.normalize();
			lastAccelTime = event.timestamp;
		} else if (event.sensor.getType() == Sensor.TYPE_PRESSURE) {
			final float v = event.values[0];
			pressHistory[pressHistoryCount++ % pressHistory.length] = v;
			if (pressHistoryCount >= pressHistory.length) {
				float min = v, max = v;
				for (float vv : pressHistory) {
					if (max < vv)
						max = vv;
					if (min > vv)
						min = vv;
				}
				if (max - min > 0.025f) {
					Log.d("Sensor", "TYPE_PRESSURE change " + v + "min:max = " + min + ":" + max);
				}
				if (pressureHeightErrorBaseTime == 0) {
					pressureHeightErrorBaseTime = event.timestamp;
					pressureHeightBase = (max + min) / 2.0f;
				}
				pressureHeightCurrent = (pressureHeightBase - v) * 8300f; // todo: h=153.8*(temp+273.2)*(1-(v/ATOM_BASE)^0.1902) 
				// float eh = pressureHeightErrorHigh + pressureHeightErrorFactor * ((event.timestamp - pressureHeightErrorBaseTime) * 0.000001f);
				// float el = pressureHeightErrorLow + pressureHeightErrorFactor * ((event.timestamp - pressureHeightErrorBaseTime) * 0.000001f);
				// Log.d("Sensor","TYPE_PRESSURE pressureHeightCurrent " + pressureHeightCurrent );
			}
		} else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
			//Log.d("Sensor","TYPE_MAGNETIC_FIELD " + event.values[0] + "," + event.values[1] + "," + event.values[2]+ " ("+  event.timestamp);
			if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
				//return;
			}
			System.arraycopy(event.values, 0, mag, 0, 3);
			if (landscape) {
				mag[0] = -event.values[1];
				mag[1] = event.values[0];
			}
		} else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
			if (lastGyroTime > 0) {
				float dt = (event.timestamp - lastGyroTime) * 0.000000001f;
				if (landscape) {
					gyroVec.set(event.values[1], -event.values[0], event.values[2]);
				} else {
					gyroVec.set(event.values[0], event.values[1], event.values[2]);
				}
				Matrix.rotateM(rotationMatrix, 0, gyroVec.length() * dt * 180 / PI, gyroVec.array()[0], gyroVec.array()[1], gyroVec.array()[2]);
				posIntegretedError += gyroVec.length() * dt * 5.0f; // TODO: error ratio control.
			}
			lastGyroTime = event.timestamp;
		}

		// adjust ground vector.
		if (gyroVec.length() < 0.3f && Math.abs(accVecN.length() - G) < 0.5f) {
			// estimated ground vec.
			Matrix.multiplyMV(tmpVec.array(), 0, rotationMatrix, 0, accVec.values, 0);
			float theta = (float) Math.acos(tmpVec.dot(gravityVecI));
			if (theta > 0) {
				float[] cross = tmpVec.cross(gravityVecI).normalize().array();
				float factor = (System.currentTimeMillis() - resetTime < 500) ? 0.9f : 0.0005f;

				//Matrix.rotateM(rotationMatrix, 0, theta * 180 / PI * factor, cross[0], cross[1], cross[2]);
				Matrix.setRotateM(rotationMatrix_t1, 0, theta * 180 / PI * factor, cross[0], cross[1], cross[2]);
				Matrix.multiplyMM(rotationMatrix_t2, 0, rotationMatrix_t1, 0, rotationMatrix, 0);
				float tm[] = rotationMatrix_t2;
				rotationMatrix_t2 = rotationMatrix;
				rotationMatrix = tm;
			}
		}
	}
}
