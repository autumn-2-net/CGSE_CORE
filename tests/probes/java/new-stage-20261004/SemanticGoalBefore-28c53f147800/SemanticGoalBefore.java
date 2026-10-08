package org.cgse.core;
public final class SemanticGoalBefore {
    public static void main(String[] args) throws Exception {
        try {
            SemanticGoalProbe.coordinator();
            throw new AssertionError("Old implementation unexpectedly passed");
        } catch (AssertionError expected) {
            if (!expected.getMessage().equals("learned choice silently selected proof mode")) throw expected;
            System.out.println("REPRODUCED: valid learned choice silently selected PROOF before first executable witness");
        }
    }
}
