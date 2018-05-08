/* BLE demo: Demonstrate how to bi-directionally communicate with the Duo board through BLE. In this
 * example code, it shows how to send digital and analog data from the Android app to the Duo board
 * and how to receive data from the board.
 *
 * The app is built based on the example code provided by the RedBear Team:
 * https://github.com/RedBearLab/Android
 */

package com.example.lianghe.android_ble_advanced;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.IBinder;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.example.lianghe.android_ble_advanced.BLE.RBLGattAttributes;
import com.example.lianghe.android_ble_advanced.BLE.RBLService;
import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.skydoves.colorpickerpreference.ColorEnvelope;
import com.skydoves.colorpickerpreference.ColorListener;
import com.skydoves.colorpickerpreference.ColorPickerView;

import org.apache.commons.math3.stat.descriptive.SummaryStatistics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity implements SensorEventListener {
    // Define the device name and the length of the name
    // Note the device name and the length should be consistent with the ones defined in the Duo sketch
    private String mTargetDeviceName = "JEESHNM";
    private int mNameLen = 0x08;

    private final static String TAG = MainActivity.class.getSimpleName();
    private boolean overridePhysicalControls = true;

    // Declare all variables associated with the UI components
    private Button mConnectBtn = null;
    private TextView mDeviceName = null;
    private TextView mRssiValue = null;
    private TextView mUUID = null;
    private SeekBar mPWMSeekBar;
    private String mBluetoothDeviceName = "";
    private String mBluetoothDeviceUUID = "";
    private ColorPickerView colorPickerView = null;
    private TextView txtColorValue = null;
    private CheckBox chkOverridePhysicalControls = null;
    private CheckBox chkSyncWithSteptracker = null;

    private SensorManager mSensorManager;
    private Sensor mAccelerometer;
    private Sensor mStepCounter;

    private PieChart pieChart;
    private Timer pieChartRefreshTimer;

    private int readingCount = 0;
    private int peakCount = 0;
    private int stepCount = 0;
    private int calculatedStepCount = 0;
    private int initialCounterValue = 0;
    private int LAG_SIZE = 5;
    private int DATA_SAMPLING_SIZE = 15;
    private List<Double> zscoreCalculationValues = new ArrayList<>();
    private float rawAccelValues[] = new float[3];
    private float[] gravity = {0, 0, 0};

    private int goal = 1000;

    // Declare all Bluetooth stuff
    private BluetoothGattCharacteristic mCharacteristicTx = null;
    private RBLService mBluetoothLeService;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothDevice mDevice = null;
    private String mDeviceAddress;

    private boolean flag = true;
    private boolean mConnState = false;
    private boolean mScanFlag = false;

    private byte[] mData = new byte[3];
    private static final int REQUEST_ENABLE_BT = 1;
    private static final long SCAN_PERIOD = 2000;   // millis

    final private static char[] hexArray = { '0', '1', '2', '3', '4', '5', '6',
            '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };

    public int getRemainingGoal(){
        return this.goal - this.calculatedStepCount;
    }

    // Process service connection. Created by the RedBear Team
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName,
                                       IBinder service) {
            mBluetoothLeService = ((RBLService.LocalBinder) service)
                    .getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                finish();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothLeService = null;
        }
    };

    private void setButtonDisable() {
        flag = false;
        mConnState = false;

        mPWMSeekBar.setEnabled(flag);
        mConnectBtn.setText("Connect");
        mRssiValue.setText("");
        mDeviceName.setText("");
        mUUID.setText("");
    }

    private void setButtonEnable() {
        flag = true;
        mConnState = true;

        mPWMSeekBar.setEnabled(flag);
        mConnectBtn.setText("Disconnect");
    }

    // Process the Gatt and get data if there is data coming from Duo board. Created by the RedBear Team
    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (RBLService.ACTION_GATT_DISCONNECTED.equals(action)) {
                Toast.makeText(getApplicationContext(), "Disconnected",
                        Toast.LENGTH_SHORT).show();
                setButtonDisable();
            } else if (RBLService.ACTION_GATT_SERVICES_DISCOVERED
                    .equals(action)) {
                Toast.makeText(getApplicationContext(), "Connected",
                        Toast.LENGTH_SHORT).show();

                getGattService(mBluetoothLeService.getSupportedGattService());
            } else if (RBLService.ACTION_DATA_AVAILABLE.equals(action)) {
                mData = intent.getByteArrayExtra(RBLService.EXTRA_DATA);

                //readAnalogInValue(mData);
            } else if (RBLService.ACTION_GATT_RSSI.equals(action)) {
                displayData(intent.getStringExtra(RBLService.EXTRA_DATA));
            }
        }
    };

    // Display the received RSSI on the interface
    private void displayData(String data) {
        if (data != null) {
            mRssiValue.setText(data);
            mDeviceName.setText(mBluetoothDeviceName);
            mUUID.setText(mBluetoothDeviceUUID);
        }
    }

    // Display the received Analog/Digital read on the interface
   /* private void readAnalogInValue(byte[] data) {
        for (int i = 0; i < data.length; i += 3) {
            if (data[i] == 0x0A) {
                if (data[i + 1] == 0x01)
                    mDigitalInBtn.setChecked(false);
                else
                    mDigitalInBtn.setChecked(true);
            } else if (data[i] == 0x0B) {
                int Value;

                Value = ((data[i + 1] << 8) & 0x0000ff00)
                        | (data[i + 2] & 0x000000ff);

                mAnalogInValue.setText(Value + "");
            }
        }
    }*/

    // Get Gatt service information for setting up the communication
    private void getGattService(BluetoothGattService gattService) {
        if (gattService == null)
            return;

        setButtonEnable();
        startReadRssi();

        mCharacteristicTx = gattService
                .getCharacteristic(RBLService.UUID_BLE_SHIELD_TX);

        BluetoothGattCharacteristic characteristicRx = gattService
                .getCharacteristic(RBLService.UUID_BLE_SHIELD_RX);
        mBluetoothLeService.setCharacteristicNotification(characteristicRx,
                true);
        mBluetoothLeService.readCharacteristic(characteristicRx);
    }

    // Start a thread to read RSSI from the board
    private void startReadRssi() {
        new Thread() {
            public void run() {

                while (flag) {
                    mBluetoothLeService.readRssi();
                    try {
                        sleep(500);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            };
        }.start();
    }

    // Scan all available BLE-enabled devices
    private void scanLeDevice() {
        new Thread() {

            @Override
            public void run() {
                mBluetoothAdapter.startLeScan(mLeScanCallback);

                try {
                    Thread.sleep(SCAN_PERIOD);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                mBluetoothAdapter.stopLeScan(mLeScanCallback);
            }
        }.start();
    }

    // Callback function to search for the target Duo board which has matched UUID
    // If the Duo board cannot be found, debug if the received UUID matches the predefined UUID on the board
    private BluetoothAdapter.LeScanCallback mLeScanCallback = new BluetoothAdapter.LeScanCallback() {

        @Override
        public void onLeScan(final BluetoothDevice device, final int rssi,
                             final byte[] scanRecord) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    byte[] serviceUuidBytes = new byte[16];
                    String serviceUuid = "";
                    for (int i = (21+mNameLen), j = 0; i >= (6+mNameLen); i--, j++) {
                        serviceUuidBytes[j] = scanRecord[i];
                    }
                    /*
                     * This is where you can test if the received UUID matches the defined UUID in the Arduino
                     * Sketch and uploaded to the Duo board: 0x713d0000503e4c75ba943148f18d941e.
                     */
                    serviceUuid = bytesToHex(serviceUuidBytes);
                    if (stringToUuidString(serviceUuid).equals(
                            RBLGattAttributes.BLE_SHIELD_SERVICE
                                    .toUpperCase(Locale.ENGLISH)) && device.getName().equals(mTargetDeviceName)) {
                        mDevice = device;
                        mBluetoothDeviceName = mDevice.getName();
                        mBluetoothDeviceUUID = serviceUuid;
                    }
                }
            });
        }
    };

    // Convert an array of bytes into Hex format string
    private String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        int v;
        for (int j = 0; j < bytes.length; j++) {
            v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    // Convert a string to a UUID format
    private String stringToUuidString(String uuid) {
        StringBuffer newString = new StringBuffer();
        newString.append(uuid.toUpperCase(Locale.ENGLISH).substring(0, 8));
        newString.append("-");
        newString.append(uuid.toUpperCase(Locale.ENGLISH).substring(8, 12));
        newString.append("-");
        newString.append(uuid.toUpperCase(Locale.ENGLISH).substring(12, 16));
        newString.append("-");
        newString.append(uuid.toUpperCase(Locale.ENGLISH).substring(16, 20));
        newString.append("-");
        newString.append(uuid.toUpperCase(Locale.ENGLISH).substring(20, 32));

        return newString.toString();
    }


    private void TimerMethod() {
        //This method is called directly by the timer
        //and runs in the same thread as the timer.

        //We call the method that will work with the UI
        //through the runOnUiThread method.
        this.runOnUiThread(Timer_Tick);
    }

    private Runnable Timer_Tick = new Runnable() {
        public void run() {
            RefreshDataSet();
        }
    };

    private void RefreshDataSet() {

        Log.d(TAG, "addDataSet started");

        ArrayList<PieEntry> yEntrys = new ArrayList<>();

        yEntrys.add(new PieEntry(this.calculatedStepCount , "Total Steps Taken"));
        yEntrys.add(new PieEntry(this.getRemainingGoal() , "Remaining Steps"));

        //create the data set
        PieDataSet pieDataSet = new PieDataSet(yEntrys, "");
        pieDataSet.setSliceSpace(2);
        pieDataSet.setValueTextSize(5);
        pieDataSet.setValueTextColor(Color.WHITE);

        //add colors to dataset
        ArrayList<Integer> colors = new ArrayList<>();
        colors.add(Color.GRAY);
        colors.add(Color.BLUE);
        pieDataSet.setColors(colors);

        //add legend to chart
        Legend legend = pieChart.getLegend();
        legend.setForm(Legend.LegendForm.CIRCLE);
        legend.setVerticalAlignment(Legend.LegendVerticalAlignment.TOP);
        legend.setHorizontalAlignment(Legend.LegendHorizontalAlignment.RIGHT);
        legend.setOrientation(Legend.LegendOrientation.VERTICAL);
        legend.setDrawInside(false);

        //create pie data object
        PieData pieData = new PieData(pieDataSet);
        pieChart.setData(pieData);
        pieChart.invalidate();
    }

    private void StartSensor() {
        mSensorManager.registerListener(
                this,
                mAccelerometer,
                SensorManager.SENSOR_DELAY_NORMAL);
        mSensorManager.registerListener(
                this,
                mStepCounter,
                SensorManager.SENSOR_DELAY_FASTEST);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Associate all UI components with variables
        mConnectBtn = (Button) findViewById(R.id.connectBtn);
        mDeviceName = (TextView) findViewById(R.id.deviceName);
        mRssiValue = (TextView) findViewById(R.id.rssiValue);
        mPWMSeekBar = (SeekBar) findViewById(R.id.PWMSeekBar);
        mUUID = (TextView) findViewById(R.id.uuidValue);
        colorPickerView = (ColorPickerView) findViewById(R.id.colorPickerView);
        txtColorValue = (TextView) findViewById(R.id.txtColorValue);
        chkOverridePhysicalControls = (CheckBox) findViewById(R.id.chkBoxOverrideCircuit);
        chkSyncWithSteptracker =(CheckBox) findViewById(R.id.chkSyncSteptracker);

        pieChart = findViewById(R.id.progressChart);

        Description description = new Description();
        description.setText("");

        pieChart.setDescription(description);
        pieChart.setRotationEnabled(true);
        pieChart.setHoleRadius(35f);
        pieChart.setTransparentCircleAlpha(0);
        pieChart.setCenterText("Step Tracker");
        pieChart.setCenterTextSize(8);

        pieChartRefreshTimer = new Timer();
        pieChartRefreshTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                TimerMethod();
            }

        }, 0, 200);

        mSensorManager = (SensorManager) this.getSystemService(Context.SENSOR_SERVICE);
        mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mStepCounter  = mSensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);

        this.StartSensor();


        // Connection button click event
        mConnectBtn.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                if (mScanFlag == false) {
                    // Scan all available devices through BLE
                    scanLeDevice();

                    Timer mTimer = new Timer();
                    mTimer.schedule(new TimerTask() {

                        @Override
                        public void run() {
                            if (mDevice != null) {
                                mDeviceAddress = mDevice.getAddress();
                                mBluetoothLeService.connect(mDeviceAddress);
                                mScanFlag = true;
                            } else {
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        Toast toast = Toast
                                                .makeText(
                                                        MainActivity.this,
                                                        "Couldn't search Ble Shiled device!",
                                                        Toast.LENGTH_SHORT);
                                        toast.setGravity(0, 0, Gravity.CENTER);
                                        toast.show();
                                    }
                                });
                            }
                        }
                    }, SCAN_PERIOD);
                }

                System.out.println(mConnState);
                if (mConnState == false) {
                    mBluetoothLeService.connect(mDeviceAddress);
                } else {
                    mBluetoothLeService.disconnect();
                    mBluetoothLeService.close();
                    setButtonDisable();
                }
            }
        });

        // Send data to Duo board
        // It has three bytes: maker, data value, reserved
        /* mDigitalOutBtn.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {


            @Override
            public void onCheckedChanged(CompoundButton buttonView,
                                         boolean isChecked) {
                byte buf[] = new byte[]{(byte) 0x01, (byte) 0x00, (byte) 0x00};

                if (isChecked == true)
                    buf[1] = 0x01;
                else
                    buf[1] = 0x00;

                mCharacteristicTx.setValue(buf);
                mBluetoothLeService.writeCharacteristic(mCharacteristicTx);
            }
        });

        mAnalogInBtn.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {

            @Override
            public void onCheckedChanged(CompoundButton buttonView,
                                         boolean isChecked) {
                byte[] buf = new byte[]{(byte) 0xA0, (byte) 0x00, (byte) 0x00};

                if (isChecked == true)
                    buf[1] = 0x01;
                else
                    buf[1] = 0x00;

                mCharacteristicTx.setValue(buf);
                mBluetoothLeService.writeCharacteristic(mCharacteristicTx);
            }
        });*/

        // Configure the PWM Seekbar
        mPWMSeekBar.setEnabled(false);
        mPWMSeekBar.setMax(255);
        mPWMSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress,
                                          boolean fromUser) {
                byte[] buf = new byte[]{(byte) 0x02, (byte) 0x00, (byte) 0x00};

                buf[1] = (byte) mPWMSeekBar.getProgress();

                mCharacteristicTx.setValue(buf);
                mBluetoothLeService.writeCharacteristic(mCharacteristicTx);
            }
        });

        // Bluetooth setup. Created by the RedBear team.
        if (!getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "Ble not supported", Toast.LENGTH_SHORT)
                    .show();
            finish();
        }

        final BluetoothManager mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, "Ble not supported", Toast.LENGTH_SHORT)
                    .show();
            finish();
            return;
        }

        Intent gattServiceIntent = new Intent(MainActivity.this,
                RBLService.class);
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);

       colorPickerView.setColorListener(new ColorListener() {
            @Override
            public void onColorSelected(ColorEnvelope colorEnvelope) {
                int[] rgb = colorEnvelope.getColorRGB();
                String rgbValues = "RGB\n";
                byte buf[] = new byte[]{(byte) 0x05, (byte) 0x00, (byte) 0x00, (byte) 0x00,(byte) 0x00};
                for (int i = 0; i < rgb.length; i++) {
                    rgbValues = rgbValues + String.valueOf(rgb[i]) + "\n";
                    buf[i + 1] = (byte) rgb[i];
                }

                if(mCharacteristicTx!=null) {
                    mCharacteristicTx.setValue(buf);
                    mBluetoothLeService.writeCharacteristic(mCharacteristicTx);
                }
                txtColorValue.setText(rgbValues);
            }
        });

        chkOverridePhysicalControls.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                //is chkIos checked?
               overridePhysicalControls = chkOverridePhysicalControls.isChecked();

                byte buf[] = new byte[]{(byte) 0x06, (byte) 0x00};
                if(overridePhysicalControls) {
                    buf[buf.length-1]= (byte)1;
                }
                else {
                    buf[buf.length - 1] = (byte)0;
                }

                if(mCharacteristicTx!=null) {
                    mCharacteristicTx.setValue(buf);
                    mBluetoothLeService.writeCharacteristic(mCharacteristicTx);
                }
            }
        });

        chkSyncWithSteptracker.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                //is chkIos checked?
                overridePhysicalControls = chkSyncWithSteptracker.isChecked();

                byte buf[] = new byte[]{(byte) 0x08, (byte) 0x00};
                if(overridePhysicalControls) {
                    buf[buf.length-1]= (byte)1;
                }
                else {
                    buf[buf.length - 1] = (byte)0;
                }

                if(mCharacteristicTx!=null) {
                    mCharacteristicTx.setValue(buf);
                    mBluetoothLeService.writeCharacteristic(mCharacteristicTx);
                }
            }
        });

    }

    @Override
    protected void onResume() {
        super.onResume();

        // Check if BLE is enabled on the device. Created by the RedBear team.
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(
                    BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }

        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());

    }

    @Override
    protected void onStop() {
        super.onStop();

        flag = false;

        unregisterReceiver(mGattUpdateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mServiceConnection != null)
            unbindService(mServiceConnection);
    }

    // Create a list of intent filters for Gatt updates. Created by the RedBear team.
    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();

        intentFilter.addAction(RBLService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(RBLService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(RBLService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(RBLService.ACTION_DATA_AVAILABLE);
        intentFilter.addAction(RBLService.ACTION_GATT_RSSI);

        return intentFilter;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // User chose not to enable Bluetooth.
        if (requestCode == REQUEST_ENABLE_BT
                && resultCode == Activity.RESULT_CANCELED) {
            finish();
            return;
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                this.InferStepFromAccelerometerData(event);
                break;
        }
    }

    void InferStepFromAccelerometerData(SensorEvent event) {
        try {
            readingCount = readingCount + 1;

            rawAccelValues[0] = event.values[0];
            rawAccelValues[1] = event.values[1];
            rawAccelValues[2] = event.values[2];
            rawAccelValues = IsolateGravity(rawAccelValues);

            double rawMagnitude = Math.sqrt(
                    rawAccelValues[0] * rawAccelValues[0] +
                            rawAccelValues[1] * rawAccelValues[1] +
                            rawAccelValues[2] * rawAccelValues[2]);

            if (zscoreCalculationValues.size() < DATA_SAMPLING_SIZE) {
                zscoreCalculationValues.add(rawMagnitude);
            } else if (zscoreCalculationValues.size() == DATA_SAMPLING_SIZE) {
                int previousStepCount = calculatedStepCount;
                calculatedStepCount = calculatedStepCount + this.DetectPeak(
                        zscoreCalculationValues, LAG_SIZE, 0.30d, 0.2d);
                zscoreCalculationValues.clear();
                zscoreCalculationValues.add(rawMagnitude);
                byte[] buf = new byte[]{(byte) 0x07, (byte) 0};
                if(calculatedStepCount - previousStepCount >0 && mCharacteristicTx!=null) {
                    buf[1] = 1;
                    mCharacteristicTx.setValue(buf);
                    mBluetoothLeService.writeCharacteristic(mCharacteristicTx);
                }
                else
                {
                    buf[1] = 0;
                    mCharacteristicTx.setValue(buf);
                    mBluetoothLeService.writeCharacteristic(mCharacteristicTx);
                }
            }
        } catch (Exception ex) {
            Log.e("Ex", ex.getMessage());
        }
    }


    /**
     * "Smoothed z-score alogrithm" adapted from https://stackoverflow.com/a/22640362/6029703
     *  Uses a rolling mean and a rolling deviation (separate) to identify peaks in a vector
     *
     * @param inputs - The input vector to analyze
     * @param lag - The lag of the moving window (i.e. how big the window is)
     * @param threshold - The z-score at which the algorithm signals (i.e. how many standard deviations away from the moving mean a peak (or signal) is)
     * @param influence - The influence (between 0 and 1) of new signals on the mean and standard deviation (how much a peak (or signal) should affect other values near it)
     * @return - number of steps detected
     */
    int DetectPeak(List<Double> inputs, int lag, Double threshold, Double influence) {
        int peaksDetected = 0;
        //init stats instance
        SummaryStatistics stats = new SummaryStatistics();

        //the results (peaks, 1 or -1) of our algorithm
        ArrayList<Integer> signals = new ArrayList<>(Collections.nCopies(inputs.size(), 0));
        //filter out the signals (peaks) from our original list (using influence arg)
        ArrayList<Double> filteredY = new ArrayList<>(Collections.nCopies(inputs.size(), 0d));
        //the current average of the rolling window
        ArrayList<Double> avgFilter = new ArrayList<>(Collections.nCopies(inputs.size(), 0.0d));
        //the current standard deviation of the rolling window
        ArrayList<Double> stdFilter = new ArrayList<>(Collections.nCopies(inputs.size(), 0.0d));

        //init avgFilter and stdFilter
        for (int i = 0; i < lag; i++) {
            stats.addValue(inputs.get(i));
            filteredY.add(inputs.get(i));
        }
        avgFilter.set(lag - 1, stats.getMean());
        stdFilter.set(lag - 1, stats.getStandardDeviation());
        stats.clear();

        peakCount = peakCount + LAG_SIZE;
        for (int i = lag; i < inputs.size(); i++) {
            peakCount = peakCount + 1;
            if (Math.abs(inputs.get(i) - avgFilter.get(i - 1)) > threshold * stdFilter.get(i - 1)) {
                //this is a signal (i.e. peak), determine if it is a positive or negative signal
                if (inputs.get(i) > avgFilter.get(i - 1)) {
                    signals.set(i, 1);
                    if (inputs.get(i) > 1.5 && inputs.get(i) < 4.0) {
                        peaksDetected = peaksDetected + 1;
                    }
                } else {
                    signals.set(i, -1);
                }
                //filter this signal out using influence
                filteredY.set(i, (influence * inputs.get(i)) + ((1 - influence) * filteredY.get(i - 1)));
            } else {
                //ensure this signal remains a zero
                signals.set(i, 0);
                //ensure this value is not filtered
                filteredY.set(i, inputs.get(i));
            }
            //update rolling average and deviation
            for (int j = i - lag; j < i; j++)
            {
                stats.addValue(filteredY.get(j));
            }
            avgFilter.set(i, stats.getMean());
            stdFilter.set(i, stats.getStandardDeviation());

        }
        return peaksDetected;
    }
    //Removes the gravity component from acceleration.
    float[] IsolateGravity(float[] sensorValues) {
        final float alpha = 0.8f;
        float[] acceleration = {0, 0, 0};
        gravity[0] = alpha * gravity[0] + (1 - alpha) * sensorValues[0];
        gravity[1] = alpha * gravity[1] + (1 - alpha) * sensorValues[1];
        gravity[2] = alpha * gravity[2] + (1 - alpha) * sensorValues[2];

        // Remove the gravity contribution with the high-pass filter.
        acceleration[0] = sensorValues[0] - gravity[0];
        acceleration[1] = sensorValues[1] - gravity[1];
        acceleration[2] = sensorValues[2] - gravity[2];

        return acceleration;
    }


    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }
}