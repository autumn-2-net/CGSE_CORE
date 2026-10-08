package org.cgse.core;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
public class ViewSweep {
    public static void main(String[] args)throws Exception {
        var gs=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();
        try(var writer=Files.newBufferedWriter(Path.of(args[1]))) {
            for(var e:gs) {
                var g=e.getAsJsonObject();var c=QuantitySweep.catalog(g.getAsJsonObject("catalog"));
                for(var req:g.getAsJsonArray("requests")) {
                    long n=req.getAsJsonObject().get("amount").getAsLong();var row=new LinkedHashMap<String,Object>();
                    row.put("id",g.get("id").getAsString());row.put("amount",n);
                    row.put("original",KernelProbe.direct(c,g,n,false));row.put("reduced",KernelProbe.direct(c,g,n,true));
                    writer.write(StabilityProbe.JSON.toJson(row));writer.newLine();writer.flush();
                }
            }
        }
    }
}
