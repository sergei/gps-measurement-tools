package com.google.android.apps.location.gps.gnsslogger;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.text.format.Formatter;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RemoteControlService extends Service {
    static final int NOTIFICATION_ID = 8888;
    private static final String TAG = "RemoteControlService";
    private static final boolean D = true;

    private final IBinder mBinder = new RemoteControlBinder();

    private final ExecutorService pool;
    private FileLogger mFileLogger;
    private GnssContainer mGnssContainer;
    Handler mHandler;
    private TcpServer mTcpServer;
    private UdpBroadcaster mUdpBroadcaster;
    private PowerManager.WakeLock mWakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK,
                "com.google.android.apps.location.gps.gnsslogger:RemoteControl");
        if( mWakeLock != null)
            mWakeLock.acquire();
    }

    public RemoteControlService() {
        pool = Executors.newFixedThreadPool(2);
        mTcpServer = null;
    }

    public void setGpsContainer(GnssContainer gnssContainer) {
        mGnssContainer = gnssContainer;
    }

    public void setFileLogger(FileLogger fileLogger) {
        mFileLogger = fileLogger;
    }

    /** A {@link Binder} that exposes a {@link TimerService}. */
    public class RemoteControlBinder extends Binder {
        RemoteControlService getService() {
            return RemoteControlService.this;
        }
    }

    private class UdpBroadcaster implements Runnable {
        private final int m_port;
        private boolean mKeepRunning = true;

        UdpBroadcaster(int port){
            m_port = port;
        }

        InetAddress getBroadcastAddress() throws IOException {
            WifiManager wifi = (WifiManager) RemoteControlService.this.getSystemService(Context.WIFI_SERVICE);
            DhcpInfo dhcp = wifi.getDhcpInfo();
            // handle null somehow

            int broadcast = (dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
            byte[] quads = new byte[4];
            for (int k = 0; k < 4; k++)
                quads[k] = (byte) ((broadcast >> k * 8) & 0xFF);
            return InetAddress.getByAddress(quads);
        }

        @Override
        public void run() {
            while ( mKeepRunning ) {
                WifiManager wm = (WifiManager) getSystemService(WIFI_SERVICE);
                String ip = Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
                String msg = String.format("Broadcasting  IP address %s", ip);
                if (D) Log.d(TAG, msg);

                try {
                    //Open a random port to send the package
                    DatagramSocket socket = new DatagramSocket();
                    socket.setBroadcast(true);
                    byte[] sendData = msg.getBytes();
                    DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, getBroadcastAddress(), m_port);
                    socket.send(sendPacket);
                    if (D) Log.d(TAG,  "Broadcast packet sent to: " + getBroadcastAddress().getHostAddress() + ':' + m_port);
                } catch (IOException e) {
                    Log.e(TAG, "IOException: " + e.getMessage());
                }

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignore) {
                }

            }
        }

        void shutdown() {
            mKeepRunning = false;
        }
    }


    class TcpServer implements Runnable {
        private final ServerSocket serverSocket;
        private final ExecutorService tcpServerPool;
        private boolean mKeepRunning = true;

        public TcpServer(int port)
                throws IOException {
            if(D) Log.d(TAG, "Listening for commands on port " + port);
            int poolSize = 1;
            serverSocket = new ServerSocket(port);
            tcpServerPool = Executors.newFixedThreadPool(poolSize);
        }

        public void run() { // run the service
            try {
                while (mKeepRunning) {
                    tcpServerPool.execute(new TcpConnectionHandler(serverSocket.accept()));
                }
            } catch (IOException ex) {
                tcpServerPool.shutdown();
            }
        }

        void shutdown(){
            mKeepRunning = false;
            tcpServerPool.shutdown();
            try {
                serverSocket.close();
            } catch (IOException ignore) {

            }
        }
    }

    class TcpConnectionHandler implements Runnable {
        private final Socket socket;
        TcpConnectionHandler(Socket socket) {
            this.socket = socket;
            if(D) Log.d(TAG, "Accepted TCP connection");
        }
        public void run() {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                BufferedWriter writer = new BufferedWriter( new OutputStreamWriter(socket.getOutputStream()));
                String line = reader.readLine();
                if(D) Log.d(TAG, "> " + line);
                if (line.toLowerCase().startsWith("start")) {
                    startLogging(writer);
                }else if ( line.toLowerCase().startsWith("stop")){
                    stopLogging(writer);
                }
                if(D) Log.d(TAG, "Closing TCP connection");
                this.socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void startLogging(BufferedWriter writer) throws IOException {
        if (mGnssContainer != null){
            if(D)Log.d(TAG, "Starting GPS");

            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mFileLogger.startNewLog();
                    mGnssContainer.registerLocation();
                    mGnssContainer.registerMeasurements();
                    mGnssContainer.registerNavigation();
                    mGnssContainer.registerNmea();
                }
            });

            writer.write("OK\n");
        }else{
            if(D)Log.d(TAG, "No GPS container is available");
            writer.write("FAILED\n");
        }
        writer.close();
    }

    private void stopLogging(BufferedWriter writer) throws IOException {
        if (mGnssContainer != null){
            if(D)Log.d(TAG, "Stopping GPS");

            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mGnssContainer.unregisterLocation();
                    mGnssContainer.unregisterMeasurements();
                    mGnssContainer.unregisterNavigation();
                    mGnssContainer.unregisterNmea();
                }
            });

            File logFile = mFileLogger.stopCurrentLog();
            BufferedReader r = new BufferedReader(new  InputStreamReader(new FileInputStream(logFile)));
            char [] buffer = new char[10];
            while ( r.ready() ){
                int n = r.read(buffer);
                writer.write(buffer, 0, n);
            }
        }else{
            if(D)Log.d(TAG, "No GPS container is available");
            writer.write("FAILED\n");
        }
        writer.close();
    }


    @Override
    public IBinder onBind(Intent intent) {
        Notification notification = new Notification();
        startForeground(NOTIFICATION_ID, notification);

        // Start UDP broadcaster
        int udpBroadcastPort = 1122;
        mUdpBroadcaster = new UdpBroadcaster(udpBroadcastPort);
        pool.execute(mUdpBroadcaster);

        // Start TCP server
        int tcpListenPort = 3322;
        try {
            mTcpServer = new TcpServer(tcpListenPort);
            pool.execute(mTcpServer);
        } catch (IOException e) {
            e.printStackTrace();
        }

        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "onUnbind() called with: intent = [" + intent + "]");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy() called");
        super.onDestroy();
        if ( mTcpServer != null){
            mTcpServer.shutdown();
        }
        if ( mUdpBroadcaster != null ) {
            mUdpBroadcaster.shutdown();
        }

        if( mWakeLock != null)
            mWakeLock.release();

        pool.shutdown();
    }
}
