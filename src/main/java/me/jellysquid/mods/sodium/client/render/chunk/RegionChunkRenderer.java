package me.jellysquid.mods.sodium.client.render.chunk;

import com.google.common.collect.Lists;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexAttributeBinding;
import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexFormat;
import me.jellysquid.mods.sodium.client.gl.buffer.GlBufferUsage;
import me.jellysquid.mods.sodium.client.gl.buffer.GlMutableBuffer;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.device.DrawCommandList;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.gl.tessellation.GlIndexType;
import me.jellysquid.mods.sodium.client.gl.tessellation.GlPrimitiveType;
import me.jellysquid.mods.sodium.client.gl.tessellation.GlTessellation;
import me.jellysquid.mods.sodium.client.gl.tessellation.TessellationBinding;
import me.jellysquid.mods.sodium.client.gl.util.ElementRange;
import me.jellysquid.mods.sodium.client.gl.util.MultiDrawBatch;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import me.jellysquid.mods.sodium.client.model.vertex.type.ChunkVertexType;
import me.jellysquid.mods.sodium.client.render.chunk.data.ChunkRenderBounds;
import me.jellysquid.mods.sodium.client.render.chunk.format.ChunkMeshAttribute;
import me.jellysquid.mods.sodium.client.render.chunk.format.ChunkModelVertexFormats;
import me.jellysquid.mods.sodium.client.render.chunk.format.VanillaLikeChunkMeshAttribute;
import me.jellysquid.mods.sodium.client.render.chunk.passes.BlockRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderBindingPoints;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ComputeShaderInterface;
import org.lwjgl.system.MemoryUtil;
import repack.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RegionChunkRenderer extends ShaderChunkRenderer {
    private static final ByteBuffer DRAW_INFO_BUFFER = createChunkInfoBuffer();

    private final MultiDrawBatch[] batches;
    private final GlVertexAttributeBinding[] vertexAttributeBindings;

    private final GlMutableBuffer chunkInfoBuffer;
    private final boolean isBlockFaceCullingEnabled = SodiumClientMod.options().performance.useBlockFaceCulling;

    private double lastComputeUpdateX = 0;
    private double lastComputeUpdateY = 0;
    private double lastComputeUpdateZ = 0;

    private GlVertexAttributeBinding[] getBindingsForType() {
        if(this.vertexType != ChunkModelVertexFormats.VANILLA_LIKE) {
            GlVertexFormat<ChunkMeshAttribute> compactFormat = (GlVertexFormat<ChunkMeshAttribute>)this.vertexFormat;
            return new GlVertexAttributeBinding[] {
                    new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_POSITION_ID,
                            compactFormat.getAttribute(ChunkMeshAttribute.POSITION_ID)),
                    new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_COLOR,
                            compactFormat.getAttribute(ChunkMeshAttribute.COLOR)),
                    new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_BLOCK_TEXTURE,
                            compactFormat.getAttribute(ChunkMeshAttribute.BLOCK_TEXTURE)),
                    new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_LIGHT_TEXTURE,
                            compactFormat.getAttribute(ChunkMeshAttribute.LIGHT_TEXTURE))
            };
        } else {
            GlVertexFormat<VanillaLikeChunkMeshAttribute> vanillaFormat = (GlVertexFormat<VanillaLikeChunkMeshAttribute>)this.vertexFormat;
            return new GlVertexAttributeBinding[] {
                    new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_POSITION_ID,
                            vanillaFormat.getAttribute(VanillaLikeChunkMeshAttribute.POSITION)),
                    new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_COLOR,
                            vanillaFormat.getAttribute(VanillaLikeChunkMeshAttribute.COLOR)),
                    new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_BLOCK_TEXTURE,
                            vanillaFormat.getAttribute(VanillaLikeChunkMeshAttribute.BLOCK_TEX_ID)),
                    new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_LIGHT_TEXTURE,
                            vanillaFormat.getAttribute(VanillaLikeChunkMeshAttribute.LIGHT)),
            };
        }
    }

    public RegionChunkRenderer(RenderDevice device, ChunkVertexType<?> vertexType) {
        super(device, vertexType);

        this.vertexAttributeBindings = getBindingsForType();

        try (CommandList commandList = device.createCommandList()) {
            this.chunkInfoBuffer = commandList.createMutableBuffer();
            commandList.uploadData(this.chunkInfoBuffer, DRAW_INFO_BUFFER, GlBufferUsage.STATIC_DRAW);
        }

        this.batches = new MultiDrawBatch[GlIndexType.VALUES.length];

        for (int i = 0; i < this.batches.length; i++) {
            this.batches[i] = MultiDrawBatch.create(ModelQuadFacing.COUNT * RenderRegion.REGION_SIZE);
        }
    }

    @Override
    public void render(ChunkRenderMatrices matrices, CommandList commandList,
                       ChunkRenderList list, BlockRenderPass pass,
                       ChunkCameraContext camera) {
        if(pass.isTranslucent() && SodiumClientMod.options().performance.useTranslucentFaceSorting) {
            computeTranslucency(matrices, commandList, list, pass, camera);
        }
        super.begin(pass);

        ChunkShaderInterface shader = this.activeProgram.getInterface();
        shader.setProjectionMatrix(matrices.projection());
        shader.setModelViewMatrix(matrices.modelView());

        shader.setDrawUniforms(this.chunkInfoBuffer);

        for (Map.Entry<RenderRegion, List<RenderSection>> entry : sortedRegions(list, pass.isTranslucent())) {
            RenderRegion region = entry.getKey();
            List<RenderSection> regionSections = entry.getValue();

            if (!this.buildDrawBatches(regionSections, pass, camera)) {
                continue;
            }

            this.setModelMatrixUniforms(shader, region, camera);
            this.executeDrawBatches(commandList, this.createTessellationForRegion(commandList, region.getArenas(), pass));
        }
        
        super.end();
    }

    private final Matrix4f cachedModelViewMatrix = new Matrix4f();
    private final List<RenderSection> sectionsToCompute = new ArrayList<>();

    private void computeTranslucency(ChunkRenderMatrices matrices, CommandList commandList,
                                     ChunkRenderList list, BlockRenderPass pass,
                                     ChunkCameraContext camera) {
        if(this.batches.length > 1)
            throw new UnsupportedOperationException("Multiple multidraw batches found");

        super.beginCompute(pass);

        boolean fullRebuild = false;
        if (activeComputeProgram != null) {
            ComputeShaderInterface compute = activeComputeProgram.getInterface();

            double cameraX = camera.blockX + camera.deltaX;
            double cameraY = camera.blockY + camera.deltaY;
            double cameraZ = camera.blockZ + camera.deltaZ;

            //If we have moved set all chunks as needing compute
            double dx = cameraX - lastComputeUpdateX;
            double dy = cameraY - lastComputeUpdateY;
            double dz = cameraZ - lastComputeUpdateZ;
            if(dx * dx + dy * dy + dz * dz > 1.0D) {
                lastComputeUpdateX = cameraX;
                lastComputeUpdateY = cameraY;
                lastComputeUpdateZ = cameraZ;
                fullRebuild = true;
            }

            compute.setDrawUniforms(this.chunkInfoBuffer);

            boolean runCompute = true;
            int regionsComputed = 0;

            //We want compute to run beginning with the closest chunks
            for (Map.Entry<RenderRegion, List<RenderSection>> entry : sortedRegions(list, false)) {
                RenderRegion region = entry.getKey();
                List<RenderSection> regionSections = entry.getValue();

                if(fullRebuild) {
                    region.setNeedsTranslucencyCompute(true);
                    if(!runCompute) {
                        continue;
                    }
                }

                if (region.getNeedsTranslucencyCompute() && !regionSections.isEmpty()) {
                    sectionsToCompute.clear();

                    for(RenderSection section : regionSections) {
                        if(section.getGraphicsState(pass) != null
                                && section.getSquaredDistance(camera.blockX, camera.blockY, camera.blockZ) < 96*96) {
                            sectionsToCompute.add(section);
                        }
                    }

                    if (sectionsToCompute.isEmpty() || !buildDrawBatches(sectionsToCompute, pass, camera)) {
                        continue;
                    }
                    float x = getCameraTranslation(region.getOriginX(), camera.blockX, camera.deltaX);
                    float y = getCameraTranslation(region.getOriginY(), camera.blockY, camera.deltaY);
                    float z = getCameraTranslation(region.getOriginZ(), camera.blockZ, camera.deltaZ);

                    Matrix4f matrix = this.cachedModelViewMatrix;
                    matrix.set(matrices.modelView());
                    matrix.translate(x, y, z);

                    compute.setModelViewMatrix(matrix);

                    RenderRegion.RenderRegionArenas arenas = region.getArenas();
                    runCompute = compute.execute(commandList, batches[0], arenas);
                    region.setNeedsTranslucencyCompute(false);
                    if(runCompute) {
                        regionsComputed += sectionsToCompute.size();
                        if(regionsComputed >= 15)
                            runCompute = false; // do not continue sorting for the rest of the frame
                    }
                }
                if(!runCompute && !fullRebuild) {
                    break;
                }
            }
        }
        super.endCompute();
    }

    private boolean buildDrawBatches(List<RenderSection> sections, BlockRenderPass pass, ChunkCameraContext camera) {
        for (MultiDrawBatch batch : this.batches) {
            batch.begin();
        }

        for (RenderSection render : sortedChunks(sections, pass.isTranslucent())) {
            ChunkGraphicsState state = render.getGraphicsState(pass);

            if (state == null) {
                continue;
            }

            ChunkRenderBounds bounds = render.getBounds();

            long indexOffset = state.getIndexSegment()
                    .getOffset();

            int baseVertex = state.getVertexSegment()
                    .getOffset() / this.vertexFormat.getStride();

            this.addDrawCall(state.getModelPart(ModelQuadFacing.UNASSIGNED), indexOffset, baseVertex);

            if (this.isBlockFaceCullingEnabled && !(pass.isTranslucent() && SodiumClientMod.options().performance.useTranslucentFaceSorting)) {
                if (camera.posY > bounds.y1) {
                    this.addDrawCall(state.getModelPart(ModelQuadFacing.UP), indexOffset, baseVertex);
                }

                if (camera.posY < bounds.y2) {
                    this.addDrawCall(state.getModelPart(ModelQuadFacing.DOWN), indexOffset, baseVertex);
                }

                if (camera.posX > bounds.x1) {
                    this.addDrawCall(state.getModelPart(ModelQuadFacing.EAST), indexOffset, baseVertex);
                }

                if (camera.posX < bounds.x2) {
                    this.addDrawCall(state.getModelPart(ModelQuadFacing.WEST), indexOffset, baseVertex);
                }

                if (camera.posZ > bounds.z1) {
                    this.addDrawCall(state.getModelPart(ModelQuadFacing.SOUTH), indexOffset, baseVertex);
                }

                if (camera.posZ < bounds.z2) {
                    this.addDrawCall(state.getModelPart(ModelQuadFacing.NORTH), indexOffset, baseVertex);
                }
            } else {
                for (ModelQuadFacing facing : ModelQuadFacing.DIRECTIONS) {
                    this.addDrawCall(state.getModelPart(facing), indexOffset, baseVertex);
                }
            }
        }

        boolean nonEmpty = false;

        for (MultiDrawBatch batch : this.batches) {
            batch.end();

            nonEmpty |= !batch.isEmpty();
        }

        return nonEmpty;
    }

    private GlTessellation createTessellationForRegion(CommandList commandList, RenderRegion.RenderRegionArenas arenas, BlockRenderPass pass) {
        GlTessellation tessellation = arenas.getTessellation(pass);

        if (tessellation == null) {
            arenas.setTessellation(pass, tessellation = this.createRegionTessellation(commandList, arenas));
        }

        return tessellation;
    }

    private void executeDrawBatches(CommandList commandList, GlTessellation tessellation) {
        for (int i = 0; i < this.batches.length; i++) {
            MultiDrawBatch batch = this.batches[i];

            if (!batch.isEmpty()) {
                try (DrawCommandList drawCommandList = commandList.beginTessellating(tessellation)) {
                    drawCommandList.multiDrawElementsBaseVertex(batch.getPointerBuffer(), batch.getCountBuffer(), batch.getBaseVertexBuffer(), GlIndexType.VALUES[i]);
                }
            }
        }
    }

    private void setModelMatrixUniforms(ChunkShaderInterface shader, RenderRegion region, ChunkCameraContext camera) {
        float x = getCameraTranslation(region.getOriginX(), camera.blockX, camera.deltaX);
        float y = getCameraTranslation(region.getOriginY(), camera.blockY, camera.deltaY);
        float z = getCameraTranslation(region.getOriginZ(), camera.blockZ, camera.deltaZ);

        shader.setRegionOffset(x, y, z);
    }

    private void addDrawCall(ElementRange part, long baseIndexPointer, int baseVertexIndex) {
        if (part != null) {
            MultiDrawBatch batch = this.batches[part.indexType().ordinal()];
            batch.add(baseIndexPointer + part.elementPointer(), part.elementCount(), baseVertexIndex + part.baseVertex());
        }
    }

    private GlTessellation createRegionTessellation(CommandList commandList, RenderRegion.RenderRegionArenas arenas) {
        return commandList.createTessellation(GlPrimitiveType.TRIANGLES, new TessellationBinding[] {
                TessellationBinding.forVertexBuffer(arenas.vertexBuffers.getBufferObject(), this.vertexAttributeBindings),
                TessellationBinding.forElementBuffer(arenas.indexBuffers.getBufferObject())
        });
    }

    @Override
    public void delete() {
        super.delete();

        for (MultiDrawBatch batch : this.batches) {
            batch.delete();
        }

        RenderDevice.INSTANCE.createCommandList()
                .deleteBuffer(this.chunkInfoBuffer);
    }

    private static Iterable<Map.Entry<RenderRegion, List<RenderSection>>> sortedRegions(ChunkRenderList list, boolean translucent) {
        return list.sorted(translucent);
    }

    private static Iterable<RenderSection> sortedChunks(List<RenderSection> chunks, boolean translucent) {
        return translucent ? Lists.reverse(chunks) : chunks;
    }

    private static float getCameraTranslation(int chunkBlockPos, int cameraBlockPos, float cameraPos) {
        return (chunkBlockPos - cameraBlockPos) - cameraPos;
    }

    private static ByteBuffer createChunkInfoBuffer() {
        int stride = 4 * 4;

        ByteBuffer data = MemoryUtil.memAlloc(RenderRegion.REGION_SIZE * stride);

        for (int x = 0; x < RenderRegion.REGION_WIDTH; x++) {
            for (int y = 0; y < RenderRegion.REGION_HEIGHT; y++) {
                for (int z = 0; z < RenderRegion.REGION_LENGTH; z++) {
                    int i = RenderRegion.getChunkIndex(x, y, z) * stride;

                    data.putFloat(i + 0, x * 16.0f);
                    data.putFloat(i + 4, y * 16.0f);
                    data.putFloat(i + 8, z * 16.0f);
                }
            }
        }

        return data;
    }
}
