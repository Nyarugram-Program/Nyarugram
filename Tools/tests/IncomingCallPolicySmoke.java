package org.telegram.messenger.voip;

import java.util.HashMap;
import java.util.Map;

public final class IncomingCallPolicySmoke {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        IncomingCallPolicy calls = new IncomingCallPolicy();
        IncomingCallPolicy.Decision ordinary = calls.begin("sessionA", 0, 1, false, false);
        check(ordinary.importance == 3 && ordinary.priority() == 0, "ordinary importance and pre-O priority");
        check(!ordinary.claimFullScreen(true), "ordinary never sets FSI");
        check(!ordinary.claimAutomaticPage(false, true), "ordinary background cannot launch");
        check(!ordinary.claimAutomaticPage(true, false), "locked or screen-off cannot launch directly");
        check(ordinary.claimAutomaticPage(true, true), "visible foreground page permitted");
        check(!ordinary.claimAutomaticPage(true, true), "duplicate foreground update ignored");
        check(calls.begin("sessionA", 0, 1, false, true) == ordinary, "enable mid-call cannot upgrade");
        IncomingCallPolicy.Decision special = calls.begin("sessionA", 0, 2, false, true);
        check(special.importance == 4 && special.priority() == 2, "special and pre-O priority");
        check(!special.claimFullScreen(false), "Android14 permission denial degrades");
        check(special.claimFullScreen(true), "system allowed special FSI");
        check(!special.claimFullScreen(true), "duplicate special update cannot request FSI again");
        check(calls.recover("sessionA", 0, 2, false) == special, "pre to service preserves exact decision and claim");
        check(calls.begin("sessionA", 0, 2, false, false) == special, "cancel mid-call cannot downgrade");
        check(!special.claimAutomaticPage(false, true), "special background uses only system FSI");
        IncomingCallPolicy.Decision specialBackground = calls.begin("sessionA", 0, 20, false, true);
        check(!specialBackground.claimAutomaticPage(false, true, false), "special background requires granted system permissions");
        check(!specialBackground.claimAutomaticPage(false, false, true), "locked special background still uses system FSI");
        check(specialBackground.claimAutomaticPage(false, true, true), "special unlocked background can open original call page");
        check(!specialBackground.claimAutomaticPage(false, true, true), "duplicate background launch suppressed");
        check(!specialBackground.claimAutomaticPage(true, true, true), "foreground takeover does not launch a second page");
        check(!calls.begin("sessionA", 0, 21, false, false).claimAutomaticPage(false, true, true),
                "ordinary background remains notification-only with overlay permission");
        check(!calls.begin("sessionA", 0, 22, true, true).claimAutomaticPage(false, true, true),
                "group background remains notification-only with overlay permission");
        check(calls.begin("sessionA", 1, 2, false, false).importance == 3, "account slot isolation");
        check(calls.begin("sessionB", 0, 2, false, false).importance == 3, "same slot new session isolation");
        check(calls.begin(null, 0, 3, false, true).importance == 3, "unknown authentication cannot upgrade");
        check(calls.begin("sessionA", 0, 0, false, true).importance == 3, "unknown call cannot upgrade");
        IncomingCallPolicy.Decision group = calls.begin("sessionA", 0, 2, true, true);
        check(group.importance == 3 && !group.claimFullScreen(true), "group inviter cannot upgrade");
        calls.finish("sessionB", 0, 2, false);
        check(calls.recover("sessionA", 0, 2, false) == special, "late other session terminal isolated");
        calls.finish("sessionA", 0, 2, true);
        check(calls.recover("sessionA", 0, 2, false) == special, "group terminal cannot clear private call");
        calls.finish("sessionA", 0, 2, false);
        check(!calls.contains("sessionA", 0, 2, false), "terminal releases frozen call");
        check(calls.begin("sessionA", 0, 2, false, false).importance == 3, "later call uses new preference");
        IncomingCallPolicy restarted = new IncomingCallPolicy();
        IncomingCallPolicy.Decision recovered = restarted.recover("sessionA", 0, 99, false);
        check(recovered.importance == 3 && !recovered.claimFullScreen(true), "process restart recovery safe");
        check(restarted.begin("sessionA", 0, 99, false, true) == recovered, "recovered call cannot upgrade on replay");
        class Channels implements IncomingCallPolicy.Channels {
            final Map<String, Integer> settings = new HashMap<>();
            int creates;
            public boolean exists(String id) { return settings.containsKey(id); }
            public void create(String id, int importance) { creates++; settings.put(id, importance); }
        }
        Channels channels = new Channels();
        channels.settings.put("incoming_calls40", 4);
        channels.settings.put("botgram_incoming_calls_v1", 4);
        String ordinaryId = IncomingCallPolicy.ensureChannel(ordinary, channels);
        String specialId = IncomingCallPolicy.ensureChannel(special, channels);
        check(!ordinaryId.equals(specialId), "two separate stable channels");
        check(channels.settings.get(ordinaryId) == 3 && channels.settings.get(specialId) == 4, "new channel defaults");
        check(channels.creates == 2, "legacy high channels never reused");
        channels.settings.put(ordinaryId, 1);
        channels.settings.put(specialId, 2);
        for (int i = 0; i < 10; i++) {
            check(ordinaryId.equals(IncomingCallPolicy.ensureChannel(ordinary, channels)), "ordinary channel stable");
            check(specialId.equals(IncomingCallPolicy.ensureChannel(special, channels)), "special channel stable");
        }
        check(channels.creates == 2 && channels.settings.get(ordinaryId) == 1 && channels.settings.get(specialId) == 2,
                "user importance retained without recreating channels");
        check(channels.settings.get("incoming_calls40") == 4 && channels.settings.get("botgram_incoming_calls_v1") == 4,
                "legacy user channels untouched");
        // Publish plan matrix: the exact decision every Android builder consumes.
        IncomingCallPolicy publish = new IncomingCallPolicy();
        IncomingCallPolicy.Decision ordinaryPlan = publish.begin("sessionP", 0, 10, false, false);
        check(!ordinaryPlan.claimFullScreen(true), "ordinary plan never claims FSI");
        IncomingCallNotificationPlan ordinaryVoice = IncomingCallNotificationPlan.create(ordinaryPlan, false, false, false, false);
        IncomingCallNotificationPlan ordinaryVideo = IncomingCallNotificationPlan.create(ordinaryPlan, false, true, false, false);
        check(!ordinaryVoice.callStyle && !ordinaryVideo.callStyle, "ordinary voice/video plain notify never uses CallStyle");
        check(ordinaryVoice.answerAction && ordinaryVoice.declineAction
                && ordinaryVideo.answerAction && ordinaryVideo.declineAction, "ordinary template keeps answer and decline");
        check(ordinaryVoice.channelImportance == 3 && ordinaryVoice.priority == 0
                && ordinaryVoice.channelId.equals(IncomingCallPolicy.ORDINARY_CHANNEL), "ordinary channel and priority");
        check(!ordinaryVoice.fullScreenIntent && !ordinaryVideo.fullScreenIntent, "ordinary template carries no FSI");
        check(!ordinaryVoice.video && ordinaryVideo.video, "voice and video retained");

