package net.vulkanmod.render.chunk;

import it.unimi.dsi.fastutil.longs.Long2ObjectArrayMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.vulkanmod.render.chunk.util.StaticQueue;
import net.vulkanmod.vulkan.*;
import net.vulkanmod.vulkan.memory.StagingBuffer;
import net.vulkanmod.vulkan.queue.CommandPool;

import net.vulkanmod.vulkan.queue.QueueFamilyIndices;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

import org.lwjgl.vulkan.VkBufferCopy;

import static net.vulkanmod.vulkan.queue.Queue.TransferQueue;

public class AreaUploadManager {
    public static final int FRAME_NUM = 2;
    public static AreaUploadManager INSTANCE;

    public static void createInstance() {
        INSTANCE = new AreaUploadManager();
    }

    boolean hasBufferSwap = false;

    ObjectArrayList<AreaBuffer.Segment>[] recordedUploads;
    CommandPool.CommandBuffer[] commandBuffers;

    LongOpenHashSet dstBuffers = new LongOpenHashSet();

    Long2ObjectArrayMap<StaticQueue<SubCopyCommand>> subCopyCommands = new Long2ObjectArrayMap<>();

    int currentFrame;

    public void init() {
        this.commandBuffers = new CommandPool.CommandBuffer[FRAME_NUM];
        this.recordedUploads = new ObjectArrayList[FRAME_NUM];

        for (int i = 0; i < FRAME_NUM; i++) {
            this.recordedUploads[i] = new ObjectArrayList<>();
        }
    }

    public boolean swapBuffers(long srcBuffer, long dstBuffer)
    {        hasBufferSwap=true;
        if(!this.subCopyCommands.containsKey(srcBuffer)) return false;
        StaticQueue<SubCopyCommand> remove = this.subCopyCommands.remove(srcBuffer);
        this.subCopyCommands.put(dstBuffer, remove);
        this.dstBuffers.remove(srcBuffer);
        this.dstBuffers.add(dstBuffer);

        return true;
    }
    public synchronized void submitUploads(boolean b) {
        if (subCopyCommands.isEmpty()) {
            return;
        }
        if(commandBuffers[currentFrame] == null)
            this.commandBuffers[currentFrame] = TransferQueue.beginCommands();

        try (MemoryStack stack = MemoryStack.stackPush()) {

            long stagingBufferId = Vulkan.getStagingBuffer().getId();
            for (long bufferHandle : dstBuffers) {
                StaticQueue<SubCopyCommand> subCopyCommands1 = subCopyCommands.get(bufferHandle);

                int size = subCopyCommands1.size();
                VkBufferCopy.Buffer vkBufferCopies = VkBufferCopy.malloc(size, stack);

                for (var a : vkBufferCopies) {
                    SubCopyCommand subCopyCommand = subCopyCommands1.poll();
                    a.set(subCopyCommand.srcOffset(), subCopyCommand.dstOffset(), subCopyCommand.bufferSize());
                }

                TransferQueue.uploadBufferCmds(this.commandBuffers[currentFrame], stagingBufferId, bufferHandle, vkBufferCopies);
            }


            TransferQueue.GigaBarrier2(this.commandBuffers[currentFrame].getHandle(), stack, this.hasBufferSwap);

            this.hasBufferSwap=false;
        }
        dstBuffers.clear();
        subCopyCommands.clear();
        TransferQueue.submitCommands(this.commandBuffers[currentFrame]);

    }

    public void uploadAsync(AreaBuffer.Segment uploadSegment, long bufferId, int dstOffset, int bufferSize, ByteBuffer src) {


        StagingBuffer stagingBuffer = Vulkan.getStagingBuffer();
        stagingBuffer.copyBuffer(bufferSize, src);

        dstBuffers.add(bufferId);
        if(!subCopyCommands.containsKey(bufferId))
        {
            subCopyCommands.put(bufferId, new StaticQueue<>(512));
        }
        subCopyCommands.get(bufferId).add(new SubCopyCommand(stagingBuffer.getOffset(), dstOffset, bufferSize));


        this.recordedUploads[this.currentFrame].add(uploadSegment);
    }

    public void updateFrame() {
        this.currentFrame = (this.currentFrame + 1) % FRAME_NUM;
        waitUploads(this.currentFrame);

        this.dstBuffers.clear();
    }

    public void waitUploads() {
        this.waitUploads(this.currentFrame);
    }
    private void waitUploads(int frame) {
        CommandPool.CommandBuffer commandBuffer = commandBuffers[frame];
        if(commandBuffer == null)
            return;
        Synchronization.waitFence(commandBuffers[frame].getFence());

        for(AreaBuffer.Segment uploadSegment : this.recordedUploads[frame]) {
            uploadSegment.setReady();
        }

        this.commandBuffers[frame].reset();
        this.commandBuffers[frame] = null;
        this.recordedUploads[frame].clear();
    }

    public synchronized void waitAllUploads() {
        for(int i = 0; i < this.commandBuffers.length; ++i) {
            waitUploads(i);
        }
    }

    public void copyBuffer(long srcBuffer, long dstBuffer, int bufferSize) {
        if(commandBuffers[currentFrame] == null)
            this.commandBuffers[currentFrame] = TransferQueue.beginCommands();
        TransferQueue.uploadBufferCmd(this.commandBuffers[currentFrame], srcBuffer, 0, dstBuffer, 0, bufferSize);
    }
}
