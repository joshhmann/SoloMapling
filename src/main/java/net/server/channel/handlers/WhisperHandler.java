/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package net.server.channel.handlers;

import client.Character;
import client.Client;
import client.autoban.AutobanFactory;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ChatLogger;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.BotAddressee;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import tools.PacketCreator;
import tools.PacketCreator.WhisperFlag;

import java.util.List;

/**
 * @author Chronos
 */
public final class WhisperHandler extends AbstractPacketHandler {
    private static final Logger log = LoggerFactory.getLogger(WhisperHandler.class);

    // result types, not sure if there are proper names for these
    public static final byte RT_ITC = 0x00;
    public static final byte RT_SAME_CHANNEL = 0x01;
    public static final byte RT_CASH_SHOP = 0x02;
    public static final byte RT_DIFFERENT_CHANNEL = 0x03;

    @Override
    public void handlePacket(InPacket p, Client c) {
        byte request = p.readByte();
        String name = p.readString();
        Character target = c.getWorldServer().getPlayerStorage().getCharacterByName(name);

        if (target == null) {
            c.sendPacket(PacketCreator.getWhisperResult(name, false));
            suggestBotName(c.getPlayer(), name);
            return;
        }

        switch (request) {
            case WhisperFlag.LOCATION | WhisperFlag.REQUEST:
                handleFind(c.getPlayer(), target, WhisperFlag.LOCATION);
                break;
            case WhisperFlag.WHISPER | WhisperFlag.REQUEST:
                String message = p.readString();
                handleWhisper(message, c.getPlayer(), target);
                break;
            case WhisperFlag.LOCATION_FRIEND | WhisperFlag.REQUEST:
                handleFind(c.getPlayer(), target, WhisperFlag.LOCATION_FRIEND);
                break;
            default:
                log.warn("Unknown request {} triggered by {}", request, c.getPlayer().getName());
                break;
        }
    }

    private void handleFind(Character user, Character target, byte flag) {
        if (user.gmLevel() >= target.gmLevel()) {
            if (target.getCashShop().isOpened()) {
                user.sendPacket(PacketCreator.getFindResult(target, RT_CASH_SHOP, -1, flag));
            } else if (target.getClient().getChannel() == user.getClient().getChannel()) {
                user.sendPacket(PacketCreator.getFindResult(target, RT_SAME_CHANNEL, target.getMapId(), flag));
            } else {
                user.sendPacket(PacketCreator.getFindResult(target, RT_DIFFERENT_CHANNEL, target.getClient().getChannel() - 1, flag));
            }
        } else {
            // not found for whisper is the same message
            user.sendPacket(PacketCreator.getWhisperResult(target.getName(), false));
        }
    }

    private static final List<String> BUSY_WHISPERS = List.of("sry busy rn", "cant talk, grinding", "brb", "?", "hm?");

    /**
     * A whisper to a bot goes into its conversation with the player and is answered by whisper. With no brain the bot
     * is a busy player: now and then a short "busy" whisper, otherwise nothing.
     */
    private static void whisperBot(String message, Character user, Character bot) {
        BotSM brain = CharacterStorage.getBotById(bot.getId());
        int channel = user.getClient().getChannel() - 1;
        java.util.function.Consumer<String> whisperBack = line ->
                user.sendPacket(PacketCreator.getWhisperReceive(bot.getName(), channel, false, line));
        if (brain == null || !BotConversation.say(brain, user, message, null, whisperBack)) {
            if (java.util.concurrent.ThreadLocalRandom.current().nextInt(3) == 0) {
                whisperBack.accept(BUSY_WHISPERS.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(BUSY_WHISPERS.size())));
            }
        }
    }

    /** "No one named resme" - but a bot on the player's map is Ressurectme: say so. */
    private static void suggestBotName(Character user, String typed) {
        if (user == null || user.getMap() == null) {
            return;
        }
        List<BotAddressee.Candidate> nearby = new java.util.ArrayList<>();
        for (Character chr : user.getMap().getCharacters()) {
            if (BotHelpers.isBot(chr)) {
                nearby.add(new BotAddressee.Candidate(chr.getId(), chr.getName()));
            }
        }
        BotAddressee.Result result = BotAddressee.resolve(typed, nearby);
        if (result.best() != null && (result.sure() || result.worthAsking())) {
            user.dropMessage(5, "Did you mean " + result.best().name() + "?");
        }
    }

    private void handleWhisper(String message, Character user, Character target) {
        if (user.getAutobanManager().getLastSpam(7) + 200 > currentServerTime()) {
            return;
        }
        user.getAutobanManager().spam(7);

        if (message.length() > Byte.MAX_VALUE) {
            AutobanFactory.PACKET_EDIT.alert(user, user.getName() + " tried to packet edit with whispers.");
            log.warn("Chr {} tried to send text with length of {}", user.getName(), message.length());
            user.getClient().disconnect(true, false);
            return;
        }

        ChatLogger.log(user.getClient(), "Whisper To " + target.getName(), message);

        if (BotHelpers.isBot(target)) {
            whisperBot(message, user, target); // bot-persistence ticket 22: a bot reads its whispers
        } else {
            target.sendPacket(PacketCreator.getWhisperReceive(user.getName(), user.getClient().getChannel() - 1, user.isGM(), message));
        }

        boolean hidden = target.isHidden() && target.gmLevel() > user.gmLevel();
        user.sendPacket(PacketCreator.getWhisperResult(target.getName(), !hidden));
    }
}
