package nav.dash;

/** Host-side test for NavText (run: hosttest/run.sh). */
public class NavTextTest {
    static int fail = 0, n = 0;
    static void eq(String what, String got, String want) {
        n++; if (!got.equals(want)) { fail++; System.out.println("FAIL " + what + "\n   got  '" + got + "'\n   want '" + want + "'"); }
    }
    static void c(String title, String street, String kind) { eq("street: " + title, NavText.street(title), street); eq("kind: " + title, NavText.kind(title), kind); }
    public static void main(String[] a) {
        c("Head toward West Rd", "West Rd", "straight");
        c("700 m · Turn right after City Travels (on the right) onto College Rd", "College Rd", "right");
        c("120 m · Turn left after Hotel Central (on the right) onto Market Rd", "Market Rd", "left");
        c("Turn left after Right Choice Mart onto Mill Rd", "Mill Rd", "left");
        c("50 m · Turn right onto Main Rd", "Main Rd", "right");
        c("Turn right", "", "right");
        c("Slight left onto Hill Rd", "Hill Rd", "slight_left");
        c("Keep right to continue on Highway 48", "", "slight_right");
        c("Sharp right onto Lane 5", "Lane 5", "sharp_right");
        c("At the roundabout, take the 2nd exit onto Ridge Rd", "Ridge Rd", "roundabout");
        c("Make a U-turn", "", "uturn");
        c("Merge onto City Expressway", "City Expressway", "merge");
        c("Take the exit on the left toward Tech Park", "Tech Park", "ramp_left");
        c("Continue on Lake Rd for 2 km", "Lake Rd", "straight");
        c("Continue straight onto Park Rd", "Park Rd", "straight");
        c("Your destination is on the left", "", "arrive");
        c("Arrive at Old Fort", "", "arrive");
        c("1.2 km · Turn slightly left onto Station Rd", "Station Rd", "slight_left");
        c("Head north on Lake Path", "Lake Path", "straight");
        c("Turn left toward Sports Club", "Sports Club", "left");
        c("Keep left at the fork", "", "slight_left");
        eq("place dest", NavText.place("950 m · Corner Cafe  studio", ""), "Corner Cafe  studio");
        eq("place arriving", NavText.place("Arriving", "at Cafe Corner (on the left)"), "Cafe Corner");
        eq("kind arriving", NavText.kind("Arriving"), "arrive");
        eq("place manoeuvre", NavText.place("Turn right", ""), "");
        eq("place head", NavText.place("Head north", ""), "");
        eq("place empty", NavText.place("", ""), "");
        eq("dist", NavText.turnDistance("700 m · Turn right"), "700 m");
        eq("dist km", NavText.turnDistance("1,2 km · Turn"), "1.2 km");
        java.util.Calendar k = java.util.Calendar.getInstance(); k.set(2026, 10, 15, 3, 27, 40); long now = k.getTimeInMillis();
        eq("eta same minute", NavText.minutesLeft("3:27 am", now), "0 min");
        eq("eta just passed", NavText.minutesLeft("3:26 am", now), "0 min");
        eq("eta 12 min", NavText.minutesLeft("3:40 am", now), "12 min");
        eq("eta hours", NavText.minutesLeft("5:00 am", now), "1 h 32 min");
        eq("eta 24h clock", NavText.minutesLeft("13:00", now), "9 h 32 min");
        k.set(2026, 10, 15, 23, 50, 0); long late = k.getTimeInMillis();
        eq("eta across midnight", NavText.minutesLeft("12:20 am", late), "30 min");
        eq("eta a.m. dots", NavText.minutesLeft("12:20 a.m.", late), "30 min");
        k.set(2026, 10, 15, 21, 50, 0); long eve = k.getTimeInMillis();
        eq("eta pm narrow nbsp", NavText.minutesLeft("10:52\u202fpm", eve), "1 h 2 min");
        eq("eta pm already passed", NavText.minutesLeft("10:52\u202fpm", late), "0 min");
        eq("eta Arrive prefix", NavText.minutesLeft("Arrive 11:59\u202fpm", late), "9 min");
        eq("hhmm pm nbsp", NavText.hhmm("Arrive 10:52\u202fpm"), "2252");
        eq("hhmm am", NavText.hhmm("Arrive 9:05 am"), "0905");
        eq("hhmm 12 am", NavText.hhmm("12:20\u00a0am"), "0020");
        eq("hhmm 12 pm", NavText.hhmm("12:20 pm"), "1220");
        eq("hhmm 24h", NavText.hhmm("Arrive 18:40"), "1840");
        eq("eta empty", NavText.minutesLeft("", late), "");
        System.out.println(n - fail + "/" + n + " passed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
