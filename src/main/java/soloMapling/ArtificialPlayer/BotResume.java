package soloMapling.ArtificialPlayer;

import constants.id.MapId;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.function.IntFunction;

/**
 * Where a roster member reappears when it logs in (bot-persistence ticket 06): exactly where it logged out,
 * like a player. A map that sends players elsewhere on login - a dungeon, boss room or event map with a forced
 * return - sends the bot there too, to that map's spawn point. A map that no longer loads leaves the bot where
 * its spawn wave put it.
 */
public final class BotResume {

    /** The map and point a member logs in at. */
    public record Placement(MapleMap map, Point position) {
    }

    private BotResume() {
    }

    /**
     * @param storedMapId the map in the member's row
     * @param storedPosition the point in the member's row, used only when the bot comes back to that same map
     * @param maps looks a map up by id (null when it does not exist)
     * @return where to place the bot, or null to keep the spawn wave's placement
     */
    public static Placement placementFor(int storedMapId, Point storedPosition, IntFunction<MapleMap> maps) {
        if (storedMapId <= 0 || storedMapId == MapId.NONE) {
            return null;
        }
        MapleMap stored = maps.apply(storedMapId);
        if (stored == null) {
            return null;
        }
        int forced = stored.getForcedReturnId();
        if (forced != MapId.NONE && forced != storedMapId) {
            MapleMap returnMap = maps.apply(forced);
            if (returnMap == null || returnMap.getPortal(0) == null) {
                return null;
            }
            return new Placement(returnMap, returnMap.getPortal(0).getPosition());
        }
        return new Placement(stored, storedPosition);
    }
}
