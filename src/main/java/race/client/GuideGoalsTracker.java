package race.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Кастомные цели из гайда: независимы от ванильных достижений.
 */
public final class GuideGoalsTracker {
    public static final class Goal {
        public final String id;
        public final String title;
        public final int guideTabIndex; // вкладка RaceGuideScreen: 0 Overworld, 1 Nether, 2 Stronghold, 3 End, 4 Tips, 5 Checklist
        Goal(String id, String title, int guideTabIndex) {
            this.id = id; this.title = title; this.guideTabIndex = guideTabIndex;
        }
    }

    private static final List<Goal> GOALS = new ArrayList<>();
    private static final Map<String, Boolean> DONE = new LinkedHashMap<>();
    private static final Map<String, Long> DONE_TIME_MS = new LinkedHashMap<>();

    static {
        // Overworld
        add("ow_tools", "Инструменты + еда", 0);
        add("ow_iron3", "Железо 3: ведро/огниво", 0);
        add("ow_portal", "Портал в ад", 0);
        // Nether
        add("nether_perls", "Перлы 12–16", 1);
        add("nether_rods", "Стержни 6–8", 1);
        // Stronghold
        add("sh_eyes12", "Глаза края 12", 2);
        add("sh_found", "Найти стронгхолд", 2);
        add("sh_activate", "Активировать портал", 2);
        // End
        add("end_setup", "Кровати/площадка", 3);
        add("end_kill", "Убийство дракона", 3);
    }

    private static void add(String id, String title, int tab) {
        GOALS.add(new Goal(id, title, tab));
        DONE.put(id, false);
    }

    public static List<Goal> getGoals() { return Collections.unmodifiableList(GOALS); }
    public static boolean isDone(String id) { return DONE.getOrDefault(id, false); }
    public static long getDoneTimeMs(String id) { return DONE_TIME_MS.getOrDefault(id, -1L); }
    public static void toggle(String id, long currentRunMs) {
        boolean newVal = !isDone(id);
        DONE.put(id, newVal);
        if (newVal) DONE_TIME_MS.put(id, currentRunMs); else DONE_TIME_MS.remove(id);
    }
    public static void clearAll() {
        for (Goal g : GOALS) DONE.put(g.id, false);
        DONE_TIME_MS.clear();
    }

    private GuideGoalsTracker() {}
}


