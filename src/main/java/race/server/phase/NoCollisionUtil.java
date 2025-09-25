package race.server.phase;

import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

public final class NoCollisionUtil {
    private static final String TEAM = "race_noclip";

    public static void apply(MinecraftServer s) {
        Scoreboard sb = s.getScoreboard();
        Team team = sb.getTeam(TEAM);
        if (team == null) team = sb.addTeam(TEAM);
        team.setCollisionRule(Team.CollisionRule.NEVER);
        team.setFriendlyFireAllowed(false);
        for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
            String entry = p.getGameProfile().getName();
            sb.addScoreHolderToTeam(entry, team);
        }
    }

    public static void remove(MinecraftServer s) {
        Scoreboard sb = s.getScoreboard();
        Team team = sb.getTeam(TEAM);
        if (team != null) sb.removeTeam(team);
    }

    private NoCollisionUtil() {}
}
