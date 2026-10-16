package cellbreak.game;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import cellbreak.CbContent;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.LightBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.inventory.Inventory;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * /cellbreak map ...: mark the parts of a prison on an existing map. Every command takes a position,
 * so it works from the console with coordinates as well as in game with ~ ~ ~.
 */
public final class MapCommands {
	private static final int MAX_ROOM = 120;

	private MapCommands() {
	}

	public static LiteralArgumentBuilder<ServerCommandSource> build() {
		return CommandManager.literal("map").requires(s -> s.hasPermissionLevel(2))
				.then(CommandManager.literal("new").executes(c -> {
					if (CellbreakGame.get(c.getSource().getServer()).isRunning()) {
						c.getSource().sendError(Text.literal("Сначала остановите раунд."));
						return 0;
					}
					PrisonState.get(c.getSource().getServer()).setLayout(new PrisonLayout());
					ok(c, "Начата разметка новой тюрьмы. Отметьте территорию, камеры и зоны, проверка: /cellbreak map info");
					return 1;
				}))
				.then(CommandManager.literal("area").then(box((c, b) -> {
					layout(c).areas.add(b);
					ok(c, "Добавлена часть территории тюрьмы: " + str(b));
				})))
				.then(CommandManager.literal("breakminy")
						.then(CommandManager.argument("y", IntegerArgumentType.integer(-64, 320)).executes(c -> {
							layout(c).breakMinY = IntegerArgumentType.getInteger(c, "y");
							save(c);
							ok(c, "Ниже высоты " + layout(c).breakMinY + " ломать нельзя.");
							return 1;
						})))
				.then(CommandManager.literal("cell").then(pos((c, p) -> {
					Room room = scanRoom(world(c), p);
					if (room.door == null) {
						throw error("Не нашёл железную дверь у этой комнаты. Встаньте внутри камеры.");
					}
					PrisonLayout l = layout(c);
					for (PrisonLayout.Cell existing : l.cells) {
						if (existing.interior.contains(p)) {
							throw error("Эта камера уже отмечена.");
						}
					}
					l.cells.add(new PrisonLayout.Cell(p, room.door, room.findContainer(world(c)), room.box));
					ok(c, "Камера №" + l.cells.size() + " отмечена: " + str(room.box) + ", дверь " + room.door.toShortString()
							+ (room.findContainer(world(c)) == null ? ", тумбочки нет" : ""));
				})))
				.then(CommandManager.literal("door").then(pos((c, p) -> {
					BlockPos door = lowerDoor(world(c), p);
					if (door == null) {
						throw error("Здесь нет железной двери.");
					}
					layout(c).dayDoors.add(door);
					ok(c, "Дверь " + door.toShortString() + " будет открыта днём и заперта на отбой.");
				})))
				.then(CommandManager.literal("rollcall").then(box((c, b) -> {
					layout(c).rollCallZone = b;
					ok(c, "Место переклички: " + str(b));
				})))
				.then(CommandManager.literal("canteen").then(box((c, b) -> {
					layout(c).canteenZone = b;
					ok(c, "Столовая: " + str(b));
				})))
				.then(CommandManager.literal("work").then(box((c, b) -> {
					layout(c).workZone = b;
					ok(c, "Рабочее поле: " + str(b) + ". Нужны железная руда и деревья, ящик с рабочими инструментами (map supply)"
							+ " и стол сдачи работы (cellbreak:job_desk) рядом.");
				})))
				.then(CommandManager.literal("workshop").then(box((c, b) -> {
					layout(c).workshopZone = b;
					ok(c, "Мастерская (со столом сдачи работы): " + str(b));
				})))
				.then(CommandManager.literal("showerhead").then(pos((c, p) -> {
					if (!world(c).getBlockState(p).isOf(Blocks.LEVER)) {
						throw error("Здесь нет рычага: кран душа — это рычаг.");
					}
					layout(c).showerHeads.add(p);
					ok(c, "Кран душа №" + layout(c).showerHeads.size() + ": " + p.toShortString());
				})))
				.then(CommandManager.literal("infirmary")
						.then(CommandManager.argument("from", BlockPosArgumentType.blockPos())
								.then(CommandManager.argument("to", BlockPosArgumentType.blockPos())
										.then(CommandManager.argument("spawn", BlockPosArgumentType.blockPos()).executes(c -> {
											PrisonLayout l = layout(c);
											l.infirmaryZone = BlockBox.create(BlockPosArgumentType.getBlockPos(c, "from"),
													BlockPosArgumentType.getBlockPos(c, "to"));
											l.infirmarySpawn = BlockPosArgumentType.getBlockPos(c, "spawn");
											save(c);
											ok(c, "Медпункт: " + str(l.infirmaryZone));
											return 1;
										})))))
				.then(CommandManager.literal("detector").then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
						.then(CommandManager.argument("facing", com.mojang.brigadier.arguments.StringArgumentType.word())
								.suggests((c, b) -> net.minecraft.command.CommandSource.suggestMatching(
										List.of("north", "south", "east", "west"), b))
								.executes(c -> placeDetector(c, false))
								.then(CommandManager.literal("materials").executes(c -> placeDetector(c, true))))))
				.then(CommandManager.literal("cook").then(pos((c, p) -> {
					layout(c).cookPosts.add(p);
					ok(c, "Место повара №" + layout(c).cookPosts.size() + ": " + p.toShortString()
							+ (layout(c).cookPosts.size() == 1 ? " (угрюмый Степаныч)" : " (весёлый Гена)"));
				})))
				.then(CommandManager.literal("queue").then(pos((c, p) -> {
					layout(c).queueSpots.add(p);
					ok(c, "Место в очереди за едой №" + layout(c).queueSpots.size() + ": " + p.toShortString());
				})))
				.then(CommandManager.literal("foodchest").then(pos((c, p) -> {
					if (!(world(c).getBlockEntity(p) instanceof Inventory)) {
						throw error("Здесь нет сундука или бочки.");
					}
					layout(c).foodChests.add(p);
					ok(c, "Сундук с едой на кухне №" + layout(c).foodChests.size() + ": " + p.toShortString());
				})))
				.then(CommandManager.literal("gym").then(box((c, b) -> {
					layout(c).gymZone = b;
					ok(c, "Качалка: " + str(b));
				})))
				.then(CommandManager.literal("camera").then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
						.then(CommandManager.argument("facing", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(c -> {
							BlockPos p = BlockPosArgumentType.getBlockPos(c, "pos");
							Direction f = Direction.byName(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "facing"));
							if (f == null) {
								throw error("Направление: north, south, east, west, up или down.");
							}
							world(c).setBlockState(p, net.minecraft.block.Blocks.OBSERVER.getDefaultState()
									.with(net.minecraft.block.ObserverBlock.FACING, f));
							layout(c).cameras.add(p);
							layout(c).cameraFacing.add(f.getId());
							ok(c, "Камера №" + layout(c).cameras.size() + ": " + p.toShortString() + " смотрит " + f.asString());
							return 1;
						}))))
				.then(CommandManager.literal("monitor").then(pos((c, p) -> {
					layout(c).monitor = p;
					ok(c, "Пульт видеонаблюдения: " + p.toShortString());
				})))
				.then(CommandManager.literal("locker").then(pos((c, p) -> {
					layout(c).locker = p;
					ok(c, "Шкафчик охраны (запасная форма): " + p.toShortString());
				})))
				.then(CommandManager.literal("job").then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
						.then(CommandManager.argument("kind", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(c -> {
							BlockPos p = BlockPosArgumentType.getBlockPos(c, "pos");
							String kind = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "kind");
							if (!List.of("library", "laundry", "canteen").contains(kind)) {
								throw error("Работа: library, laundry или canteen.");
							}
							layout(c).jobStations.add(p);
							layout(c).jobKinds.add(kind);
							ok(c, "Рабочее место (" + kind + "): " + p.toShortString());
							return 1;
						}))))
				.then(CommandManager.literal("gatepost").then(pos((c, p) -> {
					layout(c).gatePost = p;
					ok(c, "Пост у главных ворот: " + p.toShortString());
				})))
				.then(CommandManager.literal("gymmachine").then(pos((c, p) -> {
					layout(c).gymMachines.add(p);
					ok(c, "Тренажёр (блок) №" + layout(c).gymMachines.size() + ": " + p.toShortString());
				})))
				.then(CommandManager.literal("punchbag").then(pos((c, p) -> {
					layout(c).punchBags.add(p);
					ok(c, "Боксёрская груша (блок) №" + layout(c).punchBags.size() + ": " + p.toShortString());
				})))
				.then(CommandManager.literal("walk").then(pos((c, p) -> {
					layout(c).walkPoints.add(p);
					ok(c, "Точка прогулочной тропинки №" + layout(c).walkPoints.size() + ": " + p.toShortString());
				})))
				.then(CommandManager.literal("rollcallpost").then(pos((c, p) -> {
					layout(c).rollCallPosts.add(p);
					ok(c, "Пост охраны на перекличке №" + layout(c).rollCallPosts.size() + ": " + p.toShortString());
				})))
				.then(CommandManager.literal("yard").then(box((c, b) -> {
					layout(c).yardZone = b;
					ok(c, "Прогулочный двор: " + str(b));
				})))
				.then(CommandManager.literal("shower").then(box((c, b) -> {
					layout(c).showerZone = b;
					ok(c, "Душевая: " + str(b));
				})))
				.then(CommandManager.literal("supply").then(pos((c, p) -> {
					if (!(world(c).getBlockEntity(p) instanceof Inventory)) {
						throw error("Здесь нет сундука или бочки.");
					}
					layout(c).workSupplies.add(p);
					ok(c, "Склад сырья для работы №" + layout(c).workSupplies.size() + ": " + p.toShortString());
				})))
				.then(CommandManager.literal("sniper").then(pos((c, p) -> {
					layout(c).sniperPosts.add(p);
					ok(c, "Пост снайпера №" + layout(c).sniperPosts.size() + ": " + p.toShortString());
				})))
				.then(CommandManager.literal("solitary").then(pos((c, p) -> {
					Room room = scanRoom(world(c), p);
					PrisonLayout l = layout(c);
					l.solitarySpawn = p;
					l.solitaryInterior = room.box;
					l.solitaryDoor = room.door;
					ok(c, "Карцер: " + str(room.box) + (room.door == null ? " (без двери!)" : ", дверь " + room.door.toShortString()));
				})))
				.then(CommandManager.literal("buildsolitary").then(pos((c, p) -> {
					buildSolitary(world(c), p, layout(c));
					ok(c, "Карцер построен, вход с юга. Вход: " + layout(c).solitaryDoor.toShortString());
				})))
				.then(CommandManager.literal("guardroom").then(pos((c, p) -> {
					PrisonLayout l = layout(c);
					l.guardSpawn = p;
					Room room = scanRoom(world(c), p);
					l.guardRoomDoor = room.door;
					ok(c, "Комната охраны: появление " + p.toShortString()
							+ (room.door == null ? "" : ", дверь " + room.door.toShortString()));
				})))
				.then(CommandManager.literal("npcspawn").then(pos((c, p) -> {
					layout(c).npcGuardSpawn = p;
					ok(c, "NPC-охрана появляется в " + p.toShortString());
				})))
				.then(CommandManager.literal("patrol").then(pos((c, p) -> {
					layout(c).patrolPoints.add(p);
					ok(c, "Точка патруля №" + layout(c).patrolPoints.size() + ": " + p.toShortString());
				})))
				.then(CommandManager.literal("scrap").then(pos((c, p) -> {
					if (!(world(c).getBlockEntity(p) instanceof Inventory)) {
						throw error("Здесь нет сундука или бочки.");
					}
					layout(c).scrapChests.add(p);
					ok(c, "Ящик с хламом №" + layout(c).scrapChests.size() + ": " + p.toShortString());
				})))
				.then(CommandManager.literal("info").executes(MapCommands::info));
	}

	/** Puts a scanner arch; with materials it also stops logs, planks, ingots and nuggets. */
	private static int placeDetector(CommandContext<ServerCommandSource> c, boolean materials) throws CommandSyntaxException {
		BlockPos p = BlockPosArgumentType.getBlockPos(c, "pos");
		Direction facing = Direction.byName(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "facing"));
		if (facing == null || facing.getAxis() == Direction.Axis.Y) {
			throw error("Направление прохода: north, south, east или west.");
		}
		BlockState lower = CbContent.DETECTOR.getDefaultState().with(cellbreak.block.DetectorBlock.FACING, facing);
		int flags = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
		world(c).setBlockState(p, lower, flags);
		world(c).setBlockState(p.up(), lower.with(cellbreak.block.DetectorBlock.HALF, DoubleBlockHalf.UPPER), flags);
		PrisonLayout l = layout(c);
		if (!l.detectors.contains(p)) {
			l.detectors.add(p.toImmutable());
		}
		if (materials && !l.materialDetectors.contains(p)) {
			l.materialDetectors.add(p.toImmutable());
		}
		save(c);
		ok(c, "Сканер-рамка поставлена: " + p.toShortString() + (materials ? " (не выпускает брёвна, доски, слитки, самородки)" : ""));
		return 1;
	}

	private static int info(CommandContext<ServerCommandSource> c) {
		PrisonLayout l = PrisonState.get(c.getSource().getServer()).getLayout();
		if (l == null) {
			c.getSource().sendError(Text.literal("Тюрьмы нет. Начните разметку: /cellbreak map new"));
			return 0;
		}
		StringBuilder sb = new StringBuilder();
		sb.append("Территория: ").append(l.areas.size()).append(" частей, ломать можно с высоты ").append(l.breakMinY);
		sb.append("\nКамер: ").append(l.cells.size()).append(", дневных дверей: ").append(l.dayDoors.size());
		sb.append("\nПерекличка: ").append(l.rollCallZone == null ? "-" : str(l.rollCallZone));
		sb.append("\nСтоловая: ").append(l.canteenZone == null ? "-" : str(l.canteenZone));
		sb.append("\nРабота: ").append(l.workZone == null ? "- (работы не будет)" : str(l.workZone))
				.append(", складов сырья: ").append(l.workSupplies.size());
		sb.append("\nМастерская: ").append(l.workshopZone == null ? "-" : str(l.workshopZone));
		sb.append("\nМедпункт: ").append(l.infirmaryZone == null ? "- (избитые идут в карцер)" : str(l.infirmaryZone))
				.append(", кранов душа: ").append(l.showerHeads.size())
				.append(", постов на перекличке: ").append(l.rollCallPosts.size());
		sb.append("\nПрогулка: ").append(l.yardZone == null ? "- (прогулки не будет)" : str(l.yardZone));
		sb.append("\nДуш: ").append(l.showerZone == null ? "- (душа не будет)" : str(l.showerZone));
		sb.append("\nСнайперов: ").append(l.sniperPosts.size());
		sb.append("\nКарцер: ").append(l.solitarySpawn == null ? "-" : l.solitarySpawn.toShortString());
		sb.append("\nКомната охраны: ").append(l.guardSpawn == null ? "-" : l.guardSpawn.toShortString());
		sb.append("\nNPC-охрана: ").append(l.npcGuardSpawn == null ? "-" : l.npcGuardSpawn.toShortString());
		sb.append("\nТочек патруля: ").append(l.patrolPoints.size()).append(", ящиков с хламом: ").append(l.scrapChests.size());
		List<String> missing = l.missing();
		sb.append(missing.isEmpty() ? "\nВсё готово, можно начинать раунд." : "\nНе хватает: " + String.join(", ", missing));
		c.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
		return 1;
	}

	// ------------------------------------------------------------------ room scanning

	/** The walkable floor area around a point, up to the walls, closed doors and bars. */
	static class Room {
		final Set<BlockPos> floor = new HashSet<>();
		BlockBox box;
		BlockPos door;

		BlockPos findContainer(ServerWorld world) {
			for (BlockPos p : BlockPos.iterate(box.getMinX() - 1, box.getMinY(), box.getMinZ() - 1, box.getMaxX() + 1,
					box.getMinY() + 1, box.getMaxZ() + 1)) {
				// Only a chest or a barrel: droppers and hoppers there belong to the door mechanism.
				var be = world.getBlockEntity(p);
				if (be instanceof net.minecraft.block.entity.ChestBlockEntity
						|| be instanceof net.minecraft.block.entity.BarrelBlockEntity) {
					return p.toImmutable();
				}
			}
			return null;
		}
	}

	static Room scanRoom(ServerWorld world, BlockPos start) throws CommandSyntaxException {
		Room room = new Room();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start);
		room.floor.add(start);
		while (!queue.isEmpty()) {
			BlockPos p = queue.poll();
			for (Direction d : Direction.Type.HORIZONTAL) {
				BlockPos n = p.offset(d);
				if (room.floor.contains(n)) {
					continue;
				}
				BlockPos door = lowerDoor(world, n);
				if (door != null) {
					if (room.door == null) {
						room.door = door;
					}
					continue;
				}
				if (!passable(world, n)) {
					continue;
				}
				room.floor.add(n);
				queue.add(n);
				if (room.floor.size() > MAX_ROOM) {
					throw error("Это слишком большое помещение (больше " + MAX_ROOM + " блоков пола). Встаньте внутри комнаты с дверью.");
				}
			}
		}
		int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (BlockPos p : room.floor) {
			minX = Math.min(minX, p.getX());
			maxX = Math.max(maxX, p.getX());
			minZ = Math.min(minZ, p.getZ());
			maxZ = Math.max(maxZ, p.getZ());
		}
		room.box = new BlockBox(minX, start.getY(), minZ, maxX, start.getY() + 2, maxZ);
		return room;
	}

	private static boolean passable(ServerWorld world, BlockPos p) {
		return world.getBlockState(p).getCollisionShape(world, p).isEmpty()
				&& world.getBlockState(p.up()).getCollisionShape(world, p.up()).isEmpty()
				|| world.getBlockState(p).isIn(net.minecraft.registry.tag.BlockTags.BEDS);
	}

	/** The lower half of the iron door at this position (or the one right above/below), if any. */
	static BlockPos lowerDoor(ServerWorld world, BlockPos p) {
		BlockState state = world.getBlockState(p);
		if (!state.isOf(Blocks.IRON_DOOR)) {
			return null;
		}
		return state.get(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? p.down().toImmutable() : p.toImmutable();
	}

	/** A small reinforced solitary cell (inside 3x4) with its door on the south side, at ground level p. */
	static void buildSolitary(ServerWorld world, BlockPos p, PrisonLayout layout) {
		BlockState reinforced = CbContent.REINFORCED_WALL.getDefaultState();
		int flags = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
		for (BlockPos q : BlockPos.iterate(p.add(-2, -5, -2), p.add(2, 3, 3))) {
			world.setBlockState(q, reinforced, flags);
		}
		for (BlockPos q : BlockPos.iterate(p.add(-1, 0, -1), p.add(1, 2, 2))) {
			world.setBlockState(q, Blocks.AIR.getDefaultState(), flags);
		}
		BlockState door = Blocks.IRON_DOOR.getDefaultState().with(DoorBlock.FACING, Direction.SOUTH);
		world.setBlockState(p.add(0, 0, 3), door.with(DoorBlock.HALF, DoubleBlockHalf.LOWER), flags);
		world.setBlockState(p.add(0, 1, 3), door.with(DoorBlock.HALF, DoubleBlockHalf.UPPER), flags);
		world.setBlockState(p.add(0, 2, 0), Blocks.LIGHT.getDefaultState().with(LightBlock.LEVEL_15, 6), flags);
		layout.solitarySpawn = p.toImmutable();
		layout.solitaryInterior = BlockBox.create(p.add(-1, 0, -1), p.add(1, 2, 2));
		layout.solitaryDoor = p.add(0, 0, 3).toImmutable();
	}

	// ------------------------------------------------------------------ helpers

	interface PosAction {
		void run(CommandContext<ServerCommandSource> c, BlockPos pos) throws CommandSyntaxException;
	}

	interface BoxAction {
		void run(CommandContext<ServerCommandSource> c, BlockBox box) throws CommandSyntaxException;
	}

	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, ?> pos(PosAction action) {
		return CommandManager.argument("pos", BlockPosArgumentType.blockPos()).executes(c -> {
			action.run(c, BlockPosArgumentType.getLoadedBlockPos(c, "pos"));
			save(c);
			return 1;
		});
	}

	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, ?> box(BoxAction action) {
		return CommandManager.argument("from", BlockPosArgumentType.blockPos())
				.then(CommandManager.argument("to", BlockPosArgumentType.blockPos()).executes(c -> {
					action.run(c, BlockBox.create(BlockPosArgumentType.getBlockPos(c, "from"),
							BlockPosArgumentType.getBlockPos(c, "to")));
					save(c);
					return 1;
				}));
	}

	private static PrisonLayout layout(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
		PrisonLayout l = PrisonState.get(c.getSource().getServer()).getLayout();
		if (l == null) {
			throw error("Сначала начните разметку: /cellbreak map new");
		}
		return l;
	}

	private static void save(CommandContext<ServerCommandSource> c) {
		PrisonState.get(c.getSource().getServer()).markDirty();
	}

	private static ServerWorld world(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
		if (c.getSource().getWorld() != c.getSource().getServer().getOverworld()) {
			throw error("Тюрьма может быть только в обычном мире.");
		}
		return c.getSource().getWorld();
	}

	private static void ok(CommandContext<ServerCommandSource> c, String msg) {
		c.getSource().sendFeedback(() -> Text.literal(msg).formatted(Formatting.GREEN), true);
	}

	private static CommandSyntaxException error(String msg) {
		return new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Text.literal(msg)).create();
	}

	private static String str(BlockBox b) {
		return b.getMinX() + " " + b.getMinY() + " " + b.getMinZ() + " .. " + b.getMaxX() + " " + b.getMaxY() + " " + b.getMaxZ();
	}
}
