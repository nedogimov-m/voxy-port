package me.cortex.voxy.common.world.service;

import me.cortex.voxy.common.storage.StorageCompressor;
import me.cortex.voxy.common.world.SaveLoadSystem;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.lwjgl.system.MemoryUtil;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;

//TODO: add an option for having synced saving, that is when call enqueueSave, that will instead, instantly
// save to the db, this can be useful for just reducing the amount of thread pools in total
// might have some issues with threading if the same section is saved from multiple threads?
public class SectionSavingService {
    private volatile boolean running = true;
    private final Thread[] workers;

    private final ConcurrentLinkedDeque<WorldSection> saveQueue = new ConcurrentLinkedDeque<>();
    private final Semaphore saveCounter = new Semaphore(0);

    private final WorldEngine world;


    public SectionSavingService(WorldEngine worldEngine, int workers) {
        this.workers = new Thread[workers];
        for (int i = 0; i < workers; i++) {
            var worker = new Thread(this::saveWorker);
            worker.setDaemon(false);
            worker.setName("Saving service #" + i);
            worker.start();
            this.workers[i] = worker;
        }
        this.world = worldEngine;
    }

    private void saveWorker() {
        while (running) {
            this.saveCounter.acquireUninterruptibly();
            if (!this.running) break;
            var section = this.saveQueue.pop();
            section.assertNotFree();
            java.nio.ByteBuffer saveData = null;
            try {
                section.inSaveQueue.set(false);
                saveData = SaveLoadSystem.serialize(section);
                this.world.storage.setSectionData(section.key, saveData);
            } catch (Exception e) {
                System.err.println("[Voxy] Saver exception: " + e.getMessage());
                e.printStackTrace();
                try {
                    MinecraftClient.getInstance().executeSync(()->{
                        var player = MinecraftClient.getInstance().player;
                        if (player != null) {
                            player.sendMessage(Text.literal("Voxy saver had an exception, check logs"));
                        }
                    });
                } catch (Exception ignored) {}
            } finally {
                if (saveData != null) MemoryUtil.memFree(saveData);
            }
            section.release();
        }
    }

    public void enqueueSave(WorldSection section) {
        //If its not enqueued for saving then enqueue it
        if (!section.inSaveQueue.getAndSet(true)) {
            //Acquire the section for use
            section.acquire();
            this.saveQueue.add(section);
            this.saveCounter.release();
        }
    }

    public void shutdown() {
        boolean anyAlive = false;
        boolean allAlive = true;
        for (var worker : this.workers) {
            anyAlive |= worker.isAlive();
            allAlive &= worker.isAlive();
        }

        if (!anyAlive) {
            System.err.println("Section saving workers already dead on shutdown! this is very very bad, check log for errors from this thread");
            return;
        }
        if (!allAlive) {
            System.err.println("Some section saving works have died, please check log and report errors.");
        }


        int i = 0;
        //Wait for all the saving to finish (with timeout)
        while (!this.saveQueue.isEmpty() || this.saveCounter.availablePermits() != 0) {
            try {Thread.sleep(500);} catch (InterruptedException e) {break;}
            if (i++%20 == 0) {
                System.out.println("[Voxy] Saving shutdown: " + this.saveQueue.size() + " queued, " + this.saveCounter.availablePermits() + " permits");
            }
            if (i > 60) { // 30 second timeout
                System.err.println("[Voxy] Saving shutdown timeout, forcing stop");
                break;
            }
        }
        //Shutdown
        this.running = false;
        this.saveCounter.release(1000);
        //Wait for threads to join
        try {
            for (var worker : this.workers) {
                worker.join();
            }
        } catch (InterruptedException e) {throw new RuntimeException(e);}
    }

    public int getTaskCount() {
        return this.saveCounter.availablePermits();
    }
}
