package org.embeddedt.embeddium.impl.gl.arena.staging;

import org.embeddedt.embeddium.impl.gl.buffer.GlBuffer;
import org.embeddedt.embeddium.impl.gl.buffer.GlBufferUsage;
import org.embeddedt.embeddium.impl.gl.buffer.GlMutableBuffer;
import org.embeddedt.embeddium.impl.gl.device.CommandList;

import java.nio.ByteBuffer;

public class FallbackStagingBuffer implements StagingBuffer {
    private final GlMutableBuffer fallbackBufferObject;

    public FallbackStagingBuffer(CommandList commandList) {
        this.fallbackBufferObject = commandList.createMutableBuffer();
    }

    @Override
    public void enqueueCopy(CommandList commandList, ByteBuffer data, GlBuffer dst, long writeOffset) {
        commandList.uploadData(this.fallbackBufferObject, data, GlBufferUsage.STREAM_COPY);
        commandList.copyBufferSubData(this.fallbackBufferObject, dst, 0, writeOffset, data.remaining());
    }

    @Override
    public void flush(CommandList commandList) {
        commandList.allocateStorage(this.fallbackBufferObject, 0L, GlBufferUsage.STREAM_COPY);
    }

    @Override
    public void delete(CommandList commandList) {
        commandList.deleteBuffer(this.fallbackBufferObject);
    }

    @Override
    public void flip() {

    }

    @Override
    public String toString() {
        return "Fallback";
    }
}
