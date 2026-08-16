package com.safelogj.limserver;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public class UdpRelayServer {
    private static final int BUFFER_SIZE = 2048;
    private static final int HEADER_SIZE = 16;

    private final int port;
    private DatagramSocket socket;
    private volatile boolean running;

    private final Map<Long, InetSocketAddress> clients = new HashMap<>();
    private final byte[] inBuffer = new byte[BUFFER_SIZE];
    private final byte[] outBuffer = new byte[BUFFER_SIZE];
    private final DatagramPacket inPacket = new DatagramPacket(inBuffer, BUFFER_SIZE);
    private final DatagramPacket outPacket = new DatagramPacket(outBuffer, BUFFER_SIZE);
    private final ByteBuffer headerReader = ByteBuffer.wrap(inBuffer);
    private final ByteBuffer headerWriter = ByteBuffer.wrap(outBuffer);

    public UdpRelayServer(int port) {
        this.port = port;
    }

    public void start() {
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
            if (inPacket.getLength() >= HEADER_SIZE) {
                headerReader.clear();
                long senderId = headerReader.getLong();
                long targetId = headerReader.getLong();
                updateClientAddress(senderId);
                if (targetId != 0) {
                    relayToTarget(senderId, targetId);
                }
            }
        } catch (IOException e) {
            if (running) {
                LimController.log.warn("UDP packet receive error: {}", e.getMessage());
            }
        }
    }

    private void updateClientAddress(long senderId) {
        InetSocketAddress currentAddress = clients.get(senderId);
        if (currentAddress == null || !currentAddress.getAddress().equals(inPacket.getAddress())
                || currentAddress.getPort() != inPacket.getPort()) {
            clients.put(senderId, new InetSocketAddress(inPacket.getAddress(), inPacket.getPort()));
        }
    }

    private void relayToTarget(long senderId, long targetId) throws IOException {
        InetSocketAddress targetAddress = clients.get(targetId);
        if (targetAddress != null) {
            int payloadLen = inPacket.getLength() - HEADER_SIZE;
            // Формируем исходящий пакет: [SenderID(8)] [Data]
            headerWriter.clear();
            headerWriter.putLong(senderId);
            System.arraycopy(inBuffer, HEADER_SIZE, outBuffer, 8, payloadLen);
            outPacket.setSocketAddress(targetAddress);
            outPacket.setLength(8 + payloadLen);
            socket.send(outPacket);
        }
    }
}
