package communicationmod;

import basemod.*;
import basemod.interfaces.PostDungeonUpdateSubscriber;
import basemod.interfaces.PostInitializeSubscriber;
import basemod.interfaces.PostUpdateSubscriber;
import basemod.interfaces.PreUpdateSubscriber;
import basemod.eventUtil.EventUtils;
import basemod.eventUtil.AddEventParams;
import com.evacipated.cardcrawl.modthespire.lib.SpireConfig;
import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;
import com.google.gson.Gson;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import com.megacrit.cardcrawl.events.shrines.FaceTrader;
import communicationmod.patches.InputActionPatch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.lang.ProcessBuilder;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static java.lang.System.exit;
import static java.lang.Thread.sleep;

@SpireInitializer
public class CommunicationMod implements PostInitializeSubscriber, PostUpdateSubscriber, PostDungeonUpdateSubscriber, PreUpdateSubscriber {

    private static Thread server;
    private static StringBuilder inputBuffer = new StringBuilder();
    public static boolean messageReceived = false;
    private static final Logger logger = LogManager.getLogger(CommunicationMod.class.getName());
    // private static Thread writeThread;
    private static BlockingQueue<HashMap<String, Object>> stateQueue;
    // private static Thread readThread;
    private static BlockingQueue<String> readQueue;
    private static final String MODNAME = "Communication Mod";
    private static final String AUTHOR = "Forgotten Arbiter";
    private static final String DESCRIPTION = "This mod communicates with an external program to play Slay the Spire.";
    public static boolean mustSendGameState = false;

    private static SpireConfig communicationConfig;
    private static final String IP_OPTION = "IP";
    private static final String PORT_OPTION = "port";
    private static final String COLOR_OPTION = "color";
    private static final String DEFAULT_IP = "127.0.0.1";
    private static final Integer DEFAULT_PORT = 9001;
    private static final Integer DEFAULT_BACKLOG = 10;
    private static final Boolean DEFAULT_COLOR = true;

    public CommunicationMod(){
        BaseMod.subscribe(this);

        try {
            communicationConfig = new SpireConfig("CommunicationMod", "config");
	    if(communicationConfig.getString(IP_OPTION) == null)
		communicationConfig.setString(IP_OPTION, DEFAULT_IP);
	    if(communicationConfig.getString(PORT_OPTION) == null)
		communicationConfig.setInt(PORT_OPTION, DEFAULT_PORT);
	    if(communicationConfig.getString(COLOR_OPTION) == null)
		communicationConfig.setBool(COLOR_OPTION, DEFAULT_COLOR);
            communicationConfig.save();
        } catch (IOException e) {
            e.printStackTrace();
        }

	startServer();
    }

    public static void initialize() {
        CommunicationMod mod = new CommunicationMod();
    }

    public void receivePreUpdate() {
        if(messageAvailable()) {
            try {
                boolean stateChanged = CommandExecutor.executeCommand(readMessage());
                if(stateChanged) {
                    GameStateListener.registerCommandExecution();
                }
            } catch (InvalidCommandException e) {
                HashMap<String, Object> error = new HashMap<>();
                error.put("error", e.getMessage());
                error.put("ready_for_command", GameStateListener.isWaitingForCommand());
                sendState(error);
            }
        }
    }

    public void receivePostInitialize() {
        setUpOptionsMenu();
	BaseMod.addEvent((new AddEventParams.Builder(FaceTrader.ID, FaceTrader.class))
			 .eventType(EventUtils.EventType.FULL_REPLACE).overrideEvent("Match and Keep!")
			 .create());
    }

    public void receivePostUpdate() {
        if(!mustSendGameState && GameStateListener.checkForMenuStateChange()) {
            mustSendGameState = true;
        }
        if(mustSendGameState) {
            sendGameState();
            mustSendGameState = false;
        }
        InputActionPatch.doKeypress = false;
    }

    public void receivePostDungeonUpdate() {
        if (GameStateListener.checkForDungeonStateChange()) {
            mustSendGameState = true;
        }
        if(AbstractDungeon.getCurrRoom().isBattleOver) {
            GameStateListener.signalTurnEnd();
        }
    }

    private void setUpOptionsMenu() {
        ModPanel settingsPanel = new ModPanel();

        ModLabel ipLabel = new ModLabel(
                "", 350, 700, Settings.CREAM_COLOR, FontHelper.charDescFont,
                settingsPanel, modLabel -> {
                    modLabel.text = String.format("TCP Server IP: %s", getIPString());
                });
        settingsPanel.addUIElement(ipLabel);

	ModLabel portLabel = new ModLabel(
                "", 350, 650, Settings.CREAM_COLOR, FontHelper.charDescFont,
                settingsPanel, modLabel -> {
                    modLabel.text = String.format("TCP Server port: %d", getPort());
                });
        settingsPanel.addUIElement(portLabel);

	ModLabel colorLabel = new ModLabel(
                "", 350, 600, Settings.CREAM_COLOR, FontHelper.charDescFont,
                settingsPanel, modLabel -> {
                    modLabel.text = String.format("Enable color: %s", getColorOption());
                });
        settingsPanel.addUIElement(colorLabel);
	
        BaseMod.registerModBadge(ImageMaster.loadImage("Icon.png"),"Communication Mod", "Forgotten Arbiter", null, settingsPanel);
    }

    private void startServerThread() {
        stateQueue = new LinkedBlockingQueue<>();
        readQueue = new LinkedBlockingQueue<>();
	server = new Thread(new SlayTheSpireServer(getPort(), DEFAULT_BACKLOG, getIPString(), stateQueue, readQueue, getColorOption()));
        server.start();
    }

    private static void sendGameState() {
        HashMap<String, Object> state = GameStateConverter.getCommunicationState();
        sendState(state);
    }

    public static void dispose() {
        logger.info("Shutting down child process...");
        /* if(server != null) {
            server.destroy();
	    }*/
    }

    private static void sendState(HashMap<String, Object> state) {
        if(stateQueue != null && server.isAlive()) {
            stateQueue.add(state);
        }
    }

    private static boolean messageAvailable() {
        return readQueue != null && !readQueue.isEmpty();
    }

    private static String readMessage() {
        if(messageAvailable()) {
	    String message = readQueue.remove();
	    if(message.equals("exit"))
		exit(0);
	    return message;
        } else {
            return null;
        }
    }

    private static String getIPString() {
        if (communicationConfig == null)
            return DEFAULT_IP;
        return communicationConfig.getString(IP_OPTION).trim();
    }

    private static int getPort() {
	if (communicationConfig == null)
            return DEFAULT_PORT;

        return communicationConfig.getInt(PORT_OPTION);
    }

    private static Boolean getColorOption() {
	if (communicationConfig == null)
	    return DEFAULT_COLOR;
	return communicationConfig.getBool(COLOR_OPTION);
    }

    private boolean startServer() {
	startServerThread();
	if (GameStateListener.isWaitingForCommand()) {
	    mustSendGameState = true;
	}
	return true;
    }   
}