        IncomingCallPolicy.Decision deniedPlan = publish.begin("sessionP", 0, 11, false, true);
        check(!deniedPlan.claimFullScreen(false), "special FSI permission denied");
        IncomingCallNotificationPlan denied = IncomingCallNotificationPlan.create(deniedPlan, false, false, false, false);
        check(!denied.callStyle && denied.channelImportance == 4
                && denied.answerAction && denied.declineAction, "denied special degrades to ordinary template");

        IncomingCallPolicy.Decision allowedPlan = publish.begin("sessionP", 0, 12, false, true);
        check(allowedPlan.claimFullScreen(true), "special FSI allowed");
        IncomingCallNotificationPlan allowed = IncomingCallNotificationPlan.create(allowedPlan, false, false, false, true);
        check(allowed.callStyle && allowed.fullScreenIntent
                && allowed.channelImportance == 4 && allowed.priority == 2, "allowed special uses CallStyle with FSI");
        check(!allowedPlan.claimFullScreen(true), "single FSI claim consumed");
        IncomingCallNotificationPlan repeated = IncomingCallNotificationPlan.create(allowedPlan, false, true, false, false);
        check(!repeated.callStyle && repeated.answerAction && repeated.declineAction,
                "repeated non-FGS update degrades to ordinary template");
        IncomingCallNotificationPlan takeover = IncomingCallNotificationPlan.create(allowedPlan, false, false, true, true);
        check(takeover.callStyle && takeover.fullScreenIntent, "service takeover keeps legal CallStyle");

        IncomingCallPolicy.Decision servicePlan = publish.begin("sessionP", 0, 13, false, true);
        IncomingCallNotificationPlan service = IncomingCallNotificationPlan.create(servicePlan, false, false, true, false);
        check(service.callStyle && !service.fullScreenIntent, "foreground service CallStyle without FSI is legal");

        IncomingCallPolicy.Decision groupPlan = publish.begin("sessionP", 0, 14, true, true);
        IncomingCallNotificationPlan groupPlanResult = IncomingCallNotificationPlan.create(groupPlan, true, true, false, false);
        check(!groupPlanResult.callStyle && groupPlanResult.answerAction && groupPlanResult.declineAction
                && groupPlanResult.channelImportance == 3, "group invite stays ordinary with answer and decline");

        IncomingCallPolicy restartedPublish = new IncomingCallPolicy();
        IncomingCallNotificationPlan recoveredPlan = IncomingCallNotificationPlan.create(
                restartedPublish.recover("sessionP", 0, 15, false), false, false, false, false);
        check(!recoveredPlan.callStyle, "restart recovery publishes ordinary template");

        System.out.println("IncomingCallPolicy: " + checks + " assertions PASS");
    }
}
