import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;

/** Loopback-only authoritative dedicated-server assertions, independent of client prediction. */
final class Rcon implements AutoCloseable {
  private final Socket socket;
  private final DataInputStream input;
  private final OutputStream output;
  private int sequence = 10;
  final String address;

  Rcon() throws IOException {
    var configuration = new Properties();
    try (var file = Files.newInputStream(Path.of(System.getProperty("latticium.audit.server")))) {
      configuration.load(file);
    }
    address = configuration.getProperty("address");
    socket = new Socket();
    socket.connect(
        new InetSocketAddress("127.0.0.1", Integer.parseInt(configuration.getProperty("port"))),
        5000);
    socket.setSoTimeout(10000);
    input = new DataInputStream(socket.getInputStream());
    output = socket.getOutputStream();
    send(3, configuration.getProperty("password"));
    Packet response;
    do {
      response = read();
    } while (response.type != 2);
    if (response.id != sequence) throw new IOException("Dedicated-server authentication failed");
  }

  String command(String command) throws IOException {
    send(2, command);
    var response = read();
    if (response.id != sequence) throw new IOException("Unexpected RCON response sequence");
    return response.body;
  }

  boolean block(int x, int y, int z, String block) throws IOException {
    command(
        "execute store success score probe latticium_audit if block "
            + x
            + " "
            + y
            + " "
            + z
            + " "
            + block);
    return command("scoreboard players get probe latticium_audit").contains("has 1 [");
  }

  private void send(int type, String body) throws IOException {
    byte[] text = body.getBytes(StandardCharsets.UTF_8);
    var packet = ByteBuffer.allocate(text.length + 14).order(ByteOrder.LITTLE_ENDIAN);
    packet
        .putInt(text.length + 10)
        .putInt(++sequence)
        .putInt(type)
        .put(text)
        .put((byte) 0)
        .put((byte) 0);
    output.write(packet.array());
    output.flush();
  }

  private Packet read() throws IOException {
    int length = Integer.reverseBytes(input.readInt());
    if (length < 10 || length > 1_048_576) throw new IOException("Invalid RCON frame size");
    byte[] bytes = input.readNBytes(length);
    if (bytes.length != length) throw new EOFException("Truncated RCON response");
    var packet = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    return new Packet(
        packet.getInt(),
        packet.getInt(),
        new String(bytes, 8, length - 10, StandardCharsets.UTF_8));
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }

  private record Packet(int id, int type, String body) {}
}
