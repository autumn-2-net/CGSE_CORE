package org.cgse.core;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
public final class InspectGraph {
    public static void main(String[] args)throws Exception {
        Path area=Path.of(args[0]);var manual=MixedSweep.read(area.resolve("manual.json"));var extra=MixedSweep.read(area.resolve("reduced-extra.json"));
        var data=JsonParser.parseString(Files.readString(area.resolve("reduced-extra.json"))).getAsJsonObject();String target=data.getAsJsonObject("request").get("target").getAsString();
        JsonArray rows=new JsonArray();
        for(String mode:List.of("manual","manual-first","extra-first")) {
            var compiler=MixedSweep.compiler(manual,extra,mode,0);var b=new PlanningBudget(0,20_000_000,128L<<20,()->false,System::nanoTime);
            var g=compiler.compile(target,Map.of(),Set.of(),b);JsonObject row=new JsonObject();row.addProperty("mode",mode);row.addProperty("recipes",g.recipes().size());row.addProperty("selected_keys",g.selected().size());row.addProperty("regions",g.regions().size());row.addProperty("cyclic_regions",g.regions().stream().filter(GraphCompiler.Region::cyclic).count());row.addProperty("largest_cycle_recipes",g.regions().stream().filter(GraphCompiler.Region::cyclic).mapToInt(r->r.recipes().size()).max().orElse(0));row.addProperty("work",b.nodes());row.addProperty("peak_bytes",b.peakBytes());rows.add(row);
        }
        Files.writeString(area.resolve("selected-graphs.json"),MixedSweep.JSON.toJson(rows));System.out.println(rows);
    }
}
