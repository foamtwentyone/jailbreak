package cellbreak.game;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import cellbreak.CbContent;
import cellbreak.entity.GuardEntity;
import cellbreak.game.CellbreakGame.Phase;
import cellbreak.game.CellbreakGame.PlayerData;
import cellbreak.game.CellbreakGame.Role;
import cellbreak.game.CellbreakGame.Zone;
import cellbreak.net.GuidePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;

/**
 * The routine arrow, as in The Escapists: a prisoner who is not where the routine wants them sees
 * an arrow pointing the way there. The way is found by the guards' own path finding (through the
 * doors, round the walls), and the arrow points a few blocks ahead along it.
 */
final class Guide {
	private static final int AHEAD = 5;

	private final CellbreakGame game;
	/** One spot per zone for the round (the arrow should not jump around inside a zone). */
	private final Map<Zone, BlockPos> spots = new EnumMap<>(Zone.class);
	private final Map<UUID, Boolean> shown = new HashMap<>();
	private GuardEntity walker;

	Guide(CellbreakGame game) {
		this.game = game;
	}

	void onStart() {
		spots.clear();
		walker = null;
	}

	void onStop() {
		for (ServerPlayerEntity p : game.server.getPlayerManager().getPlayerList()) {
			send(p, GuidePayload.NONE);
		}
		shown.clear();
		walker = null;
	}

	void tick() {
		Phase phase = game.phase();
		for (ServerPlayerEntity p : game.online(Role.PRISONER)) {
			// Each player twice a second, not all in the same tick.
			if ((game.ticks + (p.getId() & 7)) % 10 != 0) {
				continue;
			}
			GuidePayload g = guide(p, game.data(p), phase);
			if (g != null || Boolean.TRUE.equals(shown.get(p.getUuid()))) {
				send(p, g == null ? GuidePayload.NONE : g);
				shown.put(p.getUuid(), g != null);
			}
		}
	}

	private GuidePayload guide(ServerPlayerEntity p, PlayerData d, Phase phase) {
		if (d == null || d.escaped || d.solitaryUntil != 0 || d.infirmaryUntil != 0 || d.cell < 0 || phase.zone == Zone.ANYWHERE
				|| p.getServerWorld() != game.world() || game.isInPlace(p, d, phase)) {
			return null;
		}
		BlockPos goal = phase.zone == Zone.CELLS ? game.layout().cells.get(d.cell).spawn : spot(phase.zone);
		if (goal == null) {
			return null;
		}
		BlockPos next = nextStep(p, goal);
		String label = CellbreakGame.placeName(phase)[1];
		label = Character.toUpperCase(label.charAt(0)) + label.substring(1);
		return new GuidePayload(label, next.getX() + 0.5F, next.getY(), next.getZ() + 0.5F, goal.getX() + 0.5F, goal.getY(),
				goal.getZ() + 0.5F);
	}

	private BlockPos spot(Zone zone) {
		BlockBox box = game.zoneBox(zone);
		if (box == null) {
			return null;
		}
		return spots.computeIfAbsent(zone, z -> game.floorSpotIn(box));
	}

	/** A point a few blocks ahead on the way from the player to the goal (the goal itself if no way is found). */
	private BlockPos nextStep(ServerPlayerEntity p, BlockPos goal) {
		if (walker == null) {
			walker = CbContent.GUARD.create(game.world());
			if (walker == null) {
				return goal;
			}
		}
		walker.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ(), 0, 0);
		walker.setOnGround(true);
		walker.getNavigation().stop();
		Path path = walker.getNavigation().findPathTo(goal, 1);
		if (path == null || path.getLength() < 2) {
			return goal;
		}
		BlockPos from = p.getBlockPos();
		for (int i = 0; i < path.getLength(); i++) {
			BlockPos n = path.getNodePos(i);
			if (n.getSquaredDistance(from) >= AHEAD * AHEAD) {
				return n;
			}
		}
		return path.getEnd() != null && path.reachesTarget() ? goal : path.getNodePos(path.getLength() - 1);
	}

	private static void send(ServerPlayerEntity p, GuidePayload g) {
		if (ServerPlayNetworking.canSend(p, GuidePayload.ID)) {
			ServerPlayNetworking.send(p, g);
		}
	}
}
