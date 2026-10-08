package org.cgse.core;
import java.nio.file.*;import java.util.*;
public class ExportBaseline {
 public static void main(String[]a)throws Exception {
  try(var w=Files.newBufferedWriter(Path.of(a[0]))){
   for(int seed=20000;seed<40000;seed++){
    var f=MassRandomProbe.generate(seed);var d=new LinkedHashMap<String,Object>();d.put("name",f.name());d.put("stock",f.stock());d.put("target",f.target());d.put("amount",f.amount());d.put("recipes",f.recipes().stream().map(r->Map.of("id",r.id(),"inputs",r.inputs(),"outputs",r.outputs())).toList());w.write(AdversarialProbe.json(d));w.newLine();
   }
  }
 }
}
