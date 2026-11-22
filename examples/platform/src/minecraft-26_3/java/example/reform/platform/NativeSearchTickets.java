package example.reform.platform;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;

/** Native 26.3 tickets are keyed by type identity and level, rather than a caller UUID. */
final class NativeSearchTickets {
  private final ChunkPos chunk;
  private final TicketStorage storage;
  private final Ticket[] tickets = {
    new Ticket(new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING), 33),
    new Ticket(new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING), 33)
  };

  NativeSearchTickets(ServerLevel level) {
    var spawn = level.getRespawnData().pos();
    chunk = new ChunkPos(spawn.getX() >> 4, spawn.getZ() >> 4);
    storage = level.getDataStorage().computeIfAbsent(TicketStorage.TYPE);
  }

  ChunkPos chunk() {
    return chunk;
  }

  int x() {
    return chunk.x();
  }

  int z() {
    return chunk.z();
  }

  Runnable acquire(int slot) {
    storage.addTicket(tickets[slot], chunk);
    return () -> storage.removeTicket(tickets[slot], chunk);
  }

  boolean live(int slot) {
    return storage.getTickets(chunk.pack()).stream()
        .anyMatch(
            ticket ->
                ticket.getType() == tickets[slot].getType()
                    && ticket.getTicketLevel() == tickets[slot].getTicketLevel());
  }
}
