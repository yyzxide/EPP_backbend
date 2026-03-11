package com.epp.backend.handler;

public interface IMessageHandler {
    String getType();
    void handle(String deviceId, String payload);
}
