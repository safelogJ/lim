package com.safelogj.limserver.model;

import java.net.InetSocketAddress;

public class LimSocketAddress {
    private InetSocketAddress address;
    private long lastCallToken;
    private long callStartTime;


    public LimSocketAddress(InetSocketAddress address) {
        this.address = address;
    }

    public InetSocketAddress getAddress() {
        return address;
    }

    public void setAddress(InetSocketAddress address) {
        this.address = address;
    }

    public long getLastCallToken() {
        return lastCallToken;
    }

    public void setLastCallToken(long lastCallToken) {
        this.lastCallToken = lastCallToken;
    }

    public long getCallStartTime() {
        return callStartTime;
    }

    public void setCallStartTime(long callStartTime) {
        this.callStartTime = callStartTime;
    }
}
