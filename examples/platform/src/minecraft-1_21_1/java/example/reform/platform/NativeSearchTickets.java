package example.reform.platform;

import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/** Reads actual native UUID tickets through the fixture's narrowly widened ticket table. */
final class NativeSearchTickets {
  private static final TicketType<UUID> TYPE =
      TicketType.create("reform_platform_search", UUID::compareTo);

  private final ServerLevel level;
  private final ChunkPos chunk;
  private final UUID[] keys = {UUID.randomUUID(), UUID.randomUUID()};

  NativeSearchTickets(ServerLevel level) {
    this.level = level;
    chunk = new ChunkPos(level.getSharedSpawnPos());
  }

  ChunkPos chunk() {
    return chunk;
  }

  int x() {
    return chunk.x;
  }

  int z() {
    return chunk.z;
  }

  Runnable acquire(int slot) {
    level.getChunkSource().addRegionTicket(TYPE, chunk, 0, keys[slot]);
    return () -> level.getChunkSource().removeRegionTicket(TYPE, chunk, 0, keys[slot]);
  }

  boolean live(int slot) {
    var rows = level.getChunkSource().chunkMap.getDistanceManager().tickets.get(chunk.toLong());
    return rows != null
        && rows.stream()
            .anyMatch(ticket -> ticket.getType() == TYPE && ticket.key.equals(keys[slot]));
  }
}
