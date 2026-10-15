package cellbreak.game;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.GameMode;
import net.minecraft.world.PersistentState;

/**
 * Stores the prison layout in the overworld save so it survives restarts, plus the game modes
 * participants had before the current round (to restore them if the server restarts mid-round).
 */
public class PrisonState extends PersistentState {
	private static final Type<PrisonState> TYPE = new Type<>(PrisonState::new, PrisonState::fromNbt, null);

	private PrisonLayout layout;
	private final Map<UUID, GameMode> roundModes = new HashMap<>();
	/** Searchlight lights and graffiti of the running round: removed if the server stopped mid-round. */
	private final Set<Long> leftovers = new HashSet<>();
	/** Players who already got the side-choice buttons on joining (they only come once). */
	private final Set<UUID> menuShown = new HashSet<>();

	public static PrisonState get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE, "cellbreak");
	}

	public PrisonLayout getLayout() {
		return layout;
	}

	public void setLayout(PrisonLayout layout) {
		this.layout = layout;
		markDirty();
	}

	/** Game modes participants had before the running round. */
	public Map<UUID, GameMode> roundModes() {
		return roundModes;
	}

	public Set<UUID> menuShown() {
		return menuShown;
	}

	public Set<Long> leftovers() {
		return leftovers;
	}

	private static PrisonState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
		PrisonState state = new PrisonState();
		if (nbt.contains("layout")) {
			state.layout = PrisonLayout.fromNbt(nbt.getCompound("layout"));
		}
		NbtCompound modes = nbt.getCompound("roundModes");
		for (String key : modes.getKeys()) {
			state.roundModes.put(UUID.fromString(key), GameMode.byName(modes.getString(key), GameMode.SURVIVAL));
		}
		for (net.minecraft.nbt.NbtElement e : nbt.getList("menuShown", net.minecraft.nbt.NbtElement.INT_ARRAY_TYPE)) {
			state.menuShown.add(net.minecraft.nbt.NbtHelper.toUuid(e));
		}
		for (long p : nbt.getLongArray("leftovers")) {
			state.leftovers.add(p);
		}
		return state;
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
		if (layout != null) {
			nbt.put("layout", layout.toNbt());
		}
		NbtCompound modes = new NbtCompound();
		roundModes.forEach((id, mode) -> modes.putString(id.toString(), mode.getName()));
		nbt.put("roundModes", modes);
		net.minecraft.nbt.NbtList shown = new net.minecraft.nbt.NbtList();
		menuShown.forEach(id -> shown.add(net.minecraft.nbt.NbtHelper.fromUuid(id)));
		nbt.put("menuShown", shown);
		nbt.putLongArray("leftovers", leftovers.stream().mapToLong(Long::longValue).toArray());
		return nbt;
	}
}
