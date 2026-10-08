package org.cgse.core;

import java.nio.file.*;

public final class StructuralTiming {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);
        for(int wave=0;wave<5;wave++){
            Path out=root.resolve((wave<2?"warmup-":"measured-")+wave);
            Files.createDirectories(out);
            StructuralBenchmark.main(new String[]{out.toString()});
        }
    }
}
