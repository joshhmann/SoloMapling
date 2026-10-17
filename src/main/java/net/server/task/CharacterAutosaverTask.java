/*
    This file is part of the HeavenMS MapleStory Server
    Copyleft (L) 2016 - 2019 RonanLana

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
package net.server.task;

import client.Character;
import config.YamlConfig;
import net.server.PlayerStorage;
import net.server.world.World;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.ArrayList;
import java.util.List;

import static soloMapling.server.ExecutorServiceManager.runAsync;

/**
 * @author Ronan
 */
public class CharacterAutosaverTask extends BaseTask implements Runnable {  // thanks Alex09 (Alex-0000) for noticing these runnable classes are tasks, "workers" runs them

    @Override
    public void run() {
        if (!YamlConfig.config.server.USE_AUTOSAVE) {
            return;
        }

        PlayerStorage ps = wserv.getPlayerStorage();
        List<Character> bots = new ArrayList<>();
        for (Character chr : ps.getAllCharacters()) {
            if (chr == null) {
                continue;
            }
            if (chr.isLoggedin()) {
                chr.saveCharToDB(false);
            } else if (BotHelpers.isBot(chr)) {
                // Bots are never logged in, so the player sweep above skips them by construction; their
                // state is written through the bot store. Snapshot them here and hand the DB work to a
                // virtual thread - this task runs on the shared timer thread.
                bots.add(chr);
            }
        }
        if (!bots.isEmpty()) {
            runAsync(() -> BotStore.saveAll(bots));
        }
    }

    public CharacterAutosaverTask(World world) {
        super(world);
    }
}
