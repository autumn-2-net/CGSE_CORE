package org.cgse.core;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
public class TailProbe {
    public static void main(String[] args)throws Exception {
        var groups=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();
        var first=groups.get(0).getAsJsonObject();var warm=QuantitySweep.catalog(first.getAsJsonObject("catalog"));
        for(int i=0;i<5;i++)StabilityProbe.cgse(warm,first,1,false,false);
        try(var writer=Files.newBufferedWriter(Path.of(args[1]))) {
            for(int repeat=0;repeat<4;repeat++) for(var element:groups) {
                var g=element.getAsJsonObject();if(!g.get("id").getAsString().equals("cut-dag-374-90"))continue;var c=QuantitySweep.catalog(g.getAsJsonObject("catalog"));
                for(var request:g.getAsJsonArray("requests")) {
                    long n=request.getAsJsonObject().get("amount").getAsLong();
                    var result=new LinkedHashMap<String,Object>();result.put("id",g.get("id").getAsString());result.put("amount",n);result.put("repeat",repeat);
                    result.putAll(StabilityProbe.cgse(c,g,n,true,false));
                    writer.write(StabilityProbe.JSON.toJson(result));writer.newLine();writer.flush();
                }
            }
        }
    }
}
