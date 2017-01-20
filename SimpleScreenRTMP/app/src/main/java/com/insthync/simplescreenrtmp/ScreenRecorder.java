package com.insthync.simplescreenrtmp;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.util.Log;
import android.view.Surface;

import net.butterflytv.rtmp_client.RTMPMuxer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenRecorder extends Thread {
    private static final String TAG = "ScreenRecorder";

    private int mWidth;
    private int mHeight;
    private int mBitRate;
    private int mDpi;
    private MediaProjection mMediaProjection;
    // parameters for the encoder
    private static final String MIME_TYPE = "video/avc"; // H.264 Advanced Video Coding
    private static final int FRAME_RATE = 60; // 60 fps
    private static final int IFRAME_INTERVAL = 1; // 1 seconds between I-frames
    private static final int TIMEOUT_US = 10000;
    // RTMP_URL Constraints
    private static final String RTMP_URL = "rtmp://188.166.191.129/live/test";

    private MediaCodec mEncoder;
    private MediaFormat mFormat;
    private Surface mSurface;
    private AtomicBoolean mQuit = new AtomicBoolean(false);
    private MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
    private VirtualDisplay mVirtualDisplay;
    // RTMP_URL
    private RTMPMuxer mRTMPMuxer;
    private long startTime;
    private long tryingAgainTime;
    private boolean isSetHeader;

    public ScreenRecorder(int width, int height, int bitrate, int dpi, MediaProjection mp) {
        super(TAG);
        mWidth = width;
        mHeight = height;
        mBitRate = bitrate;
        mDpi = dpi;
        mMediaProjection = mp;
    }

    public ScreenRecorder(MediaProjection mp) {
        // 480p 2Mbps
        this(640, 480, 2000000, 1, mp);
    }

    /**
     * stop task
     */
    public final void quit() {
        mQuit.set(true);
    }

    @Override
    public void run() {
        try {
            startTime = 0;
            tryingAgainTime = 0;
            isSetHeader = false;
            try {
                prepareEncoder();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            mRTMPMuxer = new RTMPMuxer();
            int result = mRTMPMuxer.open(RTMP_URL, mWidth, mHeight);
            Log.d(TAG, "RTMP_URL open result: " + result);

            mVirtualDisplay = mMediaProjection.createVirtualDisplay(TAG + "-display",
                    mWidth, mHeight, mDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                    mSurface, null, null);
            Log.d(TAG, "created virtual display: " + mVirtualDisplay);
            recordVirtualDisplay();

        } finally {
            release();
        }
    }

    private void recordVirtualDisplay() {
        while (!mQuit.get()) {

            int index = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_US);

            if (startTime == 0)
                startTime = System.currentTimeMillis();

            int timestamp = (int) (System.currentTimeMillis() - startTime);
            //Log.d(TAG, "dequeue output buffer index=" + index);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.d(TAG, "Format changed " + mEncoder.getOutputFormat());
            } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (tryingAgainTime == 0)
                    tryingAgainTime = System.currentTimeMillis();
                //Log.d(TAG, "Contents are not ready, trying again...");
            } else if (index >= 0) {
                if (tryingAgainTime > 0) {
                    long tryAgainAfterTime = System.currentTimeMillis() - tryingAgainTime;
                    Log.d(TAG, "Tried again after " + tryAgainAfterTime +" ms");
                    tryingAgainTime = 0;
                }
                ByteBuffer encodedData = mEncoder.getOutputBuffer(index);
                encodedData.position(mBufferInfo.offset);
                encodedData.limit(mBufferInfo.offset + mBufferInfo.size);

                byte[] bytes = new byte[encodedData.remaining()];
                encodedData.get(bytes);

                encodeToVideoTrack(index, timestamp, bytes);
                mEncoder.releaseOutputBuffer(index, false);
            }
        }
    }

    private void encodeToVideoTrack(int index, int timestamp, byte[] bytes) {


        if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            // Pulling codec config data
            if (!isSetHeader) {
                writeToMuxer(true, timestamp, bytes);
                isSetHeader = true;
            }
            mBufferInfo.size = 0;
        }

        if (mBufferInfo.size > 0) {
            writeToMuxer(false, timestamp, bytes);
        }
    }

    private void writeToMuxer(boolean isHeader, int timestamp, byte[] bytes) {
        int rtmpConnectionState = mRTMPMuxer != null ? mRTMPMuxer.isConnected() : 0;
        Log.d(TAG, "RTMP connection state: " + rtmpConnectionState + " timestamp: " + timestamp + " byte[] length: " + bytes.length);
        int writeResult = mRTMPMuxer.writeVideo(bytes, 0, bytes.length, timestamp);
        Log.d(TAG, "RTMP write data result: " + writeResult + " is header: " + isHeader);
    }

    private void prepareEncoder() throws IOException {
        mFormat = MediaFormat.createVideoFormat(MIME_TYPE, mWidth, mHeight);
        mFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        mFormat.setInteger(MediaFormat.KEY_BIT_RATE, mBitRate);
        mFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        mFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        mFormat.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000 / FRAME_RATE);

        Log.d(TAG, "created video format: " + mFormat);
        mEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
        mEncoder.configure(mFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mSurface = mEncoder.createInputSurface();
        Log.d(TAG, "created input surface: " + mSurface);
        mEncoder.start();
    }

    private void release() {
        if (mRTMPMuxer != null) {
            mRTMPMuxer.close();
            mRTMPMuxer = null;
        }
        if (mEncoder != null) {
            mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
        }
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }
    }
}