package com.genymobile.scrcpy;

import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Surface;

import com.cry.cry.rtmp.RtmpClient;
import com.cry.cry.rtmp.rtmp.FLvMetaData;
import com.cry.cry.rtmp.sender.model.RESCoreParameters;
import com.genymobile.scrcpy.RtmpHelper.FlvMetaData;
import com.genymobile.scrcpy.RtmpHelper.Packager;
import com.genymobile.scrcpy.wrappers.SurfaceControl;
import com.github.faucamp.simplertmp.DefaultRtmpPublisher;

import net.ossrs.rtmp.ConnectCheckerRtmp;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

public class ScreenEncoder implements Device.RotationListener {

    private static final int DEFAULT_I_FRAME_INTERVAL = 5; // seconds
    private static final int REPEAT_FRAME_DELAY_US = 100_000; // repeat after 100ms
    private static final String KEY_MAX_FPS_TO_ENCODER = "max-fps-to-encoder";

    private static final int NO_PTS = -1;

    private final AtomicBoolean rotationChanged = new AtomicBoolean();
    private final AtomicBoolean rtmpStreamingEnabled = new AtomicBoolean(false);
    private final ByteBuffer headerBuffer = ByteBuffer.allocate(12);

    private List<CodecOption> codecOptions;
    private int bitRate;
    private int maxFps;
    private boolean sendFrameMeta;
    private long ptsOrigin;

    // Use for RTMP Streaming
    private final int MAX_QUEUE_CAPACITY = 50;
    private String rtmpServerUrl;
    private LinkedBlockingDeque<RtmpMessage> rtmpMessagesQueue = new LinkedBlockingDeque<>(MAX_QUEUE_CAPACITY);
    private Object syncWriteMsg = new Object();
    private int writeMsgNum = 0;
    private long startTime;
    private long jniPointer;

    public ScreenEncoder(boolean sendFrameMeta, int bitRate, int maxFps, List<CodecOption> codecOptions) {
        this.sendFrameMeta = sendFrameMeta;
        this.bitRate = bitRate;
        this.maxFps = maxFps;
        this.codecOptions = codecOptions;
    }

    public ScreenEncoder(boolean sendFrameMeta, int bitRate, int maxFps, List<CodecOption> codecOptions, String rtmpServerUrl) {
        this.sendFrameMeta = sendFrameMeta;
        this.bitRate = bitRate;
        this.maxFps = maxFps;
        this.codecOptions = codecOptions;

        this.rtmpServerUrl = rtmpServerUrl;
    }

    private boolean isRtmpStreamingEnabled() {
        return rtmpServerUrl != null && rtmpStreamingEnabled.get();
    }

    @Override
    public void onRotationChanged(int rotation) {
        rotationChanged.set(true);
    }

    public boolean consumeRotationChange() {
        return rotationChanged.getAndSet(false);
    }

    public void streamScreen(Device device, FileDescriptor fd) throws IOException {
        Workarounds.prepareMainLooper();

        try {
            internalStreamScreen(device, fd);
        } catch (NullPointerException e) {
            // Retry with workarounds enabled:
            // <https://github.com/Genymobile/scrcpy/issues/365>
            // <https://github.com/Genymobile/scrcpy/issues/940>
            Ln.d("Applying workarounds to avoid NullPointerException");
            Workarounds.fillAppInfo();
            internalStreamScreen(device, fd);
        }
    }

    // Video Content Rect for sending RTMP stream.
    private Rect videoRect;

    private boolean openRtmpConnect() {
        jniPointer = RtmpClient.open(rtmpServerUrl, true);
        return jniPointer != 0;
    }

    private void firstHandshake(int frameRate,int width,int height) {

        RESCoreParameters cp = new RESCoreParameters();
        cp.mediacodecAVCFrameRate = 60;
        cp.videoWidth = videoRect.width();
        cp.videoHeight = videoRect.height();
        FLvMetaData fLvMetaData = new FLvMetaData(cp);
        byte[] flvMetaBytes = fLvMetaData.getMetaData();
        RtmpClient.write(jniPointer,
                flvMetaBytes,
                flvMetaBytes.length,
                Packager.FLVPackager.FLV_RTMP_PACKET_TYPE_INFO, 0);
    }

