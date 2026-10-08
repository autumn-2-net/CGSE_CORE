package org.cgse.core;
import com.google.gson.*;
import java.nio.file.*;
public final class ReplayReduced {
 public static void main(String[] args)throws Exception{
  Path file=Path.of(args[0]);var data=MixedSweep.read(file);var root=JsonParser.parseString(Files.readString(file)).getAsJsonObject();var config=root.getAsJsonObject("config");config.addProperty("fallback",false);var row=MixedSweep.solve(data,new GraphCompiler<>(data.recipes(),data.producers()),config,root.getAsJsonObject("request"));Files.writeString(Path.of(args[1]),MixedSweep.JSON.toJson(row));System.out.println(row.get("result")+" work="+row.get("work")+" peak="+row.get("peak_bytes")+" ms="+row.get("ms"));
 }
}
