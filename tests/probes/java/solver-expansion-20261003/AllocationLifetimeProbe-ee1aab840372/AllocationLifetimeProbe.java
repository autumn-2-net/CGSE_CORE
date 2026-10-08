package org.cgse.core;
public final class AllocationLifetimeProbe { public static void main(String[] args)throws Exception {MacroLifetimeProbe.allocation();System.out.println("PASS allocation phase0/5 cancellation cleanup checks="+MacroLifetimeProbe.checks);} }
