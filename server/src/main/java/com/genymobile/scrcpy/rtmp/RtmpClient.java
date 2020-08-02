package com.genymobile.scrcpy.rtmp;

public class RtmpClient {
    static {
        System.loadLibrary("rtmp-lib");
    }
    private RtmpClient() {}

    /**
     * @param url
     * @param isPublishMode
     * @return rtmpPointer ,pointer to native rtmp struct
     */
    public static native long open(String url, boolean isPublishMode);

    public static native int write(long rtmpPointer, byte[] data, int size, int type, int ts);

    public static native int close(long rtmpPointer);

    public static native String getIpAddr(long rtmpPointer);
}
