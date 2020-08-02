package com.genymobile.scrcpy.rtmp.senders;

import com.genymobile.scrcpy.rtmp.RtmpClient;
import com.genymobile.scrcpy.rtmp.Packager;
import com.genymobile.scrcpy.rtmp.RtmpStreamSender;

/**
 * @author <a href="yingyin.lsy@alibaba-inc.com"萤音</a>
 * @date 2020/8/2
 * @time 4:22 PM
 * @description:
 */
public class NativeRtmpStreamSender implements RtmpStreamSender {

    private long jniPointer;

    private int videoWidth = 0;
    private int videoHeight = 0;
    private boolean connected = false;

    private String rtmpServerUrl;

    @Override
    public boolean connect(String rtmpServerUrl) {
        this.rtmpServerUrl = rtmpServerUrl;
        jniPointer = RtmpClient.open(rtmpServerUrl, true);
        boolean opened = jniPointer != 0;
        if(opened) {
            connected = true;
        }
        return opened;
    }

    @Override
    public boolean writeVideoData(byte[] videoData, int offset, int length, int dts, int type) {
        if(!connected) {
            return false;
        }
        return RtmpClient.write(jniPointer, videoData, length, type, dts) == 0;
    }

    @Override
    public boolean writeAudioData(byte[] audioData, int offset, int length, int dts) {
        return RtmpClient.write(jniPointer, audioData, length, Packager.FLVPackager.FLV_RTMP_PACKET_TYPE_AUDIO, dts) == 0;
    }

    @Override
    public boolean disconnect() {
        RtmpClient.close(jniPointer);
        return true;
    }

    @Override
    public String getServerUrl() {
        return rtmpServerUrl;
    }

    @Override
    public void offerVideoResolution(int videoWidth, int videoHeight) {
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
    }
}
