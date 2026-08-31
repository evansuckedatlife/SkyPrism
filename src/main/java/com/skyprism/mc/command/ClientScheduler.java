package com.skyprism.mc.command;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.ArrayList;
import java.util.List;

/**
 * A minimal "run this in N ticks" queue on the client thread.
 *
 * <p><b>Why not a {@code ScheduledExecutorService}.</b> The performance rules forbid
 * threads and executors, and they are right to: everything this schedules - replaying a
 * chat line, staging a simulated Diana kill - touches Minecraft state that is only safe to
 * touch on the client thread, so a background timer would need to hand the work back
 * anyway. The client tick is already running at a known 20 Hz and is exactly the right
 * clock for "space these chat lines out like a real server would".</p>
 *
 * <p><b>Cost.</b> One {@code isEmpty()} check per tick when nothing is queued, which is the
 * overwhelmingly common case: no allocation, no iterator, no clock read. When tasks do
 * exist the loop is a single pass over a short list. The queue is also where
 * {@link Metrics#tick()} gets called from, which is how the profiler's per-second rates
 * stay off the render and chat paths entirely.</p>
 *
 * <p>Single-threaded by construction - every method must be called from the client thread,
 * and every task runs there.</p>
 */
public final class ClientScheduler {

    private ClientScheduler() {
    }

    /**
     * A queued task.
     *
     * @param dueTick the tick counter value at which it should run
     * @param group   a cancellation token, so a second {@code /skyprism replay} can drop the
     *                first one's remaining lines instead of interleaving with them
     * @param action  the work
     */
    private record Task(long dueTick, String group, Runnable action) {
    }

    private static final List<Task> TASKS = new ArrayList<>();

    private static long tickCounter;
    private static boolean installed;

    /**
     * Installs the tick listener. Idempotent, so calling it from more than one place cannot
     * double-run the queue.
     */
    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    /**
     * @return the number of client ticks seen since installation, the unit
     *         {@link #schedule} counts in
     */
    public static long ticks() {
        return tickCounter;
    }

    /**
     * Queues work for later.
     *
     * @param delayTicks how many ticks to wait; anything below 1 runs on the next tick
     * @param group      a cancellation token; see {@link #cancel(String)}
     * @param action     the work, run on the client thread
     */
    public static void schedule(long delayTicks, String group, Runnable action) {
        TASKS.add(new Task(tickCounter + Math.max(1L, delayTicks), group, action));
    }

    /**
     * Drops every pending task in a group.
     *
     * @param group the cancellation token
     * @return how many tasks were dropped, so the caller can tell the player what it
     *         interrupted
     */
    public static int cancel(String group) {
        int before = TASKS.size();
        TASKS.removeIf(task -> task.group().equals(group));
        return before - TASKS.size();
    }

    /**
     * @param group the cancellation token
     * @return how many tasks in that group are still waiting
     */
    public static int pending(String group) {
        int count = 0;
        for (Task task : TASKS) {
            if (task.group().equals(group)) {
                count++;
            }
        }
        return count;
    }

    private static void tick() {
        tickCounter++;
        Metrics.tick();

        if (TASKS.isEmpty()) {
            return;
        }

        // Collected first, then run, because a task is allowed to schedule another one and
        // mutating the list mid-iteration would be a ConcurrentModificationException on the
        // client thread - which is to say, a crash in the middle of somebody's game.
        List<Task> due = null;
        for (int i = TASKS.size() - 1; i >= 0; i--) {
            Task task = TASKS.get(i);
            if (task.dueTick() <= tickCounter) {
                if (due == null) {
                    due = new ArrayList<>(4);
                }
                due.add(task);
                TASKS.remove(i);
            }
        }
        if (due == null) {
            return;
        }
        // Reversed above by the descending scan; restore submission order so a replay's
        // lines cannot arrive out of sequence when several fall due on the same tick.
        for (int i = due.size() - 1; i >= 0; i--) {
            due.get(i).action().run();
        }
    }
}
