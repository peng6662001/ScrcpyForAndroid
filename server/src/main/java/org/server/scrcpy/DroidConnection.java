package org.server.scrcpy;


import org.server.scrcpy.model.ByteUtils;
import org.server.scrcpy.model.CommandPacket;
import org.server.scrcpy.model.ControlPacket;
import org.server.scrcpy.model.MediaPacket;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public final class DroidConnection implements Closeable {

    public static final int PORT_VIDEO_CONTROL = 7007;
    public static final int PORT_AUDIO = 7008;

    private final Socket socket;
    private final OutputStream outputStream;
    private final DataInputStream inputStream;

    private DroidConnection(Socket socket) throws IOException {
        this.socket = socket;
        inputStream = new DataInputStream(socket.getInputStream());
        outputStream = socket.getOutputStream();
    }


    private static Socket listenAndAccept(int port) throws IOException {
        ServerSocket serverSocket = new ServerSocket(port);
        Socket sock = null;
        try {
            sock = serverSocket.accept();
        } finally {
            serverSocket.close();
        }
        return sock;
    }

    public static DroidConnection open(String ip) throws IOException {
        return open(ip, PORT_VIDEO_CONTROL);
    }

    public static DroidConnection open(String ip, int port) throws IOException {
        Socket socket = listenAndAccept(port);
        DroidConnection connection = null;
        if (!socket.getInetAddress().toString().equals(ip)) {
            Ln.w("socket connect address != " + ip);
        }
        // 判断 socket 有一个正确的地址
        if (!socket.getInetAddress().toString().isEmpty()) {
            connection = new DroidConnection(socket);
        }
        return connection;
    }

    public void close() throws IOException {
        try {
            socket.shutdownInput();
        } catch (IOException ignore) {
        }
        try {
            socket.shutdownOutput();
        } catch (IOException ignore) {
        }
        socket.close();
    }

    public OutputStream getOutputStream() {
        return outputStream;
    }


    /**
     *
     * @return
     * @throws IOException
     */
    public MediaPacket NewReceiveEvent() throws IOException {

        byte[] packetSize = new byte[4];
        inputStream.readFully(packetSize, 0, packetSize.length);

        int size = ByteUtils.bytesToInt(packetSize);

        if (size <= 0 || size > 4 * 1024 * 1024) {  // 如果单个数据包大于 4m ，直接断开连接
            throw new EOFException("Event controller socket closed");
        }
        byte[] packet = new byte[size];
        inputStream.readFully(packet, 0, size);

        MediaPacket.Type type = MediaPacket.Type.getType(packet[0]);
        switch (type) {
            case CONTROL:
                return new ControlPacket().fromArray(packet);
            case COMMAND:
                return new CommandPacket().fromArray(packet);
        }


//        byte[] buf = new byte[20];
//        int n = inputStream.read(buf, 0, 20);
//        if (n == -1) {
//            throw new EOFException("Event controller socket closed");
//        }
//
//        final int[] array = new int[buf.length / 4];
//        for (int i = 0; i < array.length; i++)
//            array[i] = (((int) (buf[i * 4]) << 24) & 0xFF000000) |
//                    (((int) (buf[i * 4 + 1]) << 16) & 0xFF0000) |
//                    (((int) (buf[i * 4 + 2]) << 8) & 0xFF00) |
//                    ((int) (buf[i * 4 + 3]) & 0xFF);
//        return array;


        return null;

    }

}
