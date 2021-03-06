package net.runelite.client.plugins.quester.Generic;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.paistisuite.api.*;
import net.runelite.client.plugins.paistisuite.api.WebWalker.api_lib.WebWalkerServerApi;
import net.runelite.client.plugins.paistisuite.api.WebWalker.api_lib.models.PathResult;
import net.runelite.client.plugins.paistisuite.api.WebWalker.api_lib.models.PathStatus;
import net.runelite.client.plugins.paistisuite.api.WebWalker.api_lib.models.PlayerDetails;
import net.runelite.client.plugins.paistisuite.api.WebWalker.api_lib.models.Point3D;
import net.runelite.client.plugins.paistisuite.api.WebWalker.walker_engine.local_pathfinding.Reachable;
import net.runelite.client.plugins.paistisuite.api.WebWalker.wrappers.RSTile;
import net.runelite.client.plugins.quester.Quester;
import net.runelite.client.plugins.quester.Task;

@Slf4j
public class TalkToNpcTask implements Task {
    String npcName;
    WorldPoint location;
    String talkAction;
    String[] choices;
    String[] backupChoices;
    boolean isCompleted;
    boolean failed;
    int walkAttempts = 0;
    private Quester plugin;
    int talkAttempts = 0;
    int cachedDistance = -1;
    int cachedDistanceTick = -1;

    public TalkToNpcTask(Quester plugin, String npcName, WorldPoint location, String talkAction, String[] choices, String[] backupChoices){
        this.npcName = npcName;
        this.location = location;
        this.talkAction = talkAction;
        this.choices = choices;
        this.backupChoices = backupChoices;
        this.plugin = plugin;
    }

    public TalkToNpcTask(Quester plugin, String npcName, WorldPoint location, String talkAction, String[] choices){
        this.npcName = npcName;
        this.location = location;
        this.talkAction = talkAction;
        this.choices = choices;
        this.backupChoices = null;
        this.plugin = plugin;
    }

    public String name() {
        return "Talk to " + this.npcName;
    }

    public WorldPoint location() {
        return this.location;
    }

    public NPC findTarget(){
        return PObjects.findNPC(Filters.NPCs.nameContains(npcName));
    }
    public boolean execute() {
        if (talkAttempts >= 5){
            log.info("Failed talk to npc task. Too many attempts to talk to npc.");
            this.failed = true;
            return false;
        }
        NPC npc = findTarget();
        WorldPoint nearestReachable = npc != null ? Reachable.getNearestReachableTile(npc.getWorldLocation(), 1) : null;
        if (npc == null || nearestReachable == null) {
            if (walkAttempts >= 5){
                this.failed = true;
                log.info("Unable to walk to npc! Too many attempts!");
                return false;
            }
            walkAttempts++;
            if (plugin.webWalkTo(location())){
                PUtils.waitCondition(PUtils.random(2500, 3100), () -> !PPlayer.isMoving() && PPlayer.distanceTo(location()) <= 2);
                log.info("Walked to npc");
            } else {
                log.info("Failed webwalk to npc location! (Attempt " + walkAttempts + ")");
            }
            return true;
        }
        if (!PInteraction.npc(npc, talkAction)) {
            log.info("Unable to intaract with NPC!");
            this.failed = true;
            return false;
        }

        int distance = (int)Math.round(Reachable.getMap().getDistance(nearestReachable));
        if (distance > 1) PUtils.waitCondition(PUtils.random(800, 1400), PPlayer::isMoving);
        int multiplier = PPlayer.isRunEnabled() ? 300 : 600;
        int timeout = distance * multiplier + (int)PUtils.randomNormal(1900, 2800);
        PUtils.waitCondition(timeout, () -> !PPlayer.isMoving() || PPlayer.location().distanceTo(npc.getWorldLocation()) <= 1);

        if (!PUtils.waitCondition(PUtils.random(1300, 1900), PDialogue::isConversationWindowUp)){
            talkAttempts++;
            log.info("Timed out while waiting for conversation window!");
            return true;
        }

        if (PDialogue.handleDialogueInOrder(choices)){
            this.isCompleted = true;
            return true;
        } else if (this.backupChoices != null) {
            log.info("Using backup dialogue options");
            if (!PInteraction.npc(npc, talkAction)) {
                log.info("Unable to intaract with NPC!");
                this.failed = true;
                return false;
            }
            PUtils.sleepNormal(1900, 2800);
            if (PDialogue.handleDialogueInOrder(backupChoices)){
                this.isCompleted = true;
                return true;
            }
        }

        log.info("Failed at handling talk to npc dialogue!");
        this.failed = true;
        return false;
    };

    public boolean condition() {
        return !isCompleted() && !isFailed();
    }

    public boolean isCompleted() {
        return isCompleted;
    }

    public boolean isFailed(){
        return this.failed;
    }

    public int getDistance(){
        if (PUtils.getClient().getTickCount() <= cachedDistanceTick+30) return cachedDistance;
        WorldPoint playerLoc = PPlayer.getWorldLocation();
        Point3D playerLocPoint = new Point3D(playerLoc.getX(), playerLoc.getY(), playerLoc.getPlane());
        WorldPoint taskLoc = location();
        Point3D taskLocPoint = new Point3D(taskLoc.getX(), taskLoc.getY(), taskLoc.getPlane());
        PathResult path = WebWalkerServerApi.getInstance().getPath(playerLocPoint, taskLocPoint, PlayerDetails.generate());
        if (path.getPathStatus() == PathStatus.SUCCESS) {
            cachedDistance = path.getCost();
            cachedDistanceTick = PUtils.getClient().getTickCount();
            return cachedDistance;
        } else {
            cachedDistance = Integer.MAX_VALUE;
            cachedDistanceTick = PUtils.getClient().getTickCount();
            return cachedDistance;
        }
    };
}
