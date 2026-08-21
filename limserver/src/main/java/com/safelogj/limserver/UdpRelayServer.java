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
    private static final int MAX_ADDRESSES_PER_USER = 30;
    private final DatabaseManager dbManager;
    private final byte[] inBuffer = new byte[BUFFER_SIZE];
    private final byte[] outBuffer = new byte[BUFFER_SIZE];
    private final int[] tokensIdxs = new int[MAX_ADDRESSES_PER_USER];
    private final int[] portsIdxs = new int[MAX_ADDRESSES_PER_USER];
    private final DatagramPacket inPacket = new DatagramPacket(inBuffer, BUFFER_SIZE);
    private final DatagramPacket outPacket = new DatagramPacket(outBuffer, BUFFER_SIZE);
    private final ByteBuffer headerReader = ByteBuffer.wrap(inBuffer);
    private final int port;
    private volatile boolean running;
    private DatagramSocket socket;
    private LimSocketAddress[][] usersAddresses;
    private LimSocketAddress portAddress;

    public UdpRelayServer(int port, DatabaseManager dbManager) {
        this.port = port;
        this.dbManager = dbManager;
    }

    public void start() {
        usersAddresses = new LimSocketAddress[Math.max(MAX_ADDRESSES_PER_USER, dbManager.getUsersCount())][MAX_ADDRESSES_PER_USER];
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
                if (senderId > 0 && senderId <= usersAddresses.length) {
                    long now = System.currentTimeMillis();
                    if (pLen == 4) {
                        sortAddresses(senderId, cleanAddresses(senderId, now), now);
                    } else if (pLen > HEADER_SIZE) {
                        long token = headerReader.getLong();
                        int targetId = headerReader.getInt();
                        if (targetId != senderId && targetId > 0 && targetId <= usersAddresses.length) {
                            setCallTokenToAddress(senderId, token, now);
                            relayToTarget(token, targetId);
                        }
                    }
                } else {
                    ensureCapacityUsersArray();
                }
            }
        } catch (IOException e) {
            if (running) {
                LimController.log.warn("UDP packet receive error: {}", e.getMessage());
            }
        }
    }

    private boolean cleanAddresses(int senderId, long now) {
        LimSocketAddress[] senderAddresses = usersAddresses[senderId - 1];
        InetSocketAddress incomingAddress = (InetSocketAddress) inPacket.getSocketAddress();
        int tokenIdx = 0;
        int portIdx = 0;
        boolean foundAddress = false;
        Arrays.fill(tokensIdxs, MAX_ADDRESSES_PER_USER); // забиваем индексы заглушкой размером массива
        Arrays.fill(portsIdxs, MAX_ADDRESSES_PER_USER);
        for (int i = 0; i < MAX_ADDRESSES_PER_USER && senderAddresses[i] != null; i++) {
            if (senderAddresses[i].isOldAddress(now) && !senderAddresses[i].getAddress().equals(incomingAddress)) {
                senderAddresses[i] = null;
                continue;
            }
            if (senderAddresses[i].getLastCallToken() > 0) {
                tokensIdxs[tokenIdx++] = i;
            } else {
                portsIdxs[portIdx++] = i;
                if (senderAddresses[i].getAddress().equals(incomingAddress)) {
                    senderAddresses[i].setLastKeepAliveTime(now);
                    senderAddresses[i].setLastCallToken(0);
                    foundAddress = true;
                }
            }
        }
        return foundAddress;
    }

    private void sortAddresses(int senderId, boolean foundAddress, long now) {
        if (tokensIdxs[MAX_ADDRESSES_PER_USER - 1] != MAX_ADDRESSES_PER_USER || portsIdxs[MAX_ADDRESSES_PER_USER - 1] != MAX_ADDRESSES_PER_USER) {  // нечего сортировать
            if (!foundAddress) {
                keepAliveUdp(senderId, now);
            }
            return;
        }

        LimSocketAddress[] senderAddresses = usersAddresses[senderId - 1];
        int tokenIdx = 0; // указатель по массиву индексов tokensIdxs
        int portIdx = 0; // указатель по массиву индексов portsIdxs
        for (int i = 0; i < MAX_ADDRESSES_PER_USER; i++) {
            if (tokensIdxs[tokenIdx] == MAX_ADDRESSES_PER_USER && portsIdxs[portIdx] == MAX_ADDRESSES_PER_USER) break; // отсортировали
            if (senderAddresses[i] == null) {
                if (tokensIdxs[tokenIdx] < MAX_ADDRESSES_PER_USER) { // указывает на индекс в senderAddresses с адресом с токеном
                    senderAddresses[i] = senderAddresses[tokensIdxs[tokenIdx]];
                    senderAddresses[tokensIdxs[tokenIdx]] = null;
                    tokenIdx++; // двигаем указатель по массиву индексов tokens так как его мы отсортировали

                } else if (portsIdxs[portIdx] < MAX_ADDRESSES_PER_USER) { // указывает на индекс в senderAddresses с адресом без токена
                    senderAddresses[i] = senderAddresses[portsIdxs[portIdx]];
                    senderAddresses[portsIdxs[portIdx]] = null;
                    portIdx++; // двигаем указатель по массиву индексов portsIdxs так как его мы отсортировали
                }
            } else { // если senderAddresses[i] не null
                if (tokensIdxs[tokenIdx] < MAX_ADDRESSES_PER_USER) { // указывает на индекс в senderAddresses с адресом с токеном, значит есть что ещё сортировать
                    if (tokensIdxs[tokenIdx] != i) { // значит в senderAddresses[i] адрес без токена, делаем свап с адресом без токена
                        portAddress = senderAddresses[i]; // сохраняем адрес без токена
                        senderAddresses[i] = senderAddresses[tokensIdxs[tokenIdx]]; // двигаем левее адрес с токеном, это сортировка
                        senderAddresses[tokensIdxs[tokenIdx]] = portAddress; // двигаем правее адрес без токена, а это не сортировка
                        portsIdxs[portIdx] = tokensIdxs[tokenIdx]; // запоминаем индекс в senderAddresses[i] куда двинули адрес без токена на который указывал portIdx, это не сортировка, по этому portIdx мы не увеличиваем
                    }
                    tokenIdx++; // двигаем указатель по массиву индексов tokens так как его мы отсортировали
                } else {
                    portIdx++; // двигаем указатель по массиву индексов ports так как в senderAddresses[i] лежит адрес без токена
                }
            }
        }
        portAddress = null;
        if (!foundAddress) {
            keepAliveUdp(senderId, now);
        }
    }

    private void keepAliveUdp(int senderId, long now) {
        LimSocketAddress[] senderAddresses = usersAddresses[senderId - 1];
        InetSocketAddress incomingAddress = (InetSocketAddress) inPacket.getSocketAddress();
        for (int i = 0; i < MAX_ADDRESSES_PER_USER; i++) {
            if (senderAddresses[i] == null) {
                senderAddresses[i] = new LimSocketAddress(incomingAddress);
                senderAddresses[i].setLastKeepAliveTime(now);
                return;
            }
        }
    }

    private void setCallTokenToAddress(int senderId, long token, long now) {
        LimSocketAddress[] senderAddresses = usersAddresses[senderId - 1];
        InetSocketAddress incomingAddress = (InetSocketAddress) inPacket.getSocketAddress();
        int addressIdx = -1;
        for (int i = 0; i < MAX_ADDRESSES_PER_USER && senderAddresses[i] != null; i++) {
            if (senderAddresses[i].getLastCallToken() == token) {
                if (!senderAddresses[i].getAddress().equals(incomingAddress) && (now - senderAddresses[i].getCallStartTime() > 5000)) {
                    senderAddresses[i].setAddress(incomingAddress);
                }
                senderAddresses[i].setLastKeepAliveTime(now);
                return;
            } else if (senderAddresses[i].getAddress().equals(incomingAddress)) {
                addressIdx = i;
            }
        }
        if (addressIdx != -1) {
            senderAddresses[addressIdx].setLastCallToken(token);
            senderAddresses[addressIdx].setCallStartTime(now);
            senderAddresses[addressIdx].setLastKeepAliveTime(now);
        } else {
            System.arraycopy(senderAddresses, 0, senderAddresses, 1, MAX_ADDRESSES_PER_USER - 1);
            senderAddresses[0] = new LimSocketAddress(incomingAddress);
            senderAddresses[0].setLastCallToken(token);
            senderAddresses[0].setCallStartTime(now);
            senderAddresses[0].setLastKeepAliveTime(now);
        }
    }

    private void relayToTarget(long token, int targetId) throws IOException {
        LimSocketAddress[] targetAddresses = usersAddresses[targetId - 1];
        for (int i = 0; i < MAX_ADDRESSES_PER_USER && targetAddresses[i] != null; i++) {
            if (targetAddresses[i].getLastCallToken() == token) {
                sendTo(targetAddresses[i].getAddress());
                return;
            }
        }
        for (int i = 0; i < MAX_ADDRESSES_PER_USER && targetAddresses[i] != null; i++) {
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

    private void ensureCapacityUsersArray() {
        int oldSize = usersAddresses.length;
        int newSize = dbManager.getUsersCount();
        if (oldSize < newSize) {
            usersAddresses = Arrays.copyOf(usersAddresses, newSize);
            for (int i = oldSize; i < newSize; i++) {
                usersAddresses[i] = new LimSocketAddress[MAX_ADDRESSES_PER_USER];
            }
        }
    }
}
