package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotLogoutTest {

    private static PartyCharacter member(int id) {
        PartyCharacter pc = mock(PartyCharacter.class);
        when(pc.getId()).thenReturn(id);
        return pc;
    }

    private static Character botIn(Party party) {
        Character bot = Character.getDefault(new BotClient(0, 0));
        bot.setID(BotStore.BOT_ID_BASE + BotStore.FIRST_PLANNED_SLOT);
        bot.setParty(party);
        return bot;
    }

    @Test
    void aPartyCountsAsAPlayersOnlyWhenARealPlayerIsInIt() {
        List<PartyCharacter> mixed = List.of(member(BotStore.BOT_ID_BASE + 9000), member(1234));
        List<PartyCharacter> bots = List.of(member(BotStore.BOT_ID_BASE + 9000), member(BotStore.BOT_ID_BASE + 9001));
        Party withPlayer = mock(Party.class);
        when(withPlayer.getMembers()).thenReturn(mixed);
        Party botsOnly = mock(Party.class);
        when(botsOnly.getMembers()).thenReturn(bots);

        assertTrue(BotLogout.partiedWithPlayer(botIn(withPlayer)));
        assertFalse(BotLogout.partiedWithPlayer(botIn(botsOnly)));
        assertFalse(BotLogout.partiedWithPlayer(botIn(null)));
    }

    @Test
    void thereIsAGoodbyeForTheRoomAndOneForTheParty() {
        String alone = BotLogout.line("Alone");
        String party = BotLogout.line("Party");
        assertNotNull(alone);
        assertNotNull(party);
        assertTrue(alone.length() <= 70 && party.length() <= 70, "lines fit the chat box");
    }
}
