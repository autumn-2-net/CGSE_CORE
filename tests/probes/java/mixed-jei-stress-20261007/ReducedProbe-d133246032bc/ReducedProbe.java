package org.cgse.core;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
public final class ReducedProbe {
    public static void main(String[] args)throws Exception {
        Path area=Path.of(args[0]);var manual=MixedSweep.read(area.resolve("manual.json"));var extra=MixedSweep.read(area.resolve("reduced-extra.json"));
        var test=JsonParser.parseString(Files.readString(area.resolve("reduced-extra.json"))).getAsJsonObject();
        var request=test.getAsJsonObject("request");var config=test.getAsJsonObject("config");
        try(var out=Files.newBufferedWriter(area.resolve("reduced-variants.jsonl"))) {
            for(String variant:List.of("original","first_only","second_only","both","physical_returns","machine_tools_installed","both_80m")) {
                var list=new ArrayList<GraphRecipe<String>>();
                for(int i=0;i<extra.recipes().size();i++) {
                    var r=extra.recipes().get(i);
                    if(variant.equals("original")||variant.equals("first_only")&&i!=0||variant.equals("second_only")&&i!=1)continue;
                    if(variant.equals("physical_returns")) r=new GraphRecipe<>(r.id(),r.binding(),r.slots().stream().map(s->new GraphRecipe.Slot<>(s.key(),s.amount(),s.inputSlot(),false,false)).toList(),r.outputs());
                    if(variant.equals("machine_tools_installed")) {
                        var outputs=new LinkedHashMap<>(r.outputs());for(var s:r.slots())if(s.reusable()){long left=outputs.get(s.key())-s.amount();if(left==0)outputs.remove(s.key());else outputs.put(s.key(),left);}
                        r=new GraphRecipe<>(r.id(),r.binding(),r.slots().stream().filter(s->!s.reusable()).toList(),outputs);
                    }
                    list.add(r);
                }
                config.addProperty("work",variant.equals("both_80m")?80_000_000:20_000_000);
                var row=MixedSweep.solve(manual,MixedSweep.compiler(manual,MinimizeStress.data(list),"manual-first",0),config,request);row.addProperty("variant",variant);
                out.write(MixedSweep.JSON.toJson(row));out.newLine();out.flush();System.out.println(variant+" "+row.get("result")+" "+row.get("effective_result")+" "+row.get("work"));
            }
        }
    }
}
