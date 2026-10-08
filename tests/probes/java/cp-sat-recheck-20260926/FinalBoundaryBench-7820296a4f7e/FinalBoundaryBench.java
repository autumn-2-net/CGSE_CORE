package org.cgse.core;
public final class FinalBoundaryBench {
    public static void main(String[]args){
        com.google.ortools.Loader.loadNativeLibraries();
        RecheckProbe.bench(ContrastProbe.sat(20,80,42),20,31);
        RecheckProbe.bench(ContrastProbe.sat(32,128,42),20,31);
    }
}
