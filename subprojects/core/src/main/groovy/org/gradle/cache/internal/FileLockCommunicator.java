package org.gradle.cache.internal;

import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;

/**
 * By Szczepan Faber on 5/23/13
 */
public class FileLockCommunicator {
    private DatagramSocket socket;
    private boolean stopped;

    public static void pingOwner(int ownerPort, File target) {
        DatagramSocket datagramSocket = null;
        try {
            datagramSocket = new DatagramSocket();
            byte[] bytesToSend = encodeFile(target);
            datagramSocket.send(new DatagramPacket(bytesToSend, bytesToSend.length, InetAddress.getLocalHost(), ownerPort));
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (datagramSocket != null) {
                datagramSocket.close();
            }
        }
    }

    public File receive() {
        try {
            byte[] bytes = new byte[2048];
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
            socket.receive(packet);
            return decodeFile(bytes);
        } catch (IOException e) {
            if (!stopped) {
                throw new RuntimeException(e);
            }
            return null;
        }
    }

    public void stop() {
        stopped = true;
        if (socket == null) {
            throw new IllegalStateException("The communicator was not started.");
        }
        socket.close();
    }

    private static byte[] encodeFile(File target) throws IOException {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(packet);
        data.writeUTF(target.getAbsolutePath());
        return packet.toByteArray();
    }

    private static File decodeFile(byte[] bytes) throws IOException {
        DataInputStream data = new DataInputStream(new ByteArrayInputStream(bytes));
        return new File(data.readUTF());
    }

    public int getPort() {
        return socket.getLocalPort();
    }

    public void start() {
        try {
            socket = new DatagramSocket();
        } catch (SocketException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean isStarted() {
        return socket != null;
    }
}