package com.jcraft.jsch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class DirectTcpipChannelTuningTest {
    @Test
    public void appliesOneMiBToInitialAndMaximumLocalWindow() {
        ChannelDirectTCPIP channel = new ChannelDirectTCPIP();

        DirectTcpipChannelTuning.setLocalWindowSize(channel, 1024 * 1024);

        assertEquals(1024 * 1024, channel.lwsize);
        assertEquals(1024 * 1024, channel.lwsize_max);
    }

    @Test
    public void rejectsInvalidWindow() {
        ChannelDirectTCPIP channel = new ChannelDirectTCPIP();

        assertThrows(
                IllegalArgumentException.class,
                () -> DirectTcpipChannelTuning.setLocalWindowSize(channel, 0));
    }
}
