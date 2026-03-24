package me.cortex.voxy.client.core.model.bakery;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.common.util.UnsafeUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.Identifier;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.biome.ColorResolver;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.light.LightingProvider;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.FluidState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL45C.glGetTextureImage;

public class SoftwareModelTextureBakery {
    //Note: the first bit of metadata is if alpha discard is enabled
    private static final Matrix4f[] VIEWS = new Matrix4f[6];

    private final ReuseVertexConsumer vc = new ReuseVertexConsumer();
    private final SoftwareRasterizer rasterizer = new SoftwareRasterizer();

    public SoftwareModelTextureBakery() {
    }

    public void setupTexture() {
        // In 1.20.1, we use GL-based texture readback instead of the 1.21 GpuBuffer API
        var atlasTexture = (SpriteAtlasTexture) MinecraftClient.getInstance()
                .getTextureManager()
                .getTexture(new Identifier("minecraft", "textures/atlas/blocks.png"));

        int texId = atlasTexture.getGlId();

        // Get texture dimensions via GL45 DSA
        int[] w = new int[1], h = new int[1];
        org.lwjgl.opengl.GL45.glGetTextureLevelParameteriv(texId, 0, GL_TEXTURE_WIDTH, w);
        org.lwjgl.opengl.GL45.glGetTextureLevelParameteriv(texId, 0, GL_TEXTURE_HEIGHT, h);
        int width = w[0];
        int height = h[0];

        // Read texture data using GL45 DSA (no need to bind)
        int bufSize = width * height * 4;
        ByteBuffer buffer = MemoryUtil.memAlloc(bufSize);
        try {
            glGetTextureImage(texId, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);

            int[] texture = new int[width * height];
            buffer.asIntBuffer().get(texture);

            // Check if texture is valid
            int nonZero = 0;
            for (int i = 0; i < Math.min(1000, texture.length); i++) {
                if (texture[i] != 0) nonZero++;
            }
            System.out.println("[Voxy] setupTexture: texId=" + texId + " size=" + width + "x" + height + " nonZeroPixels(first1000)=" + nonZero);
            this.rasterizer.setSamplerTexture(texture, width, height);
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }

    public static int getMetaFromLayer(RenderLayer layer) {
        boolean hasDiscard = layer == RenderLayer.getCutout() ||
                layer == RenderLayer.getTranslucent()||
                layer == RenderLayer.getTripwire();

        int meta = hasDiscard?1:0;
        meta |= true?2:0;
        return meta;
    }

    private void bakeBlockModel(BlockState state, RenderLayer layer) {
        if (state.getRenderType() == BlockRenderType.INVISIBLE) {
            return;//Dont bake if invisible
        }
        var model = MinecraftClient.getInstance()
                .getBlockRenderManager()
                .getModel(state);

        int meta = getMetaFromLayer(layer);

        // In 1.20.1, BakedModel.getQuads(state, face, random) returns quads directly
        var random = new net.minecraft.util.math.random.LocalRandom(42L);
        for (Direction direction : new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null}) {
            var quads = model.getQuads(state, direction, random);
            for (var quad : quads) {
                this.vc.quad(quad, meta|(quad.hasColor()?4:0));
            }
        }
    }


    private void bakeFluidState(BlockState state, RenderLayer layer, int face) {
        {
            //TODO: somehow set the tint flag per quad or something?
            int metadata = getMetaFromLayer(layer);
            //Just assume all fluids are tinted, if they arnt it should be implicitly culled in the model baking phase
            // since it wont have the colour provider
            metadata |= 4;//Has tint
            this.vc.setDefaultMeta(metadata);//Set the meta while baking
        }
        // In 1.20.1: BlockRenderManager.renderFluid(pos, world, vertexConsumer, blockState, fluidState)
        MinecraftClient.getInstance().getBlockRenderManager().renderFluid(BlockPos.ORIGIN, new BlockRenderView() {
            @Override
            public float getBrightness(Direction direction, boolean shaded) {
                return 0;
            }

            @Override
            public LightingProvider getLightingProvider() {
                return null;
            }

            @Override
            public int getLightLevel(LightType type, BlockPos pos) {
                return 0;
            }

            @Override
            public int getColor(BlockPos pos, ColorResolver colorResolver) {
                return 0;
            }

            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.getDefaultState();
                }

                return state;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.getDefaultState().getFluidState();
                }

                return state.getFluidState();
            }

            @Override
            public int getHeight() {
                return 0;
            }

