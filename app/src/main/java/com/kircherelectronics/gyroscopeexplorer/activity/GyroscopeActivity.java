package com.kircherelectronics.gyroscopeexplorer.activity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;
import android.widget.Toast;

import com.kircherelectronics.fsensor.filter.averaging.MeanFilter;
import com.kircherelectronics.fsensor.observer.SensorSubject;
import com.kircherelectronics.fsensor.sensor.FSensor;
import com.kircherelectronics.fsensor.sensor.gyroscope.ComplementaryGyroscopeSensor;
import com.kircherelectronics.fsensor.sensor.gyroscope.GyroscopeSensor;
import com.kircherelectronics.fsensor.sensor.gyroscope.KalmanGyroscopeSensor;
import com.kircherelectronics.gyroscopeexplorer.R;
import com.kircherelectronics.gyroscopeexplorer.datalogger.DataLoggerManager;
import com.kircherelectronics.gyroscopeexplorer.gauge.GaugeBearing;
import com.kircherelectronics.gyroscopeexplorer.gauge.GaugeRotation;
import com.kircherelectronics.gyroscopeexplorer.view.VectorDrawableButton;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Scanner;
import java.util.*;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/*
 * Copyright 2013-2017, Kaleb Kircher - Kircher Engineering, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * The main activity displays the orientation estimated by the sensor(s) and
 * provides an interface for the user to modify settings, reset or view help.
 *
 * @author Kaleb
 */
public class GyroscopeActivity extends AppCompatActivity {
    private final static int WRITE_EXTERNAL_STORAGE_REQUEST = 1000;

    // Indicate if the output should be logged to a .csv file
    private boolean logData = false;

    private boolean meanFilterEnabled;

    private float[] fusedOrientation = new float[3];

    // The gauge views. Note that these are views and UI hogs since they run in
    // the UI thread, not ideal, but easy to use.
    private GaugeBearing gaugeBearingCalibrated;
    private GaugeRotation gaugeTiltCalibrated;

    // Handler for the UI plots so everything plots smoothly
    protected Handler uiHandler;
    protected Runnable uiRunnable;

    private TextView tvXAxis;
    private TextView tvYAxis;
    private TextView tvZAxis;
    private TextView water_value;

    private FSensor fSensor;

    private MeanFilter meanFilter;

    private DataLoggerManager dataLogger;

    private Dialog helpDialog;

    public Context mContext;
    /*
    public GyroscopeActivity(Context context){
        this.mContext = context;
    }

    public GyroscopeActivity(){

    }
    */
    //GyroscopeActivity gyroscopeactivity = new GyroscopeActivity(mContext);



