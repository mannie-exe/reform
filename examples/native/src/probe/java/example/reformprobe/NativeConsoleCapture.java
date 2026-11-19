package example.reformprobe;

import java.util.ArrayList;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/** Bounded observations of real native console output for the isolated server fixture. */
final class NativeConsoleCapture implements AutoCloseable {
  private final ArrayList<String> lines = new ArrayList<>();
  private final Logger logger = (Logger) LogManager.getLogger(MinecraftServer.class);
  private final AbstractAppender appender;

  NativeConsoleCapture(MinecraftServer server) {
    appender =
        new AbstractAppender("reform-probe-console", null, null, true, Property.EMPTY_ARRAY) {
          @Override
          public void append(LogEvent event) {
            if (server.isSameThread() && lines.size() < 32)
              lines.add(event.getMessage().getFormattedMessage());
          }
        };
    appender.start();
    logger.addAppender(appender);
  }

  void clear() {
    lines.clear();
  }

  Optional<String> find(String part) {
    return lines.stream().filter(line -> line.contains(part)).findFirst();
  }

  @Override
  public String toString() {
    return lines.toString();
  }

  @Override
  public void close() {
    logger.removeAppender(appender);
    appender.stop();
  }
}