            @Override
            public int getBottomY() {
                return 0;
            }
        }, this.vc, state, state.getFluidState());
        this.vc.setDefaultMeta(0);//Reset default meta
    }

    private static boolean shouldReturnAirForFluid(BlockPos pos, int face) {
        var fv = Direction.byId(face).getVector();
        int dot = fv.getX()*pos.getX() + fv.getY()*pos.getY() + fv.getZ()*pos.getZ();
        return dot >= 1;
    }

    public void free() {
        this.vc.free();
    }

    private static final long SINGLE_FACE_OUTPUT_SIZE = (ModelFactory.MODEL_TEXTURE_SIZE * ModelFactory.MODEL_TEXTURE_SIZE)*8;
    //The outputBuffer layout is different from the non software rasterized ModelTextureBakery
    // in this version the values are simply appended (0,0),(1,0),(2,0),(0,1),(1,1),(2,1)

    public int renderToOutput(BlockState state, long outputBuffer) {
        MemoryUtil.memSet(outputBuffer,0,16*16*8*6);


        boolean isBlock = true;
        RenderLayer layer;
        if (state.getBlock() instanceof FluidBlock) {
            layer = RenderLayers.getFluidLayer(state.getFluidState());
            isBlock = false;
        } else {
            if (state.getBlock() instanceof LeavesBlock) {
                layer = RenderLayer.getSolid();
            } else {
                layer = RenderLayers.getBlockLayer(state);
            }
        }

        //TODO: support block model entities
        //BakedBlockEntityModel bbem = null;
        if (state.getBlock() instanceof net.minecraft.block.BlockEntityProvider) {
            //bbem = BakedBlockEntityModel.bake(state);
        }

        {
            this.rasterizer.setBlending(layer == RenderLayer.getTranslucent());
        }

        boolean isAnyShaded = false;
        boolean isAnyDarkend = false;
        if (isBlock) {
            this.vc.reset();
            this.bakeBlockModel(state, layer);
            isAnyShaded |= this.vc.anyShaded;
            isAnyDarkend |= this.vc.anyDarkendTex;
            if (!this.vc.isEmpty()) {//only render if there... is shit to render
                for (int i = 0; i < VIEWS.length; i++) {
                    this.rasterizer.setFaceCull(i==1||i==2||i==4);

                    this.rasterizer.raster(VIEWS[i], this.vc);
                    UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(), outputBuffer+(SINGLE_FACE_OUTPUT_SIZE*i));
                }
            }
        } else {//Is fluid, slow path :(

            if (!(state.getBlock() instanceof FluidBlock)) throw new IllegalStateException();
            for (int i = 0; i < VIEWS.length; i++) {
                this.vc.reset();
                this.bakeFluidState(state, layer, i);
                if (this.vc.isEmpty()) continue;
                isAnyShaded |= this.vc.anyShaded;
                isAnyDarkend |= this.vc.anyDarkendTex;

                this.rasterizer.setFaceCull(i==1||i==2||i==4);

                //The projection matrix
                this.rasterizer.raster(VIEWS[i], this.vc);
                UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(), outputBuffer+(SINGLE_FACE_OUTPUT_SIZE*i));
            }
        }


        return (isAnyShaded?1:0)|(isAnyDarkend?2:0);
    }




    static {
        //the face/direction is the face (e.g. down is the down face)
        addView(0, -90,0, 0, 0);//Direction.DOWN
        addView(1, 90,0, 0, 0b100);//Direction.UP

        addView(2, 0,180, 0, 0b001);//Direction.NORTH
        addView(3, 0,0, 0, 0);//Direction.SOUTH

        addView(4, 0,90, 270, 0b100);//Direction.WEST
        addView(5, 0,270, 270, 0);//Direction.EAST
    }

    private static void addView(int i, float pitch, float yaw, float rotation, int flip) {
        // In 1.20.1 Yarn: MatrixStack uses multiply() instead of mulPose(),
        // and peek().getPositionMatrix() instead of last().pose()
        var stack = new net.minecraft.client.util.math.MatrixStack();
        stack.translate(0.5f,0.5f,0.5f);
        stack.multiply(makeQuatFromAxisExact(new Vector3f(0,0,1), rotation));
        stack.multiply(makeQuatFromAxisExact(new Vector3f(1,0,0), pitch));
        stack.multiply(makeQuatFromAxisExact(new Vector3f(0,1,0), yaw));
        // Apply scale via direct matrix multiplication
        var scaleMat = new Matrix4f().scale(1-2*(flip&1), 1-(flip&2), 1-((flip>>1)&2));
        stack.multiplyPositionMatrix(scaleMat);
        stack.translate(-0.5f,-0.5f,-0.5f);
        var mat = new Matrix4f(stack.peek().getPositionMatrix());

        mat = new Matrix4f().set(
                        2,0,0,0,
                        0,2,0,0,
                        0,0,-2,0,
                        -1,-1,1,1)
                .mul(mat);
        VIEWS[i] = mat;
    }

    private static Quaternionf makeQuatFromAxisExact(Vector3f vec, float angle) {
        angle = (float) Math.toRadians(angle);
        float hangle = angle / 2.0f;
        float sinAngle = (float) Math.sin(hangle);
        float invVLength = (float) (1/Math.sqrt(vec.lengthSquared()));
        return new Quaternionf(vec.x * invVLength * sinAngle,
                vec.y * invVLength * sinAngle,
                vec.z * invVLength * sinAngle,
                Math.cos(hangle));
    }
}
