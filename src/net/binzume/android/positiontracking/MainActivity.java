package net.binzume.android.positiontracking;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Vibrator;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements SensorEventListener {
	private OrientationEstimater orientationEstimater = new OrientationEstimater();
	private MotionDetector motionDetector = new MotionDetector();
	private AccelerometerCalibrator calibrator = new AccelerometerCalibrator();
	private Bitmap graphBitmap = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888);

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		calibrator.load();

		final Handler handler = new Handler();
		handler.post(new Runnable() {
			@Override
			public void run() {

				((TextView) findViewById(R.id.heightText)).setText("" + orientationEstimater.getPosition()[1]);

				Canvas canvas = new Canvas(graphBitmap);
				Paint paint = new Paint();
				canvas.drawBitmap(graphBitmap, -1, 0, paint);
				paint.setColor(Color.WHITE);
				canvas.drawLine(graphBitmap.getWidth() - 1, 0, graphBitmap.getWidth() - 1, graphBitmap.getHeight(), paint);

				paint.setColor(Color.GRAY);
				canvas.drawPoint(graphBitmap.getWidth() - 1, 200, paint);

				paint.setColor(Color.BLUE);
				canvas.drawPoint(graphBitmap.getWidth() - 1, -orientationEstimater.getPosition()[1] * 0.2f + 200, paint);

				paint.setColor(Color.GREEN);
				canvas.drawPoint(graphBitmap.getWidth() - 1, -orientationEstimater.getPosition()[2] * 0.2f + 200, paint);

				paint.setColor(Color.RED);
				canvas.drawPoint(graphBitmap.getWidth() - 1, -orientationEstimater.posIntegretedError * 0.2f + 200, paint);

				paint.setColor(Color.MAGENTA);
				canvas.drawPoint(graphBitmap.getWidth() - 1, -orientationEstimater.pressureHeightCurrent * 0.2f + 200, paint);

				((ImageView) findViewById(R.id.graphImage)).setImageBitmap(graphBitmap);

				handler.postDelayed(this, 30);
			}
		});
	}

	@Override
	protected void onStart() {
		super.onStart();
		SensorManager sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
		Sensor sensorAccel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
		Sensor sensorGyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
		Sensor sensorMag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
		Sensor sensorPressure = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);

		sensorManager.registerListener(this, sensorAccel, SensorManager.SENSOR_DELAY_FASTEST);
		sensorManager.registerListener(this, sensorGyro, SensorManager.SENSOR_DELAY_FASTEST);
		sensorManager.registerListener(this, sensorMag, SensorManager.SENSOR_DELAY_FASTEST);
		sensorManager.registerListener(this, sensorPressure, SensorManager.SENSOR_DELAY_FASTEST);

		findViewById(R.id.calibrateButton).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				calibrator.start(new AccelerometerCalibrator.OnFinishListener() {
					@Override
					public void onFinish(boolean ok, AccelerometerCalibrator calibrator) {
						Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
						if (ok) {
							vibrator.vibrate(new long[] { 0, 100, 100, 100 }, -1);
						} else {
							vibrator.vibrate(new long[] { 0, 400 }, -1);
						}
						Toast.makeText(MainActivity.this, ok ? "OK!" : "Unstable", Toast.LENGTH_LONG).show();
					}
				});
			}
		});

		findViewById(R.id.resetButton).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				v.postDelayed(new Runnable() {
					@Override
					public void run() {
						orientationEstimater.reset();
					}
				}, 300);
			}
		});

	}

	@Override
	protected void onStop() {
		SensorManager sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
		sensorManager.unregisterListener(this);
		super.onStop();
	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		calibrator.onSensorEvent(event);
		orientationEstimater.onSensorEvent(event);
		motionDetector.onSensorEvent(event);
	}

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {
	}

}
