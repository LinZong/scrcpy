package com.genymobile.scrcpy.rtmp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author <a href="yingyin.lsy@alibaba-inc.com"萤音</a>
 * @date 2020/8/2
 * @time 4:39 PM
 * @description:
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RtmpMessage {
    private byte[] buffer;
    private long dts;
    private int bufferSize;
    private int type;
}
