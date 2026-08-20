package com.safelogj.limserver;

import com.safelogj.limserver.model.LimSocketAddress;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class UdpRelayServer {
    private static final int BUFFER_SIZE = 2048;
    private static final int HEADER_SIZE = 16;
    private static final int MAX_DEVICES_PER_USER = 10;
    private final DatabaseManager dbManager;
    private final byte[] inBuffer = new byte[BUFFER_SIZE];
    private final byte[] outBuffer = new byte[BUFFER_SIZE];
    private final DatagramPacket inPacket = new DatagramPacket(inBuffer, BUFFER_SIZE);
    private final DatagramPacket outPacket = new DatagramPacket(outBuffer, BUFFER_SIZE);
    private final ByteBuffer headerReader = ByteBuffer.wrap(inBuffer);
    private final int port;
    private volatile boolean running;
    private DatagramSocket socket;
    private LimSocketAddress[][] usersAddresses;

    public UdpRelayServer(int port, DatabaseManager dbManager) {
        this.port = port;
        this.dbManager = dbManager;
    }

    public void start() {
        usersAddresses = new LimSocketAddress[Math.max(MAX_DEVICES_PER_USER, dbManager.getUsersCount())][MAX_DEVICES_PER_USER];
        running = true;
        new Thread(this::run, "udp-relay-thread").start();
    }

    public void stop() {
        running = false;
        if (socket != null) {
            socket.close();
        }
    }

    private void run() {
        LimController.log.info("UDP Relay Server starting on port {}", port);
        try (DatagramSocket ds = new DatagramSocket(port)) {
            socket = ds;
            while (running) {
                processNextPacket();
            }
        } catch (IOException e) {
            if (running) {
                LimController.log.error("UDP Server critical error: ", e);
            }
        } finally {
            LimController.log.info("UDP Relay Server stopped");
        }
    }

    private void processNextPacket() {
        try {
            socket.receive(inPacket);
            int pLen = inPacket.getLength();
            if (pLen >= 4) {
                headerReader.clear();
                int senderId = headerReader.getInt();
                if (senderId > 0 && senderId < usersAddresses.length) {
                    if (pLen == 4) {
                        keepAliveUdp(senderId);
                    } else if (pLen > HEADER_SIZE) {
                        long token = headerReader.getLong();
                        int targetId = headerReader.getInt();
                        if (targetId != senderId && targetId > 0 && targetId < usersAddresses.length) {
                            setCallTokenToAddress(senderId, token);
                            relayToTarget(token, targetId);
                        }
                    }
                } else {
                    ensureCapacity();
                }
            }
        } catch (IOException e) {
            if (running) {
                LimController.log.warn("UDP packet receive error: {}", e.getMessage());
            }
        }
    }

    private void keepAliveUdp(int senderId) {
        LimSocketAddress[] senderAddresses = usersAddresses[senderId];
        InetSocketAddress incomingAddress = (InetSocketAddress) inPacket.getSocketAddress();
        for (int i = 0; (senderAddresses[i] != null && i < MAX_DEVICES_PER_USER); i++) {
            if (senderAddresses[i].getAddress().equals(incomingAddress)) {
                return;
            }
        }
        System.arraycopy(senderAddresses, 0, senderAddresses, 1, MAX_DEVICES_PER_USER - 1);
        senderAddresses[0] = new LimSocketAddress(incomingAddress);
    }

    private void setCallTokenToAddress(int senderId, long token) {
        LimSocketAddress[] senderAddresses = usersAddresses[senderId];
        InetSocketAddress incomingAddress = (InetSocketAddress) inPacket.getSocketAddress();
        int addressIdx = -1;
        for (int i = 0; (senderAddresses[i] != null && i < MAX_DEVICES_PER_USER); i++) {
            if (senderAddresses[i].getLastCallToken() == token) {
                if (!senderAddresses[i].getAddress().equals(incomingAddress) && (System.currentTimeMillis() - senderAddresses[i].getCallStartTime() > 5000)) {
                    senderAddresses[i].setAddress(incomingAddress);
                }
                return;
            } else if (senderAddresses[i].getAddress().equals(incomingAddress)) {
                addressIdx = i;
            }
        }
        if (addressIdx != -1) {
            senderAddresses[addressIdx].setLastCallToken(token);
            senderAddresses[addressIdx].setCallStartTime(System.currentTimeMillis());
        } else {
            System.arraycopy(senderAddresses, 0, senderAddresses, 1, MAX_DEVICES_PER_USER - 1);
            senderAddresses[0] = new LimSocketAddress(incomingAddress);
            senderAddresses[0].setLastCallToken(token);
            senderAddresses[0].setCallStartTime(System.currentTimeMillis());
        }
    }

    private void relayToTarget(long token, int targetId) throws IOException {
        LimSocketAddress[] targetAddresses = usersAddresses[targetId];
        for (int i = 0; (targetAddresses[i] != null && i < MAX_DEVICES_PER_USER); i++) {
            if (targetAddresses[i].getLastCallToken() == token) {
                sendTo(targetAddresses[i].getAddress());
                return;
            }
        }
        for (int i = 0; (targetAddresses[i] != null && i < MAX_DEVICES_PER_USER); i++) {
            sendTo(targetAddresses[i].getAddress());
        }
    }

    private void sendTo(InetSocketAddress targetAddress) throws IOException {
        int packetLen = inPacket.getLength();
        System.arraycopy(inBuffer, 0, outBuffer, 0, packetLen);

        outPacket.setSocketAddress(targetAddress);
        outPacket.setLength(packetLen);
        socket.send(outPacket);
    }

    private void ensureCapacity() {
        int oldSize = usersAddresses.length;
        int newSize = dbManager.getUsersCount();
        if (oldSize < newSize) {
            usersAddresses = Arrays.copyOf(usersAddresses, newSize);
            for (int i = oldSize; i < newSize; i++) {
                usersAddresses[i] = new LimSocketAddress[MAX_DEVICES_PER_USER];
            }
        }
    }
}
