package com.genymobile.scrcpy.rtmp;

/**
 * @author <a href="yingyin.lsy@alibaba-inc.com"萤音</a>
 * @date 2020/8/2
 * @time 4:22 PM
 * @description:
 */
public interface RtmpStreamSender {

    boolean connect(String rtmpServerUrl);

    boolean writeVideoData(byte[] videoData, int offset, int length, int dts, int type);

    boolean writeAudioData(byte[] audioData, int offset, int length, int dts);

    boolean disconnect();

    String getServerUrl();

    void offerVideoResolution(int videoWidth, int videoHeight);
}
