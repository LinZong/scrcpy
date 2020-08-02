package com.genymobile.scrcpy.rtmp;

import com.genymobile.scrcpy.Ln;
import com.genymobile.scrcpy.rtmp.senders.NativeRtmpStreamSender;
import lombok.NonNull;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * @author <a href="yingyin.lsy@alibaba-inc.com"萤音</a>
 * @date 2020/8/2
 * @time 4:11 PM
 * @description:
 */
public class RtmpStreamClient {

    // For Video:

    private int avcFrameRate;
    private int videoWidth;
    private int videoHeight;
    private String rtmpServerUrl;
    private RtmpStreamSender sender;

    private volatile boolean connected = false;

    private static final int DEFAULT_CONNECT_RETRY_TIME = 30;
    private static final int MAX_QUEUE_MESSAGE = 100;


    private LinkedBlockingQueue<RtmpMessage> pendingMessageQueue = new LinkedBlockingQueue<>(MAX_QUEUE_MESSAGE);
    private ExecutorService executor = Executors.newFixedThreadPool(3);

    public RtmpStreamClient(int avcFrameRate, int videoWidth, int videoHeight, @NonNull String rtmpServerUrl) {
        this(avcFrameRate, videoWidth, videoHeight, rtmpServerUrl, new NativeRtmpStreamSender());
    }

    public RtmpStreamClient(int avcFrameRate, int videoWidth, int videoHeight, @NonNull String rtmpServerUrl, @NonNull RtmpStreamSender sender) {
        this.avcFrameRate = avcFrameRate;
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        this.rtmpServerUrl = rtmpServerUrl;
        this.sender = sender;
        sender.offerVideoResolution(videoWidth, videoHeight);
    }

    public boolean connect() {
        return connect(DEFAULT_CONNECT_RETRY_TIME);
    }

    private boolean checkRtmpServerUrl() {
        return (rtmpServerUrl != null && (rtmpServerUrl.startsWith("rtmp://") || rtmpServerUrl.startsWith("rtmps://")));
    }

    private void doHandshakeAfterConnection() {
        FlvMetaData flv = new FlvMetaData(avcFrameRate, videoWidth, videoHeight);
        byte[] flvMeta = flv.getMetaData();
        sender.writeVideoData(flvMeta, 0, flvMeta.length, 0, Packager.FLVPackager.FLV_RTMP_PACKET_TYPE_INFO);
    }

    private void beforeConnect() {
        if (!checkRtmpServerUrl()) {
            throw new IllegalArgumentException("RTMP server url is invald!");
        }
    }

    private void afterSuccessfulConnect() {
        Ln.i("Connect to RTMP server successfully! " + rtmpServerUrl);
        connected = true;
        doHandshakeAfterConnection();
        executor.submit(this::messageConsumeLoop);
    }


    public boolean putMessage(RtmpMessage message) {
        if (!connected) {
            Ln.e("Cannot put message before connected!");
            return false;
        }
        return pendingMessageQueue.offer(message);
    }

    private void messageConsumeLoop() {
        while (connected) {
            try {
                RtmpMessage message = pendingMessageQueue.poll(500L, TimeUnit.MILLISECONDS);
                if (message != null) {
                    byte[] data = message.getBuffer();
                    int size = message.getBufferSize();
                    int dts = (int) message.getDts();
                    int type = message.getType();
                    if(size > 0) {
                        switch (type) {
                            case Packager.FLVPackager.FLV_RTMP_PACKET_TYPE_VIDEO:
                                sender.writeVideoData(data, 0, size, dts, type);
                                break;
                            case Packager.FLVPackager.FLV_RTMP_PACKET_TYPE_AUDIO:
                                sender.writeAudioData(data, 0, size, dts);
                                break;
                            default:
                                break;
                        }
                    }
                }
            } catch (InterruptedException e) {
                // ignore
                break;
            }
        }
    }

    public boolean connect(int retryTime) {
        beforeConnect();
        boolean status = false;
        for (int i = 0; i <= retryTime; ++i) {
            status = sender.connect(rtmpServerUrl);
            if (status) {
                break;
            }
            Ln.e(String.format(Locale.ENGLISH, "Connect to RTMP server: %s failed. Retry: %d time(s)...", rtmpServerUrl, i + 1));
        }
        if (status) {
            afterSuccessfulConnect();
        } else {
            Ln.e("Connect to RTMP server failed after " + retryTime + " time(s) retry.");
        }
        return status;
    }

    public void disconnect() {
        sender.disconnect();
        pendingMessageQueue.clear();
        connected = false;
        RtmpMessageUtils.reset();
    }
}
