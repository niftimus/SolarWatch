package com.analyticsanvil.solarwatch;

import androidx.annotation.NonNull;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import com.android.volley.Request;
import com.android.volley.toolbox.StringRequest;
import com.jjoe64.graphview.DefaultLabelFormatter;
import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.RoundingMode;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "Home";
    SharedPreferences sharedPreferences;
    int checkInterval;
    GraphView graph;
    LineGraphSeries<DataPoint> series_consumption;
    LineGraphSeries<DataPoint> series_production;
    LineGraphSeries<DataPoint> series_net;
    SSLSocketFactory ssl_socket_factory = null;
    Date lastRefreshed;
    Timer refreshTimer;
    Timer lastRefreshedTimer;
    TimerTask refreshTimerTask;
    TimerTask lastRefreshedTimerTask;
    Handler timerHandler;
    String v_inverter_type;
    String v_monitoring_ip;
    String url;

    // Create an SSLSocketFactory which trusts the SMA inverter CA cert
    // Reference: https://gist.github.com/erickok/7692592
    public SSLSocketFactory getSocketFactory(Context context)
            throws CertificateException, IOException, KeyStoreException, NoSuchAlgorithmException, KeyManagementException {

        // Load CAs from an InputStream (could be from a resource or ByteArrayInputStream or ...)
        CertificateFactory cf = CertificateFactory.getInstance("X.509");

        InputStream caInput = new BufferedInputStream(context.getResources().openRawResource(R.raw.sma_cert));

        Certificate ca;

        try {
            ca = cf.generateCertificate(caInput);
            System.out.println("ca=" + ((X509Certificate) ca).getSubjectDN());
        } finally {
            caInput.close();
        }

        // Create a KeyStore containing our trusted CAs
        String keyStoreType = KeyStore.getDefaultType();
        KeyStore keyStore = KeyStore.getInstance(keyStoreType);
        keyStore.load(null, null);
        keyStore.setCertificateEntry("ca", ca);

        // Create a TrustManager that trusts the CAs in our KeyStore
        String tmfAlgorithm = TrustManagerFactory.getDefaultAlgorithm();
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(tmfAlgorithm);
        tmf.init(keyStore);

        // Create an SSLContext that uses our TrustManager
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), null);

        return sslContext.getSocketFactory();
    }

    // Add production, consumption and net series to chart
    private void addSeries() {
        series_consumption = new LineGraphSeries<>();
        series_consumption.setTitle(getString(R.string.string_legend_consumption));
        series_consumption.setColor(Color.RED);
        series_production = new LineGraphSeries<>();
        series_production.setTitle(getString(R.string.string_legend_production));
        series_production.setColor(Color.GREEN);
        series_net = new LineGraphSeries<>();
        series_net.setTitle(getString(R.string.string_legend_net));
        series_net.setColor(Color.BLUE);
        graph.addSeries(series_consumption);
        graph.addSeries(series_production);
        graph.addSeries(series_net);
    }

    // Refresh the chart with latest values
    private void refreshGraph() throws CertificateException, NoSuchAlgorithmException, KeyStoreException, KeyManagementException, IOException {
        final TextView tv_production = findViewById(R.id.tv_production);
        final TextView tv_consumption = findViewById(R.id.tv_consumption);
        final TextView tv_net_export = findViewById(R.id.tv_net_export);
        final TextView tv_consumption_kwh = findViewById(R.id.tv_consumption_wh);

        // Instantiate the RequestQueue.
        SingletonRequestQueue.getInstance(this.getApplicationContext()).getRequestQueue();

        // Get inverter type and IP address from preferences (default: SMA)
        v_monitoring_ip = sharedPreferences.getString("pref_text_inverter_ip", "192.168.1.107");
        v_inverter_type = sharedPreferences.getString("pref_text_inverter_type", "SMA");

        // Set the URL according to the inverter type (SMA or Enphase)
        if ("SMA".equals(v_inverter_type)) {
            url = "https://" + v_monitoring_ip + "/dyn/getDashValues.json";
        } else if ("Enphase".equals(v_inverter_type)) {
            url = "http://" + v_monitoring_ip + "/production.json";
        }

        // Get check interval from preferences
        int checkInterval_tmp = Integer.parseInt(Objects.requireNonNull(sharedPreferences.getString("pref_text_refresh_interval", "10"))) * 1000;
        Log.d(TAG, "Current timer check interval: " + checkInterval);
        Log.d(TAG, "New timer check interval: " + checkInterval_tmp);

        // Recreate a new refresh task if the check interval has changed
        if (checkInterval_tmp != checkInterval) {
            checkInterval = checkInterval_tmp;
            Log.d(TAG, "Updated timer check interval: " + checkInterval);
            refreshTimerTask.cancel();
            refreshTimerTask = new TimerTask() {
                public void run() {
                    timerHandler.obtainMessage(1).sendToTarget();
                }
            };
            refreshTimer.schedule(refreshTimerTask, 0L, checkInterval);
        }

        // Request a string response from the provided URL.
        StringRequest stringRequest = new StringRequest(Request.Method.GET, url,
                response -> {
                    DataPoint dp_consumption, dp_production, dp_net;
                    Date date;
                    int productionWatts = 0, consumptionWatts = 0, netWatts = 0;
                    double consumptionkWhLifetime = 0;

                    try {

                        // Set reading time to current time
                        long readingTime = System.currentTimeMillis() / 1000;
                        date = new Date(readingTime * 1000L);

                        if ("SMA".equals(v_inverter_type)) {
                            // Get JSON object containing metrics
                            JSONObject obj = new JSONObject(response).getJSONObject("result").getJSONObject("0199-xxxxxC06");

                            netWatts = obj.getJSONObject("6100_40463600").getJSONArray("1").getJSONObject(0).getInt("val") - obj.getJSONObject("6100_40463700").getJSONArray("1").getJSONObject(0).getInt("val");

                            // Initialise production to zero
                            productionWatts = 0;
                            // Get production if it is not null
                            if (!obj.getJSONObject("6100_40263F00").getJSONArray("1").getJSONObject(0).isNull("val")) {
                                productionWatts = obj.getJSONObject("6100_40263F00").getJSONArray("1").getJSONObject(0).getInt("val");
                            }

                            // Calculate consumption from production and net
                            consumptionWatts = productionWatts - netWatts;

                            consumptionkWhLifetime = obj.getJSONObject("6400_00462500").getJSONArray("1").getJSONObject(0).getDouble("val") / 1000;


                        } else if ("Enphase".equals(v_inverter_type)) {
                            JSONObject obj = new JSONObject(response);
                            JSONArray production = obj.getJSONArray("production");
                            JSONArray consumption = obj.getJSONArray("consumption");

                            productionWatts = production.getJSONObject(1).getInt("wNow");
                            consumptionWatts = consumption.getJSONObject(0).getInt("wNow");
                            consumptionkWhLifetime = consumption.getJSONObject(0).getDouble("whLifetime") / 1000;
                        }

                        // Update text fields
                        tv_consumption.setText(String.valueOf(consumptionWatts));
                        tv_production.setText(String.valueOf(productionWatts));
                        tv_net_export.setText(String.valueOf(netWatts));
                        DecimalFormat df = new DecimalFormat("#.###");
                        df.setRoundingMode(RoundingMode.CEILING);
                        tv_consumption_kwh.setText(df.format(consumptionkWhLifetime));

                        // Add measurements to chart
                        dp_consumption = new DataPoint(date, consumptionWatts);
                        dp_production = new DataPoint(date, productionWatts);
                        dp_net = new DataPoint(date, netWatts);
                        series_consumption.appendData(dp_consumption, false, 2000);
                        series_production.appendData(dp_production, false, 2000);
                        series_net.appendData(dp_net, false, 2000);

                        // Reset chart scale based on newly added measurements
                        graph.getViewport().calcCompleteRange();
                        graph.getViewport().setMinX(graph.getViewport().getMinX(true));
                        graph.getViewport().setMaxX(graph.getViewport().getMaxX(true));
                        graph.getViewport().setMinY(graph.getViewport().getMinY(true));
                        graph.getViewport().setMaxY(graph.getViewport().getMaxY(true));

                        // Add legend to chart
                        graph.getLegendRenderer().setVisible(true);
                        graph.getLegendRenderer().setFixedPosition(0, graph.getBottom() - (int) (graph.getHeight() * 0.40));//.setAlign(LegendRenderer.LegendAlign.BOTTOM);
                        graph.getLegendRenderer().setBackgroundColor(Color.WHITE);
                        graph.getLegendRenderer().setTextSize(30);

                        // Update last refereshed timestamp
                        lastRefreshed = Calendar.getInstance().getTime();

                    } catch (JSONException e) {
                        e.printStackTrace();
                    }

                }, Throwable::printStackTrace);

        // Skip hostname verification and trust internal SSL cert
        if (v_inverter_type.equals("SMA")) {
            if (ssl_socket_factory == null) ssl_socket_factory = getSocketFactory(this);
            HttpsURLConnection.setDefaultSSLSocketFactory(ssl_socket_factory);
            HttpsURLConnection.setDefaultHostnameVerifier((arg0, arg1) -> true);
        }

        // Add the request to the RequestQueue.
        SingletonRequestQueue.getInstance(this.getApplicationContext()).addToRequestQueue(stringRequest);
    }

    // Update last refresh UI text
    private void updateLastRefreshed() {
        final TextView tv_lastRefreshed = findViewById(R.id.tv_last_refreshed_seconds);
        Date now = Calendar.getInstance().getTime();
        if (lastRefreshed != null)
            tv_lastRefreshed.setText(String.valueOf(Math.abs(now.getTime() - lastRefreshed.getTime()) / 1000));
    }

    // Get the time of day (used for X axis labels)
    private String printStandardDate(double date) {
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(date);
    }

    // Open settings menu
    private void switch_settings() {
        Intent switchActivityIntent = new Intent(this, SettingsActivity.class);
        startActivity(switchActivityIntent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        // Create a background thread that has a Looper
        // Reference: https://stackoverflow.com/questions/61023968/what-do-i-use-now-that-handler-is-deprecated
        HandlerThread handlerThread = new HandlerThread("HandlerThread");
        handlerThread.start();

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this /* Activity context */);

        // Set up settings button
        final Button button_settings = findViewById(R.id.button_settings);
        button_settings.setOnClickListener(view -> switch_settings());

        // Set up refresh button
        final Button button_refresh = findViewById(R.id.button_refresh);
        button_refresh.setOnClickListener(v -> {
            try {
                refreshGraph();
            } catch (CertificateException | NoSuchAlgorithmException | KeyStoreException | KeyManagementException | IOException e) {
                e.printStackTrace();
            }
        });

        // Set up reset button
        final Button button_reset = findViewById(R.id.button_reset);
        button_reset.setOnClickListener(v -> {
            graph.removeAllSeries();
            addSeries();
        });

        // Set up chart, including each series, axis labels and legend
        graph = findViewById(R.id.gv_graph);
        addSeries();
        graph.getViewport().setXAxisBoundsManual(true);
        graph.getViewport().setYAxisBoundsManual(true);
        graph.getGridLabelRenderer().setLabelFormatter(new DefaultLabelFormatter() {
            @Override
            public String formatLabel(double value, boolean isValueX) {

                if (isValueX) {
                    // show date x values
                    return printStandardDate(value);
                } else {
                    // show Watts for y values
                    return super.formatLabel(value, false);
                }
            }
        });
        graph.getGridLabelRenderer().setNumHorizontalLabels(4); // only 4 because of the space
        graph.getGridLabelRenderer().setNumVerticalLabels(6);
        graph.getGridLabelRenderer().setTextSize(30f);
        graph.getViewport().setScrollable(true); // enables horizontal scrolling
        graph.getViewport().setScalable(true); // enables horizontal zooming and scrolling

        // Set up chart refresh timer
        timerHandler = new Handler(handlerThread.getLooper()) {
            public void handleMessage(@NonNull Message msg) {
                runOnUiThread(() -> {
                    try {
                        refreshGraph();
                    } catch (CertificateException | NoSuchAlgorithmException | KeyStoreException | KeyManagementException | IOException e) {
                        e.printStackTrace();
                    }
                });
            }
        };

        // Set up last refresh label update timer
        final Handler lastRefreshedTimerHandler = new Handler(handlerThread.getLooper()) {
            public void handleMessage(@NonNull Message msg) {
                runOnUiThread(() -> updateLastRefreshed());
            }
        };

        // Set up timers
        refreshTimer = new Timer();
        lastRefreshedTimer = new Timer();
        refreshTimerTask = new TimerTask() {
            public void run() {
                timerHandler.obtainMessage(1).sendToTarget();
            }
        };
        lastRefreshedTimerTask = new TimerTask() {
            public void run() {
                lastRefreshedTimerHandler.obtainMessage(1).sendToTarget();
            }
        };

        // Set schedule for refresh based on preferences (default 10 seconds)
        checkInterval = Integer.parseInt(Objects.requireNonNull(sharedPreferences.getString("pref_text_inverter_interval", "10"))) * 1000;
        Log.d(TAG, "Timer check interval: " + checkInterval);

        // Schedule timers
        refreshTimer.schedule(refreshTimerTask, 0L, checkInterval);
        lastRefreshedTimer.schedule(lastRefreshedTimerTask, 0L, 1000L);
    }
}