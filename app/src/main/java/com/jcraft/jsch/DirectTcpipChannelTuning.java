package com.jcraft.jsch;

/**
 * Package-local bridge to JSch channel window setters, which are intentionally not public.
 */
public final class DirectTcpipChannelTuning {
    private DirectTcpipChannelTuning() {}

    public static void setLocalWindowSize(ChannelDirectTCPIP channel, int windowSizeBytes) {
        if (channel == null) {
            throw new NullPointerException("channel");
        }
        if (windowSizeBytes <= 0) {
            throw new IllegalArgumentException("windowSizeBytes must be positive");
        }
        channel.setLocalWindowSizeMax(windowSizeBytes);
        channel.setLocalWindowSize(windowSizeBytes);
    }
}
