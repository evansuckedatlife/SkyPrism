package com.skyprism.core.loot.combat;

import java.util.Optional;

/**
 * "Is a slayer quest running, and which one" -- the fact the slayer detectors gate on and caption
 * with, kept out of Minecraft so the detectors stay unit-testable on a bare JVM.
 *
 * <p>The cheapest correct gate for a slayer boss is not an island test. It is "the sidebar currently
 * has a Slayer Quest section", which is strictly tighter: it is shut for every player who is not
 * mid-quest, on <em>every</em> island, including the ones where slayer bosses do spawn. The sidebar
 * is re-read on change rather than per tick, so evaluating it costs nothing.
 *
 * <h2>The tri-state, which is the whole point of this class</h2>
 * <p>A gate keyed on this would be a hazard if it defaulted shut, because
 * {@link com.skyprism.core.loot.LootEventBus} recomputes gates only when the {@code GameContext}
 * changes -- and a quest starting is not a context change. A slayer detector shut at registration
 * time, on a bus that is never told to look again, is a feature that silently never fires. That is
 * the exact failure this design exists to avoid, and it would be self-inflicted.
 *
 * <p>So the state is three-valued, not two. Until something actually reports quest state the answer
 * to {@link #mayBeActive()} is <b>yes</b>: an unwired install pays two {@code String.indexOf} calls
 * per chat line for markers that are among the most distinctive strings in the game, and everything
 * works. Once a sidebar reader starts reporting, {@link #mayBeActive()} becomes exact and the
 * detector really is unregistered from the filter outside a quest. The failure mode of forgetting to
 * wire this is "very slightly less free", never "silently broken", and that asymmetry is deliberate.
 *
 * <p>{@link #onChange(Runnable)} lets the owner re-run the bus's gate computation -- by
 * unregistering and re-registering the detector -- when a quest starts or ends, which is what turns
 * the tighter gate on. Not wiring it is safe for the same reason.
 *
 * <p><b>Chat outranks this.</b> {@link SlayerBossDetector} trusts a "SLAYER BOSS SLAIN!" line even
 * when this says no quest is running, because a sidebar read can lag a kill by a frame and a missed
 * roll is worse than a spurious caption. This supplies the caption and the gate hint, never a veto.
 *
 * <p><b>Threading:</b> not thread safe, like everything else on this path. It is written by whatever
 * polls the sidebar and read by the chat callback, which are the same client thread.
 */
public final class SlayerQuestState {

    private boolean everReported;
    private SlayerQuest quest;
    private Runnable onChange = () -> {
    };

    /** The quest currently running, or empty when none is (or none has been reported). */
    public Optional<SlayerQuest> quest() {
        return Optional.ofNullable(quest);
    }

    /** Whether a quest is definitely running right now. */
    public boolean active() {
        return quest != null;
    }

    /** Whether anything has ever reported quest state; false means "assume a quest may be running". */
    public boolean reported() {
        return everReported;
    }

    /**
     * Whether a slayer detector should stay armed.
     *
     * <p>True while a quest is running, and true while nothing has ever reported -- see the class
     * notes for why the unknown case must resolve open rather than shut.
     */
    public boolean mayBeActive() {
        return !everReported || quest != null;
    }

    /**
     * Reports the sidebar's Slayer Quest section, e.g. "Voidgloom Seraph IV".
     *
     * <p>A blank or unparseable line is treated as {@link #questEnded()}, because that is what the
     * sidebar losing its Slayer Quest section looks like.
     */
    public void questStarted(String sidebarLine) {
        SlayerQuest parsed = SlayerQuest.parse(sidebarLine).orElse(null);
        if (parsed == null) {
            questEnded();
            return;
        }
        boolean changed = !everReported || quest == null || !quest.equals(parsed);
        everReported = true;
        quest = parsed;
        if (changed) {
            onChange.run();
        }
    }

    /** Reports that the sidebar no longer has a Slayer Quest section. */
    public void questEnded() {
        boolean changed = !everReported || quest != null;
        everReported = true;
        quest = null;
        if (changed) {
            onChange.run();
        }
    }

    /** Returns to the unreported state, e.g. on disconnect. Gates reopen; see the class notes. */
    public void forget() {
        boolean changed = everReported || quest != null;
        everReported = false;
        quest = null;
        if (changed) {
            onChange.run();
        }
    }

    /**
     * Sets the callback fired when the quest starts, changes or ends.
     *
     * <p>The intended body is "unregister and re-register the slayer detectors on the bus", which is
     * what makes the bus recompute its open set and its marker union. Never called from a chat line.
     */
    public void onChange(Runnable listener) {
        this.onChange = listener == null ? () -> {
        } : listener;
    }

    /** The caption for the running quest, or {@code fallback} when none is known. */
    public String captionOr(String fallback) {
        SlayerQuest current = quest;
        return current == null || current.caption().isEmpty() ? fallback : current.caption();
    }
}
