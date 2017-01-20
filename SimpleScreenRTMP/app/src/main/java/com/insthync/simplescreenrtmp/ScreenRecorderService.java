package com.insthync.simplescreenrtmp;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.view.Surface;

import net.butterflytv.rtmp_client.RTMPMuxer;

import java.io.IOException;
import java.nio.ByteBuffer;

public class ScreenRecorderService extends Service {
    private final String TAG = "ScreenRecorderService";
    public static final String ACTION_STOP = "ACTION_STOP";
    public static final int DEFAULT_SCREEN_WIDTH = 640;
    public static final int DEFAULT_SCREEN_HEIGHT = 480;
    public static final int DEFAULT_SCREEN_DPI = 320;
    public static final int DEFAULT_VIDEO_BITRATE = 1024000;
    public static final int DEFAULT_VIDEO_FPS = 25;
    public static final String DEFAULT_VIDEO_MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int IFRAME_INTERVAL = 1; // 1 seconds between I-frames
    private static final int TIMEOUT_US = 10000;

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_RTMP_ADDRESS = "rtmp_address";

    public static final String EXTRA_SCREEN_WIDTH = "screen_width";
    public static final String EXTRA_SCREEN_HEIGHT = "screen_height";
    public static final String EXTRA_SCREEN_DPI = "screen_dpi";
    public static final String EXTRA_VIDEO_FORMAT = "video_format";
    public static final String EXTRA_VIDEO_BITRATE = "video_bitrate";

    private final int NT_ID_CASTING = 0;

    private MediaProjectionManager mMediaProjectionManager;
    private String mRtmpAddresss;
    private int mResultCode;
    private Intent mResultData;
    private String mSelectedFormat;
    private int mSelectedWidth;
    private int mSelectedHeight;
    private int mSelectedDpi;
    private int mSelectedBitrate;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private Surface mInputSurface;
    private MediaCodec mVideoEncoder;
    private MediaCodec.BufferInfo mVideoBufferInfo;
    private RTMPMuxer mRTMPMuxer;
    private long mStartTime;
    private long mTryingAgainTime;
    private boolean mIsSetHeader;
    private Handler mDrainHandler = new Handler();
    private IntentFilter mBroadcastIntentFilter;

