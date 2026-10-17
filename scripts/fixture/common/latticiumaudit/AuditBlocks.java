package latticiumaudit;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/** Test-only registered stateful and adversarial BlockItems; never a production dependency. */
public final class AuditBlocks {
  public static final Identifier STATEFUL =
      Identifier.fromNamespaceAndPath("latticiumaudit", "stateful");
  public static final Identifier FAILING =
      Identifier.fromNamespaceAndPath("latticiumaudit", "failing");
  public static final Block STATEFUL_BLOCK = new RotatedPillarBlock(properties(STATEFUL));
  public static final Block FAILING_BLOCK = new FailingBlock(properties(FAILING));
  public static final Item STATEFUL_ITEM = new BlockItem(STATEFUL_BLOCK, itemProperties(STATEFUL));
  public static final Item FAILING_ITEM = new BlockItem(FAILING_BLOCK, itemProperties(FAILING));

  private AuditBlocks() {}

  private static BlockBehaviour.Properties properties(Identifier id) {
    return BlockBehaviour.Properties.of()
        .strength(0.5F)
        .setId(ResourceKey.create(Registries.BLOCK, id));
  }

  private static Item.Properties itemProperties(Identifier id) {
    return new Item.Properties()
        .setId(ResourceKey.create(Registries.ITEM, id))
        .useBlockDescriptionPrefix();
  }

  public static void register() {
    Registry.register(BuiltInRegistries.BLOCK, STATEFUL, STATEFUL_BLOCK);
    Registry.register(BuiltInRegistries.BLOCK, FAILING, FAILING_BLOCK);
    Registry.register(BuiltInRegistries.ITEM, STATEFUL, STATEFUL_ITEM);
    Registry.register(BuiltInRegistries.ITEM, FAILING, FAILING_ITEM);
  }

  private static final class FailingBlock extends Block {
    FailingBlock(BlockBehaviour.Properties properties) {
      super(properties);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
      throw new IllegalStateException("Deliberate modded placement predictor failure");
    }
  }
}
