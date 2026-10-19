package soloMapling.ArtificialPlayer.BotMessagingSystem;

import client.Character;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotMenus;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;
import soloMapling.server.BotShutdown;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.expirePlayerChatCommands;
import static soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getBotById;
import static soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.checkIfInvisibleBot;
import static soloMapling.server.ExecutorServiceManager.getExecutorService;
import static soloMapling.server.ExecutorServiceManager.getScheduledExecutorService;
import static soloMapling.BotLogger.log;

public class Dispatcher implements Runnable {

    private static final Dispatcher dispatcher = new Dispatcher(MessageQueue.getInstance());
    private final MessageQueue messageQueue;
    private final ExecutorService executor = getExecutorService();
    private final ScheduledExecutorService scheduler = getScheduledExecutorService();

    public Dispatcher(MessageQueue messageQueue) {
        log("Dispatcher OBJECT Created");
        this.messageQueue = messageQueue;
        this.scheduler.scheduleAtFixedRate(this, 0, 2, TimeUnit.SECONDS);
    }

    // Static method to access the singleton instance
    public static Dispatcher getInstance() {
        log("Dispatcher getInstance");
        return dispatcher;
    }

    @Override
    public void run() {
//        log("Dispatcher: RUN");
        if (BotShutdown.isQuiesced()) {
            return; // Ticket 09: delivery touches maps and bot commands - a quiesced server drops the pass
        }
        processMessages();
    }

    //    @Override
    private void processMessages() {
        // Every waiting line, not one per pass: at one line per 2 s pass, a burst of more than five lines outlives
        // the queue's 10 s expiry (QueueCleaner) and the tail is dropped before any bot sees it.
        ChatMessage message;
        while ((message = messageQueue.getMessageNonBlocking("primary")) != null) {
            dispatch(message);
        }
    }

    private void dispatch(ChatMessage message) {
        try {
            Collection<Character> chars_on_map = message.getMap().getCharacters();
            final int[] botToCall = new int[1];  // Using an array to hold the bot ID

            boolean characterFound = checkIfCharacterOnMap(chars_on_map, message, botToCall);

            // Determine if the message is for any registered bot
            if (characterFound) {
                handleBotRunning(botToCall, message);
            } else if (!routeWithoutExactName(chars_on_map, message)) {
                handleMessageWithNoBotName(message);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * bot-persistence ticket 19: a line with no exact bot name may still be aimed at one - a nickname or typo
     * ("hey resme"), an answer to the bot that just spoke ("ill come"), or a yes to a bot that asked "u talking to
     * me?". Lines of a conversation already under way keep their old path. Returns true when the line was handled.
     */
    private boolean routeWithoutExactName(Collection<Character> charsOnMap, ChatMessage message) {
        Character sender = message.getSender();
        long now = System.currentTimeMillis();
        BotChatRouting.Pending pending = BotChatRouting.pendingFor(sender.getId());
        if (pending != null) {
            BotSM asker = getBotById(pending.botId());
            String name = asker == null || asker.getChr() == null ? null : asker.getChr().getName();
            if (BotChatRouting.confirmation(message, name, now).isPresent()) {
                handleBotRunning(new int[]{pending.botId()}, pending.original());
                return true;
            }
        }
        if (CharacterStorage.checkIfRespondant(sender) || CharacterStorage.checkIfInquirer(sender)) {
            return false;
        }
        // Mid-chat with a bot that has no menu: the next line is for that bot (ticket 21).
        int partner = BotConversation.partnerOf(sender.getId());
        BotSM partnerBot = partner < 0 ? null : getBotById(partner);
        if (partnerBot != null && !partnerBot.consumesFreeText() && partnerBot.getChr() != null
                && partnerBot.getChr().getMapId() == sender.getMapId()) {
            BotConversation.say(partnerBot, sender, message.getContent(), null);
            return true;
        }
        if (soloMapling.ArtificialPlayer.BotBuffRequestSystem.BotBuffRequestHandler.isBuffRequest(message.getContent())) {
            return true; // "hs pls": the buff handler already has the nearest eligible bot cast it
        }
        List<BotAddressee.Candidate> nearby = new ArrayList<>();
        for (Character chr : charsOnMap) {
            if (chr.getId() != sender.getId() && !checkIfInvisibleBot(chr.getId()) && getBotById(chr.getId()) != null) {
                nearby.add(new BotAddressee.Candidate(chr.getId(), chr.getName(), chr.getJob().getId()));
            }
        }
        if (nearby.isEmpty()) {
            return false;
        }
        BotAddressee.Result named = BotAddressee.resolve(message.getContent(), nearby);
        if (named.sure()) {
            handleBotRunning(new int[]{named.best().id()}, message);
            return true;
        }
        if (named.worthAsking() || named.ambiguous()) {
            askFirst(named.best().id(), message, now);
            return true;
        }
        // Layer 3a: a short acceptance right after a bot asked or recruited answers that bot.
        BotChatRouting.LastSpeaker last = BotChatRouting.lastSpeaker(sender.getMapId(), now);
        java.util.Optional<Integer> answered = BotChatRouting.replyToLastSpeaker(message.getContent(), last);
        if (answered.isPresent() && getBotById(answered.get()) != null) {
            handleBotRunning(new int[]{answered.get()}, message);
            return true;
        }
        // Layer 3b: a request for a kind of player ("can i get a bless") - a network call, so off the dispatcher. The
        // model is right often, not always, so the one bot of that kind asks first instead of answering outright.
        executor.execute(() -> BotChatRouting.decideByJob(message.getContent(), nearby).ifPresent(choice -> {
            if (choice.confidence() >= config.YamlConfig.config.server.BOT_DECIDE_ASK) {
                askFirst(choice.botId(), message, System.currentTimeMillis());
            }
        }));
        return true;
    }

    /** Whether this bot type shows a hint of its own when named (it overrides {@code BotSM.displayCommands}). */
    private static final ClassValue<Boolean> HAS_MENU = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("displayCommands", Character.class).getDeclaringClass() != BotSM.class;
            } catch (NoSuchMethodException e) {
                return false;
            }
        }
    };