    private SensorSubject.SensorObserver sensorObserver = new SensorSubject.SensorObserver() {
        @Override
        public void onSensorChanged(float[] values) {
          updateValues(values);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gyroscope);
        dataLogger = new DataLoggerManager(this);
        meanFilter = new MeanFilter();

        uiHandler = new Handler();
        uiRunnable = new Runnable() {
            @Override
            public void run() {
                uiHandler.postDelayed(this, 100);
                updateText();
                updateGauges();
            }
        };

        initUI();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.gyroscope, menu);
        return true;
    }

    /**
     * Event Handling for Individual menu item selected Identify single menu
     * item by it's id
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_reset:
                fSensor.reset();
                break;
            case R.id.action_config:
                Intent intent = new Intent();
                intent.setClass(this, ConfigActivity.class);
                startActivity(intent);
                break;
            case R.id.action_help:
                showHelpDialog();
                break;
            default:
                return super.onOptionsItemSelected(item);
        }

        return true;
    }

    @Override
    public void onResume() {
        super.onResume();

        Mode mode = readPrefs();

        switch (mode) {
            case GYROSCOPE_ONLY:
                fSensor = new GyroscopeSensor(this);
                break;
            case COMPLIMENTARY_FILTER:
                fSensor = new ComplementaryGyroscopeSensor(this);
                ((ComplementaryGyroscopeSensor)fSensor).setFSensorComplimentaryTimeConstant(getPrefImuOCfQuaternionCoeff());
                break;
            case KALMAN_FILTER:
                fSensor = new KalmanGyroscopeSensor(this);
                break;
        }

        fSensor.register(sensorObserver);
        fSensor.start();
        uiHandler.post(uiRunnable);
    }

    @Override
    public void onPause() {
        if(helpDialog != null && helpDialog.isShowing()) {
            helpDialog.dismiss();
        }

        fSensor.unregister(sensorObserver);
        fSensor.stop();
        uiHandler.removeCallbacksAndMessages(null);

        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == WRITE_EXTERNAL_STORAGE_REQUEST) {// If request is cancelled, the result arrays are empty.
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // permission was granted, yay! Do the
                // contacts-related task you need to do.

                startDataLog();
            }
        }
    }

    private boolean getPrefMeanFilterEnabled() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        return prefs.getBoolean(ConfigActivity.MEAN_FILTER_SMOOTHING_ENABLED_KEY, false);
    }

    private float getPrefMeanFilterTimeConstant() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        return Float.parseFloat(prefs.getString(ConfigActivity.MEAN_FILTER_SMOOTHING_TIME_CONSTANT_KEY, "0.5"));
    }

    private boolean getPrefKalmanEnabled() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        return prefs.getBoolean(ConfigActivity.KALMAN_QUATERNION_ENABLED_KEY, false);
    }

    private boolean getPrefComplimentaryEnabled() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        return prefs.getBoolean(ConfigActivity.COMPLIMENTARY_QUATERNION_ENABLED_KEY, false);
    }

    private float getPrefImuOCfQuaternionCoeff() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        return Float.parseFloat(prefs.getString(ConfigActivity.COMPLIMENTARY_QUATERNION_COEFF_KEY, "0.5"));
    }

    private void initStartButton() {
        final VectorDrawableButton button = findViewById(R.id.button_start);

        button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!logData) {
                    button.setText(getString(R.string.action_stop));
                    startDataLog();
                } else {
                    button.setText(getString(R.string.action_start));
                    stopDataLog();
                }
            }
        });
    }

    /**
     * Initialize the UI.
     */
    private void initUI() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if(getSupportActionBar()!=null) {
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }

        // Initialize the calibrated text views
        tvXAxis = this.findViewById(R.id.value_x_axis_calibrated);
        tvYAxis = this.findViewById(R.id.value_y_axis_calibrated);
        tvZAxis = this.findViewById(R.id.value_z_axis_calibrated);
        water_value = this.findViewById(R.id.water_level);

        // Initialize the calibrated gauges views
        gaugeBearingCalibrated = findViewById(R.id.gauge_bearing_calibrated);
        gaugeTiltCalibrated = findViewById(R.id.gauge_tilt_calibrated);

        initStartButton();
    }

    /*private String readCSV(){

    }
    */

    private String csvWaterLevel(){
        String water_class = "Calculating" ;String datastr;
        int numtemp = 0; int datalim = 31; int num = 0; int datanum = 30;
        double  x = 0.0;double y = 0.0;double z = 0.0; double datadoub[]={0.0,0.0,0.0};
        SharedPreferences pref = getApplicationContext().getSharedPreferences("MyPref",MODE_PRIVATE);
        SharedPreferences.Editor editor = pref.edit();

        //Get data from gyro sensor
        x = (Math.toDegrees(fusedOrientation[1]) + 360)%360;
        y = (Math.toDegrees(fusedOrientation[2]) + 360)%360;
        z = (Math.toDegrees(fusedOrientation[0]) + 360)%360;

        num = pref.getInt("num", 0);
        //System.out.println("pref init");

        //store realtime data
        if(num<datalim){
            if(num==0){
                editor.putInt("num",1);
                editor.apply();
            }
            numtemp = pref.getInt("num",0);
            datastr = x+","+y+","+z;
            editor.putString("data"+numtemp,datastr);
            numtemp++;
            editor.putInt("num",numtemp);
            editor.apply();
            //System.out.println("Temp File create test");
            //System.out.println("num loop value test "+numtemp);
        }

        //Data retrieving from temp file for data averaging
        if(num==datalim){
            for(int i=1;i<datalim;i++){
                datastr = pref.getString("data"+i,"0");
                List<String> str = Arrays.asList(datastr.split(","));
                datadoub[0] = datadoub[0]+Double.parseDouble(str.get(0)); datadoub[1] = datadoub[1]+Double.parseDouble(str.get(1)); datadoub[2] = datadoub[2]+Double.parseDouble(str.get(2));
                //System.out.println("Data Array Test "+str.get(0)+" "+str.size()+" "+num+" "+datastr);
                //System.out.println("num value test "+num);
            }

            //Data averaging
            x = datadoub[0]/datanum;
            y = datadoub[1]/datanum;
            z = datadoub[2]/datanum;
            //System.out.println("Ave X "+x);
            //System.out.println("Ave Y "+y);
            //System.out.println("Ave Z "+z);

            //Call kNN algorithm
            try {
                water_class = kNN(x,y,z);
            } catch (FileNotFoundException e) {
                System.out.println("kNN Call Method ERROR");
            }
            editor.remove("num");
            editor.apply();
        }
        return water_class;
    }

    /*
    private BufferedReader readTXT(String filename){
        BufferedReader inputreader = null;

        try {
            inputreader = new BufferedReader(new FileReader(filename));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        return inputreader;
    }
    */

    private String kNN(double x, double y, double z) throws FileNotFoundException {
        int column = 0; int row = 0; int k = 20; int r = 0; int c = 0;
        int c1=0; int c2 = 0; int c3 = 0;
        String classes = " "; String line = null;
        double distance = 0.0; double x1 = 0.0; double y1 = 0.0; double z1 = 0.0;
        //ArrayList<Double> calcdist = new ArrayList<Double>();
        //ArrayList<Double> classes = new ArrayList<Double>();
        ArrayList<ArrayList<Double>> calcdist = new ArrayList<ArrayList<Double>>();
        /*ArrayList<ArrayList<Double>> data = new ArrayList<ArrayList<Double>>();*/

        //Read data from dataset
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(getAssets().open("All_Dataset_with_header.txt")));

            // do reading, usually loop until end of file reading
            String mLine;
            while ((mLine = reader.readLine()) != null) {
                //System.out.println("TEST TEXT DATA "+mLine);
                String[] datastr = mLine.split(",");

                distance = Math.sqrt(((Double.parseDouble(datastr[0])-x)*(Double.parseDouble(datastr[0])-x))
                        + ((Double.parseDouble(datastr[1])-y)*(Double.parseDouble(datastr[1])-y))
                        + ((Double.parseDouble(datastr[2])-z)*(Double.parseDouble(datastr[2])-z)));

                calcdist.add(new ArrayList<Double>(Arrays.asList(distance,Double.parseDouble(datastr[3]))));
                System.out.println("TEST DISTANCE DATA "+distance);
            }
        } catch (IOException e) {
            //log the exception
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    //log the exception
                }
            }
        }

        //0 = X, 1 = Y, 2 = Z



        Collections.sort(calcdist, new Comparator<ArrayList<Double>>(){
            @Override
            public int compare(ArrayList<Double> o1, ArrayList<Double> o2){
                return o1.get(0).compareTo(o2.get(0));
            }
        });

        System.out.println(calcdist.get(0).get(1));
        System.out.println(calcdist.get(1).get(1));
        System.out.println(calcdist.get(2).get(1));
        System.out.println(calcdist.get(3).get(1));
        System.out.println(calcdist.get(4).get(1));

        for(int j = 0; j<k; j++){

            if(calcdist.get(j).get(1) == 0.0){
                c1++;
                System.out.println("C1 "+c1);
            }
            else if(calcdist.get(j).get(1) == 1.0){
                c2++;
                System.out.println("C2 "+c2);
            }
            else if(calcdist.get(j).get(1) == 2.0){
                c3++;
                System.out.println("C3 "+c3);
            }
        }

        if((c1>c2)&&(c1>c3)){
            System.out.println("The class is 0");
            classes = "Ankle";
        }
        else if((c2>c3)&&(c2>c1)){
            System.out.println("The class is 1");
            classes = "Calf";
        }
        else if((c3>c1)&&(c3>c2)){
            System.out.println("The class is 2");
            classes = "Knee";
        }
        return classes;
    }


    private Mode readPrefs() {
        meanFilterEnabled = getPrefMeanFilterEnabled();
        boolean complimentaryFilterEnabled = getPrefComplimentaryEnabled();
        boolean kalmanFilterEnabled = getPrefKalmanEnabled();

        if(meanFilterEnabled) {
            meanFilter.setTimeConstant(getPrefMeanFilterTimeConstant());
        }

        Mode mode;

        if(!complimentaryFilterEnabled && !kalmanFilterEnabled) {
            mode = Mode.GYROSCOPE_ONLY;
        } else if(complimentaryFilterEnabled) {
            mode = Mode.COMPLIMENTARY_FILTER;
        } else {
            mode = Mode.KALMAN_FILTER;
        }

        return mode;
    }

    private void showHelpDialog() {
        helpDialog = new Dialog(this);
        helpDialog.setCancelable(true);
        helpDialog.setCanceledOnTouchOutside(true);
        helpDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View view = getLayoutInflater().inflate(R.layout.layout_help_home, (ViewGroup) findViewById(android.R.id.content), false);
        helpDialog.setContentView(view);
        helpDialog.show();
    }

    private void startDataLog() {
        if(!logData && requestPermissions()) {
            logData = true;
            dataLogger.startDataLog();
        }
    }

    private void stopDataLog() {
        if(logData) {
            logData = false;
            String path = dataLogger.stopDataLog();
            Toast.makeText(this, "File Written to: " + path, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateText() {
        tvXAxis.setText(String.format(Locale.getDefault(),"%.1f", (Math.toDegrees(fusedOrientation[1]) + 360) % 360));
        tvYAxis.setText(String.format(Locale.getDefault(),"%.1f", (Math.toDegrees(fusedOrientation[2]) + 360) % 360));
        tvZAxis.setText(String.format(Locale.getDefault(),"%.1f", (Math.toDegrees(fusedOrientation[0]) + 360) % 360));
        water_value.setText(String.format(Locale.getDefault(),csvWaterLevel()));
    }

    private void updateGauges() {
        gaugeBearingCalibrated.updateBearing(fusedOrientation[0]);
        gaugeTiltCalibrated.updateRotation(fusedOrientation[1], fusedOrientation[2]);
    }

    private boolean requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, WRITE_EXTERNAL_STORAGE_REQUEST);
            return false;
        }

        return true;
    }

    private void updateValues(float[] values) {
        fusedOrientation = values;
        if(meanFilterEnabled) {
            fusedOrientation = meanFilter.filter(fusedOrientation);
        }

        if(logData) {
            dataLogger.setRotation(fusedOrientation);
        }
    }

    private enum Mode {
        GYROSCOPE_ONLY,
        COMPLIMENTARY_FILTER,
        KALMAN_FILTER
    }

}
