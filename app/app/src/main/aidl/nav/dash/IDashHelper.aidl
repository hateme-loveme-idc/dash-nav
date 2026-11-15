package nav.dash;

import android.view.Surface;
import android.os.Bundle;

// Runs inside the privileged (shell/root) process started by Shizuku.
interface IDashHelper {
    // Shizuku calls this when the user service is unbound; must exit the process.
    void destroy() = 16777114;

    /** If this binder dies (app crashed/killed), the helper restores the phone panel and cleans up. */
    void setClient(IBinder client) = 1;
    /** Create the hidden display rendering into the app's surface; returns its display id. */
    int createDisplay(in Surface surface, int width, int height, int dpi) = 2;
    /** Run "am start --display <id> <args>" as the privileged user. */
    String startOnDisplay(int displayId, String amArgs) = 3;
    /** Phone panel on/off; the device stays awake while off. */
    boolean setPanel(boolean on) = 4;
    /** Release the hidden display and turn the panel back on. */
    void release() = 5;
    /** "<taskId> <displayId>" for Google Maps, or empty. */
    String mapsTask() = 6;
    /** Move a root task to another display. */
    String moveTask(int taskId, int displayId) = 7;
    /** Force-stop Google Maps (ends its navigation): used when the rider gives a new destination. */
    String stopMaps() = 8;
    /** Live data: Maps navigation (from its notification), call state + caller, now playing. */
    Bundle feed() = 9;
    /** Music control: 1 next, 2 previous, 3 play/pause. */
    void mediaAction(int action) = 10;
    /** Key injection limited to answer call / end call / wake. */
    void key(int code) = 11;
}