    static boolean showsOwnHint(BotSM bot) {
        return HAS_MENU.get(bot.getClass());
    }

    private void askFirst(int botId, ChatMessage message, long now) {
        BotSM bot = getBotById(botId);
        if (bot == null || bot.getChr() == null) {
            return;
        }
        soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak(bot.getChr(),
                BotChatRouting.askFirst(botId, message, now));
    }

    private boolean checkIfCharacterOnMap(Collection<Character> chars_on_map, ChatMessage message, int[] botToCall) {
        boolean characterFound = false;
        for (Character character : chars_on_map) {
            if (message.getContent().contains(character.getName())) {
                if (!checkIfInvisibleBot(character.getId())) {
                    botToCall[0] = character.getId();
                    characterFound = true;
                    break;
                }
            }
        }
        return characterFound;
    }

    private void handleBotRunning(int[] botToCall, ChatMessage message) {
        executor.execute(() -> {
            BotSM bot = getBotById(botToCall[0]);
            if (bot == null) {
                logBotNotFound(botToCall[0]);
                return;
            }

            if (!bot.getRunning()) {
                startNewBotSession(bot, message);
            } else {
                handleExistingBotSession(bot, message);
            }
        });
    }

    private void startNewBotSession(BotSM bot, ChatMessage message) {
        log("Bot not running. Start scheduledTask line");
        bot.setRunning(true);
        bot.getInteractors().setRespondant(message.getSender());
        bot.startScheduledTask();
        if (bot instanceof SocialBot socialBot) {
            socialBot.onFirstInteraction(message.getSender());
        }
    }

