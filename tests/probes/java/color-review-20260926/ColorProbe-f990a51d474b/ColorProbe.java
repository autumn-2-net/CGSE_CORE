package org.cgse.core;
import java.io.*;
import java.nio.file.*;
import java.math.BigInteger;
import java.util.*;

public class ColorProbe {
    static String text(DataInputStream in) throws IOException { return new String(in.readNBytes(in.readInt()), java.nio.charset.StandardCharsets.UTF_8); }
    static Map<String,Long> amounts(DataInputStream in) throws IOException {
        var values=new LinkedHashMap<String,Long>();
        for(int n=in.readInt();n>0;n--)values.put(text(in),in.readLong());
        return values;
    }
    public static void main(String[] args) throws Exception {
        List<GraphRecipe<String>> recipes=new ArrayList<>();Map<String,Long> stock;String target;long amount;
        try(var in=new DataInputStream(Files.newInputStream(Path.of(args[0])))) {
            in.readInt();text(in);target=text(in);amount=in.readLong();text(in);stock=amounts(in);
            for(int n=in.readInt();n>0;n--){String id=text(in);var inputs=amounts(in);var outputs=amounts(in);
                recipes.add(new GraphRecipe<>(id,id,inputs.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),outputs));}
        }
        for(int run=-3;run<Integer.parseInt(args[1]);run++) {
            var chosen=new ArrayList<>(recipes);if(run>0)Collections.shuffle(chosen,new Random(20260926+run));
            var budget=new PlanningBudget(3000,20_000_000,256L<<20,()->false,System::nanoTime);
            long start=System.nanoTime();String result;
            try { result=new GraphPlanner<>(new GraphCompiler<>(chosen)).plan(target,amount,stock,false,true,budget).result().toString(); }
            catch(PlanningBudget.Exhausted fail) {result=fail.toString();}
            System.out.println("CASE "+run+" "+result+" ms="+(System.nanoTime()-start)/1e6+" work="+budget.nodes());
            if(run>=0)System.out.println(budget.diagnostics());
        }
    }
}
