/*
    SoloMapling bot menu (bot-persistence tickets 21/23): a bot's options in the NPC window.
    The menu itself lives in soloMapling.ArtificialPlayer.BotMenus; this script only shows it and reports the click.
*/
var BotMenus = Java.type('soloMapling.ArtificialPlayer.BotMenus');

function start() {
    cm.sendSimple(BotMenus.text(cm.getPlayer()));
}

function action(mode, type, selection) {
    if (mode == 1 && selection >= 0) {
        BotMenus.select(cm.getPlayer(), selection);
    } else {
        BotMenus.closed(cm.getPlayer());
    }
    cm.dispose();
}
