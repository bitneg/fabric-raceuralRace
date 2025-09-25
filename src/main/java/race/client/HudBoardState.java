package race.client;

final class HudBoardState {
    private static java.util.List<race.net.RaceBoardPayload.Row> rows = new java.util.ArrayList<>();
    static void setRows(java.util.List<race.net.RaceBoardPayload.Row> r) { rows = new java.util.ArrayList<>(r); }
    static java.util.List<race.net.RaceBoardPayload.Row> getRows() { return rows; }
}

