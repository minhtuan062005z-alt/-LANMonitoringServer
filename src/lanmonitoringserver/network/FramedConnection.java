package lanmonitoringserver.network;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import lanmonitoringserver.protocol.ProtocolException;
import lanmonitoringserver.protocol.ProtocolMessage;

/** UTF-8 JSON transport with a four-byte, big-endian length prefix. */
public final class FramedConnection implements AutoCloseable {
    public static final int MAX_FRAME_BYTES = 1024 * 1024;

    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final Object writeLock = new Object();

    public FramedConnection(Socket socket) throws IOException {
        this(socket, socket.getInputStream(), socket.getOutputStream());
    }

    public FramedConnection(Socket socket, InputStream input, OutputStream output) {
        this.socket = socket;
        this.input = new DataInputStream(input instanceof BufferedInputStream
                ? input : new BufferedInputStream(input));
        this.output = new DataOutputStream(output instanceof BufferedOutputStream
                ? output : new BufferedOutputStream(output));
    }

    public ProtocolMessage read() throws IOException {
        int length;
        try {
            length = input.readInt();
        } catch (EOFException endOfStream) {
            return null;
        }
        if (length <= 0 || length > MAX_FRAME_BYTES) {
            throw new ProtocolException("Invalid frame length: " + length);
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return ProtocolMessage.fromJson(new String(bytes, StandardCharsets.UTF_8));
    }

    public void send(ProtocolMessage message) throws IOException {
        byte[] bytes = message.toJson().getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_FRAME_BYTES) {
            throw new ProtocolException("Message exceeds maximum frame size");
        }
        synchronized (writeLock) {
            output.writeInt(bytes.length);
            output.write(bytes);
            output.flush();
        }
    }

    public Socket socket() { return socket; }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
