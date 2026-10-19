package server.maps;

import client.BotClient;
import client.Character;
import client.Client;
import client.inventory.Item;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.PacketCreator;
import java.awt.Point;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HeadlessDropDeliveryTest {
    private Character recipient(Client client) {
        Character chr = mock(Character.class);
        when(chr.getClient()).thenReturn(client);
        return chr;
    }

    @Test
    void botBetweenPlayersCannotInterruptDropDeliveryToEitherPlayer() {
        Client first = mock(Client.class), last = mock(Client.class);
        BotClient bot = mock(BotClient.class);
        Character firstChr = recipient(first), lastChr = recipient(last);
        when(first.getPlayer()).thenReturn(firstChr);
        when(last.getPlayer()).thenReturn(lastChr);
        List<Client> delivered = new ArrayList<>();
        assertDoesNotThrow(() -> MapleMap.sendRangedSpawnPackets(
                List.of(firstChr, recipient(bot), lastChr), c -> {
                    assertNotNull(c.getPlayer(), "Drop builders require a real player recipient");
                    delivered.add(c);
                }));
        assertEquals(List.of(first, last), delivered);
        verify(bot, never()).getPlayer();
    }

    @Test
    void allHeadlessRecipientsSkipPacketConstruction() {
        MapleMap.sendRangedSpawnPackets(List.of(recipient(mock(BotClient.class))), c -> fail("No packet for bots"));
    }

    @Test
    void itemEnteringBotViewDoesNotInspectQuestStateOrBuildPackets() {
        MapItem drop = mock(MapItem.class);
        BotClient bot = mock(BotClient.class);
        doCallRealMethod().when(drop).sendSpawnData(bot);
        assertDoesNotThrow(() -> drop.sendSpawnData(bot));
        verify(bot, never()).getPlayer();
        verify(bot, never()).sendPacket(any());
    }

    @Test
    void realPlayerStillReceivesOriginalItemPacketBytes() {
        Client client = mock(Client.class);
        Character player = recipient(client);
        when(player.getId()).thenReturn(42);
        when(player.getPartyId()).thenReturn(-1);
        when(player.needQuestItem(-1, 2000000)).thenReturn(true);
        when(client.getPlayer()).thenReturn(player);
        MapObject dropper = mock(MapObject.class);
        when(dropper.getObjectId()).thenReturn(99);
        MapItem drop = new MapItem(new Item(2000000, (short) 1, (short) 1), new Point(10, 20),
                dropper, player, client, (byte) 0, false);
        drop.setDropTime(System.currentTimeMillis());
        byte[] expected = PacketCreator.dropItemFromMapObject(player, drop, null, drop.getPosition(), (byte) 2, (short) 0).getBytes();
        drop.sendSpawnData(client);
        ArgumentCaptor<Packet> packet = ArgumentCaptor.forClass(Packet.class);
        verify(client).sendPacket(packet.capture());
        assertArrayEquals(expected, packet.getValue().getBytes());
    }

    @Test
    void questIneligiblePlayerStillReceivesNoItemPacket() {
        Client client = mock(Client.class);
        Character player = recipient(client);
        when(client.getPlayer()).thenReturn(player);
        MapItem drop = new MapItem(new Item(2000000, (short) 1, (short) 1), new Point(),
                mock(MapObject.class), player, client, (byte) 0, false);
        drop.sendSpawnData(client);
        verify(client, never()).sendPacket(any());
    }
}
