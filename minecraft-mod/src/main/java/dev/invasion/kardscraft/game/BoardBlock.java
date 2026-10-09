package dev.invasion.kardscraft.game;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The visible, breakable board surface. TNT and Steve can break it; holes stay (BoardManager never refills them).
 * While the transparent overlay shows the real KARDS table the layer is not drawn, so KARDS stays visible underneath
 * (holes then show as the dark base block below); in the first-person board view it is drawn normally. The game logic (collision, explosions, breaking) is the same
 * in both views; only the client mesh changes.
 */
public class BoardBlock extends Block {
    public static final MapCodec<BoardBlock> CODEC = simpleCodec(BoardBlock::new);

    /** Set by the client: true while the overlay table view is shown. Read when chunk sections are meshed. */
    public static volatile boolean hidden;

    public BoardBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return hidden ? RenderShape.INVISIBLE : RenderShape.MODEL;
    }
}
