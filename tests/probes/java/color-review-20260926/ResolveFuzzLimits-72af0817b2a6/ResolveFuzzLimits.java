package org.cgse.core;

import com.google.ortools.Loader;
import java.util.*;
import java.nio.file.*;
import java.util.regex.*;
import static org.cgse.core.ContrastProbe.*;
import static org.cgse.core.AdversarialProbe.*;

/** Exact finite-horizon CP-SAT for cases where the separate BFS oracle hit its cap. */
public final class ResolveFuzzLimits {
    public static void main(String[] args)throws Exception{
        Loader.loadNativeLibraries();Path root=Path.of(args[1]);Files.createDirectories(root);out=root.resolve("cases");Files.createDirectories(out);
        for(String line:Files.readAllLines(Path.of(args[0])))if(line.contains("\"LIMIT\"")){
            Matcher matcher=Pattern.compile("\"seed\"\\s*:\\s*(\\d+)").matcher(line);if(!matcher.find())throw new AssertionError(line);
            int seed=Integer.parseInt(matcher.group(1));var f=FuzzWide.generate(seed);int horizon=f.stock().get("F").intValue();
            for(var r:f.recipes())if(r.inputs().getOrDefault("F",0L)!=1||r.outputs().getOrDefault("F",0L)!=0)throw new AssertionError("not fuel bounded");
            var test=new BatchProbe.Test("wide_fuel",f,"UNKNOWN",List.of(),horizon);var cp=BatchProbe.timedCp(test,3);var g=run(f,3000,false).result();
            if(cp.status().equals("INFEASIBLE")&&feasible(g))throw new AssertionError("Contradictory witnesses "+f.name());
            var row=Map.of("seed",seed,"case",f.name(),"cp_sat",BatchProbe.result(cp),"cgse",BatchProbe.result(g),"horizon",horizon);
            Files.writeString(root.resolve("results.jsonl"),json(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);save(f,json(row));
            System.out.println(f.name()+" CP="+cp.status()+" CGSE="+g.status());System.out.flush();
        }
    }
}
