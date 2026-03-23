package com.epp.backend.netty;

import com.epp.backend.config.JwtUtils;
import com.epp.backend.handler.MessageDispatcher;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceChannelHandlerTest {

    @Test
    void shouldRejectMessageWhenPayloadDeviceIdDoesNotMatchAuthedIdentity() {
        MessageDispatcher dispatcher = mock(MessageDispatcher.class);
        ChannelManager channelManager = mock(ChannelManager.class);
        JwtUtils jwtUtils = mock(JwtUtils.class);
        NettyBusinessThreadPool businessThreadPool = new NettyBusinessThreadPool();

        when(jwtUtils.validateAndGetId("valid-token")).thenReturn("device-1");

        DeviceChannelHandler handler = new DeviceChannelHandler(
                dispatcher, channelManager, businessThreadPool, jwtUtils);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        channel.writeInbound(new EppMessage((byte) 4, "", "valid-token"));
        channel.writeInbound(new EppMessage((byte) 1, "device-2", "{}"));

        assertFalse(channel.isActive());
        verify(dispatcher, never()).dispatch(eq("HEARTBEAT"), eq("device-2"), eq("{}"));
    }

    @Test
    void shouldUseAuthedIdentityWhenDispatchingBusinessMessage() {
        MessageDispatcher dispatcher = mock(MessageDispatcher.class);
        ChannelManager channelManager = mock(ChannelManager.class);
        JwtUtils jwtUtils = mock(JwtUtils.class);
        NettyBusinessThreadPool businessThreadPool = new NettyBusinessThreadPool();

        when(jwtUtils.validateAndGetId("valid-token")).thenReturn("device-1");

        DeviceChannelHandler handler = new DeviceChannelHandler(
                dispatcher, channelManager, businessThreadPool, jwtUtils);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        channel.writeInbound(new EppMessage((byte) 4, "", "valid-token"));
        channel.writeInbound(new EppMessage((byte) 1, "", "{}"));

        verify(dispatcher, timeout(1000)).dispatch("HEARTBEAT", "device-1", "{}");
    }
}