    private Runnable mDrainEncoderRunnable = new Runnable() {
        @Override
        public void run() {
            drainEncoder();
        }
    };

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "Service receive broadcast action: " + action);
            if (action == null) {
                return;
            }
            if (ACTION_STOP.equals(action)) {
                stopScreenCapture();
                stopSelf();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mMediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mBroadcastIntentFilter = new IntentFilter();
        mBroadcastIntentFilter.addAction(ACTION_STOP);
        registerReceiver(mBroadcastReceiver, mBroadcastIntentFilter);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Destroy service");
        stopScreenCapture();
        unregisterReceiver(mBroadcastReceiver);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null)
            return START_NOT_STICKY;

        mRtmpAddresss = intent.getStringExtra(EXTRA_RTMP_ADDRESS);
        mResultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1);
        mResultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        Log.d(TAG, "RTMP Address: " + mRtmpAddresss);

        if (mRtmpAddresss == null)
            return START_NOT_STICKY;

        if (mResultCode != Activity.RESULT_OK || mResultData == null) {
            Log.e(TAG, "Failed to start service, mResultCode: " + mResultCode + ", mResultData: " + mResultData);
            return START_NOT_STICKY;
        }

        mSelectedWidth = intent.getIntExtra(EXTRA_SCREEN_WIDTH, DEFAULT_SCREEN_WIDTH);
        mSelectedHeight = intent.getIntExtra(EXTRA_SCREEN_HEIGHT, DEFAULT_SCREEN_HEIGHT);
        mSelectedDpi = intent.getIntExtra(EXTRA_SCREEN_DPI, DEFAULT_SCREEN_DPI);
        mSelectedBitrate = intent.getIntExtra(EXTRA_VIDEO_BITRATE, DEFAULT_VIDEO_BITRATE);
        mSelectedFormat = intent.getStringExtra(EXTRA_VIDEO_FORMAT);

        if (mSelectedFormat == null)
            mSelectedFormat = DEFAULT_VIDEO_MIME_TYPE;

        if (!startScreenCapture()) {
            Log.e(TAG, "Failed to start capture screen");
            return START_NOT_STICKY;
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void showNotification() {
        final Intent notificationIntent = new Intent(ACTION_STOP);
        PendingIntent notificationPendingIntent = PendingIntent.getBroadcast(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        final NotificationCompat.Action actionStop = new NotificationCompat.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_stop), notificationPendingIntent).build();
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
        builder.setSmallIcon(R.mipmap.ic_launcher)
                .setDefaults(Notification.DEFAULT_ALL)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.casting_screen))
                .addAction(actionStop);
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NT_ID_CASTING, builder.build());
    }

    private void dismissNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(NT_ID_CASTING);
    }

    private boolean startScreenCapture() {
        Log.d(TAG, "mResultCode: " + mResultCode + ", mResultData: " + mResultData);
        if (mResultCode != 0 && mResultData != null) {
            setUpMediaProjection();
            startRecording();
            showNotification();
            return true;
        }
        return false;
    }

    private void setUpMediaProjection() {
        mMediaProjection = mMediaProjectionManager.getMediaProjection(mResultCode, mResultData);
    }

    private void startRecording() {
        Log.d(TAG, "startRecording");

        mStartTime = 0;
        mTryingAgainTime = 0;
        mIsSetHeader = false;

        prepareVideoEncoder();

        mRTMPMuxer = new RTMPMuxer();
        int result = mRTMPMuxer.open(mRtmpAddresss, mSelectedWidth, mSelectedHeight);
        Log.d(TAG, "RTMP_URL open result: " + result);

        // Start the video input.
        mVirtualDisplay = mMediaProjection.createVirtualDisplay("Recording Display", mSelectedWidth,
                mSelectedHeight, mSelectedDpi, 0 /* flags */, mInputSurface,
                null /* callback */, null /* handler */);

        // Start the encoders
        drainEncoder();
    }

    private void prepareVideoEncoder() {
        mVideoBufferInfo = new MediaCodec.BufferInfo();
        MediaFormat format = MediaFormat.createVideoFormat(mSelectedFormat, mSelectedWidth, mSelectedHeight);
        int frameRate = DEFAULT_VIDEO_FPS;

        // Set some required properties. The media codec may fail if these aren't defined.
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, mSelectedBitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_CAPTURE_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000 / frameRate);

        // Create a MediaCodec encoder and configure it. Get a Surface we can use for recording into.
        try {
            mVideoEncoder = MediaCodec.createEncoderByType(mSelectedFormat);
            mVideoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mInputSurface = mVideoEncoder.createInputSurface();
            mVideoEncoder.start();
        } catch (IOException e) {
            Log.e(TAG, "Failed to initial encoder, e: " + e);
            releaseEncoders();
        }
    }

    private boolean drainEncoder() {
        mDrainHandler.removeCallbacks(mDrainEncoderRunnable);
        while (true) {
            int index = mVideoEncoder.dequeueOutputBuffer(mVideoBufferInfo, TIMEOUT_US);

            if (mStartTime == 0)
                mStartTime = mVideoBufferInfo.presentationTimeUs / 1000;

            int timestamp = (int) ((mVideoBufferInfo.presentationTimeUs / 1000) - mStartTime);
            //Log.d(TAG, "dequeue output buffer index=" + index);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.d(TAG, "Format changed " + mVideoEncoder.getOutputFormat());
            } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (mTryingAgainTime == 0)
                    mTryingAgainTime = System.currentTimeMillis();
                //Log.d(TAG, "Contents are not ready, trying again...");
                break;
            } else if (index >= 0) {
                if (mTryingAgainTime > 0) {
                    long tryAgainAfterTime = System.currentTimeMillis() - mTryingAgainTime;
                    Log.d(TAG, "Tried again after " + tryAgainAfterTime +" ms");
                    mTryingAgainTime = 0;
                }
                ByteBuffer encodedData = mVideoEncoder.getOutputBuffer(index);
                encodedData.position(mVideoBufferInfo.offset);
                encodedData.limit(mVideoBufferInfo.offset + mVideoBufferInfo.size);

                byte[] bytes = new byte[encodedData.remaining()];
                encodedData.get(bytes);

                encodeToVideoTrack(timestamp, bytes);
                mVideoEncoder.releaseOutputBuffer(index, false);
            }
        }

        mDrainHandler.postDelayed(mDrainEncoderRunnable, 10);
        return true;
    }


    private void encodeToVideoTrack(int timestamp, byte[] bytes) {


        if ((mVideoBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            // Pulling codec config data
            if (!mIsSetHeader) {
                writeToMuxer(true, timestamp, bytes);
                mIsSetHeader = true;
            }
            mVideoBufferInfo.size = 0;
        }

        if (mVideoBufferInfo.size > 0) {
            writeToMuxer(false, timestamp, bytes);
        }
    }

    private void writeToMuxer(boolean isHeader, int timestamp, byte[] bytes) {
        int rtmpConnectionState = mRTMPMuxer != null ? mRTMPMuxer.isConnected() : 0;
        Log.d(TAG, "RTMP connection state: " + rtmpConnectionState + " timestamp: " + timestamp + " byte[] length: " + bytes.length);
        int writeResult = mRTMPMuxer.writeVideo(bytes, 0, bytes.length, timestamp);
        Log.d(TAG, "RTMP write data result: " + writeResult + " is header: " + isHeader);
    }

    private void stopScreenCapture() {
        dismissNotification();
        releaseEncoders();
    }

    private void releaseEncoders() {
        mDrainHandler.removeCallbacks(mDrainEncoderRunnable);

        if (mRTMPMuxer != null) {
            mRTMPMuxer.close();
            mRTMPMuxer = null;
        }
        if (mVideoEncoder != null) {
            mVideoEncoder.stop();
            mVideoEncoder.release();
            mVideoEncoder = null;
        }
        if (mInputSurface != null) {
            mInputSurface.release();
            mInputSurface = null;
        }
        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        mVideoBufferInfo = null;
    }
}