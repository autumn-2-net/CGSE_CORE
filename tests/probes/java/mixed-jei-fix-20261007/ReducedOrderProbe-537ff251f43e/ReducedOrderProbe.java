package org.cgse.core;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
public final class ReducedOrderProbe {
    public static void main(String[] args)throws Exception {
        Path area=Path.of(args[0]);var manual=MixedSweep.read(area.resolve("manual.json"));var extra=MixedSweep.read(area.resolve("reduced-extra.json"));
        var data=JsonParser.parseString(Files.readString(area.resolve("reduced-extra.json"))).getAsJsonObject();var request=data.getAsJsonObject("request");var config=data.getAsJsonObject("config");
        try(var out=Files.newBufferedWriter(area.resolve("order-matrix.jsonl"))) {
            for(String mode:List.of("manual-first","extra-first"))for(var order:List.of(new int[]{0,1,2},new int[]{0,2,1},new int[]{1,0,2},new int[]{1,2,0},new int[]{2,0,1},new int[]{2,1,0})) {
                var recipes=new ArrayList<GraphRecipe<String>>();for(int index:order)recipes.add(extra.recipes().get(index));config.addProperty("mode",mode);
                var row=MixedSweep.solve(manual,MixedSweep.compiler(manual,MinimizeStress.data(recipes),mode,0),config,request);row.add("order",MixedSweep.JSON.toJsonTree(order));
                out.write(MixedSweep.JSON.toJson(row));out.newLine();out.flush();System.out.println(mode+" "+Arrays.toString(order)+" "+row.get("result")+" "+row.get("effective_result")+" "+row.get("work"));
            }
        }
    }
}
