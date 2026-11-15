package nav.dash;

import java.nio.file.*;

/** Host test for NotifDump against a real captured `dumpsys notification --noredact` Maps record. */
public class NotifDumpTest {
    static int fail = 0, n = 0;
    static void eq(String what, Object got, Object want) { n++; if (!String.valueOf(got).equals(String.valueOf(want))) { fail++; System.out.println("FAIL " + what + ": got '" + got + "' want '" + want + "'"); } }
    public static void main(String[] a) throws Exception {
        String maps = new String(Files.readAllBytes(Paths.get("fixtures/maps-nav.txt")));
        NotifDump.Result r = NotifDump.parse("Current Notification Manager state:\n  Notification List:\n" + maps + "\n  Snoozed notifications:\n    android.title=String (should be ignored)\n");
        eq("nav found", r.nav, true);
        eq("title", r.title, "Head toward Main Rd");
        eq("subText (no-break space normalised)", r.sub, "Arrive 10:52 am");
        eq("text null -> empty", r.text, "");
        eq("progress", r.progress, 0);
        eq("max", r.max, 18807);
        eq("no caller", r.caller, "");
        // parentheses inside the value, a call record, and a non-nav Maps notification
        String turn = maps.replace("android.title=SpannableString (Head toward Main Rd)",
                "android.title=SpannableString (700 m · Turn right after City Travels (on the right) onto Mill Rd)");
        String call = "    NotificationRecord(0x1: pkg=com.google.android.dialer user=UserHandle{0} id=7 tag=null importance=5 key=x: Notification(channel=phone_incoming_call category=call flags=ONGOING_EVENT|FOREGROUND_SERVICE vis=PUBLIC))\n"
                + "          extras={\n                android.title=String (Alex)\n                android.text=String (Incoming call)\n          }\n";
        String other = "    NotificationRecord(0x2: pkg=com.google.android.apps.maps user=UserHandle{0} id=9 tag=null importance=2 key=y: Notification(channel=promo category=recommendation vis=PUBLIC))\n"
                + "                android.title=String (Rate this place)\n";
        NotifDump.Result r2 = NotifDump.parse("  Notification List:\n" + other + call + turn);
        eq("title with nested parens", r2.title, "700 m · Turn right after City Travels (on the right) onto Mill Rd");
        eq("caller", r2.caller, "Alex");
        eq("promo Maps notification ignored", r2.nav && !r2.title.startsWith("Rate"), true);
        NotifDump.Result r3 = NotifDump.parse("  Notification List:\n" + other);
        eq("no nav when Maps isn't navigating", r3.nav, false);
        eq("empty dump", NotifDump.parse("").nav, false);
        eq("caller in title", NotifDump.callerName("Alex", "Incoming call"), "Alex");
        eq("caller in text (Samsung style)", NotifDump.callerName("Incoming call", "Alex"), "Alex");
        eq("number only", NotifDump.callerName("Incoming voice call", "+1 555 0100"), "+1 555 0100");
        eq("WhatsApp", NotifDump.callerName("Rahul", "Incoming voice call"), "Rahul");
        eq("ongoing", NotifDump.callerName("Ongoing call", "Mom"), "Mom");
        eq("both generic", NotifDump.callerName("Incoming call", "Calling…"), "");
        eq("wifi call", NotifDump.callerName("Wi-Fi call", "Office"), "Office");
        String samsung = "    NotificationRecord(0x3: pkg=com.samsung.android.incallui user=UserHandle{0} id=1 tag=null importance=5 key=z: Notification(channel=incoming category=call flags=ONGOING_EVENT vis=PUBLIC))\n"
                + "                android.title=String (Incoming call)\n                android.text=String (Corner Office)\n";
        eq("samsung record", NotifDump.parse("  Notification List:\n" + samsung).caller, "Corner Office");
        System.out.println(n - fail + "/" + n + " passed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