    private void handleExistingBotSession(BotSM bot, ChatMessage message) {
        if (bot instanceof SocialBot socialBot) {
            handleSocialBotSession(socialBot, message);
            return;
        }
        if (!bot.consumesFreeText()) {
            // bot-persistence ticket 21: a bot that does not read typed lines itself (a merchant, a gacha host, a
            // town wanderer) talks back through the brain. Its name alone shows its own hint (a merchant's) or
            // the shared menu window (a wanderer's), and opens the chat so the player's next lines reach it.
            String said = message.getContent().replace(bot.getChr().getName(), "").trim();
            if (said.isEmpty()) {
                if (showsOwnHint(bot)) {
                    bot.getDialogueHandler().listOptions(message.getSender(), bot);
                    BotConversation.open(bot, message.getSender());
                    return;
                }
                if (BotMenus.open(bot, message.getSender(), List.of(), (index, who) -> { })) {
                    BotConversation.open(bot, message.getSender());
                    return;
                }
            }
            if (BotConversation.say(bot, message.getSender(), said.isEmpty() ? message.getContent() : said, null)) {
                return;
            }
        }
        log("bot already running");
        bot.getInteractors().setInquirer(message.getSender());
        bot.getDialogueHandler().listOptions(message.getSender(), bot);
        // One-line inquiry: a naming message that also carries a keyword ("Tiger wana party?") pre-selects the
        // menu option in the same breath. Strip the bot's name first so a name that itself contains a keyword
        // can't self-match, then re-enqueue the remainder onto the tertiary queue exactly as a stand-alone
        // follow-up would have arrived — BotOptionMenu.poll drains it on the bot's next tick with the same
        // trim/lowercase/contains matcher. The menu is already active (listOptions ran synchronously above),
        // and a non-matching remainder ("yo Tiger") falls through harmlessly like a junk follow-up would.
        String remainder = message.getContent().replace(bot.getChr().getName(), "").trim();
        if (!remainder.isEmpty()) {
            messageQueue.addMessage("tertiary", new ChatMessage(message.getSender(), remainder));
            bot.nudgeSoon(0L); // player is on the bot's map -> pull the pre-selected option forward so it feels instant
        }
    }

    private void handleSocialBotSession(SocialBot socialBot, ChatMessage message) {
        if (socialBot.hasActiveRespondant()) {
            log("[Dispatcher] SocialBot busy, ignoring second player");
            return;
        }
        log("[Dispatcher] SocialBot available, setting respondant");
        socialBot.getInteractors().setRespondant(message.getSender());
        socialBot.onFirstInteraction(message.getSender());
    }

    private void logBotNotFound(int botId) {
        log("No bot found for ID: " + botId);
    }

//    private void handleBotRunning(int[] botToCall, ChatMessage message) {
//        executor.execute(() -> {
//            BotSM bot = getBotById(botToCall[0]);
//            if (bot != null && !bot.getRunning()) {
//                log("Bot not running. Start scheduledTask line");
//                bot.setRunning(true);
//                bot.setRespondant(message.getSender());
//                bot.startScheduledTask();
//            } else if (bot != null & bot.getRunning()) {
//                log("bot already running");
//                bot.setInquirer(message.getSender());
//                bot.listOptions(message.getSender());
//            } else {
//                log("No bot found for ID: " + botToCall[0] + " or bot is already running.");
//            }
//        });
//    }

    private void handleMessageWithNoBotName(ChatMessage message) {
        // message does not contain any info w/ bot names.
        Character respondant = message.getSender();
        if (CharacterStorage.checkIfRespondant(respondant)) { // Check if message contains a respondant
            expirePlayerChatCommands(respondant); // Expires any bubbles for respondants, catch all.
            messageQueue.addMessage(message); // Put into 2nd queue
        } else if (CharacterStorage.checkIfInquirer(respondant)) {
            messageQueue.addMessage("tertiary", message);
        }
    }

    public void shutdown() {
        executor.shutdown();
        try {
            // Wait for existing tasks to complete
            if (!executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

            /*
            Message:
            - Has Bot Name -> start that bot

            - Doesn't have bot name
                -> contains a respondant -> 2ndary queue
                -> doesn't contain a respondant -> random chat, remove

            2ndary queue:
                - active bots pull from this.
             */