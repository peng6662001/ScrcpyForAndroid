package org.client.scrcpy;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;

import org.client.scrcpy.decoder.AudioDecoder;
import org.client.scrcpy.decoder.VideoDecoder;
import org.client.scrcpy.model.AudioPacket;
import org.client.scrcpy.model.ByteUtils;
import org.client.scrcpy.model.CommandPacket;
import org.client.scrcpy.model.ControlPacket;
import org.client.scrcpy.model.MediaPacket;
import org.client.scrcpy.model.VideoPacket;
import org.client.scrcpy.utils.Util;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;


public class Scrcpy extends Service {
    public static final String DISCONNECT_REASON_CONNECT_RETRY_EXHAUSTED = "connect_retry_exhausted";
    public static final String DISCONNECT_REASON_CONTROL_WRITE_FAILED = "control_write_failed";
    public static final String DISCONNECT_REASON_PACKET_TOO_LARGE = "packet_too_large";
    public static final String DISCONNECT_REASON_INVALID_PACKET_SIZE = "invalid_packet_size";
    public static final String DISCONNECT_REASON_STREAM_READ_FAILED = "stream_read_failed";

    public static final String LOCAL_IP = "127.0.0.1";
    // 本地画面转发占用的端口
    public static final int LOCAL_FORWART_PORT = 7008;
    public static final int LOCAL_AUDIO_FORWARD_PORT = 7009;

    public static final int DEFAULT_ADB_PORT = 5555;
    private String serverHost;
    private int serverPort = DEFAULT_ADB_PORT;
    private Surface surface;
    private int screenWidth;
    private int screenHeight;

    private final Queue<byte[]> event = new LinkedList<byte[]>();
    // private byte[] event = null;
    private VideoDecoder videoDecoder;
    private AudioDecoder audioDecoder;
    private final AtomicBoolean updateAvailable = new AtomicBoolean(false);
    private final IBinder mBinder = new MyServiceBinder();
    private boolean first_time = true;

    private final AtomicBoolean LetServceRunning = new AtomicBoolean(true);
    private ServiceCallbacks serviceCallbacks;
    private final int[] remote_dev_resolution = new int[2];
    private boolean socket_status = false;

    private DataInputStream socketInputStream = null;
    private DataOutputStream socketOutputStream = null;
    private final AtomicBoolean audioConfigured = new AtomicBoolean(false);
    private final AtomicBoolean audioEnabled = new AtomicBoolean(true);
    private final Object trafficLock = new Object();
    private long trafficWindowStartMs = System.currentTimeMillis();
    private long trafficWindowBytes = 0;
    private long trafficBytesPerSecond = 0;