    private void tryOpenRtmpStreaming() {
        new Thread(() -> {
            for (int i = 1; i <= 30; i++) {
                if (!openRtmpConnect()) {
                    Ln.e("Connect to RTMP server: " + rtmpServerUrl + " failed. Retrying times: " + i);
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Ln.e("Interrupted!", e);
                    }
                } else {
                    Ln.i("Connect to RTMP server: " + rtmpServerUrl + " successfully!");
                    rtmpStreamingEnabled.getAndSet(true);
                    firstHandshake(60, videoRect.width(),videoRect.height());
                    rtmpStreamingEventLoop();
                    break;
                }
            }
        }).start();
    }

    private void rtmpStreamingEventLoop() {
        new Thread(() -> {
            while (isRtmpStreamingEnabled()) {

                if(!rtmpMessagesQueue.isEmpty()) {
                    synchronized (syncWriteMsg) {
                        writeMsgNum--;
                    }

                    RtmpMessage elem = rtmpMessagesQueue.pop();
                    if(writeMsgNum >= ( (MAX_QUEUE_CAPACITY*2)/3 ) && ( elem.type == Packager.FLVPackager.FLV_RTMP_PACKET_TYPE_VIDEO ) ) {
                        continue;
                    }
                    final int res = RtmpClient.write(jniPointer,elem.buffer,elem.buffer.length, elem.type, (int)elem.dts);
                }
            }
        }).start();
    }

    private void internalStreamScreen(Device device, FileDescriptor fd) throws IOException {
        device.setRotationListener(this);
        boolean alive;
        try {
            do {
                MediaCodec codec = createCodec();
                IBinder display = createDisplay();
                ScreenInfo screenInfo = device.getScreenInfo();
                videoRect = screenInfo.getVideoSize().toRect();
                MediaFormat format = createFormat(bitRate, maxFps,videoRect.width(),videoRect.height(),codecOptions);



                Rect contentRect = screenInfo.getContentRect();
                // include the locked video orientation
                // does not include the locked video orientation
                Rect unlockedVideoRect = screenInfo.getUnlockedVideoSize().toRect();
                int videoRotation = screenInfo.getVideoRotation();
                int layerStack = device.getLayerStack();

                setSize(format, videoRect.width(), videoRect.height());
                configure(codec, format);
                Surface surface = codec.createInputSurface();
                setDisplaySurface(display, surface, videoRotation, contentRect, unlockedVideoRect, layerStack);
                codec.start();
                if (rtmpServerUrl != null) {
                    tryOpenRtmpStreaming();
                }
                try {
                    alive = encode(codec, fd);
                    // do not call stop() on exception, it would trigger an IllegalStateException
                    codec.stop();
                } finally {
                    destroyDisplay(display);
                    codec.release();
                    surface.release();
                    if (isRtmpStreamingEnabled()) {
                        Ln.i("Closing RTMP stream...");
                        RtmpClient.close(jniPointer);
                        rtmpStreamingEnabled.getAndSet(false);
                        Ln.i("RTMP Stream closed.");
                    }
                }
            } while (alive);
        } finally {
            device.setRotationListener(null);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class RtmpMessage {
        private byte[] buffer;
        private long dts;
        private int bufferSize;
        private int type;
    }

    private void handleVideoFormatChanged(MediaFormat newFormat) {
        byte[] AVCDecoderConfigurationRecord = Packager.H264Packager.generateAVCDecoderConfigurationRecord(newFormat);
        int packetLen = Packager.FLVPackager.FLV_VIDEO_TAG_LENGTH +
                AVCDecoderConfigurationRecord.length;
        byte[] finalBuff = new byte[packetLen];
        Packager.FLVPackager.fillFlvVideoTag(finalBuff,
                0,
                true,
                true,
                AVCDecoderConfigurationRecord.length);
        System.arraycopy(AVCDecoderConfigurationRecord, 0,
                finalBuff, Packager.FLVPackager.FLV_VIDEO_TAG_LENGTH, AVCDecoderConfigurationRecord.length);
        try {
            rtmpMessagesQueue.put(new RtmpMessage(finalBuff,0,packetLen,Packager.FLVPackager.FLV_RTMP_PACKET_TYPE_VIDEO));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void handleVideoDataArrived(MediaCodec.BufferInfo bufferInfo, ByteBuffer codecBuffer) {
        if(bufferInfo.size == 0) {
            return;
        }
        if (startTime == 0) {
            startTime = bufferInfo.presentationTimeUs / 1000;
        }
        codecBuffer.position(bufferInfo.offset+4);
        codecBuffer.limit(bufferInfo.offset+bufferInfo.size);

        long finalDts = (bufferInfo.presentationTimeUs/1000) - startTime;

        int realDataLength = codecBuffer.remaining();
        int packetLen = Packager.FLVPackager.FLV_VIDEO_TAG_LENGTH +
                Packager.FLVPackager.NALU_HEADER_LENGTH +
                realDataLength;

        byte[] finalBuff = new byte[packetLen];
        codecBuffer.get(finalBuff, Packager.FLVPackager.FLV_VIDEO_TAG_LENGTH + Packager.FLVPackager.NALU_HEADER_LENGTH, realDataLength);
        int frameType = finalBuff[Packager.FLVPackager.FLV_VIDEO_TAG_LENGTH + Packager.FLVPackager.NALU_HEADER_LENGTH] & 0x1F;
        Packager.FLVPackager.fillFlvVideoTag(finalBuff,
                0,
                false,
                frameType == 5,
                realDataLength);
        try {
            synchronized (syncWriteMsg) {

                if(writeMsgNum <= MAX_QUEUE_CAPACITY) {
                    rtmpMessagesQueue.put(new RtmpMessage(finalBuff, finalDts, packetLen, Packager.FLVPackager.FLV_RTMP_PACKET_TYPE_VIDEO));
                    writeMsgNum++;
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private boolean encode(MediaCodec codec, FileDescriptor fd) throws IOException {
        boolean eof = false;
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (!consumeRotationChange() && !eof) {
            int outputBufferId = codec.dequeueOutputBuffer(bufferInfo, -1);
            eof = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            try {
                if (consumeRotationChange()) {
                    // must restart encoding with new size
                    break;
                }
                if(outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = codec.getOutputFormat();
                    handleVideoFormatChanged(newFormat);
                    continue;
                }
                else if (outputBufferId >= 0) {
                    ByteBuffer codecBuffer = codec.getOutputBuffer(outputBufferId);

                    long currPts = calculateTimeStamp(bufferInfo);

                    if (sendFrameMeta) {
                        writeFrameMeta(fd, currPts, codecBuffer.remaining());
                    }
                    IO.writeFully(fd, codecBuffer);
                    // rewind for rtmp sending.

                    codecBuffer.rewind();
                    if(bufferInfo.size != 0 && bufferInfo.flags != MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                        handleVideoDataArrived(bufferInfo, codecBuffer);
                    }
                }
            } finally {
                if (outputBufferId >= 0) {
                    codec.releaseOutputBuffer(outputBufferId, false);
                }
            }
        }
        if(eof) {
            rtmpStreamingEnabled.getAndSet(false);
        }
        return !eof;
    }


    private long calculateTimeStamp(MediaCodec.BufferInfo bufferInfo) {
        long pts;
        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            pts = NO_PTS; // non-media data packet
        } else {
            if (ptsOrigin == 0) {
                ptsOrigin = bufferInfo.presentationTimeUs;
            }
            pts = bufferInfo.presentationTimeUs - ptsOrigin;
        }
        return pts;
    }

    private void writeFrameMeta(FileDescriptor fd, long pts, int packetSize) throws IOException {
        headerBuffer.clear();

        headerBuffer.putLong(pts);
        headerBuffer.putInt(packetSize);
        headerBuffer.flip();
        IO.writeFully(fd, headerBuffer);
    }

    private static MediaCodec createCodec() throws IOException {
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
    }

    private static void setCodecOption(MediaFormat format, CodecOption codecOption) {
        String key = codecOption.getKey();
        Object value = codecOption.getValue();

        if (value instanceof Integer) {
            format.setInteger(key, (Integer) value);
        } else if (value instanceof Long) {
            format.setLong(key, (Long) value);
        } else if (value instanceof Float) {
            format.setFloat(key, (Float) value);
        } else if (value instanceof String) {
            format.setString(key, (String) value);
        }

        Ln.d("Codec option set: " + key + " (" + value.getClass().getSimpleName() + ") = " + value);
    }

    private static MediaFormat createFormat(int bitRate, int maxFps, int videoWidth, int videoHeight,List<CodecOption> codecOptions) {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,videoWidth, videoHeight);
//        MediaFormat.MIMETYPE_VIDEO_AVC
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        // must be present to configure the encoder, but does not impact the actual frame rate, which is variable
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, DEFAULT_I_FRAME_INTERVAL);
        // display the very first frame, and recover from bad quality when no new frames
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_FRAME_DELAY_US); // µs
        if (maxFps > 0) {
            // The key existed privately before Android 10:
            // <https://android.googlesource.com/platform/frameworks/base/+/625f0aad9f7a259b6881006ad8710adce57d1384%5E%21/>
            // <https://github.com/Genymobile/scrcpy/issues/488#issuecomment-567321437>
            format.setFloat(KEY_MAX_FPS_TO_ENCODER, maxFps);
        }

        if (codecOptions != null) {
            for (CodecOption option : codecOptions) {
                setCodecOption(format, option);
            }
        }

        return format;
    }

    private static IBinder createDisplay() {
        return SurfaceControl.createDisplay("scrcpy", true);
    }

    private static void configure(MediaCodec codec, MediaFormat format) {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }

    private static void setSize(MediaFormat format, int width, int height) {
        format.setInteger(MediaFormat.KEY_WIDTH, width);
        format.setInteger(MediaFormat.KEY_HEIGHT, height);
    }

    private static void setDisplaySurface(IBinder display, Surface surface, int orientation, Rect deviceRect, Rect displayRect, int layerStack) {
        SurfaceControl.openTransaction();
        try {
            SurfaceControl.setDisplaySurface(display, surface);
            SurfaceControl.setDisplayProjection(display, orientation, deviceRect, displayRect);
            SurfaceControl.setDisplayLayerStack(display, layerStack);
        } finally {
            SurfaceControl.closeTransaction();
        }
    }

    private static void destroyDisplay(IBinder display) {
        SurfaceControl.destroyDisplay(display);
    }
}
