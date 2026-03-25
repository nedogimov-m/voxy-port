package me.cortex.voxy.client;

import me.cortex.voxy.common.world.WorldEngine;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class RenderStatistics {
    public static boolean enabled = true;

    public static final int[] hierarchicalTraversalCounts = new int[WorldEngine.MAX_LOD_LAYER+1];
    public static final int[] hierarchicalRenderSections = new int[WorldEngine.MAX_LOD_LAYER+1];
    public static final int[] visibleSections = new int[WorldEngine.MAX_LOD_LAYER+1];
    public static final int[] quadCount = new int[WorldEngine.MAX_LOD_LAYER+1];


    private static int _logCounter = 0;
    public static void addDebug(List<String> debug) {
        if (!enabled) {
            return;
        }
        if (++_logCounter % 300 == 1) {
            System.out.println("[Voxy STATS] HTC=" + java.util.Arrays.toString(flipCopy(hierarchicalTraversalCounts))
                + " VS=" + java.util.Arrays.toString(flipCopy(visibleSections))
                + " QC=" + java.util.Arrays.toString(flipCopy(quadCount)));
        }
        debug.add("HTC: [" + Arrays.stream(flipCopy(RenderStatistics.hierarchicalTraversalCounts)).mapToObj(Integer::toString).collect(Collectors.joining(", "))+"]");
        debug.add("HRS: [" + Arrays.stream(flipCopy(RenderStatistics.hierarchicalRenderSections)).mapToObj(Integer::toString).collect(Collectors.joining(", "))+"]");
        debug.add("VS: [" + Arrays.stream(flipCopy(RenderStatistics.visibleSections)).mapToObj(Integer::toString).collect(Collectors.joining(", "))+"]");
        debug.add("QC: [" + Arrays.stream(flipCopy(RenderStatistics.quadCount)).mapToObj(Integer::toString).collect(Collectors.joining(", "))+"]");
    }

    private static int[] flipCopy(int[] array) {
        int[] ret = new int[array.length];
        int i = ret.length;
        for (int j : array) {
            ret[--i] = j;
        }
        return ret;
    }
}