    private void notifyDisconnect(String reason, Exception error) {
        socket_status = false;
        String detail = reason;
        if (error != null && !TextUtils.isEmpty(error.getMessage())) {
            detail = reason + ": " + error.getMessage();
        }
        Log.e("Scrcpy", "disconnect: " + detail, error);
        if (serviceCallbacks != null) {
            serviceCallbacks.errorDisconnect(reason, detail);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public void setServiceCallbacks(ServiceCallbacks callbacks) {
        serviceCallbacks = callbacks;
    }

    public void setAudioEnabled(boolean enabled) {
        audioEnabled.set(enabled);
    }

    public boolean isAudioEnabled() {
        return audioEnabled.get();
    }

    public String getTrafficSpeedText() {
        synchronized (trafficLock) {
            updateTrafficWindowLocked(System.currentTimeMillis(), 0);
            if (trafficBytesPerSecond >= 1024 * 1024) {
                return String.format(Locale.US, "%.2f MB/s", trafficBytesPerSecond / (1024f * 1024f));
            }
            return String.format(Locale.US, "%.0f KB/s", trafficBytesPerSecond / 1024f);
        }
    }

    public void setParms(Surface NewSurface, int NewWidth, int NewHeight) {
        this.screenWidth = NewWidth;
        this.screenHeight = NewHeight;
        this.surface = NewSurface;

        videoDecoder.start();
        audioDecoder.start();


        updateAvailable.set(true);
        audioConfigured.set(false);

    }

    public void start(Surface surface, String serverAdr, int screenHeight, int screenWidth, int delay) {
        this.videoDecoder = new VideoDecoder();
        videoDecoder.start();

        this.audioDecoder = new AudioDecoder();
        audioDecoder.start();

        String[] serverInfo = Util.getServerHostAndPort(serverAdr);
        this.serverHost = serverInfo[0];
        this.serverPort = Integer.parseInt(serverInfo[1]);

        this.screenHeight = screenHeight;
        this.screenWidth = screenWidth;
        this.surface = surface;
        audioConfigured.set(false);
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                startConnection(serverHost, serverPort, delay);
            }
        });
        thread.start();
    }

    public void pause() {
        if (videoDecoder != null) {
            videoDecoder.stop();
        }

        if (audioDecoder != null) {
            audioDecoder.stop();
        }
    }

    public void resume() {
        if (videoDecoder != null) {
            videoDecoder.start();
        }
        if (audioDecoder != null) {
            audioDecoder.start();
        }
        updateAvailable.set(true);

        Thread thread = new Thread(() -> {
            try {  // 请求关键帧, 避免花屏
                requestNewKeyFrame();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        thread.start();
    }

    public void StopService() {
        LetServceRunning.set(false);
        if (videoDecoder != null) {
            videoDecoder.stop();
        }
        if (audioDecoder != null) {
            audioDecoder.stop();
        }
        stopSelf();
    }


    public boolean touchevent(MotionEvent touch_event, boolean landscape, int displayW, int displayH) {
        float remoteW;
        float remoteH;
        float realH;
        float realW;

        if (landscape) {  // 横屏的话，宽高相反
            remoteW = Math.max(remote_dev_resolution[0], remote_dev_resolution[1]);
            remoteH = Math.min(remote_dev_resolution[0], remote_dev_resolution[1]);

            realW = Math.min(remoteW, screenWidth);
            realH = realW * remoteH / remoteW;
        } else {
            remoteW = Math.min(remote_dev_resolution[0], remote_dev_resolution[1]);
            remoteH = Math.max(remote_dev_resolution[0], remote_dev_resolution[1]);
            realH = Math.min(remoteH, screenHeight);
            realW = realH * remoteW / remoteH;
        }

        int actionIndex = touch_event.getActionIndex();
        int pointerId = touch_event.getPointerId(actionIndex);
        int pointCount = touch_event.getPointerCount();
        // Log.e("Scrcpy", "pointer id: " + pointerId + " , action: " + touch_event.getAction() + " ,point count: " + pointCount + " x: " + touch_event.getX() + " y: " + touch_event.getY());

        switch (touch_event.getAction()) {
            case MotionEvent.ACTION_MOVE: // 所有手指移动
                // 遍历所有触摸点，使用 pointerId 和 pointerIndex 来获取所有触摸点的信息
                for (int i = 0; i < touch_event.getPointerCount(); i++) {
                    int currentPointerId = touch_event.getPointerId(i);
                    int x = (int) touch_event.getX(i);
                    int y = (int) touch_event.getY(i);
                    // 处理每一个触摸点的x, y坐标
                    // Log.e("Scrcpy", "触摸移动，index : " + i + " ,x : " + x + " , y: " + y + " ,currentPointerId: " + currentPointerId);
                    sendTouchEvent(touch_event.getAction(), touch_event.getButtonState(), (int) (x * realW / displayW), (int) (y * realH / displayH), currentPointerId);
                }
                break;
            case MotionEvent.ACTION_POINTER_UP: // 中间手指抬起
            case MotionEvent.ACTION_UP: // 最后一个手指抬起
            case MotionEvent.ACTION_DOWN: // 第一个手指按下
            case MotionEvent.ACTION_POINTER_DOWN: // 中间的手指按下
            default:
                sendTouchEvent(touch_event.getAction(), touch_event.getButtonState(), (int) (touch_event.getX() * realW / displayW), (int) (touch_event.getY() * realH / displayH), pointerId);
                break;

        }
        return true;
    }

    private void sendTouchEvent(int action, int buttonState, int x, int y, int pointerId) {
        // 为支持多点触控，将 pointid 添加到最末尾
        // TODO : 后续需要改造 event 传输方式
        int[] buf = new int[]{action, buttonState, x, y, pointerId};
        final byte[] array = new byte[buf.length * 4]; // https://stackoverflow.com/questions/2183240/java-integer-to-byte-array
        for (int j = 0; j < buf.length; j++) {
            final int c = buf[j];
            array[j * 4] = (byte) ((c & 0xFF000000) >> 24);
            array[j * 4 + 1] = (byte) ((c & 0xFF0000) >> 16);
            array[j * 4 + 2] = (byte) ((c & 0xFF00) >> 8);
            array[j * 4 + 3] = (byte) (c & 0xFF);
        }
        if (LetServceRunning.get()) {
            event.offer(array);
        }
        // event = array;
    }

    public int[] get_remote_device_resolution() {
        return remote_dev_resolution;
    }

    public boolean check_socket_connection() {
        return socket_status;
    }

    public void sendKeyevent(int keycode) {
        int[] buf = new int[]{keycode};

        final byte[] array = new byte[buf.length * 4];   // https://stackoverflow.com/questions/2183240/java-integer-to-byte-array
        for (int j = 0; j < buf.length; j++) {
            final int c = buf[j];
            array[j * 4] = (byte) ((c & 0xFF000000) >> 24);
            array[j * 4 + 1] = (byte) ((c & 0xFF0000) >> 16);
            array[j * 4 + 2] = (byte) ((c & 0xFF00) >> 8);
            array[j * 4 + 3] = (byte) (c & 0xFF);
        }
        if (LetServceRunning.get()) {
            event.offer(array);
            // event = array;
        }
    }

    private void startConnection(String ip, int port, int delay) {

        videoDecoder = new VideoDecoder();
        videoDecoder.start();
        audioDecoder = new AudioDecoder();
        audioDecoder.start();
        resetTrafficStats();

        DataInputStream dataInputStream = null;
        DataOutputStream dataOutputStream = null;
        Socket socket = null;
        boolean firstConnect = true;
        int attempts = 50;
        while (attempts > 0 && LetServceRunning.get()) {
            try {
                Log.e("Scrcpy", "Connecting to " + LOCAL_IP);
                // socket = new Socket(ip, port);
                socket = new Socket();
                socket.connect(new InetSocketAddress(ip, port), 5000); //设置超时5000毫秒
                if (!LetServceRunning.get()) {
                    return;
                }

                Log.e("Scrcpy", "Connecting to " + LOCAL_IP + " success");

                // 能够正常进行连接，说明可能建立了 tcp 连接，需要等待数据
                // 一次等待时间为 2s ，最多等待五次，也就是 10秒
                if (firstConnect) {  // 此处有 while 循环，不能一直设置为10
                    firstConnect = false;
                    // waitResolutionCount 为 10，等待100ms 也就是共计一秒钟，设置attempts 为 5，也就是 5秒后则退出
                    attempts = 5;
                }
                dataInputStream = new DataInputStream(socket.getInputStream());
                int waitResolutionCount = 10;
                while (dataInputStream.available() <= 0 && waitResolutionCount > 0) {
                    waitResolutionCount--;
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignore) {
                    }
                }
                if (dataInputStream.available() <= 0) {
                    throw new IOException("can't read socket Resolution : " + attempts);
                }


                dataOutputStream = new DataOutputStream(socket.getOutputStream());
                attempts = 0;
                // Server sends exactly 2 ints (width/height), i.e. 8 bytes.
                // Reading 16 bytes here would consume the first 8 bytes of the media stream
                // and corrupt all subsequent packet boundaries.
                byte[] buf = new byte[remote_dev_resolution.length * 4];
                dataInputStream.readFully(buf, 0, buf.length);
                for (int i = 0; i < remote_dev_resolution.length; i++) {
                    remote_dev_resolution[i] = (((int) (buf[i * 4]) << 24) & 0xFF000000) |
                            (((int) (buf[i * 4 + 1]) << 16) & 0xFF0000) |
                            (((int) (buf[i * 4 + 2]) << 8) & 0xFF00) |
                            ((int) (buf[i * 4 + 3]) & 0xFF);
                }
                if (remote_dev_resolution[0] > remote_dev_resolution[1]) {
                    first_time = false;
                    int i = remote_dev_resolution[0];
                    remote_dev_resolution[0] = remote_dev_resolution[1];
                    remote_dev_resolution[1] = i;
                }

                socketInputStream = dataInputStream;
                socketOutputStream = dataOutputStream;

                socket_status = true;
                audioConfigured.set(false);
                startAudioConnection(ip, LOCAL_AUDIO_FORWARD_PORT, delay);

                loopVideoAndControl(dataInputStream, dataOutputStream, delay);

            } catch (Exception e) {
                e.printStackTrace();
                if (LetServceRunning.get()) {
                    attempts--;
                    if (attempts < 0) {
                        notifyDisconnect(DISCONNECT_REASON_CONNECT_RETRY_EXHAUSTED, e);
                        return;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignore) {
                    }
                }
                Log.e("Scrcpy", e.getMessage());
                Log.e("Scrcpy", "attempts--");
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                if (dataOutputStream != null) {
                    try {
                        dataOutputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                if (dataInputStream != null) {
                    try {
                        dataInputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                socketInputStream = null;
                socketOutputStream = null;
                // 清除事件队列
                event.clear();

            }

        }

    }

    private void resetTrafficStats() {
        synchronized (trafficLock) {
            trafficWindowStartMs = System.currentTimeMillis();
            trafficWindowBytes = 0;
            trafficBytesPerSecond = 0;
        }
    }

    private void recordTrafficBytes(int size) {
        synchronized (trafficLock) {
            updateTrafficWindowLocked(System.currentTimeMillis(), size);
        }
    }

    private void updateTrafficWindowLocked(long now, long appendedBytes) {
        if (now - trafficWindowStartMs >= 1000) {
            trafficBytesPerSecond = trafficWindowBytes;
            trafficWindowBytes = 0;
            trafficWindowStartMs = now;
        }
        trafficWindowBytes += appendedBytes;
    }

    /**
     * Request Keyframe
     * 请求关键帧
     */
    public boolean requestNewKeyFrame() throws IOException {
        if (LetServceRunning.get() && socketOutputStream != null) {
            socketOutputStream.write(CommandPacket.toArray(MediaPacket.Type.COMMAND, CommandPacket.CmdType.VIDEO_NEW_KEY_FRAME, new byte[0]));
            return true;
        }
        return false;
    }

    private void startAudioConnection(String ip, int port, int delay) {
        Thread thread = new Thread(() -> {
            Socket audioSocket = null;
            DataInputStream audioInputStream = null;
            int attempts = 30;
            while (attempts > 0 && LetServceRunning.get() && socket_status) {
                try {
                    audioSocket = new Socket();
                    audioSocket.connect(new InetSocketAddress(ip, port), 5000);
                    audioInputStream = new DataInputStream(audioSocket.getInputStream());
                    loopAudio(audioInputStream, delay);
                    return;
                } catch (IOException e) {
                    attempts--;
                    Log.e("Scrcpy", "audio socket connect/read failed: " + e.getMessage());
                    if (attempts <= 0 || !LetServceRunning.get() || !socket_status) {
                        return;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignore) {
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } finally {
                    if (audioInputStream != null) {
                        try {
                            audioInputStream.close();
                        } catch (IOException ignore) {
                        }
                        audioInputStream = null;
                    }
                    if (audioSocket != null) {
                        try {
                            audioSocket.close();
                        } catch (IOException ignore) {
                        }
                        audioSocket = null;
                    }
                }
            }
        }, "scrcpy-audio");
        thread.start();
    }

    private void loopVideoAndControl(DataInputStream dataInputStream, DataOutputStream dataOutputStream, int delay) throws InterruptedException {
        VideoPacket.StreamSettings streamSettings = null;
        byte[] packetSize = new byte[4];

        // 由于网络传输存在延迟，丢弃数据包计数
        long lastVideoOffset = 0;

        boolean waitKeyFrame = false;


        while (LetServceRunning.get()) {
            boolean waitEvent = true;
            try {
                byte[] sendevent = event.poll();
                if (sendevent != null) {
                    waitEvent = false;
                    try {
                        byte[] data = ControlPacket.toArray(MediaPacket.Type.CONTROL, sendevent);
                        dataOutputStream.write(data);
                    } catch (IOException e) {
                        e.printStackTrace();
                        notifyDisconnect(DISCONNECT_REASON_CONTROL_WRITE_FAILED, e);
                        LetServceRunning.set(false);
                    } finally {
                        // event = null;
                    }
                }

                if (dataInputStream.available() > 0) {
                    waitEvent = false;
                    dataInputStream.readFully(packetSize, 0, 4);
                    recordTrafficBytes(4);
                    int size = ByteUtils.bytesToInt(packetSize);
                    if (size <= 0) {
                        notifyDisconnect(DISCONNECT_REASON_INVALID_PACKET_SIZE,
                                new IllegalStateException("invalid packet size: " + size));
                        LetServceRunning.set(false);
                        return;
                    }
                    if (size > 4 * 1024 * 1024) {  // 如果单个数据包大于 4m ，直接断开连接
                        notifyDisconnect(DISCONNECT_REASON_PACKET_TOO_LARGE, null);
                        LetServceRunning.set(false);
                        return;
                    }
                    byte[] packet = new byte[size];
                    dataInputStream.readFully(packet, 0, size);
                    recordTrafficBytes(size);
                    if (MediaPacket.Type.getType(packet[0]) == MediaPacket.Type.VIDEO) {
                        VideoPacket videoPacket = VideoPacket.readHead(packet);
                        // byte[] data = videoPacket.data;
                        if (videoPacket.flag == VideoPacket.Flag.CONFIG || updateAvailable.get()) {
                            if (!updateAvailable.get()) {
                                int dataLength = packet.length - videoPacket.headLength();
                                byte[] data = new byte[dataLength];
                                System.arraycopy(packet, videoPacket.headLength(), data, 0, dataLength);
                                streamSettings = VideoPacket.getStreamSettings(data);
                                if (!first_time) {
                                    if (serviceCallbacks != null) {
                                        serviceCallbacks.loadNewRotation();
                                    }
                                    while (!updateAvailable.get()) {
                                        // Waiting for new surface
                                        try {
                                            Thread.sleep(100);
                                        } catch (InterruptedException e) {
                                            e.printStackTrace();
                                        }
                                    }

                                }
                            }
                            updateAvailable.set(false);
                            if (streamSettings != null) {
                                videoDecoder.configure(surface, screenWidth, screenHeight, streamSettings.sps, streamSettings.pps);
                            }
                        } else if (videoPacket.flag == VideoPacket.Flag.END) {
                            // need close stream
                            Log.e("Scrcpy", "END ... ");
                        } else {
                            // Log.e("Scrcpy", "videoPacket presentationTimeStamp ... " + videoPacket.presentationTimeStamp);
                            // 帧在 100 ms 以内
                            if (lastVideoOffset == 0) {
                                lastVideoOffset = System.currentTimeMillis() - (videoPacket.presentationTimeStamp / 1000);
                            }
                            if (videoPacket.flag == VideoPacket.Flag.KEY_FRAME) {
                                if (System.currentTimeMillis() - (lastVideoOffset + (videoPacket.presentationTimeStamp / 1000)) < delay) {
                                    waitKeyFrame = false;
                                    videoDecoder.decodeSample(packet, videoPacket.headLength(), packet.length - videoPacket.headLength(),
                                            0, videoPacket.flag.getFlag());
                                } else {
                                    waitKeyFrame = true;
                                    requestNewKeyFrame();
                                }
                            } else {
                                if (!waitKeyFrame) {
                                    videoDecoder.decodeSample(packet, videoPacket.headLength(), packet.length - videoPacket.headLength(),
                                            0, videoPacket.flag.getFlag());
                                }
                            }
                        }
                        first_time = false;
                    } else {
                        Log.w("Scrcpy", "unexpected packet type on video/control socket: " + packet[0]);
                    }

                }
            } catch (IOException e) {
                Log.e("Scrcpy", "IOException: " + e.getMessage());
                e.printStackTrace();
                notifyDisconnect(DISCONNECT_REASON_STREAM_READ_FAILED, e);
                LetServceRunning.set(false);
            } finally {
                if (waitEvent) {
                    Thread.sleep(5);
                }
            }
        }
    }

    private void loopAudio(DataInputStream dataInputStream, int delay) throws IOException, InterruptedException {
        byte[] packetSize = new byte[4];
        long lastAudioOffset = 0;
        while (LetServceRunning.get() && socket_status) {
            boolean waitEvent = true;
            try {
                if (dataInputStream.available() > 0) {
                    waitEvent = false;
                    dataInputStream.readFully(packetSize, 0, 4);
                    recordTrafficBytes(4);
                    int size = ByteUtils.bytesToInt(packetSize);
                    if (size <= 0 || size > 4 * 1024 * 1024) {
                        throw new IOException("invalid audio packet size: " + size);
                    }
                    byte[] packet = new byte[size];
                    dataInputStream.readFully(packet, 0, size);
                    recordTrafficBytes(size);
                    if (MediaPacket.Type.getType(packet[0]) != MediaPacket.Type.AUDIO) {
                        Log.w("Scrcpy", "unexpected packet type on audio socket: " + packet[0]);
                        continue;
                    }
                    AudioPacket audioPacket = AudioPacket.readHead(packet);
                    if (audioPacket.flag == AudioPacket.Flag.CONFIG) {
                        int dataLength = packet.length - audioPacket.headLength();
                        byte[] data = new byte[dataLength];
                        System.arraycopy(packet, audioPacket.headLength(), data, 0, dataLength);
                        audioDecoder.configure(data);
                        audioConfigured.set(true);
                    } else if (audioPacket.flag == AudioPacket.Flag.END) {
                        Log.e("Scrcpy", "Audio END ... ");
                        return;
                    } else if (audioConfigured.get()) {
                        if (lastAudioOffset == 0) {
                            lastAudioOffset = System.currentTimeMillis() - (audioPacket.presentationTimeStamp / 1000);
                        }
                        if (audioEnabled.get()
                                && System.currentTimeMillis() - (lastAudioOffset + (audioPacket.presentationTimeStamp / 1000)) < delay) {
                            audioDecoder.decodeSample(packet, audioPacket.headLength(), packet.length - audioPacket.headLength(),
                                    0, audioPacket.flag.getFlag());
                        }
                    }
                }
            } finally {
                if (waitEvent) {
                    Thread.sleep(5);
                }
            }
        }
    }

    public interface ServiceCallbacks {
        void loadNewRotation();

        void errorDisconnect(String reason, String detail);
    }

    public class MyServiceBinder extends Binder {
        public Scrcpy getService() {
            return Scrcpy.this;
        }
    }


}
