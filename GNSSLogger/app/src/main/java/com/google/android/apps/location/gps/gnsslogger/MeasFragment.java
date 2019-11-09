package com.google.android.apps.location.gps.gnsslogger;

import android.app.Activity;
import android.app.Fragment;
import android.graphics.Typeface;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementsEvent;
import android.location.GnssStatus;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import java.util.Locale;

public class MeasFragment extends Fragment {
    private MeasLogger mMeasLogger;
    private TableLayout mMeasTable;
    public static final int  MAX_SVS = 32;
    private static final double SPEED_OF_LIGHT_MPS = 299792458.0;

    public void SetMeasLogger(MeasLogger logger){
        mMeasLogger = logger;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
        View fragmentView = inflater.inflate(R.layout.fragment_meas, container, false /* attachToRoot */);
        mMeasTable = fragmentView.findViewById(R.id.tblMeas);
        for( int i =0; i < MAX_SVS; i++){
            TableRow row = (TableRow) inflater.inflate(R.layout.meas_table_row, mMeasTable, false);
            if ( i == 0){
                row.setTag(i);
                int cnt = row.getChildCount();
                for( int j = 0; j < cnt; j++) {
                    TextView cell = (TextView) row.getChildAt(j);
                    cell.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                }
            }else{
                row.setTag(-1);
                row.setVisibility(View.INVISIBLE);
            }
            mMeasTable.addView(row);
        }

        if ( mMeasLogger != null){
            mMeasLogger.setMeasComponent(this);
        }

        return fragmentView;
    }

    public void logMeas(final GnssMeasurementsEvent event) {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showMeasurement(event);
            }
        });
    }

    private void showMeasurement(GnssMeasurementsEvent event) {
        // Remove data from the previous epoch
        for(int i = 1; i <  mMeasTable.getChildCount(); i++){
            TableRow row = (TableRow) mMeasTable.getChildAt(i);
            clearRow(row);
        }

        // Fill the table
        for (GnssMeasurement meas : event.getMeasurements() ){
            if (meas.getConstellationType() != GnssStatus.CONSTELLATION_GPS)
                continue;  // Just GPS for now

            TableRow row = null;
            // Find the row that used to contain this SV to avoid unnecessary flickering
            for( int i = 0 ; i < mMeasTable.getChildCount(); i++) {
                row = (TableRow) mMeasTable.getChildAt(i);
                int rowSvId = (int)row.getTag();
                if( rowSvId == meas.getSvid()){
                    break;
                }
                if (rowSvId == -1){
                    row.setTag(meas.getSvid());
                    break;
                }
            }
            if ( row != null ){
                showRow(row, meas);
            }
        }
    }

    private void showRow(TableRow row, GnssMeasurement meas) {
        row.setVisibility(View.VISIBLE);
        ((TextView)row.findViewWithTag("getSvid")).
                setText(String.format(Locale.getDefault(),"%02d", meas.getSvid()));
        ((TextView)row.findViewWithTag("getCn0DbHz")).
                setText(String.format(Locale.getDefault(),"%.0f", meas.getCn0DbHz()));
        ((TextView)row.findViewWithTag("getReceivedSvTimeNanos")).
                setText(rcvdSvTimeToStr(meas.getReceivedSvTimeNanos(), meas.getState()));

        String unc;
        if (meas.getReceivedSvTimeUncertaintyNanos() < 1000) {
            int uncMeters = (int) (meas.getReceivedSvTimeUncertaintyNanos() * SPEED_OF_LIGHT_MPS * 1.e-9);
            unc = String.format(Locale.getDefault(), "%d m", uncMeters);
        } else {
            float uncMiliSec = (float) (meas.getReceivedSvTimeUncertaintyNanos() * 1.e-6);
            unc = String.format(Locale.getDefault(), "%.2f ms", uncMiliSec);
        }

        ((TextView)row.findViewWithTag("getReceivedSvTimeUncertaintyNanos")).
                setText(unc);

        int ratePpb = (int)(meas.getPseudorangeRateMetersPerSecond() / SPEED_OF_LIGHT_MPS * 1.e9);
        ((TextView)row.findViewWithTag("getPseudorangeRateMetersPerSecond")).
                setText(String.format(Locale.getDefault(),"%d", ratePpb));

        int uncPpb = (int)(meas.getPseudorangeRateUncertaintyMetersPerSecond() / SPEED_OF_LIGHT_MPS * 1.e9);
        if (uncPpb <= 1000) {
            unc = String.format(Locale.getDefault(), "%d", uncPpb);
        }else{
            unc = ">1ppm";
        }
        ((TextView)row.findViewWithTag("getPseudorangeRateUncertaintyMetersPerSecond")).
                setText(unc);
    }

    private String rcvdSvTimeToStr(long rxTimeNs, int state) {
        StringBuilder sb = new StringBuilder();

        if( (state & GnssMeasurement.STATE_TOW_DECODED) != 0 ){
            long hours = (rxTimeNs / ( 1000L * 1000 * 1000 * 3600)) % 24;
            long minutes = (rxTimeNs / ( 1000L * 1000 * 1000 * 60)) % 60;
            long seconds = (rxTimeNs / ( 1000L * 1000 * 1000 * 10)) % 6;
            sb.append(String.format(Locale.getDefault(),"%02d:%02d:%1d",
                    hours, minutes, seconds));
        }else{
            sb.append("tt:tt:t");
        }

        if( (state & GnssMeasurement.STATE_SUBFRAME_SYNC) != 0 ){
            long seconds = (rxTimeNs / ( 1000L * 1000 * 1000 )) % 10;
            long decisecs = (rxTimeNs / ( 1000L * 1000 * 100 )) % 10;
            sb.append(String.format(Locale.getDefault(),"%1d.%1d",
                    seconds, decisecs));
        }else{
            sb.append("f.f");
        }

        if( (state & GnssMeasurement.STATE_BIT_SYNC) != 0 ){
            long milliseconds = (rxTimeNs / ( 1000L * 1000 )) % 20;
            sb.append(String.format(Locale.getDefault(),"%02d",
                    milliseconds));
        }else{
            sb.append("bb");
        }

        return sb.toString();
    }

    private void clearRow(TableRow row) {
        // Clear all cells, but SV id
        for (int i = 0; i < row.getChildCount(); i++){
            TextView tv = (TextView)row.getChildAt(i);
            if ( ! tv.getTag().equals("getSvid")){
                tv.setText("");
            }
        }
    }
}

