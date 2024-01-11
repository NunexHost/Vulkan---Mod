import net.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCString.nmemcpy;
import org.lwjgl.vulkan.VK10.*;

import java.nio.ByteBuffer;

public class StagingBuffer extends Buffer {

    public StagingBuffer(int bufferSize) {
        super(VK_BUFFER_USAGE_TRANSFER_SRC_BIT, MemoryTypes.HOST_MEM);
        this.usedBytes = 0;
        this.offset = 0;

        this.createBuffer(bufferSize);
    }

    public void copyBuffer(int size, ByteBuffer byteBuffer) {
        if(size > this.bufferSize - this.usedBytes) {
            resizeBuffer((this.bufferSize + size) * 2);
        }

        for (int i = 0; i < size; i++) {
            nmemcpy(this.data.get(0) + this.usedBytes + i * MemoryUtil.SIZE_OF_INT, MemoryUtil.memAddress(byteBuffer).add(i * MemoryUtil.SIZE_OF_INT), MemoryUtil.SIZE_OF_INT);
        }

        offset = usedBytes;
        usedBytes += size;
    }

    public void align(int alignment) {
        int alignedValue = Util.align(usedBytes, alignment);

        if(alignedValue > this.bufferSize) {
            resizeBuffer((this.bufferSize) * 2);
        }

        usedBytes = alignedValue;
    }

    private void resizeBuffer(int newSize) {
        MemoryManager.getInstance().addToFreeable(this);
        this.createBuffer(newSize);

        System.out.println("resized staging buffer to: " + newSize);
    }
}
