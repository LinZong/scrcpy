package com.genymobile.scrcpy.rtmp;

import android.media.MediaCodec;
import android.media.MediaFormat;
import lombok.NonNull;

import java.nio.ByteBuffer;

/**
 * @author <a href="yingyin.lsy@alibaba-inc.com"萤音</a>
 * @date 2020/8/2
 * @time 4:46 PM
 * @description:
 */
public final class RtmpMessageUtils {

    private static volatile long startTime = 0;

    public static RtmpMessage parseFromSystemMediaVideoCodec(@NonNull MediaCodec.BufferInfo bufferInfo, @NonNull ByteBuffer codecBuffer) {
        if (bufferInfo.size == 0) {
            return null;
        }
        if (startTime == 0) {
            startTime = bufferInfo.presentationTimeUs / 1000;
        }
        codecBuffer.position(bufferInfo.offset + 4);
        codecBuffer.limit(bufferInfo.offset + bufferInfo.size);
        long finalDts = (bufferInfo.presentationTimeUs / 1000) - startTime;
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
        return new RtmpMessage(finalBuff, finalDts, packetLen, Packager.FLVPackager.FLV_RTMP_PACKET_TYPE_VIDEO);
    }

    public static RtmpMessage parseVideoFormatChanged(MediaFormat newFormat) {
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
        return new RtmpMessage(finalBuff, 0, packetLen, Packager.FLVPackager.FLV_RTMP_PACKET_TYPE_VIDEO);
    }

    public static synchronized void reset() {
        RtmpMessageUtils.reset();
    }
}
