/*
 * Copyright 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.example.android.screencapture;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.AppCompatActivity;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;


/**
 * A simple launcher activity containing a summary sample description, sample log and a custom
 * {@link android.support.v4.app.Fragment} which can display a view.
 * <p>
 * For devices with displays with a width of 720dp or greater, the sample log is always visible,
 * on other devices it's visibility is controlled by an item on the Action Bar.
 */
public class MainActivity extends Activity {
    private static final String TAG = "ScreenRecordActivity";
    private MediaProjectionManager mediaProjectionManager;
    private MediaProjection mediaProjection;
    private MediaMuxer muxer;
    private Surface inputSurface;
    private MediaCodec videoEncoder;
    private boolean muxerStarted;
    private int trackIndex = -1;

    private static final int REQUEST_CODE_CAPTURE_PERM = 1234;
    private static final String VIDEO_MIME_TYPE = "video/avc";

    private android.media.MediaCodec.Callback encoderCallback;

    @TargetApi(Build.VERSION_CODES.M)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.new_layout);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            new AlertDialog.Builder(this)
                    .setTitle("Error")
                    .setMessage("This activity only works on Marshmallow or later.")
                    .setNeutralButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Log.d(TAG, "Closing");
                        }
                    })
                    .show();
            return;
        }


        Button toggleRecording = findViewById(R.id.screen_record_button);
        toggleRecording.setText(R.string.toggleRecordingOff);


        toggleRecording.setOnClickListener(new View.OnClickListener() {
            @RequiresApi(api = Build.VERSION_CODES.M)
            @Override
            public void onClick(View v) {
                if (v.getId() == R.id.screen_record_button) {
                    if (muxerStarted) {
                        stopRecording();
                        Log.d(TAG, "Stopping screen recording");
                        ((Button) findViewById(R.id.screen_record_button)).setText(R.string.toggleRecordingOff);
                    } else {
                        Log.d(TAG, "Starting screen capture");
                        ((Button) findViewById(R.id.screen_record_button)).setText(R.string.toggleRecordingOn);
                        Intent permissionIntent = mediaProjectionManager.createScreenCaptureIntent();
                        startActivityForResult(permissionIntent, REQUEST_CODE_CAPTURE_PERM);
                        findViewById(R.id.screen_record_button).setEnabled(false);
                    }
                }
            }

        });

        mediaProjectionManager = (MediaProjectionManager) getSystemService(
                android.content.Context.MEDIA_PROJECTION_SERVICE);

        encoderCallback = new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                Log.d(TAG, "Input Buffer Avail");
            }

            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                ByteBuffer encodedData = videoEncoder.getOutputBuffer(index);
                if (encodedData == null) {
                    throw new RuntimeException("couldn't fetch buffer at index " + index);
                }

                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    info.size = 0;
                }

                if (info.size != 0) {
                    if (muxerStarted) {
                        encodedData.position(info.offset);
                        encodedData.limit(info.offset + info.size);
                        muxer.writeSampleData(trackIndex, encodedData, info);
                    }
                }

                videoEncoder.releaseOutputBuffer(index, false);

            }

            @Override
            public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                Log.e(TAG, "MediaCodec " + codec.getName() + " onError:", e);
            }

            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                Log.d(TAG, "Output Format changed");
                if (trackIndex >= 0) {
                    throw new RuntimeException("format changed twice");
                }
                trackIndex = muxer.addTrack(videoEncoder.getOutputFormat());
                if (!muxerStarted && trackIndex >= 0) {
                    muxer.start();
                    muxerStarted = true;
                }
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void startRecording() {
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display defaultDisplay;
        if (dm != null) {
            defaultDisplay = dm.getDisplay(Display.DEFAULT_DISPLAY);
        } else {
            throw new IllegalStateException("Cannot display manager?!?");
        }
        if (defaultDisplay == null) {
            throw new RuntimeException("No display found.");
        }

        // Get the display size and density.
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int screenWidth = metrics.widthPixels;
        int screenHeight = metrics.heightPixels;
        int screenDensity = metrics.densityDpi;

        prepareVideoEncoder(screenWidth, screenHeight);

        try {
            File outputFile = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES) + "/screen_recording", "Screen-record-" +
                    Long.toHexString(System.currentTimeMillis()) + ".mp4");
            if (!outputFile.getParentFile().exists()) {
                outputFile.getParentFile().mkdirs();
            }
            muxer = new MediaMuxer(outputFile.getCanonicalPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (IOException ioe) {
            throw new RuntimeException("MediaMuxer creation failed", ioe);
        }


        // Start the video input.
        mediaProjection.createVirtualDisplay("Recording Display", screenWidth,
                screenHeight, screenDensity, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR/* flags */, inputSurface,
                null /* callback */, null /* handler */);
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void prepareVideoEncoder(int width, int height) {
        MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, width, height);
        int frameRate = 30; // 30 fps

        // Set some required properties. The media codec may fail if these aren't defined.
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 6000000); // 6Mbps
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_CAPTURE_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000 / frameRate);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); // 1 seconds between I-frames

        // Create a MediaCodec encoder and configure it. Get a Surface we can use for recording into.
        try {
            videoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
            videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = videoEncoder.createInputSurface();
            videoEncoder.setCallback(encoderCallback);
            videoEncoder.start();
        } catch (IOException e) {
            releaseEncoders();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void releaseEncoders() {
        if (muxer != null) {
            if (muxerStarted) {
                muxer.stop();
            }
            muxer.release();
            muxer = null;
            muxerStarted = false;
        }
        if (videoEncoder != null) {
            videoEncoder.stop();
            videoEncoder.release();
            videoEncoder = null;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        trackIndex = -1;
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void stopRecording() {
        releaseEncoders();
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (REQUEST_CODE_CAPTURE_PERM == requestCode) {
            Button b = findViewById(R.id.screen_record_button);
            b.setEnabled(true);
            if (resultCode == RESULT_OK) {
                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, intent);
                startRecording();
                b.setText(R.string.toggleRecordingOn);
            } else {
                // user did not grant permissions
                new AlertDialog.Builder(this)
                        .setTitle("Error")
                        .setMessage("Permission is required to record the screen.")
                        .setNeutralButton(android.R.string.ok, null)
                        .show();
            }
        }
    }
}

